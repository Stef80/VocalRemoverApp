package com.example.vocalremover.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class WavTrimmerTest {

    @Test
    fun `copies exactly the frames of the interval`() = withTempDir { dir ->
        val source = File(dir, "src.wav").also { writePcm16StereoWav(it, rampSamples(44_100 * 3)) }
        val target = File(dir, "out.wav")

        WavTrimmer.trim(source, target, startMs = 1000, endMs = 2000)

        val info = WavFileReader.readInfo(target)
        assertEquals(44_100L, info.frameCount)
        val pcm = WavFileReader.readStereoPcm(target)
        assertEquals(44_100.toShort() / 32768f, pcm.left[0], 1e-6f)
        assertEquals((-44_100).toShort() / 32768f, pcm.right[0], 1e-6f)
        assertEquals((44_100 * 2 - 1).toShort() / 32768f, pcm.left.last(), 1e-6f)
    }

    @Test
    fun `end at duration copies up to the last frame`() = withTempDir { dir ->
        val source = File(dir, "src.wav").also { writePcm16StereoWav(it, rampSamples(88_641)) }
        val target = File(dir, "out.wav")
        val duration = WavFileReader.durationMs(WavFileReader.readInfo(source))

        WavTrimmer.trim(source, target, startMs = 500, endMs = duration)

        assertEquals(88_641L - 22_050L, WavFileReader.readInfo(target).frameCount)
    }

    @Test
    fun `full range produces an identical data chunk`() = withTempDir { dir ->
        val source = File(dir, "src.wav").also { writePcm16StereoWav(it, rampSamples(50_000)) }
        val target = File(dir, "out.wav")
        val duration = WavFileReader.durationMs(WavFileReader.readInfo(source))

        WavTrimmer.trim(source, target, startMs = 0, endMs = duration)

        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun `rejects invalid ranges`() = withTempDir { dir ->
        val source = File(dir, "src.wav").also { writePcm16StereoWav(it, rampSamples(44_100 * 3)) }
        val target = File(dir, "out.wav")

        assertThrows(IllegalArgumentException::class.java) { WavTrimmer.trim(source, target, -1, 2000) }
        assertThrows(IllegalArgumentException::class.java) { WavTrimmer.trim(source, target, 0, 3001) }
        assertThrows(IllegalArgumentException::class.java) { WavTrimmer.trim(source, target, 1000, 1999) }
        assertFalse(target.exists())
    }

    @Test
    fun `failure mid copy leaves no target and keeps previous one`() = withTempDir { dir ->
        val source = File(dir, "src.wav").also { writePcm16StereoWav(it, rampSamples(44_100 * 3)) }
        val target = File(dir, "out.wav").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            WavTrimmer.trim(source, target, 0, 2000) { file ->
                WavFileWriter(FailingOnSecondWrite(file), sampleRate = 44100, channelCount = 2)
            }
        }

        assertEquals("old", target.readText())
        assertFalse(File(target.path + ".tmp").exists())
    }

    /** Scrive davvero su disco (così il .tmp esiste) ma fallisce alla seconda write. */
    private class FailingOnSecondWrite(file: File) : WavFileAccess {
        private val raf = RandomAccessFile(file, "rw")
        private var writes = 0
        override fun setLength(length: Long) = raf.setLength(length)
        override fun seek(position: Long) = raf.seek(position)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (++writes == 2) throw IOException("disco pieno")
            raf.write(buffer, offset, length)
        }
        override fun close() = raf.close()
    }
}
