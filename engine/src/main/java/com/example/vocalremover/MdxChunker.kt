package com.example.vocalremover

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Splits the stereo signal into overlapping windows (25% by default) of the fixed
 * chunk size required by the ONNX graph (chunkSize = hopLength*(dimT-1) samples).
 * Each chunk is passed to [processChunk] (STFT->ONNX->ISTFT), then recombined with
 * overlap-add weighted by a SYMMETRIC Hann window (numpy.hanning). Replicates
 * the MDXSeparator.demix() algorithm of audio-separator.
 *
 * NOTE: not thread-safe. The processStereoInto callbacks must be synchronous and must
 * not keep references to the chunkL/chunkR they receive (they are reused).
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

    // Scratch buffers reused for every chunk (avoids ~2 MB of allocations per iteration).
    private val chunkL = FloatArray(chunkSize)
    private val chunkR = FloatArray(chunkSize)

    init {
        require(genSize > 0) {
            "genSize must be positive (chunkSize=$chunkSize, trim=$trim); " +
                    "increase dimT/hopLength or reduce nFft"
        }
    }

    /** Exact number of processChunk calls for a signal of [n] samples. */
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
        require(left.size == right.size) { "Channels must have the same length" }
        require(outLeft.size == left.size) {
            "The left output must have the same length as the input"
        }
        require(outRight.size == right.size) {
            "The right output must have the same length as the input"
        }

        val n = left.size
        val step = ((1.0 - overlap) * chunkSize).toInt()
        val pad = genSize + trim - (n % genSize)
        val mixtureLen = trim + n + pad

        // Accumulators: we use outLeft/outRight directly to save memory (avoids 2 * FloatArray(n)).
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
                // Only the last chunk is shorter: zero the tail so no stale data
                // is left from the previous iteration.
                java.util.Arrays.fill(chunkL, actualLen, chunkSize, 0f)
                java.util.Arrays.fill(chunkR, actualLen, chunkSize, 0f)
            }

            val (processedL, processedR) = processChunk(chunkL, chunkR)

            // Intersection of [i, i+actualLen) and [outStart, outEnd).
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
     * Fills [dest] with the samples of the chunk starting at [chunkStart] in the
     * "mixture" domain (with reflect padding). Replaces the per-sample `paddedSample` with three
     * specialized loops: reflected prefix / contiguous center / reflected suffix.
     */
    private fun fillChunkPadded(
        source: FloatArray,
        n: Int,
        chunkStart: Int,
        len: Int,
        dest: FloatArray
    ) {
        var k = 0

        // Reflected prefix: chunkStart + k < trim
        val prefixEnd = min(len, trim - chunkStart).coerceAtLeast(0)
        while (k < prefixEnd) {
            dest[k] = source[reflectIndex(trim - (chunkStart + k), n)]
            k++
        }

        // Contiguous center: trim <= chunkStart + k < trim + n
        val middleEnd = min(len, trim + n - chunkStart)
        var srcIdx = chunkStart + k - trim
        while (k < middleEnd) {
            dest[k] = source[srcIdx]
            srcIdx++
            k++
        }

        // Reflected suffix: chunkStart + k >= trim + n
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
