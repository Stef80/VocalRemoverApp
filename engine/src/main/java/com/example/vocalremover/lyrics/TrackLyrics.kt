package com.example.vocalremover.lyrics

import com.example.vocalremover.capture.CapturedTrackInfo
import kotlinx.serialization.Serializable

enum class LyricsStatus { PENDING, FOUND, NOT_FOUND, INSTRUMENTAL, FAILED }

/**
 * Lyrics of a saved track, in the `:engine` format: independent of the source, so files
 * already written stay valid if the service changes.
 */
@Serializable
data class TrackLyrics(
    val savedUri: String,
    /** Metadata with the offset already relative to the saved file (trim included). */
    val track: CapturedTrackInfo,
    val status: LyricsStatus,
    val sourceId: String? = null,
    val lines: List<TimedLine> = emptyList(),
    val plainText: String? = null,
    val fetchedAt: Long? = null,
)

@Serializable
data class TimedLine(val startMs: Long, val text: String)
