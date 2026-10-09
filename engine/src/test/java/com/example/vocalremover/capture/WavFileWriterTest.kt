package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @Test
    fun `writes correct header fields after closing`() {
        withTestFile("header.wav") { file ->
            val writer = WavFileWriter(file, sampleRate = 48000, channelCount = 2)
            val samples = ByteArray(2000) { 1 }
            writer.write(samples, 0, samples.size)
            writer.close()

            val header = ByteArray(44)
            RandomAccessFile(file, "r").use { it.readFully(header) }

            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val riff = ByteArray(4).also { buffer.get(it) }
            assertEquals("RIFF", String(riff, Charsets.US_ASCII))

            val riffSize = buffer.int
            assertEquals(36 + 2000, riffSize)

            val wave = ByteArray(4).also { buffer.get(it) }
            assertEquals("WAVE", String(wave, Charsets.US_ASCII))

            val fmt = ByteArray(4).also { buffer.get(it) }
            assertEquals("fmt ", String(fmt, Charsets.US_ASCII))

            assertEquals(16, buffer.int) // dimensione subchunk fmt
            assertEquals(1, buffer.short.toInt()) // PCM
            assertEquals(2, buffer.short.toInt()) // canali
            assertEquals(48000, buffer.int) // sample rate
            assertEquals(48000 * 2 * 2, buffer.int) // byte rate
            assertEquals(4, buffer.short.toInt()) // block align
            assertEquals(16, buffer.short.toInt()) // bit per campione

            val data = ByteArray(4).also { buffer.get(it) }
            assertEquals("data", String(data, Charsets.US_ASCII))
            assertEquals(2000, buffer.int)

            assertEquals(2000L, writer.totalDataBytes)
        }
    }

    @Test
    fun `accumulates data size across multiple incremental writes`() {
        withTestFile("incremental.wav") { file ->
            val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 1)
            writer.write(ByteArray(500) { 1 }, 0, 500)
            writer.write(ByteArray(500) { 1 }, 0, 500)
            writer.close()

            assertEquals(1000L, writer.totalDataBytes)
            assertEquals(44L + 1000L, file.length())
        }
    }

    @Test
    fun `accepts the largest RIFF-safe four-byte-aligned data size only`() {
        assertEquals(2_147_483_608L, WavFileWriter.MAX_DATA_BYTES)
        assertTrue(WavFileWriter.canAppendData(WavFileWriter.MAX_DATA_BYTES - 4, 4))
        assertFalse(WavFileWriter.canAppendData(WavFileWriter.MAX_DATA_BYTES - 4, 8))
        assertFalse(WavFileWriter.canAppendData(WavFileWriter.MAX_DATA_BYTES, 1))
    }

    @Test
    fun `close closes the handle and writer when header rewrite fails`() {
        val handle = HeaderFailingFileHandle()
        val writer = WavFileWriter(handle, sampleRate = 44100, channelCount = 2)
        writer.write(ByteArray(4), 0, 4)

        try {
            writer.close()
            fail("Expected header rewrite failure")
        } catch (expected: IOException) {
            assertEquals("header rewrite failed", expected.message)
            assertEquals(1, expected.suppressed.size)
            assertEquals("close failed", expected.suppressed.single().message)
        }

        assertTrue(handle.closed)
        try {
            writer.write(ByteArray(4), 0, 4)
            fail("Expected closed writer")
        } catch (expected: IllegalStateException) {
            assertEquals("WavFileWriter già chiuso", expected.message)
        }
    }

    private fun withTestFile(fileName: String, block: (File) -> Unit) {
        val directory = File("build/test-wav-writer").apply { mkdirs() }
        val file = File(directory, "${System.nanoTime()}_$fileName")
        try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private class HeaderFailingFileHandle : WavFileAccess {
        var closed = false
        private var position = 0L

        override fun setLength(length: Long) = Unit

        override fun seek(position: Long) {
            this.position = position
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (position == 0L) {
                throw IOException("header rewrite failed")
            }
            position += length
        }

        override fun close() {
            closed = true
            throw IOException("close failed")
        }
    }
}
