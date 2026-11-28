package com.stef.vocalremover.ui.projection

import android.media.session.PlaybackState
import android.net.Uri
import com.example.vocalremover.library.LibraryTrack
import com.stef.vocalremover.ui.player.PlayerLyrics
import com.stef.vocalremover.ui.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectionPlaybackStateTest {

    private fun ui(isPlaying: Boolean, positionMs: Long = 0, lyrics: PlayerLyrics = PlayerLyrics.Unavailable) = PlayerUiState(
        track = LibraryTrack(Uri.parse("content://media/external/audio/media/5"), "Canzone", 60_000L),
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = 60_000L,
        error = null,
        lyrics = lyrics,
    )

    @Test
    fun `playing is PLAYING with the position`() {
        val playback = sessionPlaybackFor(ui(isPlaying = true, positionMs = 12_000))
        assertEquals(PlaybackState.STATE_PLAYING, playback.state)
        assertEquals(12_000L, playback.positionMs)
        assertNotEquals(0L, playback.actions)
    }

    @Test
    fun `paused is PAUSED`() =
        assertEquals(PlaybackState.STATE_PAUSED, sessionPlaybackFor(ui(isPlaying = false)).state)

    @Test
    fun `lyrics loading is BUFFERING without actions`() {
        val playback = sessionPlaybackFor(ui(isPlaying = false, lyrics = PlayerLyrics.Loading))
        assertEquals(PlaybackState.STATE_BUFFERING, playback.state)
        assertEquals(0L, playback.actions)
    }

    @Test
    fun `no track is NONE`() = assertEquals(PlaybackState.STATE_NONE, sessionPlaybackFor(null).state)

    private fun published(state: Int, positionMs: Long, atMs: Long, actions: Long = 1L) =
        PublishedPlayback(SessionPlayback(state, positionMs, actions), atMs)

    @Test
    fun `first state is always published`() =
        assertTrue(shouldPublish(null, SessionPlayback(PlaybackState.STATE_PAUSED, 0, 1L), 0))

    @Test
    fun `state or actions change is published`() {
        val last = published(PlaybackState.STATE_PLAYING, 5_000, 0)
        assertTrue(shouldPublish(last, SessionPlayback(PlaybackState.STATE_PAUSED, 5_000, 1L), 0))
        assertTrue(shouldPublish(last, SessionPlayback(PlaybackState.STATE_PLAYING, 5_000, 0L), 0))
    }

    @Test
    fun `position moving as expected while playing is not published`() {
        val last = published(PlaybackState.STATE_PLAYING, 5_000, atMs = 1_000)
        assertFalse(shouldPublish(last, SessionPlayback(PlaybackState.STATE_PLAYING, 5_400, 1L), nowMs = 1_400))
    }

    @Test
    fun `seek while playing is published`() {
        val last = published(PlaybackState.STATE_PLAYING, 5_000, atMs = 1_000)
        assertTrue(shouldPublish(last, SessionPlayback(PlaybackState.STATE_PLAYING, 15_400, 1L), nowMs = 1_400))
    }

    @Test
    fun `seek while paused is published, same position is not`() {
        val last = published(PlaybackState.STATE_PAUSED, 5_000, atMs = 1_000)
        assertFalse(shouldPublish(last, SessionPlayback(PlaybackState.STATE_PAUSED, 5_000, 1L), nowMs = 9_000))
        assertTrue(shouldPublish(last, SessionPlayback(PlaybackState.STATE_PAUSED, 8_000, 1L), nowMs = 9_000))
    }
}
