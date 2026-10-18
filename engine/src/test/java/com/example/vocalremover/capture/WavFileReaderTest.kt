package com.example.vocalremover.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileReaderTest {

    @Test
    fun `reads a writer-generated stereo wav as normalized interleaved pcm`() {
        withTestFile("round_trip.wav") { file ->
            writePcm16StereoWav(
                file = file,
                sampleRate = 44100,
                interleaved = shortArrayOf(
                    32767, -32768,
                    0, 16384
                )
            )

            val info = WavFileReader.readInfo(file)
            assertEquals(44100, info.sampleRate)
            assertEquals(2, info.channelCount)

            val stereoPcm = WavFileReader.readStereoPcm(file)
            assertArrayEquals(floatArrayOf(32767f / 32768f, 0f), stereoPcm.left, 1e-6f)
            assertArrayEquals(floatArrayOf(-1f, 16384f / 32768f), stereoPcm.right, 1e-6f)
        }
    }

    @Test
    fun `reads a processed stereo wav saved by serializer`() {
        withTestFile("processed_stereo.wav") { file ->
            file.writeBytes(
                WavPcm16ProcessedSerializer.toWavBytes(
                    left = floatArrayOf(0.25f, -0.25f),
                    right = floatArrayOf(-0.5f, 0.5f)
                )
            )

            val info = WavFileReader.readInfo(file)
            assertEquals(44100, info.sampleRate)
            assertEquals(2, info.channelCount)

            val stereoPcm = WavFileReader.readStereoPcm(file)
            assertArrayEquals(floatArrayOf(8191f / 32768f, -8191f / 32768f), stereoPcm.left, 1e-6f)
            assertArrayEquals(floatArrayOf(-16383f / 32768f, 16383f / 32768f), stereoPcm.right, 1e-6f)
        }
    }

    @Test
    fun `readInfo reports data offset, frame count and duration`() = withTempDir { dir ->
        val file = File(dir, "a.wav").also { writePcm16StereoWav(it, rampSamples(44_100 * 2 + 441)) }
        val info = WavFileReader.readInfo(file)
        assertEquals(44L, info.dataOffset)
        assertEquals(4, info.blockAlign)
        assertEquals(88_641L, info.frameCount)
        assertEquals(2010L, WavFileReader.durationMs(info))
    }

    @Test
    fun `rejects mono wav files`() {
        withTestFile("mono.wav") { file ->
            writeMonoWav(file)

            assertUnsupported("stereo") {
                WavFileReader.readInfo(file)
            }
        }
    }

    @Test
    fun `rejects wav files that are not 44100 hertz`() {
        withTestFile("wrong_sample_rate.wav") { file ->
            writePcm16StereoWav(
                file = file,
                sampleRate = 48000,
                interleaved = shortArrayOf(0, 0)
            )

            assertUnsupported("44100") {
                WavFileReader.readInfo(file)
            }
        }
    }

    @Test
    fun `rejects headers that are not riff wave pcm`() {
        withTestFile("invalid_header.wav") { file ->
            file.writeBytes(
                wavHeader(
                    riffId = "RIFX",
                    sampleRate = 44100,
                    channelCount = 2,
                    bitsPerSample = 16,
                    dataSize = 0
                )
            )

            assertUnsupported("RIFF") {
                WavFileReader.readInfo(file)
            }
        }
    }

    private fun withTestFile(fileName: String, block: (File) -> Unit) {
        val directory = File("build/test-wav-reader").apply { mkdirs() }
        val file = File(directory, "${System.nanoTime()}_$fileName")
        try {
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun writeMonoWav(file: File) {
        val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 1)
        val samples = shortArrayOf(0, 1024)
        writer.write(shortsToLittleEndian(samples), 0, samples.size * Short.SIZE_BYTES)
        writer.close()
    }

    private fun wavHeader(
        riffId: String,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        dataSize: Int
    ): ByteArray {
        val byteRate = sampleRate * channelCount * (bitsPerSample / 8)
        val blockAlign = channelCount * (bitsPerSample / 8)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(riffId.toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
    }

    private fun assertUnsupported(expectedMessagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected UnsupportedWavFormatException")
        } catch (expected: WavFileReader.UnsupportedWavFormatException) {
            assertEquals(true, expected.message.orEmpty().contains(expectedMessagePart))
        }
    }
}
