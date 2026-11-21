package com.example.vocalremover.processing

import ai.onnxruntime.OrtException
import android.util.Log
import com.example.vocalremover.BackendOutputException
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.backend.BackendSelector

/** Runs the model on a whole track with one backend; implemented by `VocalRemover`. */
interface InstrumentalRemover : AutoCloseable {
    suspend fun removeVocals(signal: StereoPcm, onProgress: (Int) -> Unit = {}): StereoPcm
}

/**
 * Extracts the instrumental with the backend chosen by [selector]. If an accelerator fails
 * mid-song (ORT error or unusable output) it is reported and the whole song restarts on the next
 * backend; a CPU failure, a cancellation or any other error propagates unchanged.
 */
class FallbackInstrumentalExtractor(
    private val selector: BackendSelector,
    private val removerFactory: (ExecutionBackend) -> InstrumentalRemover,
) : InstrumentalExtractor, AutoCloseable {

    private val lock = Any()
    private var remover: InstrumentalRemover? = null  // guarded by lock
    private var removerBackend: ExecutionBackend? = null  // guarded by lock
    private var closed = false  // guarded by lock

    override fun isReady(): Boolean = selector.isReady()

    override suspend fun prepare() {
        selector.current()
    }

    override suspend fun extract(mix: StereoPcm, onProgress: (Int) -> Unit): StereoPcm =
        extract(mix, onProgress) { onProgress(0) }

    override suspend fun extract(
        mix: StereoPcm,
        onProgress: (Int) -> Unit,
        onBackendRetry: () -> Unit,
    ): StereoPcm {
        while (true) {
            val backend = selector.current()
            val current = removerFor(backend)
            selector.markInProgress(backend)
            try {
                return current.removeVocals(mix, onProgress)
            } catch (e: Exception) {
                if (backend == ExecutionBackend.CPU || !isBackendFailure(e)) throw e
                Log.w(TAG, "$backend failed during processing, retrying the whole song", e)
                release(current)
                val next = selector.reportFailure(backend, e)
                Log.i(TAG, "Retrying on $next")
                onBackendRetry()
            } finally {
                // The process survived the run: whatever happened, it was not a native crash.
                selector.markCompleted(backend)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            remover?.close()
            remover = null
            removerBackend = null
        }
    }

    private fun isBackendFailure(e: Exception) = e is OrtException || e is BackendOutputException

    /** The remover for [backend], reused until the backend changes. */
    private fun removerFor(backend: ExecutionBackend): InstrumentalRemover = synchronized(lock) {
        check(!closed) { "FallbackInstrumentalExtractor already closed" }
        remover?.takeIf { removerBackend == backend }?.let { return it }
        remover?.close()
        removerFactory(backend).also {
            remover = it
            removerBackend = backend
        }
    }

    private fun release(failed: InstrumentalRemover) {
        synchronized(lock) {
            if (remover === failed) {
                remover = null
                removerBackend = null
            }
        }
        failed.close()
    }

    private companion object {
        const val TAG = "FallbackExtractor"
    }
}
