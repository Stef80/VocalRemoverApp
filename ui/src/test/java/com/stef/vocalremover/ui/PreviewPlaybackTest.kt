package com.stef.vocalremover.ui

import android.net.Uri
import com.example.vocalremover.playback.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewPlaybackTest {

    private val playable = Uri.parse("file:///cache/Canzone_tagliato.wav")
    private val raw = Uri.parse("file:///cache/system_audio_capture_1.wav")

    @Test
    fun `player state of another file is ignored`() {
        val state = PlayerState(currentUri = raw, isPlaying = true, positionMs = 4000, durationMs = 5000)
        assertEquals(PreviewPlayback(isPlaying = false, positionMs = 0, durationMs = 1000), previewPlaybackFor(state, playable, 1000))
    }

    @Test
    fun `player state of the playable file is used`() {
        val state = PlayerState(currentUri = playable, isPlaying = true, positionMs = 400, durationMs = 1010)
        assertEquals(PreviewPlayback(isPlaying = true, positionMs = 400, durationMs = 1010), previewPlaybackFor(state, playable, 1000))
    }

    @Test
    fun `unknown duration falls back to the capture duration`() {
        val state = PlayerState(currentUri = playable, durationMs = 0)
        assertEquals(1000L, previewPlaybackFor(state, playable, 1000).durationMs)
    }
}
