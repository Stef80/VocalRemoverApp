package com.example.vocalremover.backend

import com.example.vocalremover.ExecutionBackend

/** Decides which execution backend the app runs inference on. */
interface BackendSelector {
    /** The backend to use; probes the candidates on the first call for a given device/app build. */
    suspend fun current(): ExecutionBackend

    /** Records that [backend] failed at runtime and returns the next backend to use (CPU as last resort). */
    suspend fun reportFailure(backend: ExecutionBackend, cause: Throwable): ExecutionBackend

    /** True when a choice for this key is already in memory or in the store, so no probe is needed. */
    fun isReady(): Boolean

    /** Persists a marker saying [backend] is running; a surviving marker on the next start means a crash. */
    fun markInProgress(backend: ExecutionBackend)

    /** Clears the in-progress marker and the unexplained-exit counter of [backend]. */
    fun markCompleted(backend: ExecutionBackend)
}

sealed interface ProbeResult {
    data class Passed(val runMs: Long, val snrDb: Double) : ProbeResult
    data class Rejected(val reason: String) : ProbeResult
}

fun interface BackendProber {
    /**
     * Probes one candidate. Backend failures (ORT errors, native link errors) may be thrown or
     * returned as [ProbeResult.Rejected]; any other exception (I/O, out of memory) is inconclusive.
     */
    suspend fun probe(backend: ExecutionBackend): ProbeResult

    /**
     * One-off work shared by every candidate (model copy, reference load). A failure here says
     * nothing about the backends, so the selector aborts the probe without persisting anything.
     */
    suspend fun prepare() {}
}
