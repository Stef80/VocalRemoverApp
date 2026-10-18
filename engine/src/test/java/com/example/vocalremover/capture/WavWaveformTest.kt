package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WavWaveformTest {

    @Test
    fun `returns one peak per bucket`() = withTempDir { dir ->
        val file = File(dir, "a.wav").also { writePcm16StereoWav(it, ShortArray(10_000 * 2)) }
        assertEquals(1000, WavWaveform.peaks(file, 1000).size)
    }

    @Test
    fun `peak reflects the loudest channel`() = withTempDir { dir ->
        val samples = ShortArray(10_000 * 2).apply { this[5_500 * 2 + 1] = -16384 }
        val file = File(dir, "a.wav").also { writePcm16StereoWav(it, samples) }

        val peaks = WavWaveform.peaks(file, 1000)

        assertEquals(0.5f, peaks[550], 1e-4f)
        peaks.forEachIndexed { i, peak -> if (i != 550) assertEquals(0f, peak, 0f) }
    }

    @Test
    fun `short file returns one peak per frame`() = withTempDir { dir ->
        val file = File(dir, "a.wav").also { writePcm16StereoWav(it, ShortArray(10 * 2)) }
        assertEquals(10, WavWaveform.peaks(file, 1000).size)
    }
}
