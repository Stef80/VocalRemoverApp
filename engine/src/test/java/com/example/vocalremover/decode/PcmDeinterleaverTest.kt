package com.example.vocalremover.decode

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmDeinterleaverTest {

    private fun shorts(vararg values: Int): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
            flip()
        }

    @Test
    fun `16 bit stereo is split and normalised`() {
        val left = FloatArray(2)
        val right = FloatArray(2)

        val frames = PcmDeinterleaver.deinterleave(
            shorts(16384, -16384, 8192, 0), isFloat = false, channels = 2, left, right, offset = 0
        )

        assertEquals(2, frames)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), left, 1e-6f)
        assertArrayEquals(floatArrayOf(-0.5f, 0f), right, 1e-6f)
    }

    @Test
    fun `16 bit mono is copied to both channels`() {
        val left = FloatArray(2)
        val right = FloatArray(2)

        val frames = PcmDeinterleaver.deinterleave(
            shorts(8192, -8192), isFloat = false, channels = 1, left, right, offset = 0
        )

        assertEquals(2, frames)
        assertArrayEquals(floatArrayOf(0.25f, -0.25f), left, 1e-6f)
        assertArrayEquals(floatArrayOf(0.25f, -0.25f), right, 1e-6f)
    }

    @Test
    fun `float stereo is split as is`() {
        val left = FloatArray(2)
        val right = FloatArray(2)

        val frames = PcmDeinterleaver.deinterleave(
            floats(0.1f, 0.2f, 0.3f, 0.4f), isFloat = true, channels = 2, left, right, offset = 0
        )

        assertEquals(2, frames)
        assertArrayEquals(floatArrayOf(0.1f, 0.3f), left, 1e-6f)
        assertArrayEquals(floatArrayOf(0.2f, 0.4f), right, 1e-6f)
    }

    @Test
    fun `six channels keep only the first two`() {
        val left = FloatArray(1)
        val right = FloatArray(1)

        val frames = PcmDeinterleaver.deinterleave(
            floats(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), isFloat = true, channels = 6, left, right, offset = 0
        )

        assertEquals(1, frames)
        assertArrayEquals(floatArrayOf(0.1f), left, 1e-6f)
        assertArrayEquals(floatArrayOf(0.2f), right, 1e-6f)
    }

    @Test
    fun `writes from the given offset`() {
        val left = FloatArray(5)
        val right = FloatArray(5)

        PcmDeinterleaver.deinterleave(
            floats(0.1f, 0.2f, 0.3f, 0.4f), isFloat = true, channels = 2, left, right, offset = 3
        )

        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0.1f, 0.3f), left, 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0.2f, 0.4f), right, 1e-6f)
    }
}
