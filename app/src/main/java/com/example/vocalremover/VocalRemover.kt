package com.example.vocalremover

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Carica il modello TFLite di Spleeter (2-stems) e separa
 * la traccia strumentale da quella vocale.
 *
 * Il modello si aspetta come input lo spettrogramma di magnitudine:
 *   shape = [1, frames, freqBins]   (float32)
 * e restituisce due maschere soft:
 *   vocals_mask      shape = [1, frames, freqBins]
 *   accompaniment_mask shape = [1, frames, freqBins]
 *
 * Metti il file "spleeter_2stems.tflite" in app/src/main/assets/.
 * Per convertirlo usa lo script Python fornito (convert_spleeter.py).
 */
class VocalRemover(context: Context) {

    companion object {
        private const val TAG = "VocalRemover"
        private const val MODEL_ASSET = "spleeter_2stems.tflite"
        // Chunk di frame processati per volta (evita OOM su dispositivi con poca RAM)
        const val CHUNK_FRAMES = 512
    }

    private val stft = StftProcessor()
    private val interpreter: Interpreter

    init {
        val model = loadModelFile(context)
        val options = Interpreter.Options().apply {
            numThreads = 4
            // Prova ad usare la GPU delegate; fallback su CPU se non disponibile
            try {
                addDelegate(GpuDelegate())
                Log.d(TAG, "GPU delegate attivo")
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate non disponibile, uso CPU: ${e.message}")
            }
        }
        interpreter = Interpreter(model, options)
        Log.d(TAG, "Modello caricato. Input: ${interpreter.getInputTensor(0).shape().contentToString()}")
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val assetFd = context.assets.openFd(MODEL_ASSET)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
    }

    // ── API pubblica ─────────────────────────────────────────────────────────

    /**
     * Processa il segnale PCM mono e restituisce la traccia strumentale (senza voce).
     * [onProgress]: callback con percentuale 0..100.
     */
    fun removeVocals(
        signal: FloatArray,
        onProgress: (Int) -> Unit = {}
    ): FloatArray {
        onProgress(5)

        // 1. STFT
        val (stftRe, stftIm) = stft.stft(signal)
        val mag = stft.magnitude(stftRe, stftIm)
        val totalFrames = mag.size
        onProgress(20)

        // 2. Inferenza a chunk
        val accompMask = Array(totalFrames) { FloatArray(stft.freqBins) }

        var frameOffset = 0
        while (frameOffset < totalFrames) {
            val chunkEnd = minOf(frameOffset + CHUNK_FRAMES, totalFrames)
            val chunkSize = chunkEnd - frameOffset

            // Input buffer: [1, chunkSize, freqBins]
            val inputBuffer = ByteBuffer.allocateDirect(
                1 * chunkSize * stft.freqBins * 4
            ).order(ByteOrder.nativeOrder())

            for (f in frameOffset until chunkEnd) {
                for (k in 0 until stft.freqBins) inputBuffer.putFloat(mag[f][k])
            }
            inputBuffer.rewind()

            // Output: maschera accompagnamento [1, chunkSize, freqBins]
            val outputBuffer = ByteBuffer.allocateDirect(
                1 * chunkSize * stft.freqBins * 4
            ).order(ByteOrder.nativeOrder())

            // Il secondo output del modello è la maschera dell'accompagnamento
            val outputs = HashMap<Int, Any>()
            outputs[0] = outputBuffer  // vocals mask (indice 0)
            outputs[1] = ByteBuffer.allocateDirect(1 * chunkSize * stft.freqBins * 4)
                .order(ByteOrder.nativeOrder())  // accompaniment mask (indice 1)

            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

            // Leggi la maschera dell'accompagnamento (output 1)
            val accompBuf = outputs[1] as ByteBuffer
            accompBuf.rewind()
            for (f in 0 until chunkSize) {
                for (k in 0 until stft.freqBins) {
                    accompMask[frameOffset + f][k] = accompBuf.getFloat()
                }
            }

            frameOffset = chunkEnd
            onProgress(20 + (frameOffset * 60) / totalFrames)
        }

        // 3. Applica maschera alla STFT complessa
        val maskedRe = Array(totalFrames) { f ->
            FloatArray(stft.freqBins) { k -> stftRe[f][k] * accompMask[f][k] }
        }
        val maskedIm = Array(totalFrames) { f ->
            FloatArray(stft.freqBins) { k -> stftIm[f][k] * accompMask[f][k] }
        }
        onProgress(85)

        // 4. ISTFT → segnale ricostruito
        val result = stft.istft(maskedRe, maskedIm, signal.size)
        onProgress(100)
        return result
    }

    fun close() = interpreter.close()
}
