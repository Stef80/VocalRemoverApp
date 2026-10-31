package com.example.vocalremover.capture

/** Playback state of the session, without Android types (for tests). */
data class PlaybackSnapshot(
    val playing: Boolean,
    val positionMs: Long,
    /** In `SystemClock.elapsedRealtime()`, like `PlaybackState.lastPositionUpdateTime`. */
    val lastPositionUpdateTime: Long,
    val speed: Float,
)

/** Position in the track at a given moment, inferred from the last session state. */
object SongPositionEstimator {

    /**
     * Null if the track is not playing: the paused position is not reliable
     * (apps update it late or round it).
     */
    fun positionAt(state: PlaybackSnapshot?, elapsedRealtimeMs: Long): Long? {
        if (state == null || !state.playing) return null
        val advanced = state.positionMs + (elapsedRealtimeMs - state.lastPositionUpdateTime) * state.speed
        return advanced.toLong().coerceAtLeast(0)
    }
}
