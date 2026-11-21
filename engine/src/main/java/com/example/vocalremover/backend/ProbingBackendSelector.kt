package com.example.vocalremover.backend

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.vocalremover.ExecutionBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Probes the candidate backends once per [key], keeps the fastest one that passes the quality
 * check and persists the outcome. CPU (last in the list) is probed only when no accelerator
 * passed: otherwise it stays the untimed last-resort fallback. A marker written around each probe/run lets the next start
 * blacklist a backend that crashed the process.
 */
class ProbingBackendSelector(
    private val candidates: List<ExecutionBackend>,
    private val key: String,
    private val store: BackendChoiceStore,
    private val prober: BackendProber,
    private val exitInfo: ProcessExitInfo,
) : BackendSelector {

    private val mutex = Mutex()
    private val stateLock = Any()
    private var state: BackendChoice? = null

    // Set when CPU left a crash marker: it stays the fallback but is not probed again this round.
    private var skipCpuProbe = false

    override suspend fun current(): ExecutionBackend = mutex.withLock {
        validChosen()?.let { return it }
        probeAll()
    }

    override suspend fun reportFailure(backend: ExecutionBackend, cause: Throwable): ExecutionBackend =
        mutex.withLock {
            if (backend == ExecutionBackend.CPU) {
                Log.w(TAG, "CPU reported a failure; it is the last resort", cause)
                return ExecutionBackend.CPU
            }
            val reason = describe(cause)
            val updated = update { it.copy(failed = it.failed + (backend to reason)) }
            Log.w(TAG, "$backend failed at runtime: $reason")
            val next = updated.timingsMs.filterKeys { it !in updated.failed }
                .minByOrNull { it.value }?.key ?: ExecutionBackend.CPU
            update { it.copy(chosen = next) }
            Log.i(TAG, "Switched to $next")
            next
        }

    override fun isReady(): Boolean = validChosen() != null

    override fun markInProgress(backend: ExecutionBackend) {
        update { it.copy(inProgress = backend) }
    }

    override fun markCompleted(backend: ExecutionBackend) {
        update {
            it.copy(
                inProgress = if (it.inProgress == backend) null else it.inProgress,
                unexplainedExits = it.unexplainedExits - backend,
            )
        }
    }

    private fun validChosen(): ExecutionBackend? {
        val s = loaded()
        return s.chosen?.takeIf { it !in s.failed }
    }

    private suspend fun probeAll(): ExecutionBackend {
        var acceleratorPassed = false
        for (candidate in candidates) {
            val s = loaded()
            if (candidate in s.failed) continue
            if (candidate == ExecutionBackend.CPU) {
                if (skipCpuProbe) continue
                if (acceleratorPassed) {
                    Log.i(TAG, "Skipping CPU probe: an accelerator passed")
                    continue
                }
            }
            markInProgress(candidate)
            val result = try {
                prober.probe(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Native load failures (UnsatisfiedLinkError, LinkageError) are Errors and must reject the candidate too.
                ProbeResult.Rejected("Probe threw ${describe(e)}")
            }
            markCompleted(candidate)
            when (result) {
                is ProbeResult.Passed -> {
                    Log.i(TAG, "$candidate passed: ${result.runMs} ms, SNR ${result.snrDb} dB")
                    if (candidate != ExecutionBackend.CPU) acceleratorPassed = true
                    update { it.copy(timingsMs = it.timingsMs + (candidate to result.runMs)) }
                }
                is ProbeResult.Rejected -> {
                    Log.i(TAG, "$candidate rejected: ${result.reason}")
                    update { it.copy(failed = it.failed + (candidate to result.reason)) }
                }
            }
        }
        val finished = loaded()
        val best = finished.timingsMs.filterKeys { it !in finished.failed }
            .minByOrNull { it.value }?.key ?: ExecutionBackend.CPU
        update { it.copy(chosen = best) }
        Log.i(TAG, "Chosen backend: $best")
        return best
    }

    /** Applies [change] to the in-memory state and persists the result. */
    private fun update(change: (BackendChoice) -> BackendChoice): BackendChoice = synchronized(stateLock) {
        val next = change(loaded())
        state = next
        store.save(next)
        next
    }

    /** Loads the stored state on first use, dropping it when the key changed and applying the crash marker. */
    private fun loaded(): BackendChoice = synchronized(stateLock) {
        state?.let { return it }
        val stored = store.load()?.takeIf { it.key == key }
        var choice = stored ?: BackendChoice(key)
        val marked = choice.inProgress
        if (marked != null) {
            choice = applyMarker(choice, marked)
            store.save(choice)
        }
        state = choice
        choice
    }

    private fun applyMarker(choice: BackendChoice, marked: ExecutionBackend): BackendChoice {
        val cleared = choice.copy(inProgress = null)
        return when (exitInfo.lastExitWasCrash()) {
            true -> blacklist(cleared, marked, "Native crash while in progress")
            false -> cleared
            null -> {
                val count = (cleared.unexplainedExits[marked] ?: 0) + 1
                if (count >= UNEXPLAINED_EXITS_LIMIT) {
                    blacklist(cleared, marked, "Process ended $count times without explanation while in progress")
                } else {
                    cleared.copy(unexplainedExits = cleared.unexplainedExits + (marked to count))
                }
            }
        }
    }

    private fun blacklist(choice: BackendChoice, backend: ExecutionBackend, reason: String): BackendChoice {
        if (backend == ExecutionBackend.CPU) {
            Log.w(TAG, "CPU left a crash marker ($reason); keeping it as fallback")
            skipCpuProbe = true
            return choice
        }
        Log.w(TAG, "Blacklisting $backend: $reason")
        return choice.copy(failed = choice.failed + (backend to reason))
    }

    private fun describe(cause: Throwable) = "${cause::class.java.simpleName}: ${cause.message}"

    companion object {
        private const val TAG = "BackendSelector"
        private const val UNEXPLAINED_EXITS_LIMIT = 2

        /** The production selector: probes the candidates for this SoC against the bundled CPU reference. */
        fun create(context: Context): ProbingBackendSelector {
            val app = context.applicationContext
            val installTime = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            return ProbingBackendSelector(
                candidates = BackendCandidates.forThisDevice(),
                key = "$installTime|${Build.FINGERPRINT}",
                store = SharedPrefsBackendChoiceStore(app),
                prober = OrtBackendProber(app),
                exitInfo = AndroidProcessExitInfo(app),
            )
        }
    }
}
