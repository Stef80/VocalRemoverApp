package com.example.vocalremover

import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT/ISTFT "centrata" (con reflect-padding, come torch.stft/torch.istft con center=True)
 * per il modello UVR-MDX-NET-Inst_HQ_5 (nFft=5120, non potenza di 2 -> usa [BluesteinFft]).
 * A differenza di [StftProcessor] (nFft=4096, non centrata, usata dal precedente modello
 * Open-Unmix), questa classe implementa la convenzione esatta richiesta da MDX-Net: padding
 * riflesso di nFft/2 campioni a inizio/fine segnale prima del framing, e ritaglio in
 * frequenza a [dimF] bin (il modello lavora solo sulle frequenze più basse, scartando la
 * coda alta dello spettro).
 */
class MdxStftProcessor(
    val nFft: Int = 5120,
    val hopLength: Int = 1024,
    val dimF: Int = 2560
) {
    val freqBins: Int = nFft / 2 + 1

    // Finestra di Hann "periodica" (denominatore = nFft), identica per formula a quella di
    // StftProcessor ma con nFft diverso. Usata per l'analisi (forward) e la sintesi (inverse).
    private val window: FloatArray = FloatArray(nFft) { n ->
        (0.5f * (1.0 - cos(2.0 * PI * n / nFft))).toFloat()
    }
    private val windowSquareSum: FloatArray = FloatArray(nFft) { window[it] * window[it] }

    fun frameCountFor(signalLength: Int): Int = signalLength / hopLength + 1

    /**
     * STFT centrata: applica reflect-padding di nFft/2 campioni a inizio e fine di [signal],
     * poi calcola una FFT per frame con hop [hopLength]. Ritorna Re/Im come array piatti
     * [frames * dimF] (già ritagliati alle prime [dimF] bin di frequenza).
     */
    fun forward(signal: FloatArray): Pair<FloatArray, FloatArray> {
        val half = nFft / 2
        val padded = reflectPad(signal, half)
        val frames = frameCountFor(signal.size)

        val outRe = FloatArray(frames * dimF)
        val outIm = FloatArray(frames * dimF)
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)

        for (frame in 0 until frames) {
            val start = frame * hopLength
            for (i in 0 until nFft) {
                re[i] = padded[start + i] * window[i]
                im[i] = 0f
            }
            BluesteinFft.transform(re, im)
            for (bin in 0 until dimF) {
                outRe[frame * dimF + bin] = re[bin]
                outIm[frame * dimF + bin] = im[bin]
            }
        }
        return Pair(outRe, outIm)
    }

    /**
     * ISTFT centrata: overlap-add standard (normalizzato per somma dei quadrati della finestra,
     * come [StftProcessor.istft]) seguito dal ritaglio di nFft/2 campioni a inizio e fine per
     * annullare il reflect-padding introdotto da [forward]. [re]/[im] sono piatti
     * [frames * freqBins] (NON ritagliati a dimF: il chiamante deve prima riportarli a
     * [freqBins] con zero-padding, dato che il modello produce solo le prime dimF bin).
     */
    fun inverse(re: FloatArray, im: FloatArray, frames: Int, outputLength: Int): FloatArray {
        val paddedLen = (frames - 1) * hopLength + nFft
        val output = FloatArray(paddedLen)
        val normSum = FloatArray(paddedLen)
        val frameRe = FloatArray(nFft)
        val frameIm = FloatArray(nFft)

        for (frame in 0 until frames) {
            val offset = frame * freqBins
            for (k in 0 until freqBins) {
                frameRe[k] = re[offset + k]
                frameIm[k] = im[offset + k]
            }
            for (k in freqBins until nFft) {
                frameRe[k] = frameRe[nFft - k]
                frameIm[k] = -frameIm[nFft - k]
            }
            BluesteinFft.transform(frameRe, frameIm, inverse = true)

            val start = frame * hopLength
            for (i in 0 until nFft) {
                output[start + i] += frameRe[i] * window[i]
                normSum[start + i] += windowSquareSum[i]
            }
        }

        for (i in 0 until paddedLen) {
            if (normSum[i] > 1e-8f) output[i] /= normSum[i]
        }

        // Rimuove il reflect-padding (nFft/2 campioni per lato) introdotto da forward().
        val half = nFft / 2
        val result = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val srcIdx = half + i
            result[i] = if (srcIdx < paddedLen) output[srcIdx] else 0f
        }
        return result
    }

    /**
     * Riflette [signal] di [pad] campioni a inizio e fine, modalità 'reflect' di numpy/torch
     * (esclude il campione di bordo stesso dallo specchio): per pad=3 e segnale [a,b,c,d,...],
     * il prefisso diventa [d,c,b, a,b,c,d,...] (specchiato attorno ad "a", "a" escluso).
     */
    private fun reflectPad(signal: FloatArray, pad: Int): FloatArray {
        val n = signal.size
        val out = FloatArray(n + 2 * pad)
        for (i in 0 until pad) {
            out[i] = signal[reflectIndex(pad - i, n)]
        }
        System.arraycopy(signal, 0, out, pad, n)
        for (i in 0 until pad) {
            out[pad + n + i] = signal[reflectIndex(n - 2 - i, n)]
        }
        return out
    }

    /** Indice riflesso (modalità 'reflect') dentro [0, n) per un indice potenzialmente fuori range. */
    private fun reflectIndex(indexIn: Int, n: Int): Int {
        var idx = indexIn
        if (n == 1) return 0
        val period = 2 * (n - 1)
        idx %= period
        if (idx < 0) idx += period
        return if (idx < n) idx else period - idx
    }
}
