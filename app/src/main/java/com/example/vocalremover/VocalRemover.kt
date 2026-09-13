package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
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
        private const val N_FFT = 5120
        private const val HOP_LENGTH = 1024
        private const val DIM_F = 2560
        private const val DIM_T = 256
        private const val OVERLAP = 0.25
        private const val NORMALIZATION_PEAK = 0.9f
    }

    private val mdxStft = MdxStftProcessor(nFft = N_FFT, hopLength = HOP_LENGTH, dimF = DIM_F)
    private val chunker = MdxChunker(nFft = N_FFT, hopLength = HOP_LENGTH, dimT = DIM_T, overlap = OVERLAP)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            try {
                addNnapi()
                Log.d(TAG, "NNAPI execution provider attivo")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI non disponibile, uso CPU: ${e.message}")
            }
        }
        session = env.createSession(modelBytes, options)
        inputName = session.inputNames.iterator().next()
        outputName = session.outputNames.iterator().next()
    }

    suspend fun removeVocals(
        signal: StereoPcm,
        onProgress: (Int) -> Unit = {}
    ): StereoPcm {
        onProgress(2)

        // 1. Normalizzazione di picco (replica esatta del comportamento del tool di riferimento
        //    audio-separator: se il picco supera 0.9, scala giù prima di processare, poi
        //    ri-scala il risultato per il picco ORIGINALE, non per 0.9).
        var peak = 0f
        for (i in 0 until signal.size) {
            peak = max(peak, max(abs(signal.left[i]), abs(signal.right[i])))
        }
        val (procLeft, procRight) = if (peak > NORMALIZATION_PEAK) {
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
        var chunksDone = 0
        val estimatedChunks = max(1, (signal.size / (HOP_LENGTH * (DIM_T - 1) / 2)) + 1)
        val (instL, instR) = chunker.processStereo(procLeft, procRight) { chunkL, chunkR ->
            ctx.ensureActive()
            val result = runModelOnChunk(chunkL, chunkR)
            chunksDone++
            onProgress(2 + minOf(93, (chunksDone * 93) / estimatedChunks))
            result
        }

        // 3. Annulla la normalizzazione di picco del punto 1.
        val instrumentalLeft = FloatArray(signal.size) { instL[it] * peak }
        val instrumentalRight = FloatArray(signal.size) { instR[it] * peak }

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
        return StereoPcm(instrumentalLeft, instrumentalRight)
    }

    /** Esegue STFT -> ONNX -> ISTFT su un singolo chunk stereo (dimensione fissa chunkSize). */
    private fun runModelOnChunk(chunkL: FloatArray, chunkR: FloatArray): Pair<FloatArray, FloatArray> {
        val (leftRe, leftIm) = mdxStft.forward(chunkL)
        val (rightRe, rightIm) = mdxStft.forward(chunkR)

        // Azzera le prime 3 bin di frequenza (riduzione rumore a bassa frequenza, come da
        // implementazione di riferimento: spek[:, :, :3, :] *= 0).
        zeroFirstBins(leftRe, leftIm, DIM_F, 3)
        zeroFirstBins(rightRe, rightIm, DIM_F, 3)

        val inputData = FloatArray(4 * DIM_F * DIM_T)
        // Layout canale: [L_re, L_im, R_re, R_im], ciascuno [DIM_F, DIM_T] (bin-major, poi tempo).
        writeChannel(inputData, 0, leftRe)
        writeChannel(inputData, 1, leftIm)
        writeChannel(inputData, 2, rightRe)
        writeChannel(inputData, 3, rightIm)

        val shape = longArrayOf(1L, 4L, DIM_F.toLong(), DIM_T.toLong())
        val inputBuffer = FloatBuffer.wrap(inputData)
        OnnxTensor.createTensor(env, inputBuffer, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val outputTensor = results.get(outputName).get() as OnnxTensor
                val outBuffer = outputTensor.floatBuffer
                val outData = FloatArray(4 * DIM_F * DIM_T)
                outBuffer.get(outData)

                val outLeftRe = readChannel(outData, 0)
                val outLeftIm = readChannel(outData, 1)
                val outRightRe = readChannel(outData, 2)
                val outRightIm = readChannel(outData, 3)

                // Ripristina le bin di frequenza da DIM_F a freqBins (nFft/2+1) con zero-padding
                // prima dell'ISTFT (il modello opera solo sulle frequenze più basse).
                val leftFullRe = padToFreqBins(outLeftRe, DIM_T)
                val leftFullIm = padToFreqBins(outLeftIm, DIM_T)
                val rightFullRe = padToFreqBins(outRightRe, DIM_T)
                val rightFullIm = padToFreqBins(outRightIm, DIM_T)

                val outChunkL = mdxStft.inverse(leftFullRe, leftFullIm, DIM_T, chunkL.size)
                val outChunkR = mdxStft.inverse(rightFullRe, rightFullIm, DIM_T, chunkR.size)
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

    /** Legge un canale [DIM_F, DIM_T] dal tensore di output e lo riporta al layout [frame*dimF+bin]. */
    private fun readChannel(src: FloatArray, channel: Int): FloatArray {
        val channelOffset = channel * DIM_F * DIM_T
        val out = FloatArray(DIM_T * DIM_F)
        for (frame in 0 until DIM_T) {
            for (bin in 0 until DIM_F) {
                out[frame * DIM_F + bin] = src[channelOffset + bin * DIM_T + frame]
            }
        }
        return out
    }

    /** Riporta [data] (piatto [frames*DIM_F]) a [frames*freqBins], zero-paddando le bin mancanti. */
    private fun padToFreqBins(data: FloatArray, frames: Int): FloatArray {
        val freqBins = mdxStft.freqBins
        val out = FloatArray(frames * freqBins)
        for (frame in 0 until frames) {
            System.arraycopy(data, frame * DIM_F, out, frame * freqBins, DIM_F)
        }
        return out
    }

    fun close() = session.close()
}
