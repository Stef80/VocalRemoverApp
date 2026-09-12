package com.example.vocalremover.spike

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @Test
    fun `writes correct header fields after closing`() {
        val file = File.createTempFile("spike_test", ".wav")
        file.deleteOnExit()

        val writer = WavFileWriter(file, sampleRate = 48000, channelCount = 2)
        val samples = ByteArray(2000) { 1 }
        writer.write(samples, 0, samples.size)
        writer.close()

        val raf = RandomAccessFile(file, "r")
        val header = ByteArray(44)
        raf.readFully(header)
        raf.close()

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
        file.delete()
    }

    @Test
    fun `accumulates data size across multiple incremental writes`() {
        val file = File.createTempFile("spike_test2", ".wav")
        file.deleteOnExit()

        val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 1)
        writer.write(ByteArray(500) { 1 }, 0, 500)
        writer.write(ByteArray(500) { 1 }, 0, 500)
        writer.close()

        assertEquals(1000L, writer.totalDataBytes)
        assertEquals(44L + 1000L, file.length())
        file.delete()
    }
}
