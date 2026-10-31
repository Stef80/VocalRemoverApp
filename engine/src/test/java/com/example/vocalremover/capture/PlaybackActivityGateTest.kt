package com.example.vocalremover.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.sin

class PlaybackActivityGateTest {

    private val sampleRate = 44_100
    private val channels = 2

    private fun gate() = PlaybackActivityGate(
        sampleRate = sampleRate,
        channelCount = channels,
        listenTimeoutMs = 60_000,
        trailingSilenceMs = 4_000,
        preRollMs = 300
    )

    private fun silence(ms: Int): ByteArray = ByteArray(bytesFor(ms))

    /** 440 Hz sine, 16-bit little-endian, identical on both channels. */
    private fun tone(ms: Int, amplitude: Double = 0.3): ByteArray {
        val frames = (sampleRate.toLong() * ms / 1_000).toInt()
        val bytes = ByteArray(frames * channels * 2)
        for (frame in 0 until frames) {
            val value = (sin(2 * Math.PI * 440 * frame / sampleRate) * amplitude * 32_767).toInt()
            for (channel in 0 until channels) {
                val index = (frame * channels + channel) * 2
                bytes[index] = (value and 0xFF).toByte()
                bytes[index + 1] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return bytes
    }

    private fun bytesFor(ms: Int): Int = (sampleRate.toLong() * ms / 1_000).toInt() * channels * 2

    private class Collector {
        val out = ByteArrayOutputStream()
        val sink: (ByteArray, Int, Int) -> Unit = { buffer, offset, length ->
            out.write(buffer, offset, length)
        }
    }

    @Test
    fun `silence shorter than the listen timeout keeps listening and writes nothing`() {
        val gate = gate()
        val collector = Collector()
        val state = gate.process(silence(10_000), 0, bytesFor(10_000), collector.sink)
        assertEquals(PlaybackActivityGate.State.LISTENING, state)
        assertFalse(gate.audioDetected)
        assertEquals(0, collector.out.size())
    }

    @Test
    fun `listening for the whole timeout without audio times out`() {
        val gate = gate()
        val collector = Collector()
        val state = gate.process(silence(60_000), 0, bytesFor(60_000), collector.sink)
        assertEquals(PlaybackActivityGate.State.TIMED_OUT, state)
        assertEquals(0, collector.out.size())
    }

    @Test
    fun `audio onset starts recording and keeps the pre-roll before it`() {
        val gate = gate()
        val collector = Collector()
        val lead = silence(2_000)
        val music = tone(1_000)
        gate.process(lead, 0, lead.size, collector.sink)
        val state = gate.process(music, 0, music.size, collector.sink)

        assertEquals(PlaybackActivityGate.State.RECORDING, state)
        assertTrue(gate.audioDetected)
        val expected = silence(300) + music
        assertArrayEquals(expected, collector.out.toByteArray())
    }

    @Test
    fun `pre-roll is limited to the silence actually heard`() {
        val gate = gate()
        val collector = Collector()
        val input = silence(100) + tone(500)
        gate.process(input, 0, input.size, collector.sink)
        assertArrayEquals(input, collector.out.toByteArray())
    }

    @Test
    fun `sustained trailing silence finishes the recording and is trimmed`() {
        val gate = gate()
        val collector = Collector()
        val music = tone(1_000)
        val input = music + silence(4_000) + tone(500)
        val state = gate.process(input, 0, input.size, collector.sink)

        assertEquals(PlaybackActivityGate.State.FINISHED, state)
        assertArrayEquals(music, collector.out.toByteArray())
    }

    @Test
    fun `short pauses inside the song are kept`() {
        val gate = gate()
        val collector = Collector()
        val input = tone(1_000) + silence(1_000) + tone(1_000)
        val state = gate.process(input, 0, input.size, collector.sink)
        gate.finish(collector.sink)

        assertEquals(PlaybackActivityGate.State.RECORDING, state)
        assertArrayEquals(input, collector.out.toByteArray())
    }

    @Test
    fun `finish trims trailing silence shorter than the stop threshold`() {
        val gate = gate()
        val collector = Collector()
        val music = tone(1_000)
        val input = music + silence(2_000)
        gate.process(input, 0, input.size, collector.sink)
        gate.finish(collector.sink)

        assertArrayEquals(music, collector.out.toByteArray())
    }

    @Test
    fun `finish flushes a partial window of audio`() {
        val gate = gate()
        val collector = Collector()
        val music = tone(1_010)
        gate.process(music, 0, music.size, collector.sink)
        gate.finish(collector.sink)

        assertArrayEquals(music, collector.out.toByteArray())
    }

    @Test
    fun `nothing is written after the recording finished`() {
        val gate = gate()
        val collector = Collector()
        val input = tone(1_000) + silence(4_000)
        gate.process(input, 0, input.size, collector.sink)
        val more = tone(1_000)
        val state = gate.process(more, 0, more.size, collector.sink)
        gate.finish(collector.sink)

        assertEquals(PlaybackActivityGate.State.FINISHED, state)
        assertEquals(bytesFor(1_000), collector.out.size())
    }

    @Test
    fun `arbitrary chunk sizes produce the same output as a single chunk`() {
        val input = silence(1_000) + tone(2_000) + silence(500) + tone(700) + silence(1_000)

        val whole = Collector()
        gate().apply {
            process(input, 0, input.size, whole.sink)
            finish(whole.sink)
        }

        val chunked = Collector()
        val chunkedGate = gate()
        var offset = 0
        var step = 1
        while (offset < input.size) {
            val length = minOf(step, input.size - offset)
            chunkedGate.process(input, offset, length, chunked.sink)
            offset += length
            step = step * 7 % 5_003 + 1
        }
        chunkedGate.finish(chunked.sink)

        assertArrayEquals(whole.out.toByteArray(), chunked.out.toByteArray())
    }

    // 20 ms at 44 100 Hz = 882 frames per window; 300 ms pre-roll = 15 windows.
    private val windowFrames = 882L

    @Test
    fun `first written frame accounts for the pre-roll`() {
        val gate = gate()
        val collector = Collector()
        gate.process(silence(2_000), 0, bytesFor(2_000), collector.sink)
        gate.process(tone(100), 0, bytesFor(100), collector.sink)
        assertEquals((100 - 15) * windowFrames, gate.firstWrittenFrame)
    }

    @Test
    fun `first written frame is zero when the silence is shorter than the pre-roll`() {
        val gate = gate()
        val collector = Collector()
        gate.process(silence(100), 0, bytesFor(100), collector.sink)
        gate.process(tone(100), 0, bytesFor(100), collector.sink)
        assertEquals(0L, gate.firstWrittenFrame)
    }

    @Test
    fun `first written frame is null while listening`() {
        val gate = gate()
        gate.process(silence(1_000), 0, bytesFor(1_000), Collector().sink)
        assertEquals(null, gate.firstWrittenFrame)
    }

    @Test
    fun `first written frame without pre-roll is the first audio window`() {
        val gate = PlaybackActivityGate(sampleRate, channels, preRollMs = 0)
        val collector = Collector()
        gate.process(silence(200), 0, bytesFor(200), collector.sink)
        gate.process(tone(100), 0, bytesFor(100), collector.sink)
        assertEquals(10 * windowFrames, gate.firstWrittenFrame)
    }

    @Test
    fun `first written frame matches the bytes written before the audio`() {
        val gate = gate()
        val collector = Collector()
        gate.process(silence(2_000), 0, bytesFor(2_000), collector.sink)
        gate.process(tone(100), 0, bytesFor(100), collector.sink)
        val preRollBytes = 15 * windowFrames * channels * 2
        val toneStart = collector.out.toByteArray().copyOfRange(preRollBytes.toInt(), preRollBytes.toInt() + 8)
        assertArrayEquals(tone(100).copyOfRange(0, 8), toneStart)
    }
}
