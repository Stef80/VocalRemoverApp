package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

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
 * Carica il modello ONNX Open-Unmix e ricava la traccia strumentale.
 * Ottimizzato per ridurre le allocazioni e usare array piatti.
 */
class VocalRemover(context: Context) {

    companion object {
        private const val TAG = "VocalRemover"
        private const val MODEL_ASSET = "vocal_remover.onnx"
        // Frame per chiamata ONNX: dimensione fissa richiesta dal grafo del modello.
        const val CHUNK_FRAMES = 100
        // Frame STFT processati per blocco: limita la memoria di picco (Re/Im/mag mix+voce,
        // stereo) a poche decine di MB indipendentemente dalla durata del brano, invece di
        // materializzare l'intero spettrogramma del brano in RAM (causa di OutOfMemoryError
        // su brani di durata normale). ~200 frame ≈ 4.6s di audio.
        private const val BLOCK_FRAMES = 200
        private const val EPS = 1e-8f
    }

    private val stft = StftProcessor()
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

    /** Processa i due canali PCM e restituisce lo strumentale stereo. */
    suspend fun removeVocals(
        signal: StereoPcm,
        onProgress: (Int) -> Unit = {}
    ): StereoPcm {
        onProgress(2)

        val totalFrames = stft.frameCount(signal.size)
        val instrumentalLeft = FloatArray(signal.size)
        val instrumentalRight = FloatArray(signal.size)
        val istftLeft = stft.StreamingIstft()
        val istftRight = stft.StreamingIstft()
        var writtenSamples = 0

        val inputData = FloatArray(2 * stft.freqBins * CHUNK_FRAMES)
        val inputBuffer = FloatBuffer.wrap(inputData)
        val shape = longArrayOf(1L, 2L, stft.freqBins.toLong(), CHUNK_FRAMES.toLong())

        var frameOffset = 0
        while (frameOffset < totalFrames) {
            coroutineContext.ensureActive()
            val blockEnd = minOf(frameOffset + BLOCK_FRAMES, totalFrames)
            val blockFrameCount = blockEnd - frameOffset

            // 1. STFT del mix, solo per questo blocco (non l'intero brano).
            val (blockLeftRe, blockLeftIm) = stft.stftRange(signal.left, frameOffset, blockFrameCount)
            val (blockRightRe, blockRightIm) = stft.stftRange(signal.right, frameOffset, blockFrameCount)
            val blockLeftMag = stft.magnitude(blockLeftRe, blockLeftIm)
            val blockRightMag = stft.magnitude(blockRightRe, blockRightIm)

            // 2. Inferenza a sotto-chunk di CHUNK_FRAMES (dimensione fissa richiesta dal modello).
            val vocalsLeftMag = FloatArray(blockLeftMag.size)
            val vocalsRightMag = FloatArray(blockRightMag.size)

            var sub = 0
            while (sub < blockFrameCount) {
                coroutineContext.ensureActive()
                val subEnd = minOf(sub + CHUNK_FRAMES, blockFrameCount)
                val realChunkSize = subEnd - sub

                inputData.fill(0f)
                for (k in 0 until stft.freqBins) {
                    for (f in 0 until realChunkSize) {
                        val base = k * CHUNK_FRAMES + f
                        inputData[base] = blockLeftMag[(sub + f) * stft.freqBins + k]
                        inputData[stft.freqBins * CHUNK_FRAMES + base] =
                            blockRightMag[(sub + f) * stft.freqBins + k]
                    }
                }

                inputBuffer.rewind()
                val inputTensor = OnnxTensor.createTensor(env, inputBuffer, shape)

                inputTensor.use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { results ->
                        val outputTensor = results.get(outputName).get() as OnnxTensor
                        val outputBuffer = outputTensor.floatBuffer

                        val chOut0 = FloatArray(stft.freqBins * CHUNK_FRAMES)
                        val chOut1 = FloatArray(stft.freqBins * CHUNK_FRAMES)
                        outputBuffer.get(chOut0)
                        outputBuffer.get(chOut1)

                        for (k in 0 until stft.freqBins) {
                            for (f in 0 until realChunkSize) {
                                val base = k * CHUNK_FRAMES + f
                                vocalsLeftMag[(sub + f) * stft.freqBins + k] = chOut0[base]
                                vocalsRightMag[(sub + f) * stft.freqBins + k] = chOut1[base]
                            }
                        }
                    }
                }

                sub = subEnd
            }

            // 3. Ratio mask e ricostruzione voce (Re/Im), solo per questo blocco.
            val vocalsLeftRe = FloatArray(blockLeftRe.size)
            val vocalsLeftIm = FloatArray(blockLeftIm.size)
            val vocalsRightRe = FloatArray(blockRightRe.size)
            val vocalsRightIm = FloatArray(blockRightIm.size)
            for (i in blockLeftMag.indices) {
                val leftRatio = (vocalsLeftMag[i] / (blockLeftMag[i] + EPS)).coerceIn(0f, 1f)
                val rightRatio = (vocalsRightMag[i] / (blockRightMag[i] + EPS)).coerceIn(0f, 1f)
                vocalsLeftRe[i] = blockLeftRe[i] * leftRatio
                vocalsLeftIm[i] = blockLeftIm[i] * leftRatio
                vocalsRightRe[i] = blockRightRe[i] * rightRatio
                vocalsRightIm[i] = blockRightIm[i] * rightRatio
            }

            // 4. ISTFT in streaming: ogni frame produce hopLength campioni "definitivi",
            //    subito sottratti dal mix originale (nessun buffer a piena durata brano).
            for (localFrame in 0 until blockFrameCount) {
                val offset = localFrame * stft.freqBins
                val outL = istftLeft.pushFrame(vocalsLeftRe, vocalsLeftIm, offset)
                val outR = istftRight.pushFrame(vocalsRightRe, vocalsRightIm, offset)
                writeInstrumental(instrumentalLeft, signal.left, outL, writtenSamples)
                writeInstrumental(instrumentalRight, signal.right, outR, writtenSamples)
                writtenSamples += outL.size
            }

            frameOffset = blockEnd
            onProgress(2 + (frameOffset * 93) / totalFrames)
        }

        // 5. Flush finale: gli ultimi campioni di coda dell'overlap-add.
        val tailL = istftLeft.flush()
        val tailR = istftRight.flush()
        writeInstrumental(instrumentalLeft, signal.left, tailL, writtenSamples)
        writeInstrumental(instrumentalRight, signal.right, tailR, writtenSamples)

        onProgress(100)
        return StereoPcm(instrumentalLeft, instrumentalRight)
    }

    /** Sottrae dal segnale originale [original] i campioni vocali [vocalsChunk], a partire da [startIdx]. */
    private fun writeInstrumental(dest: FloatArray, original: FloatArray, vocalsChunk: FloatArray, startIdx: Int) {
        for (i in vocalsChunk.indices) {
            val idx = startIdx + i
            if (idx < dest.size) dest[idx] = original[idx] - vocalsChunk[i]
        }
    }

    fun close() = session.close()
}
