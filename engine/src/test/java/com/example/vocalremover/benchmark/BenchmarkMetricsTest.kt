package com.example.vocalremover.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkMetricsTest {

    @Test
    fun `snr is infinite for identical signals`() {
        val ref = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertEquals(Double.POSITIVE_INFINITY, BenchmarkMetrics.snrDb(ref, ref.copyOf()), 0.0)
    }

    @Test
    fun `snr is 20 dB when error amplitude is a tenth of the reference`() {
        val ref = floatArrayOf(1f, -1f, 1f, -1f)
        val out = FloatArray(ref.size) { ref[it] * 1.1f }
        assertEquals(20.0, BenchmarkMetrics.snrDb(ref, out), 1e-4)
    }

    @Test
    fun `snr is zero dB for silent output`() {
        val ref = floatArrayOf(0.5f, -0.5f)
        assertEquals(0.0, BenchmarkMetrics.snrDb(ref, FloatArray(2)), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `snr rejects different lengths`() {
        BenchmarkMetrics.snrDb(FloatArray(2), FloatArray(3))
    }

    @Test
    fun `stereo snr pools both channels`() {
        val l = floatArrayOf(1f, 1f)
        val r = floatArrayOf(1f, 1f)
        // Errore solo su R (ampiezza 1): energia ref 4, errore 2 → 10·log10(2) dB
        val snr = BenchmarkMetrics.stereoSnrDb(l, r, l.copyOf(), floatArrayOf(0f, 0f))
        assertEquals(10.0 * Math.log10(2.0), snr, 1e-9)
    }

    @Test
    fun `rms of constant signal is its absolute value`() {
        assertEquals(0.25, BenchmarkMetrics.rms(FloatArray(10) { -0.25f }), 1e-9)
    }

    @Test
    fun `all finite detects NaN and infinity`() {
        assertTrue(BenchmarkMetrics.allFinite(floatArrayOf(0f, 1f)))
        assertFalse(BenchmarkMetrics.allFinite(floatArrayOf(0f, Float.NaN)))
        assertFalse(BenchmarkMetrics.allFinite(floatArrayOf(Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `synthetic signal is deterministic stereo with real channel difference`() {
        val a = SyntheticStereoSignal.generate(seconds = 1.0, sampleRate = 44100, seed = 7)
        val b = SyntheticStereoSignal.generate(seconds = 1.0, sampleRate = 44100, seed = 7)
        assertEquals(44100, a.first.size)
        assertEquals(44100, a.second.size)
        assertTrue(a.first.contentEquals(b.first))
        assertTrue(a.second.contentEquals(b.second))
        assertFalse(a.first.contentEquals(a.second))
        val peak = (a.first + a.second).maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak", peak in 0.1f..0.95f)
    }

    @Test
    fun `synthetic signal changes with seed`() {
        val a = SyntheticStereoSignal.generate(1.0, 44100, seed = 1)
        val b = SyntheticStereoSignal.generate(1.0, 44100, seed = 2)
        assertNotEquals(a.first.toList(), b.first.toList())
    }

    @Test
    fun `report json contains device backend timings and quality`() {
        val json = BenchmarkReport(
            manufacturer = "OPPO", model = "CPH2791", soc = "MT6993", sdk = 36,
            backend = "qnn", audioSeconds = 20.0,
            sessionLoadMs = 265, processMs = 8000, referenceProcessMs = 120000,
            snrDb = 42.5, outputRms = 0.1, passed = true, failure = null
        ).toJson()
        assertTrue(json, json.contains("\"model\":\"CPH2791\""))
        assertTrue(json, json.contains("\"backend\":\"qnn\""))
        assertTrue(json, json.contains("\"processMs\":8000"))
        assertTrue(json, json.contains("\"realtimeFactor\":0.4"))
        assertTrue(json, json.contains("\"snrDb\":42.5"))
        assertTrue(json, json.contains("\"passed\":true"))
        assertTrue(json, json.contains("\"failure\":null"))
    }

    @Test
    fun `report json escapes strings and encodes infinite snr as null`() {
        val json = BenchmarkReport(
            manufacturer = "A\"B", model = "x", soc = "y", sdk = 30,
            backend = "cpu", audioSeconds = 10.0,
            sessionLoadMs = 1, processMs = 1, referenceProcessMs = null,
            snrDb = Double.POSITIVE_INFINITY, outputRms = 0.1, passed = false, failure = "bad\nline"
        ).toJson()
        assertTrue(json, json.contains("\"manufacturer\":\"A\\\"B\""))
        assertTrue(json, json.contains("\"snrDb\":null"))
        assertTrue(json, json.contains("\"referenceProcessMs\":null"))
        assertTrue(json, json.contains("\"failure\":\"bad\\nline\""))
    }
}
