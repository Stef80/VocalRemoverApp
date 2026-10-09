package com.example.vocalremover

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Suddivisione a finestre sovrapposte (25% di default) del segnale stereo in chunk di
 * dimensione fissa richiesta dal grafo ONNX (chunkSize = hopLength*(dimT-1) campioni).
 * Ogni chunk viene passato a [processChunk] (STFT->ONNX->ISTFT), poi ricombinato con
 * overlap-add pesato da una finestra di Hann SIMMETRICA (numpy.hanning). Replica
 * l'algoritmo MDXSeparator.demix() di audio-separator.
 *
 * NOTA: non thread-safe. Le callback di processStereoInto devono essere sincrone e non
 * devono trattenere i riferimenti ai chunkL/chunkR passati (vengono riutilizzati).
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
    private val useOverlap = overlap != 0.0
    private val fullChunkWindow: FloatArray? =
        if (useOverlap) symmetricHann(chunkSize) else null

    // Scratch riutilizzati per ogni chunk (evita ~2 MB di allocazioni per iterazione).
    private val chunkL = FloatArray(chunkSize)
    private val chunkR = FloatArray(chunkSize)

    init {
        require(genSize > 0) {
            "genSize deve essere positivo (chunkSize=$chunkSize, trim=$trim); " +
                    "aumentare dimT/hopLength o ridurre nFft"
        }
    }

    /** Numero esatto di chiamate a processChunk per un segnale di [n] campioni. */
    fun chunkCount(n: Int): Int {
        val step = ((1.0 - overlap) * chunkSize).toInt()
        val mixtureLen = trim + n + genSize + trim - (n % genSize)
        return (mixtureLen + step - 1) / step
    }

    fun process(mono: FloatArray, processChunk: (FloatArray) -> FloatArray): FloatArray {
        val (left, _) = processStereo(mono, mono) { cl, _ -> Pair(processChunk(cl), cl) }
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
        require(outLeft.size == left.size) {
            "L'output sinistro deve avere la stessa lunghezza dell'input"
        }
        require(outRight.size == right.size) {
            "L'output destro deve avere la stessa lunghezza dell'input"
        }

        val n = left.size
        val step = ((1.0 - overlap) * chunkSize).toInt()
        val pad = genSize + trim - (n % genSize)
        val mixtureLen = trim + n + pad

        // Accumulatori: usiamo direttamente outLeft/outRight per risparmiare memoria (evita 2 * FloatArray(n)).
        java.util.Arrays.fill(outLeft, 0f)
        java.util.Arrays.fill(outRight, 0f)
        val divider = FloatArray(n)

        val outStart = trim
        val outEnd = trim + n

        var i = 0
        while (i < mixtureLen) {
            val end = min(i + chunkSize, mixtureLen)
            val actualLen = end - i

            fillChunkPadded(left, n, i, actualLen, chunkL)
            fillChunkPadded(right, n, i, actualLen, chunkR)
            if (actualLen < chunkSize) {
                // Solo l'ultimo chunk è più corto: azzera la coda per non lasciare dati
                // stantii dall'iterazione precedente.
                java.util.Arrays.fill(chunkL, actualLen, chunkSize, 0f)
                java.util.Arrays.fill(chunkR, actualLen, chunkSize, 0f)
            }

            val (processedL, processedR) = processChunk(chunkL, chunkR)

            // Intersezione fra [i, i+actualLen) e [outStart, outEnd).
            val effStart = max(i, outStart)
            val effEnd = min(i + actualLen, outEnd)
            val effLen = effEnd - effStart
            if (effLen > 0) {
                val outOff = effStart - outStart
                val chunkOff = effStart - i
                if (useOverlap) {
                    val win = if (actualLen == chunkSize) fullChunkWindow!!
                    else symmetricHann(actualLen)
                    for (k in 0 until effLen) {
                        val w = win[chunkOff + k]
                        outLeft[outOff + k] += processedL[chunkOff + k] * w
                        outRight[outOff + k] += processedR[chunkOff + k] * w
                        divider[outOff + k] += w
                    }
                } else {
                    for (k in 0 until effLen) {
                        outLeft[outOff + k] += processedL[chunkOff + k]
                        outRight[outOff + k] += processedR[chunkOff + k]
                        divider[outOff + k] += 1f
                    }
                }
            }
            i += step
        }

        for (idx in 0 until n) {
            val d = divider[idx]
            if (d > 1e-8f) {
                outLeft[idx] /= d
                outRight[idx] /= d
            } else {
                outLeft[idx] = 0f
                outRight[idx] = 0f
            }
        }
    }

    /**
     * Riempie [dest] con i campioni del chunk che inizia a [chunkStart] nel dominio
     * "mixture" (con reflect-padding). Sostituisce il `paddedSample` per-campione con tre
     * loop specializzati: prefisso riflesso / centro contiguo / suffisso riflesso.
     */
    private fun fillChunkPadded(
        source: FloatArray,
        n: Int,
        chunkStart: Int,
        len: Int,
        dest: FloatArray
    ) {
        var k = 0

        // Prefisso riflesso: chunkStart + k < trim
        val prefixEnd = min(len, trim - chunkStart).coerceAtLeast(0)
        while (k < prefixEnd) {
            dest[k] = source[reflectIndex(trim - (chunkStart + k), n)]
            k++
        }

        // Centro contiguo: trim <= chunkStart + k < trim + n
        val middleEnd = min(len, trim + n - chunkStart)
        var srcIdx = chunkStart + k - trim
        while (k < middleEnd) {
            dest[k] = source[srcIdx]
            srcIdx++
            k++
        }

        // Suffisso riflesso: chunkStart + k >= trim + n
        while (k < len) {
            val tailIdx = (chunkStart + k) - (trim + n)
            dest[k] = source[reflectIndex(n - 2 - tailIdx, n)]
            k++
        }
    }

    private fun symmetricHann(len: Int): FloatArray {
        if (len == 1) return floatArrayOf(1f)
        return FloatArray(len) { nn ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * nn / (len - 1))).toFloat()
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
