package com.example.vocalremover.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcm16SerializerTest {

    @Test
    fun `serializes a mono 44100 Hz PCM16 WAV header`() {
        val wav = WavPcm16Serializer.toWavBytes(floatArrayOf(0f, 0.5f))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", header.readFourCc())
        assertEquals(40, header.int)
        assertEquals("WAVE", header.readFourCc())
        assertEquals("fmt ", header.readFourCc())
        assertEquals(16, header.int)
        assertEquals(1, header.short.toInt())
        assertEquals(1, header.short.toInt())
        assertEquals(44_100, header.int)
        assertEquals(88_200, header.int)
        assertEquals(2, header.short.toInt())
        assertEquals(16, header.short.toInt())
        assertEquals("data", header.readFourCc())
        assertEquals(4, header.int)
        assertEquals(48, wav.size)
    }

    @Test
    fun `clamps float samples and encodes PCM16 endpoints`() {
        val wav = WavPcm16Serializer.toWavBytes(
            floatArrayOf(-1.2f, -1f, -0.5f, 0f, 0.5f, 1f, 1.2f)
        )
        val samples = ByteBuffer.wrap(wav, 44, wav.size - 44)
            .order(ByteOrder.LITTLE_ENDIAN)
        val encoded = ShortArray(7) { samples.short }

        assertArrayEquals(
            shortArrayOf(-32_768, -32_768, -16_383, 0, 16_383, 32_767, 32_767),
            encoded
        )
    }

    private fun ByteBuffer.readFourCc(): String =
        ByteArray(4).also(::get).toString(Charsets.US_ASCII)
}
