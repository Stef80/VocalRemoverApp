package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class MdxStftStereoTest {

    private fun randomSignal(n: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        return FloatArray(n) { rnd.nextFloat() * 2f - 1f }
    }

    private fun assertArraysClose(label: String, expected: FloatArray, actual: FloatArray, delta: Float) {
        assertEquals("$label size", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("$label[$i]", expected[i], actual[i], delta)
        }
    }

    private fun assertForwardStereoMatchesPerChannel(nFft: Int, hop: Int, dimF: Int, length: Int, delta: Float) {
        val left = randomSignal(length, seed = 1)
        val right = randomSignal(length, seed = 2)
        val reference = MdxStftProcessor(nFft, hop, dimF)
        val (lRe, lIm) = reference.forward(left)
        val (rRe, rIm) = reference.forward(right)

        val processor = MdxStftProcessor(nFft, hop, dimF)
        val size = processor.frameCountFor(length) * dimF
        val outLRe = FloatArray(size)
        val outLIm = FloatArray(size)
        val outRRe = FloatArray(size)
        val outRIm = FloatArray(size)
        processor.forwardStereoInto(left, right, outLRe, outLIm, outRRe, outRIm)

        assertArraysClose("LRe", lRe, outLRe, delta)
        assertArraysClose("LIm", lIm, outLIm, delta)
        assertArraysClose("RRe", rRe, outRRe, delta)
        assertArraysClose("RIm", rIm, outRIm, delta)
    }

    private fun assertInverseStereoMatchesPerChannel(nFft: Int, hop: Int, frames: Int, length: Int, delta: Float) {
        val reference = MdxStftProcessor(nFft, hop, nFft / 2)
        val bins = reference.freqBins
        // Spettri arbitrari, anche con immaginario non nullo su DC e Nyquist.
        val lRe = randomSignal(frames * bins, seed = 3)
        val lIm = randomSignal(frames * bins, seed = 4)
        val rRe = randomSignal(frames * bins, seed = 5)
        val rIm = randomSignal(frames * bins, seed = 6)
        val expectedL = reference.inverse(lRe, lIm, frames, length)
        val expectedR = reference.inverse(rRe, rIm, frames, length)

        val processor = MdxStftProcessor(nFft, hop, nFft / 2)
        val outL = FloatArray(length)
        val outR = FloatArray(length)
        processor.inverseStereoInto(lRe, lIm, rRe, rIm, frames, length, outL, outR)

        assertArraysClose("L", expectedL, outL, delta)
        assertArraysClose("R", expectedR, outR, delta)
    }

    @Test
    fun `forward stereo matches per channel forward for small non mixed radix size`() {
        assertForwardStereoMatchesPerChannel(nFft = 6, hop = 2, dimF = 3, length = 12, delta = 1e-4f)
    }

    @Test
    fun `forward stereo matches per channel forward for model size`() {
        assertForwardStereoMatchesPerChannel(nFft = 5120, hop = 1024, dimF = 2560, length = 1024 * 7, delta = 5e-3f)
    }

    @Test
    fun `inverse stereo matches per channel inverse for small non mixed radix size`() {
        assertInverseStereoMatchesPerChannel(nFft = 6, hop = 2, frames = 7, length = 12, delta = 1e-4f)
    }

    @Test
    fun `inverse stereo matches per channel inverse for model size`() {
        assertInverseStereoMatchesPerChannel(nFft = 5120, hop = 1024, frames = 8, length = 1024 * 7, delta = 1e-4f)
    }
}
