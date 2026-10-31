package com.stef.vocalremover.connections

/**
 * Source of song lyrics. `:engine` only knows this interface: switching service
 * (LRCLIB today) needs no changes outside `:connections`.
 */
interface LyricsProvider {
    suspend fun find(query: TrackQuery): LyricsResult
}

/** Track to look up, with the data read from the media session. */
data class TrackQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
)

sealed interface LyricsResult {
    /** [sourceId] identifies the lyrics at the source, e.g. "lrclib:761859". */
    data class Found(val lyrics: TimedLyrics, val sourceId: String) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
    /** Temporary error (network, busy server): retry after [afterMs], if given. */
    data class RetryLater(val afterMs: Long?) : LyricsResult
    /** Error that a new attempt would not fix. */
    data class Failed(val reason: String) : LyricsResult
}

/** Lyrics with synced lines (empty if the source only has plain text). */
data class TimedLyrics(val lines: List<LyricLine>, val plainText: String?)

data class LyricLine(val startMs: Long, val text: String)
