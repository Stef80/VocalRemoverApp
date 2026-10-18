package com.example.vocalremover.decode

import com.example.vocalremover.StereoPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LinearResamplerTest {

    @Test
    fun `same rate returns the same instance`() {
        val pcm = StereoPcm(FloatArray(10) { it * 0.1f }, FloatArray(10))

        assertSame(pcm, LinearResampler.resample(pcm, 44100, 44100))
    }

    @Test
    fun `doubling the rate doubles the length`() {
        val pcm = StereoPcm(FloatArray(1000), FloatArray(1000))

        val out = LinearResampler.resample(pcm, 22050, 44100)

        assertEquals(2000, out.size)
        assertEquals(2000, out.right.size)
    }

    @Test
    fun `empty input gives empty output`() {
        val out = LinearResampler.resample(StereoPcm(FloatArray(0), FloatArray(0)), 22050, 44100)

        assertEquals(0, out.size)
    }

    @Test
    fun `1 kHz sine keeps frequency and amplitude`() {
        val source = FloatArray(2205) { (0.5 * sin(2 * PI * 1000 * it / 22050.0)).toFloat() }
        val pcm = StereoPcm(source, source.copyOf())

        val out = LinearResampler.resample(pcm, 22050, 44100)

        for (i in 10 until out.size - 10) {
            val expected = 0.5 * sin(2 * PI * 1000 * i / 44100.0)
            assertTrue("L[$i]", abs(out.left[i] - expected) < 0.01)
            assertTrue("R[$i]", abs(out.right[i] - expected) < 0.01)
        }
    }
}
