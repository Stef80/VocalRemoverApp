package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MixedRadixFftTest {

    private fun naiveDft(re: FloatArray, im: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * PI * ((k.toLong() * t) % n) / n
                val c = cos(ang)
                val s = sin(ang)
                sr += re[t] * c - im[t] * s
                si += re[t] * s + im[t] * c
            }
            outRe[k] = sr
            outIm[k] = si
        }
        return Pair(outRe, outIm)
    }

    private fun randomSignal(n: Int, seed: Int): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        return Pair(
            FloatArray(n) { rnd.nextFloat() * 2f - 1f },
            FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        )
    }

    private fun assertMatchesNaiveDft(n: Int, delta: Float) {
        val (re, im) = randomSignal(n, seed = n)
        val (expRe, expIm) = naiveDft(re, im)

        MixedRadixFft(n).transform(re, im)

        for (k in 0 until n) {
            assertEquals("n=$n re[$k]", expRe[k].toFloat(), re[k], delta)
            assertEquals("n=$n im[$k]", expIm[k].toFloat(), im[k], delta)
        }
    }

    @Test
    fun `supports powers of two and five times powers of two only`() {
        assertTrue(MixedRadixFft.supports(5120))
        assertTrue(MixedRadixFft.supports(1024))
        assertTrue(MixedRadixFft.supports(10))
        assertTrue(MixedRadixFft.supports(5))
        assertFalse(MixedRadixFft.supports(6))
        assertFalse(MixedRadixFft.supports(25))
        assertFalse(MixedRadixFft.supports(0))
    }

    @Test
    fun `forward matches naive DFT for small mixed radix sizes`() {
        assertMatchesNaiveDft(5, 1e-4f)
        assertMatchesNaiveDft(10, 1e-4f)
        assertMatchesNaiveDft(40, 1e-4f)
        assertMatchesNaiveDft(16, 1e-4f)
    }

    @Test
    fun `forward matches naive DFT for model size 5120`() {
        assertMatchesNaiveDft(5120, 5e-3f)
    }

    @Test
    fun `inverse undoes forward for model size 5120`() {
        val (re, im) = randomSignal(5120, seed = 7)
        val origRe = re.copyOf()
        val origIm = im.copyOf()
        val fft = MixedRadixFft(5120)

        fft.transform(re, im)
        fft.transform(re, im, inverse = true)

        for (i in re.indices) {
            assertEquals("re[$i]", origRe[i], re[i], 1e-5f)
            assertEquals("im[$i]", origIm[i], im[i], 1e-5f)
        }
    }

    @Test
    fun `matches Bluestein output for model size 5120`() {
        val (re, im) = randomSignal(5120, seed = 11)
        val bRe = re.copyOf()
        val bIm = im.copyOf()

        MixedRadixFft(5120).transform(re, im)
        BluesteinFft.transform(bRe, bIm)

        // Bluestein (FFT interne da 16384 punti) accumula più errore float: tolleranza relativa 1e-4.
        var maxMag = 0f
        for (i in re.indices) maxMag = maxOf(maxMag, kotlin.math.abs(bRe[i]), kotlin.math.abs(bIm[i]))
        val delta = maxMag * 1e-4f
        for (i in re.indices) {
            assertEquals("re[$i]", bRe[i], re[i], delta)
            assertEquals("im[$i]", bIm[i], im[i], delta)
        }
    }
}
