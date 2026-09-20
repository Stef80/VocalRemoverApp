package com.example.vocalremover.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.OutputStream
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

    @Test
    fun `serializes stereo pcm without altering channel order`() {
        val wav = WavPcm16StereoSerializer.toWavBytes(
            left = floatArrayOf(1f, 0f),
            right = floatArrayOf(0f, -1f)
        )
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", header.readFourCc())
        assertEquals(44, header.int)
        assertEquals("WAVE", header.readFourCc())
        assertEquals("fmt ", header.readFourCc())
        assertEquals(16, header.int)
        assertEquals(1, header.short.toInt())
        header.position(22)
        assertEquals(2, header.short.toInt())
        assertEquals(44_100, header.int)
        assertEquals(176_400, header.int)
        assertEquals(4, header.short.toInt())
        assertEquals(16, header.short.toInt())
        assertEquals("data", header.readFourCc())
        assertEquals(8, header.int)

        val samples = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val encoded = ShortArray(4) { samples.short }
        assertArrayEquals(
            shortArrayOf(32_767, 0, 0, -32_768),
            encoded
        )
    }

    @Test
    fun `save serializer for processed output is stereo`() {
        val wav = WavPcm16ProcessedSerializer.toWavBytes(
            left = floatArrayOf(0.25f, -0.25f),
            right = floatArrayOf(-0.5f, 0.5f)
        )
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", header.readFourCc())
        assertEquals(44, header.int)
        assertEquals("WAVE", header.readFourCc())
        assertEquals("fmt ", header.readFourCc())
        assertEquals(16, header.int)
        assertEquals(1, header.short.toInt())
        header.position(22)
        assertEquals(2, header.short.toInt())
        assertEquals(44_100, header.int)
        assertEquals(176_400, header.int)
        assertEquals(4, header.short.toInt())
        assertEquals(16, header.short.toInt())
        assertEquals("data", header.readFourCc())
        assertEquals(8, header.int)
    }

    @Test
    fun `streams large stereo wav without allocating a giant byte array`() {
        val sampleCount = 20_000_000
        val left = FloatArray(sampleCount) { 0.25f }
        val right = FloatArray(sampleCount) { -0.25f }
        val output = CountingHeaderCapturingOutputStream()

        try {
            WavPcm16StereoSerializer.writeTo(output, left, right)
        } catch (_: IllegalArgumentException) {
            fail("Large stereo WAV should be streamed without Int-sized buffer allocation")
        }

        assertEquals(44L + sampleCount.toLong() * 4L, output.totalBytesWritten)
        val header = ByteBuffer.wrap(output.headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", header.readFourCc())
        assertEquals(sampleCount * 4 + 36, header.int)
        assertEquals("WAVE", header.readFourCc())
        assertEquals("fmt ", header.readFourCc())
        assertEquals(16, header.int)
        assertEquals(1, header.short.toInt())
        header.position(22)
        assertEquals(2, header.short.toInt())
        assertEquals(44_100, header.int)
        assertEquals(176_400, header.int)
        assertEquals(4, header.short.toInt())
        assertEquals(16, header.short.toInt())
        assertEquals("data", header.readFourCc())
        assertEquals(sampleCount * 4, header.int)
    }

    private fun ByteBuffer.readFourCc(): String =
        ByteArray(4).also(::get).toString(Charsets.US_ASCII)

    private class CountingHeaderCapturingOutputStream : OutputStream() {
        private val header = ByteArray(44)
        private var headerOffset = 0
        var totalBytesWritten: Long = 0
            private set

        val headerBytes: ByteArray
            get() = header.copyOf()

        override fun write(b: Int) {
            if (headerOffset < header.size) {
                header[headerOffset++] = b.toByte()
            }
            totalBytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (headerOffset < header.size) {
                val bytesToCopy = minOf(len, header.size - headerOffset)
                System.arraycopy(b, off, header, headerOffset, bytesToCopy)
                headerOffset += bytesToCopy
            }
            totalBytesWritten += len.toLong()
        }
    }
}
