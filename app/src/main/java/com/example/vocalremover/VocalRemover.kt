package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

data class StereoPcm(
    val left: FloatArray,
    val right: FloatArray
) {
    init {
        require(left.size == right.size) { "I canali stereo devono avere la stessa lunghezza" }
    }

    val size: Int
        get() = left.size

    fun downmix(): FloatArray = FloatArray(size) { i ->
        (left[i] + right[i]) * 0.5f
    }
}

/**
 * Carica il modello ONNX UVR-MDX-NET-Inst_HQ_5 e ricava direttamente la traccia strumentale
 * (il modello è addestrato per emettere lo spettro complesso dello strumentale, non una maschera
 * di rapporto come il precedente Open-Unmix: niente ratio-mask, l'output dell'inferenza va
 * dritto in ISTFT). Sostituisce il precedente modello Open-Unmix a seguito di un benchmark su
 * audio reale che ha mostrato una perdita di voce residua (cross-leak) 3-4 volte inferiore
 * (0.02-0.03 contro 0.08-0.14).
 */
class VocalRemover(context: Context) {

    companion object {
        private const val TAG = "VocalRemover"
        private const val MODEL_ASSET = "vocal_remover.onnx"
        private const val MODEL_CACHE_FILE = "vocal_remover.onnx"
        private const val N_FFT = 5120
        private const val HOP_LENGTH = 1024
        private const val DIM_F = 2560
        private const val DIM_T = 256
        private const val OVERLAP = 0.25
        private const val NORMALIZATION_PEAK = 0.9f

        fun copyAssetStreamToFile(input: InputStream, outputFile: File) {
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private val mdxStft = MdxStftProcessor(nFft = N_FFT, hopLength = HOP_LENGTH, dimF = DIM_F)
    private val chunker = MdxChunker(nFft = N_FFT, hopLength = HOP_LENGTH, dimT = DIM_T, overlap = OVERLAP)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    private class ModelWorkspace(freqBins: Int) {
        val inputData = FloatArray(4 * DIM_F * DIM_T)
        val inputBuffer: FloatBuffer = FloatBuffer.wrap(inputData)
        val outputData = FloatArray(4 * DIM_F * DIM_T)
        val spectrumRe = FloatArray(DIM_T * freqBins)
        val spectrumIm = FloatArray(DIM_T * freqBins)
    }

    init {
        val modelFile = File(context.cacheDir, MODEL_CACHE_FILE)
        if (!modelFile.exists()) {
            Log.i(TAG, "Copio il modello ONNX in cache: ${modelFile.absolutePath}")
            context.assets.open(MODEL_ASSET).use { input ->
                copyAssetStreamToFile(input, modelFile)
            }
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            try {
                addNnapi()
                Log.d(TAG, "NNAPI execution provider attivo")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI non disponibile, uso CPU: ${e.message}")
            }
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.iterator().next()
        outputName = session.outputNames.iterator().next()
    }

    suspend fun removeVocals(
        signal: StereoPcm,
        onProgress: (Int) -> Unit = {}
    ): StereoPcm {
        Log.i(TAG, "Rimozione voce iniziata: frames=${signal.size}")
        onProgress(2)

        // 1. Normalizzazione di picco (replica esatta del comportamento del tool di riferimento
        //    audio-separator: se il picco supera 0.9, scala giù prima di processare, poi
        //    ri-scala il risultato per il picco ORIGINALE, non per 0.9).
        var peak = 0f
        for (i in 0 until signal.size) {
            peak = max(peak, max(abs(signal.left[i]), abs(signal.right[i])))
        }
        val normalized = peak > NORMALIZATION_PEAK
        val (procLeft, procRight) = if (normalized) {
            val scale = NORMALIZATION_PEAK / peak
            val l = FloatArray(signal.size) { signal.left[it] * scale }
            val r = FloatArray(signal.size) { signal.right[it] * scale }
            Pair(l, r)
        } else {
            Pair(signal.left, signal.right)
        }

        // 2. Chunking a finestre sovrapposte (25%) con inferenza ONNX per chunk.
        // `processChunk` non è una lambda "suspend" (MdxChunker è codice puro senza
        // coroutine), quindi catturiamo qui il coroutineContext corrente per poter comunque
        // controllare la cancellazione (ensureActive() su CoroutineContext non è suspend).
        val ctx = coroutineContext
        val workspace = ModelWorkspace(mdxStft.freqBins)
        var chunksDone = 0
        val estimatedChunks = max(1, (signal.size / (HOP_LENGTH * (DIM_T - 1) / 2)) + 1)
        Log.i(TAG, "Chunking MDX: estimatedChunks=$estimatedChunks chunkFrames=${HOP_LENGTH * (DIM_T - 1)}")
        val instrumentalLeft = FloatArray(signal.size)
        val instrumentalRight = FloatArray(signal.size)
        chunker.processStereoInto(procLeft, procRight, instrumentalLeft, instrumentalRight) { chunkL, chunkR ->
            ctx.ensureActive()
            val result = runModelOnChunk(chunkL, chunkR, workspace)
            chunksDone++
            if (chunksDone == 1 || chunksDone % 10 == 0 || chunksDone == estimatedChunks) {
                Log.i(TAG, "Rimozione voce avanzamento: chunk=$chunksDone/$estimatedChunks")
            }
            onProgress(2 + minOf(93, (chunksDone * 93) / estimatedChunks))
            result
        }

        if (normalized) {
            for (i in instrumentalLeft.indices) {
                instrumentalLeft[i] *= peak
                instrumentalRight[i] *= peak
            }
        }

        // 4. Normalizzazione di sicurezza in output: il modello può occasionalmente produrre
        //    un picco leggermente superiore a 1.0 (verificato su audio reale: ~1.01), che
        //    causerebbe clipping udibile in riproduzione con AudioTrack (ENCODING_PCM_FLOAT).
        //    Replica il comportamento del tool di riferimento audio-separator, che applica la
        //    stessa normalizzazione (soglia 0.9) anche in scrittura del file di output.
        var outputPeak = 0f
        for (i in instrumentalLeft.indices) {
            outputPeak = max(outputPeak, max(abs(instrumentalLeft[i]), abs(instrumentalRight[i])))
        }
        if (outputPeak > NORMALIZATION_PEAK) {
            val outScale = NORMALIZATION_PEAK / outputPeak
            for (i in instrumentalLeft.indices) {
                instrumentalLeft[i] *= outScale
                instrumentalRight[i] *= outScale
            }
        }

        onProgress(100)
        Log.i(TAG, "Rimozione voce completata: frames=${signal.size} outputPeak=$outputPeak")
        return StereoPcm(instrumentalLeft, instrumentalRight)
    }

    /** Esegue STFT -> ONNX -> ISTFT su un singolo chunk stereo (dimensione fissa chunkSize). */
    private fun runModelOnChunk(
        chunkL: FloatArray,
        chunkR: FloatArray,
        workspace: ModelWorkspace
    ): Pair<FloatArray, FloatArray> {
        val (leftRe, leftIm) = mdxStft.forward(chunkL)
        val (rightRe, rightIm) = mdxStft.forward(chunkR)

        // Azzera le prime 3 bin di frequenza (riduzione rumore a bassa frequenza, come da
        // implementazione di riferimento: spek[:, :, :3, :] *= 0).
        zeroFirstBins(leftRe, leftIm, DIM_F, 3)
        zeroFirstBins(rightRe, rightIm, DIM_F, 3)

        // Layout canale: [L_re, L_im, R_re, R_im], ciascuno [DIM_F, DIM_T] (bin-major, poi tempo).
        writeChannel(workspace.inputData, 0, leftRe)
        writeChannel(workspace.inputData, 1, leftIm)
        writeChannel(workspace.inputData, 2, rightRe)
        writeChannel(workspace.inputData, 3, rightIm)

        val shape = longArrayOf(1L, 4L, DIM_F.toLong(), DIM_T.toLong())
        workspace.inputBuffer.rewind()
        OnnxTensor.createTensor(env, workspace.inputBuffer, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val outputTensor = results.get(outputName).get() as OnnxTensor
                val outBuffer = outputTensor.floatBuffer
                outBuffer.get(workspace.outputData)

                // Ripristina le bin di frequenza da DIM_F a freqBins (nFft/2+1) con zero-padding
                // prima dell'ISTFT (il modello opera solo sulle frequenze più basse).
                readChannelToFullSpectrum(workspace.outputData, 0, workspace.spectrumRe)
                readChannelToFullSpectrum(workspace.outputData, 1, workspace.spectrumIm)
                val outChunkL = mdxStft.inverse(
                    workspace.spectrumRe,
                    workspace.spectrumIm,
                    DIM_T,
                    chunkL.size
                )
                readChannelToFullSpectrum(workspace.outputData, 2, workspace.spectrumRe)
                readChannelToFullSpectrum(workspace.outputData, 3, workspace.spectrumIm)
                val outChunkR = mdxStft.inverse(
                    workspace.spectrumRe,
                    workspace.spectrumIm,
                    DIM_T,
                    chunkR.size
                )
                return Pair(outChunkL, outChunkR)
            }
        }
    }

    private fun zeroFirstBins(re: FloatArray, im: FloatArray, dimF: Int, count: Int) {
        val frames = re.size / dimF
        for (frame in 0 until frames) {
            for (bin in 0 until count) {
                re[frame * dimF + bin] = 0f
                im[frame * dimF + bin] = 0f
            }
        }
    }

    /** Scrive re/im [frames*dimF] (bin-major) nel layout [channel][dimF][dimT] atteso dal tensore ONNX. */
    private fun writeChannel(dest: FloatArray, channel: Int, data: FloatArray) {
        val channelOffset = channel * DIM_F * DIM_T
        for (frame in 0 until DIM_T) {
            for (bin in 0 until DIM_F) {
                dest[channelOffset + bin * DIM_T + frame] = data[frame * DIM_F + bin]
            }
        }
    }

    /** Trasforma un canale [DIM_F, DIM_T] ONNX nel layout ISTFT [frame*freqBins]. */
    private fun readChannelToFullSpectrum(src: FloatArray, channel: Int, dest: FloatArray) {
        val channelOffset = channel * DIM_F * DIM_T
        for (frame in 0 until DIM_T) {
            for (bin in 0 until DIM_F) {
                dest[frame * mdxStft.freqBins + bin] = src[channelOffset + bin * DIM_T + frame]
            }
            dest[frame * mdxStft.freqBins + DIM_F] = 0f
        }
    }

    fun close() = session.close()
}
