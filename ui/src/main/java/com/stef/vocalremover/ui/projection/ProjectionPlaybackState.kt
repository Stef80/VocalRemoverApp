package com.stef.vocalremover.ui.projection

import android.media.session.PlaybackState
import com.stef.vocalremover.ui.player.PlayerLyrics
import com.stef.vocalremover.ui.player.PlayerUiState

/** What the platform `MediaSession` should publish, see [sessionPlaybackFor]. */
data class SessionPlayback(val state: Int, val positionMs: Long, val actions: Long)

/** No actions while the lyrics load: [com.stef.vocalremover.ui.player.PlayerSession] ignores the commands then. */
fun sessionPlaybackFor(ui: PlayerUiState?): SessionPlayback = when {
    ui == null -> SessionPlayback(PlaybackState.STATE_NONE, 0L, 0L)
    ui.lyrics == PlayerLyrics.Loading -> SessionPlayback(PlaybackState.STATE_BUFFERING, ui.positionMs, 0L)
    else -> SessionPlayback(
        state = if (ui.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
        positionMs = ui.positionMs,
        actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_SEEK_TO,
    )
}

/** What was last given to the `MediaSession` and when ([atMs] is `SystemClock.elapsedRealtime`). */
data class PublishedPlayback(val playback: SessionPlayback, val atMs: Long)

private const val POSITION_JUMP_MS = 1_000L

/**
 * The session extrapolates the position by itself while playing (speed 1), so a new state is needed only when
 * the state or the actions change, or the position jumps (seek) from where it should be by more than about 1 s.
 */
fun shouldPublish(last: PublishedPlayback?, playback: SessionPlayback, nowMs: Long): Boolean {
    if (last == null || last.playback.state != playback.state || last.playback.actions != playback.actions) return true
    val elapsed = if (playback.state == PlaybackState.STATE_PLAYING) nowMs - last.atMs else 0L
    return kotlin.math.abs(playback.positionMs - (last.playback.positionMs + elapsed)) > POSITION_JUMP_MS
}
