package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Carica un modello ONNX di separazione vocale (es. MDX-Net, esportato/pre-convertito
 * dalla community "Ultimate Vocal Remover") e separa la traccia strumentale da quella vocale.
 *
 * Sostituisce la precedente implementazione basata su TensorFlow Lite + Spleeter:
 * nessuna conversione da TensorFlow è più necessaria. Basta scaricare un modello
 * MDX-Net già convertito in formato .onnx e copiarlo in app/src/main/assets/.
 *
 * Il modello si aspetta come input lo spettrogramma di magnitudine:
 *   shape = [1, frames, freqBins]   (float32)
 * e restituisce la maschera dell'accompagnamento (strumentale):
 *   accompaniment_mask shape = [1, frames, freqBins]
 *
 * NOTA: i modelli MDX-Net non condividono tutti la stessa architettura di input/output.
 * Verifica con netron.app (o l'output di debug loggato all'avvio) la shape reale del tuo
 * modello e allinea di conseguenza [StftProcessor] (nFft/hopLength) e [MODEL_OUTPUT_INDEX]
 * se il tuo modello restituisce la maschera vocale anziché quella strumentale, o lavora
 * su canali reali/immaginari separati invece che sulla sola magnitudine.
 */
class VocalRemover(context: Context) {

    companion object {
        private const val TAG = "VocalRemover"
        private const val MODEL_ASSET = "vocal_remover.onnx"
        // Chunk di frame processati per volta (evita OOM su dispositivi con poca RAM)
        const val CHUNK_FRAMES = 512
        // Indice dell'output del modello da usare come maschera strumentale.
        // Molti modelli MDX-Net restituiscono un solo output (maschera/spettro strumentale);
        // se il tuo modello espone più output, aggiorna questo indice.
        private const val MODEL_OUTPUT_INDEX = 0
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
        // Usa MODEL_OUTPUT_INDEX-esimo output dichiarato dal modello (di norma l'unico, o
        // la maschera dell'accompagnamento se il modello ne espone più di uno).
        outputName = session.outputNames.toList()[MODEL_OUTPUT_INDEX]
        Log.d(TAG, "Modello caricato. Input: $inputName, info: ${session.inputInfo[inputName]}")
        Log.d(TAG, "Output disponibili: ${session.outputNames}, usato: $outputName")
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

            // Input tensor: [1, chunkSize, freqBins]
            val inputData = FloatArray(chunkSize * stft.freqBins)
            var idx = 0
            for (f in frameOffset until chunkEnd) {
                for (k in 0 until stft.freqBins) inputData[idx++] = mag[f][k]
            }
            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(inputData),
                longArrayOf(1L, chunkSize.toLong(), stft.freqBins.toLong())
            )

            inputTensor.use { tensor ->
                session.run(mapOf(inputName to tensor)).use { results ->
                    val outputTensor = results.get(outputName).get() as OnnxTensor
                    val outputBuffer = outputTensor.floatBuffer
                    for (f in 0 until chunkSize) {
                        for (k in 0 until stft.freqBins) {
                            accompMask[frameOffset + f][k] = outputBuffer.get()
                        }
                    }
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

    fun close() = session.close()
}
