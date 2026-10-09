package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test

class BluesteinFftTest {

    private fun assertComplexArraysEqual(
        expectedRe: FloatArray, expectedIm: FloatArray,
        actualRe: FloatArray, actualIm: FloatArray, delta: Float = 1e-3f
    ) {
        for (i in expectedRe.indices) {
            assertEquals("re[$i]", expectedRe[i], actualRe[i], delta)
            assertEquals("im[$i]", expectedIm[i], actualIm[i], delta)
        }
    }

    @Test
    fun `matches known DFT for N=6 (non power of 2)`() {
        val re = floatArrayOf(0.4967f, -0.1383f, 0.6477f, 1.523f, -0.2342f, -0.2341f)
        val im = FloatArray(6)

        BluesteinFft.transform(re, im)

        val expectedRe = floatArrayOf(2.0608f, -1.41925f, 1.99915f, -0.2404f, 1.99915f, -1.41925f)
        val expectedIm = floatArrayOf(0.0f, -0.846713f, 0.680783f, 0.0f, -0.680783f, 0.846713f)
        assertComplexArraysEqual(expectedRe, expectedIm, re, im)
    }

    @Test
    fun `matches known DFT for N=10 (non power of 2)`() {
        val re = floatArrayOf(
            1.5792f, 0.7674f, -0.4695f, 0.5426f, -0.4634f,
            -0.4657f, 0.242f, -1.9133f, -1.7249f, -0.5623f
        )
        val im = FloatArray(10)

        BluesteinFft.transform(re, im)

        val expectedRe = floatArrayOf(
            -2.4679f, 2.135408f, 3.99269f, 2.579492f, 0.02501f,
            0.7947f, 0.02501f, 2.579492f, 3.99269f, 2.135408f
        )
        val expectedIm = floatArrayOf(
            0.0f, -3.89661f, -1.229859f, 1.587703f, -2.337945f,
            -0.0f, 2.337945f, -1.587703f, 1.229859f, 3.89661f
        )
        assertComplexArraysEqual(expectedRe, expectedIm, re, im)
    }

    @Test
    fun `inverse of forward recovers original signal for N=6`() {
        val original = floatArrayOf(0.4967f, -0.1383f, 0.6477f, 1.523f, -0.2342f, -0.2341f)
        val re = original.copyOf()
        val im = FloatArray(6)

        BluesteinFft.transform(re, im)
        BluesteinFft.transform(re, im, inverse = true)

        for (i in original.indices) {
            assertEquals(original[i], re[i], 1e-3f)
            assertEquals(0f, im[i], 1e-3f)
        }
    }

    @Test
    fun `inverse of forward recovers original signal for N=5120 (actual model size)`() {
        val n = 5120
        val rnd = java.util.Random(7)
        val original = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val re = original.copyOf()
        val im = FloatArray(n)

        BluesteinFft.transform(re, im)
        BluesteinFft.transform(re, im, inverse = true)

        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-2f)
            assertEquals(0f, im[i], 1e-2f)
        }
    }

    @Test
    fun `reusable workspace preserves independent transforms`() {
        val workspace = BluesteinFft.newWorkspace(6)
        val first = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val second = floatArrayOf(6f, 5f, 4f, 3f, 2f, 1f)
        val firstIm = FloatArray(6)
        val secondIm = FloatArray(6)

        BluesteinFft.transform(first, firstIm, workspace = workspace)
        BluesteinFft.transform(second, secondIm, workspace = workspace)

        val expectedFirstRe = floatArrayOf(21f, -3f, -3f, -3f, -3f, -3f)
        val expectedFirstIm = floatArrayOf(0f, 5.196152f, 1.732051f, 0f, -1.732051f, -5.196152f)
        val expectedSecondRe = floatArrayOf(21f, 3f, 3f, 3f, 3f, 3f)
        val expectedSecondIm = floatArrayOf(0f, -5.196152f, -1.732051f, 0f, 1.732051f, 5.196152f)
        assertComplexArraysEqual(expectedFirstRe, expectedFirstIm, first, firstIm)
        assertComplexArraysEqual(expectedSecondRe, expectedSecondIm, second, secondIm)
    }

    @Test
    fun `matches existing radix-2 FFT for power-of-2 size (cross-check)`() {
        // Bluestein must agree with the existing StftProcessor-style radix-2 FFT for N=8.
        val re1 = floatArrayOf(1f, 2f, 3f, 4f, -1f, -2f, 0.5f, 0.25f)
        val im1 = FloatArray(8)
        val re2 = re1.copyOf()
        val im2 = FloatArray(8)

        BluesteinFft.transform(re1, im1)
        radix2Reference(re2, im2)

        for (i in 0 until 8) {
            assertEquals(re2[i], re1[i], 1e-3f)
            assertEquals(im2[i], im1[i], 1e-3f)
        }
    }

    /** Minimal radix-2 reference copied from StftProcessor's algorithm, for cross-checking only. */
    private fun radix2Reference(re: FloatArray, im: FloatArray) {
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
            val ang = 2.0 * Math.PI / len
            val wRe = Math.cos(ang).toFloat()
            val wIm = Math.sin(ang).toFloat()
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
    }
}
