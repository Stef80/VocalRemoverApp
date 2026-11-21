package com.example.vocalremover.backend

import ai.onnxruntime.OrtException
import android.content.Context
import android.util.Log
import com.example.vocalremover.BackendOutputException
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.SingleChunkRun
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.VocalRemover
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one model chunk of [ProbeSignal] with the given backend and compares its output with the
 * bundled CPU reference. The chunk runs twice: the first run is a warm-up (WebGPU compiles its
 * shaders there); only the `session.run` of the second is timed.
 */
class OrtBackendProber(
    private val context: Context,
    private val removerFactory: (ExecutionBackend) -> VocalRemover = { VocalRemover(context, it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BackendProber {

    @Volatile private var reference: StereoPcm? = null

    /** Copies the model and loads the reference; an IOException here is an environment problem. */
    override suspend fun prepare() = withContext(dispatcher) {
        VocalRemover.ensureModelFile(context)
        loadReference()
        Unit
    }

    override suspend fun probe(backend: ExecutionBackend): ProbeResult = withContext(dispatcher) {
        val expected = loadReference()
        val remover = removerFactory(backend)
        try {
            try {
                remover.warmUp()
            } catch (e: OrtException) {
                Log.w(TAG, "$backend session failed", e)
                return@withContext ProbeResult.Rejected("session: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: LinkageError) {
                Log.w(TAG, "$backend native libraries failed", e)
                return@withContext ProbeResult.Rejected("session: ${e.javaClass.simpleName}: ${e.message}")
            }
            val run = try {
                runProbeChunk(remover)
                runProbeChunk(remover)
            } catch (e: BackendOutputException) {
                return@withContext ProbeResult.Rejected("output: ${e.message}")
            }
            val out = run.output
            OutputQuality.verdict(expected, out)?.let { return@withContext ProbeResult.Rejected(it) }
            val snr = OutputQuality.stereoSnrDb(expected.left, expected.right, out.left, out.right)
            ProbeResult.Passed(run.runNanos / 1_000_000, snr)
        } finally {
            remover.close()
        }
    }

    private fun loadReference(): StereoPcm = reference ?: context.assets.open(ProbeReference.ASSET)
        .use { ProbeReference.decode(it.readBytes()) }
        .also { reference = it }

    companion object {
        private const val TAG = "OrtBackendProber"

        /**
         * The probe's model work: one chunk of [ProbeSignal.generate], one `session.run`. Also used
         * to generate the CPU reference asset, so the two can never drift apart.
         */
        suspend fun runProbeChunk(remover: VocalRemover): SingleChunkRun =
            remover.runSingleChunk(ProbeSignal.generate())
    }
}
