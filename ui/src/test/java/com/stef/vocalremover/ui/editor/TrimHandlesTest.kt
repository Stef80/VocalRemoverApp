package com.stef.vocalremover.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimHandlesTest {

    @Test
    fun `start snaps to 10 ms and stays min gap before end`() {
        assertEquals(1230L, TrimHandles.moveStart(1234, endMs = 5000))
        assertEquals(4000L, TrimHandles.moveStart(4500, endMs = 5000))
        assertEquals(0L, TrimHandles.moveStart(-50, endMs = 5000))
    }

    @Test
    fun `end snaps and is clamped to duration`() {
        assertEquals(3340L, TrimHandles.moveEnd(3337, startMs = 1000, durationMs = 10_005))
        assertEquals(10_005L, TrimHandles.moveEnd(20_000, startMs = 1000, durationMs = 10_005))
        assertEquals(2000L, TrimHandles.moveEnd(1500, startMs = 1000, durationMs = 10_005))
    }

    @Test
    fun `capture shorter than min gap keeps full range`() {
        assertEquals(0L, TrimHandles.moveStart(300, endMs = 800))
        assertEquals(800L, TrimHandles.moveEnd(100, startMs = 0, durationMs = 800))
    }

    @Test
    fun `px and ms convert both ways`() {
        assertEquals(120_000L, TrimHandles.pxToMs(500f, widthPx = 1000f, durationMs = 240_000))
        assertEquals(0L, TrimHandles.pxToMs(-10f, widthPx = 1000f, durationMs = 240_000))
        assertEquals(240_000L, TrimHandles.pxToMs(1200f, widthPx = 1000f, durationMs = 240_000))
        assertEquals(250f, TrimHandles.msToPx(60_000, widthPx = 1000f, durationMs = 240_000), 0.01f)
    }

    @Test
    fun `release seeks to start or three seconds before end`() {
        assertEquals(1000L, TrimHandles.seekAfterRelease(TrimHandle.START, 1000, 9000))
        assertEquals(6000L, TrimHandles.seekAfterRelease(TrimHandle.END, 1000, 9000))
        assertEquals(1000L, TrimHandles.seekAfterRelease(TrimHandle.END, 1000, 2500))
    }

    @Test
    fun `playback starts inside the interval`() {
        assertEquals(5000L, TrimHandles.playbackStart(5000, 1000, 9000))
        assertEquals(1000L, TrimHandles.playbackStart(9000, 1000, 9000))
        assertEquals(1000L, TrimHandles.playbackStart(500, 1000, 9000))
    }

    @Test
    fun `full range is detected`() {
        assertTrue(TrimHandles.isFullRange(0, 3000, 3000))
        assertFalse(TrimHandles.isFullRange(10, 3000, 3000))
    }
}
