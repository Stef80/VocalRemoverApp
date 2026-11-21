package com.example.vocalremover.backend

import com.example.vocalremover.StereoPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OutputQualityTest {

    private fun tone(n: Int = 4000): StereoPcm {
        val l = FloatArray(n) { 0.5f * kotlin.math.sin(it * 0.05).toFloat() }
        val r = FloatArray(n) { 0.4f * kotlin.math.sin(it * 0.07).toFloat() }
        return StereoPcm(l, r)
    }

    @Test
    fun `stereo snr is infinite for identical signals`() {
        val ref = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertEquals(Double.POSITIVE_INFINITY, OutputQuality.stereoSnrDb(ref, ref, ref.copyOf(), ref.copyOf()), 0.0)
    }

    @Test
    fun `snr is 20 dB when error amplitude is a tenth of the reference`() {
        val ref = floatArrayOf(1f, -1f, 1f, -1f)
        val out = FloatArray(ref.size) { ref[it] * 1.1f }
        assertEquals(20.0, OutputQuality.stereoSnrDb(ref, FloatArray(0), out, FloatArray(0)), 1e-4)
    }

    @Test
    fun `snr is zero dB for silent output`() {
        val ref = floatArrayOf(0.5f, -0.5f)
        assertEquals(0.0, OutputQuality.stereoSnrDb(ref, FloatArray(0), FloatArray(2), FloatArray(0)), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `snr rejects different lengths`() {
        OutputQuality.stereoSnrDb(FloatArray(2), FloatArray(0), FloatArray(3), FloatArray(0))
    }

    @Test
    fun `stereo snr pools both channels`() {
        val l = floatArrayOf(1f, 1f)
        val r = floatArrayOf(1f, 1f)
        val snr = OutputQuality.stereoSnrDb(l, r, l.copyOf(), floatArrayOf(0f, 0f))
        assertEquals(10.0 * Math.log10(2.0), snr, 1e-9)
    }

    @Test
    fun `rms of constant signal is its absolute value`() {
        assertEquals(0.25, OutputQuality.rms(FloatArray(10) { -0.25f }), 1e-9)
    }

    @Test
    fun `all finite detects NaN and infinity`() {
        assertTrue(OutputQuality.allFinite(floatArrayOf(0f, 1f)))
        assertFalse(OutputQuality.allFinite(floatArrayOf(0f, Float.NaN)))
        assertFalse(OutputQuality.allFinite(floatArrayOf(Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `verdict passes identical output`() {
        val x = tone()
        assertNull(OutputQuality.verdict(x, x))
    }

    @Test
    fun `verdict rejects NaN`() {
        val x = tone()
        val bad = StereoPcm(x.left.copyOf().also { it[3] = Float.NaN }, x.right.copyOf())
        val reason = OutputQuality.verdict(x, bad)
        assertNotNull(reason)
        assertTrue(reason, reason!!.contains("NaN"))
    }

    @Test
    fun `verdict rejects silence`() {
        val x = tone()
        val reason = OutputQuality.verdict(x, StereoPcm(FloatArray(x.left.size), FloatArray(x.right.size)))
        assertNotNull(reason)
        assertTrue(reason, reason!!.contains("silent"))
    }

    @Test
    fun `verdict rejects low snr`() {
        val x = tone()
        val rnd = Random(1)
        // Noise with rms about 0.316 of the signal rms gives roughly 10 dB SNR.
        val noisy = StereoPcm(
            FloatArray(x.left.size) { x.left[it] + 0.16f * (rnd.nextFloat() * 2 - 1) * 1.7f },
            FloatArray(x.right.size) { x.right[it] + 0.16f * (rnd.nextFloat() * 2 - 1) * 1.7f }
        )
        val reason = OutputQuality.verdict(x, noisy)
        assertNotNull(reason)
        assertTrue(reason, reason!!.contains("SNR"))
    }
}
