package com.example.vocalremover

import kotlin.math.cos
import kotlin.math.min

/**
 * Suddivisione a finestre sovrapposte (25% di default) del segnale stereo in chunk di
 * dimensione fissa richiesta dal grafo ONNX (chunkSize = hopLength*(dimT-1) campioni). Ogni
 * chunk viene passato a [processChunk] (che internamente fa STFT->ONNX->ISTFT, vedi
 * VocalRemover), poi ricombinato con overlap-add pesato da una finestra di Hann SIMMETRICA
 * (formula numpy.hanning, diversa dalla finestra "periodica" usata dentro la STFT). Replica
 * esattamente l'algoritmo MDXSeparator.demix() di audio-separator (il tool di riferimento
 * usato per validare la qualità del modello UVR-MDX-NET-Inst_HQ_5 in fase di benchmarking).
 * I due canali (L/R) sono processati in un unico ciclo, sugli stessi confini di chunk, perché
 * il modello richiede in input un unico tensore stereo a 4 canali (L_re, L_im, R_re, R_im)
 * per chunk, non due passate indipendenti.
 */
class MdxChunker(
    nFft: Int = 5120,
    hopLength: Int = 1024,
    dimT: Int = 256,
    private val overlap: Double = 0.25
) {
    private val trim = nFft / 2
    private val chunkSize = hopLength * (dimT - 1)
    private val genSize = chunkSize - 2 * trim
    private val fullChunkWindow: FloatArray? =
        if (overlap != 0.0) symmetricHann(chunkSize) else null

    init {
        require(genSize > 0) {
            "genSize deve essere positivo (chunkSize=$chunkSize, trim=$trim); " +
                "aumentare dimT/hopLength o ridurre nFft"
        }
    }

    /** Variante mono, implementata in termini di [processStereo] duplicando il canale. */
    fun process(mono: FloatArray, processChunk: (FloatArray) -> FloatArray): FloatArray {
        val (left, _) = processStereo(mono, mono) { chunkL, _ -> Pair(processChunk(chunkL), chunkL) }
        return left
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        processChunk: (FloatArray, FloatArray) -> Pair<FloatArray, FloatArray>
    ): Pair<FloatArray, FloatArray> {
        val outL = FloatArray(left.size)
        val outR = FloatArray(right.size)
        processStereoInto(left, right, outL, outR, processChunk)
        return Pair(outL, outR)
    }

    fun processStereoInto(
        left: FloatArray,
        right: FloatArray,
        outLeft: FloatArray,
        outRight: FloatArray,
        processChunk: (FloatArray, FloatArray) -> Pair<FloatArray, FloatArray>
    ) {
        require(left.size == right.size) { "I canali devono avere la stessa lunghezza" }
        require(outLeft.size == left.size) { "L'output sinistro deve avere la stessa lunghezza dell'input" }
        require(outRight.size == right.size) { "L'output destro deve avere la stessa lunghezza dell'input" }
        val n = left.size
        val step = ((1.0 - overlap) * chunkSize).toInt()
        val pad = genSize + trim - (n % genSize)
        val mixtureLen = trim + n + pad
        val resultL = FloatArray(mixtureLen)
        val resultR = FloatArray(mixtureLen)
        val divider = FloatArray(mixtureLen)

        var i = 0
        while (i < mixtureLen) {
            val end = min(i + chunkSize, mixtureLen)
            val actualLen = end - i

            val chunkL = FloatArray(chunkSize)
            val chunkR = FloatArray(chunkSize)
            for (k in 0 until actualLen) {
                chunkL[k] = paddedSample(left, n, pad, i + k)
                chunkR[k] = paddedSample(right, n, pad, i + k)
            }

            val (processedL, processedR) = processChunk(chunkL, chunkR)

            if (overlap != 0.0) {
                val win = if (actualLen == chunkSize) fullChunkWindow!! else symmetricHann(actualLen)
                for (k in 0 until actualLen) {
                    resultL[i + k] += processedL[k] * win[k]
                    resultR[i + k] += processedR[k] * win[k]
                    divider[i + k] += win[k]
                }
            } else {
                for (k in 0 until actualLen) {
                    resultL[i + k] += processedL[k]
                    resultR[i + k] += processedR[k]
                    divider[i + k] += 1f
                }
            }
            i += step
        }

        for (idx in 0 until n) {
            val srcIdx = trim + idx
            val d = divider[srcIdx]
            outLeft[idx] = if (d > 1e-8f) resultL[srcIdx] / d else 0f
            outRight[idx] = if (d > 1e-8f) resultR[srcIdx] / d else 0f
        }
    }

    /** Finestra di Hann SIMMETRICA (formula numpy.hanning: denominatore len-1, non len). */
    private fun symmetricHann(len: Int): FloatArray {
        if (len == 1) return floatArrayOf(1f)
        return FloatArray(len) { n ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * n / (len - 1))).toFloat()
        }
    }

    private fun paddedSample(source: FloatArray, sourceLen: Int, pad: Int, index: Int): Float {
        return when {
            index < trim -> source[reflectIndex(trim - index, sourceLen)]
            index < trim + sourceLen -> source[index - trim]
            else -> {
                val tailIndex = index - (trim + sourceLen)
                source[reflectIndex(sourceLen - 2 - tailIndex, sourceLen)]
            }
        }
    }

    private fun reflectIndex(indexIn: Int, n: Int): Int {
        if (n == 1) return 0
        var idx = indexIn
        val period = 2 * (n - 1)
        idx %= period
        if (idx < 0) idx += period
        return if (idx < n) idx else period - idx
    }
}
