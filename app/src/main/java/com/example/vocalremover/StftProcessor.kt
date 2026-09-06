package com.example.vocalremover

import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Implementa STFT (Short-Time Fourier Transform) e ISTFT
 * con finestra di Hann e metodo overlap-add.
 * Ottimizzato per Android: usa array piatti per ridurre il GC e Coroutine per la parallelizzazione.
 */
class StftProcessor(
    val nFft: Int = 4096,
    val hopLength: Int = 1024,
    val sampleRate: Int = 44100
) {
    val freqBins: Int = nFft / 2 + 1

    private val window: FloatArray = FloatArray(nFft) { n ->
        (0.5f * (1.0 - cos(2.0 * PI * n / nFft))).toFloat()
    }

    private val windowSquareSum: FloatArray = FloatArray(nFft) { window[it] * window[it] }

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) -1 else 1)
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { for (i in 0 until n) { re[i] /= n; im[i] /= n } }
    }

    /**
     * Restituisce [stftRe, stftIm] come array piatti di dimensione [frames * freqBins].
     */
    suspend fun stft(signal: FloatArray): Pair<FloatArray, FloatArray> = withContext(Dispatchers.Default) {
        val frames = (signal.size - nFft) / hopLength + 1
        val stftRe = FloatArray(frames * freqBins)
        val stftIm = FloatArray(frames * freqBins)

        val cores = Runtime.getRuntime().availableProcessors()
        val framesPerCore = (frames + cores - 1) / cores

        (0 until cores).map { coreIdx ->
            async {
                val startFrame = coreIdx * framesPerCore
                val endFrame = minOf(startFrame + framesPerCore, frames)
                
                val re = FloatArray(nFft)
                val im = FloatArray(nFft)

                for (frameIdx in startFrame until endFrame) {
                    val start = frameIdx * hopLength
                    for (i in 0 until nFft) {
                        re[i] = if (start + i < signal.size) signal[start + i] * window[i] else 0f
                        im[i] = 0f
                    }
                    fft(re, im)
                    for (k in 0 until freqBins) {
                        stftRe[frameIdx * freqBins + k] = re[k]
                        stftIm[frameIdx * freqBins + k] = im[k]
                    }
                }
            }
        }.awaitAll()
        
        Pair(stftRe, stftIm)
    }

    /** Numero totale di frame STFT per un segnale di lunghezza [signalLength]. */
    fun frameCount(signalLength: Int): Int = (signalLength - nFft) / hopLength + 1

    /**
     * Calcola la STFT solo per l'intervallo di frame [startFrame, startFrame+frameCount).
     * A differenza di [stft], non richiede di materializzare l'intero brano in memoria:
     * legge direttamente la porzione necessaria di [signal] (già presente per intero, essendo
     * il PCM originale, ma di dimensione ben minore dello spettrogramma completo).
     * Usato per processare l'audio a blocchi e mantenere la memoria di picco limitata.
     */
    fun stftRange(signal: FloatArray, startFrame: Int, frameCount: Int): Pair<FloatArray, FloatArray> {
        val stftRe = FloatArray(frameCount * freqBins)
        val stftIm = FloatArray(frameCount * freqBins)
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)

        for (idx in 0 until frameCount) {
            val frameIdx = startFrame + idx
            val start = frameIdx * hopLength
            for (i in 0 until nFft) {
                re[i] = if (start + i < signal.size) signal[start + i] * window[i] else 0f
                im[i] = 0f
            }
            fft(re, im)
            for (k in 0 until freqBins) {
                stftRe[idx * freqBins + k] = re[k]
                stftIm[idx * freqBins + k] = im[k]
            }
        }
        return Pair(stftRe, stftIm)
    }

    /**
     * ISTFT in modalità streaming: ricostruisce il segnale un frame alla volta, mantenendo
     * in memoria solo un piccolo buffer di overlap (dimensione [nFft], non l'intero brano).
     * Matematicamente equivalente a [istft] applicato all'intero brano in un colpo solo:
     * ogni chiamata a [pushFrame] restituisce gli [hopLength] campioni che non riceveranno
     * più contributi da frame futuri (nessun frame successivo ne sovrappone la finestra),
     * quindi possono essere normalizzati e "finalizzati" subito. Al termine dei frame,
     * [flush] restituisce i restanti campioni di coda (nFft - hopLength), analogamente a
     * come farebbe l'ultimo tratto dell'algoritmo overlap-add non-streaming.
     */
    inner class StreamingIstft {
        private val carryData = FloatArray(nFft)
        private val carryNorm = FloatArray(nFft)
        private val re = FloatArray(nFft)
        private val im = FloatArray(nFft)

        /** Processa il frame STFT (Re/Im letti da [stftRe]/[stftIm] a partire da [offset]). */
        fun pushFrame(stftRe: FloatArray, stftIm: FloatArray, offset: Int): FloatArray {
            for (k in 0 until freqBins) {
                re[k] = stftRe[offset + k]
                im[k] = stftIm[offset + k]
            }
            for (k in freqBins until nFft) {
                re[k] = re[nFft - k]
                im[k] = -im[nFft - k]
            }
            fft(re, im, inverse = true)

            for (i in 0 until nFft) {
                carryData[i] += re[i] * window[i]
                carryNorm[i] += windowSquareSum[i]
            }
            return finalizeHop()
        }

        /** Da chiamare dopo l'ultimo frame: svuota la coda residua (nFft - hopLength campioni). */
        fun flush(): FloatArray {
            val remaining = nFft - hopLength
            val out = FloatArray(remaining)
            var pos = 0
            while (pos < remaining) {
                val chunk = finalizeHop()
                val n = minOf(chunk.size, remaining - pos)
                System.arraycopy(chunk, 0, out, pos, n)
                pos += n
            }
            return out
        }

        /** Estrae e normalizza i primi [hopLength] campioni (ormai definitivi), poi fa scorrere il buffer. */
        private fun finalizeHop(): FloatArray {
            val out = FloatArray(hopLength)
            for (i in 0 until hopLength) {
                out[i] = if (carryNorm[i] > 1e-8f) carryData[i] / carryNorm[i] else 0f
            }
            System.arraycopy(carryData, hopLength, carryData, 0, nFft - hopLength)
            System.arraycopy(carryNorm, hopLength, carryNorm, 0, nFft - hopLength)
            java.util.Arrays.fill(carryData, nFft - hopLength, nFft, 0f)
            java.util.Arrays.fill(carryNorm, nFft - hopLength, nFft, 0f)
            return out
        }
    }

    fun magnitude(stftRe: FloatArray, stftIm: FloatArray): FloatArray {
        val size = stftRe.size
        val mag = FloatArray(size)
        for (i in 0 until size) {
            mag[i] = sqrt(stftRe[i] * stftRe[i] + stftIm[i] * stftIm[i])
        }
        return mag
    }

    suspend fun istft(stftRe: FloatArray, stftIm: FloatArray, signalLength: Int): FloatArray = withContext(Dispatchers.Default) {
        val frames = stftRe.size / freqBins
        val outputLen = (frames - 1) * hopLength + nFft
        val output = FloatArray(outputLen)
        val normSum = FloatArray(outputLen)

        // Nota: ISTFT è più difficile da parallelizzare banalmente a causa dell'overlap-add (scrive nelle stesse zone).
        // Usiamo un approccio sequenziale o sincronizzato, ma l'impatto maggiore è sulla FFT.
        for (frameIdx in 0 until frames) {
            val re = FloatArray(nFft)
            val im = FloatArray(nFft)
            val offset = frameIdx * freqBins
            for (k in 0 until freqBins) {
                re[k] = stftRe[offset + k]
                im[k] = stftIm[offset + k]
            }
            for (k in freqBins until nFft) {
                re[k] = re[nFft - k]
                im[k] = -im[nFft - k]
            }
            fft(re, im, inverse = true)

            val start = frameIdx * hopLength
            for (i in 0 until nFft) {
                if (start + i < outputLen) {
                    output[start + i] += re[i] * window[i]
                    normSum[start + i] += windowSquareSum[i]
                }
            }
        }

        val result = FloatArray(signalLength)
        for (i in 0 until signalLength) {
            result[i] = if (i < outputLen && normSum[i] > 1e-8f) output[i] / normSum[i] else 0f
        }
        return@withContext result
    }
}
