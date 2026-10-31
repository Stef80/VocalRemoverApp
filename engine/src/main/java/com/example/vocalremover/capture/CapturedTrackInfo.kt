package com.example.vocalremover.capture

import kotlinx.serialization.Serializable

/** Track chosen for a saved file (see [TrackTimelineResolver.resolve]). */
@Serializable
data class CapturedTrackInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val trackDurationMs: Long?,
    /**
     * Position in the track at the start of the saved file; negative if the track starts after
     * the start of the file. Null if it cannot be estimated.
     */
    val songOffsetMs: Long?,
    val sourcePackage: String,
    /** Local use only: it is never sent over the network. */
    val mediaId: String?,
)
