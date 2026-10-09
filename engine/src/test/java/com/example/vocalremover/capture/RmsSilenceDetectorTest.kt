package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class RmsSilenceDetectorTest {

    @Test
    fun `rms of all-zero buffer is zero`() {
        val samples = FloatArray(1024) { 0f }
        assertEquals(0f, RmsSilenceDetector.rms(samples), 0.0001f)
    }

    @Test
    fun `rms of constant amplitude buffer equals that amplitude`() {
        val samples = FloatArray(1024) { 0.5f }
        assertEquals(0.5f, RmsSilenceDetector.rms(samples), 0.0001f)
    }

    @Test
    fun `all-zero buffer is detected as silent`() {
        val samples = FloatArray(1024) { 0f }
        assertTrue(RmsSilenceDetector.isSilent(samples))
    }

    @Test
    fun `buffer with audible sine signal is not silent`() {
        val samples = FloatArray(1024) { i -> sin(i * 0.1).toFloat() * 0.3f }
        assertFalse(RmsSilenceDetector.isSilent(samples))
    }

    @Test
    fun `empty buffer is treated as silent`() {
        assertTrue(RmsSilenceDetector.isSilent(FloatArray(0)))
    }
}
