package com.stef.vocalremover.connections.lrclib

import kotlinx.serialization.Serializable

/** Response of `/api/get` and entry of `/api/search`. Unused fields are ignored. */
@Serializable
internal data class LrclibTrack(
    val id: Long,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
