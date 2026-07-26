package com.example.vocalremover

import kotlin.math.*

/**
 * Implementa STFT (Short-Time Fourier Transform) e ISTFT
 * con finestra di Hann e metodo overlap-add.
 *
 * Parametri allineati al modello Spleeter 2-stems:
 *   n_fft      = 4096  → freq_bins = 2049
 *   hop_length = 1024
 *   sample_rate= 44100
 */
class StftProcessor(
    val nFft: Int = 4096,
    val hopLength: Int = 1024,
    val sampleRate: Int = 44100
) {
    val freqBins: Int = nFft / 2 + 1

    // ── Finestra di Hann ────────────────────────────────────────────────────
    private val window: FloatArray = FloatArray(nFft) { n ->
        (0.5f * (1.0 - cos(2.0 * PI * n / nFft))).toFloat()
    }

    // Somma dei quadrati della finestra per ISTFT (overlap-add normalizzato)
    private val windowSquareSum: FloatArray = FloatArray(nFft) { window[it] * window[it] }

    // ── FFT (Cooley-Tukey in-place, potenza di 2) ──────────────────────────
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        val n = re.size
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        // Butterfly
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

    // ── STFT ────────────────────────────────────────────────────────────────
    /**
     * Restituisce [stftRe, stftIm] ciascuno di shape [frames][freqBins].
     * Il segnale è mono float32 normalizzato in [-1, 1].
     */
    fun stft(signal: FloatArray): Pair<Array<FloatArray>, Array<FloatArray>> {
        val frames = (signal.size - nFft) / hopLength + 1
        val stftRe = Array(frames) { FloatArray(freqBins) }
        val stftIm = Array(frames) { FloatArray(freqBins) }

        for (frameIdx in 0 until frames) {
            val start = frameIdx * hopLength
            val re = FloatArray(nFft) { i ->
                if (start + i < signal.size) signal[start + i] * window[i] else 0f
            }
            val im = FloatArray(nFft)
            fft(re, im)
            for (k in 0 until freqBins) { stftRe[frameIdx][k] = re[k]; stftIm[frameIdx][k] = im[k] }
        }
        return Pair(stftRe, stftIm)
    }

    /** Spettrogramma di magnitudine: shape [frames][freqBins] */
    fun magnitude(stftRe: Array<FloatArray>, stftIm: Array<FloatArray>): Array<FloatArray> {
        return Array(stftRe.size) { f ->
            FloatArray(freqBins) { k ->
                sqrt(stftRe[f][k] * stftRe[f][k] + stftIm[f][k] * stftIm[f][k])
            }
        }
    }

    // ── ISTFT (overlap-add) ─────────────────────────────────────────────────
    /**
     * Ricostruisce il segnale PCM da STFT complessa.
     * [maskedRe][maskedIm] sono le STFT dopo applicazione della maschera.
     */
    fun istft(stftRe: Array<FloatArray>, stftIm: Array<FloatArray>, signalLength: Int): FloatArray {
        val frames = stftRe.size
        val outputLen = (frames - 1) * hopLength + nFft
        val output = FloatArray(outputLen)
        val normSum = FloatArray(outputLen)

        for (frameIdx in 0 until frames) {
            // Ricostruisci spettro completo (simmetria hermitiana)
            val re = FloatArray(nFft)
            val im = FloatArray(nFft)
            for (k in 0 until freqBins) {
                re[k] = stftRe[frameIdx][k]
                im[k] = stftIm[frameIdx][k]
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
        // Normalizza e ritaglia
        val result = FloatArray(signalLength)
        for (i in 0 until signalLength) {
            result[i] = if (normSum[i] > 1e-8f) output[i] / normSum[i] else 0f
        }
        return result
    }
}
