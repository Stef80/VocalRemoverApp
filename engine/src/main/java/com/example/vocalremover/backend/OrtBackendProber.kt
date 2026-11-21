package com.example.vocalremover.backend

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.VocalRemover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the model on [ProbeSignal] with the given backend and compares the output with the bundled
 * CPU reference. The first run is a warm-up; only the second is timed.
 */
class OrtBackendProber(
    private val context: Context,
    private val removerFactory: (ExecutionBackend) -> VocalRemover = { VocalRemover(context, it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BackendProber {

    override suspend fun probe(backend: ExecutionBackend): ProbeResult = withContext(dispatcher) {
        val reference = try {
            context.assets.open(ProbeReference.ASSET).use { ProbeReference.decode(it.readBytes()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load the probe reference", e)
            return@withContext ProbeResult.Rejected("reference: ${e.javaClass.simpleName}: ${e.message}")
        }
        val remover = removerFactory(backend)
        try {
            try {
                remover.warmUp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "$backend session failed", e)
                return@withContext ProbeResult.Rejected("session: ${e.javaClass.simpleName}: ${e.message}")
            }
            val signal = ProbeSignal.generate()
            remover.removeVocals(signal)
            val start = SystemClock.elapsedRealtime()
            val out: StereoPcm = remover.removeVocals(signal)
            val runMs = SystemClock.elapsedRealtime() - start

            OutputQuality.verdict(reference, out)?.let { return@withContext ProbeResult.Rejected(it) }
            val snr = OutputQuality.stereoSnrDb(reference.left, reference.right, out.left, out.right)
            ProbeResult.Passed(runMs, snr)
        } finally {
            remover.close()
        }
    }

    private companion object {
        const val TAG = "OrtBackendProber"
    }
}
