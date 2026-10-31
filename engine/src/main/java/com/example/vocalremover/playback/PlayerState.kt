package com.example.vocalremover.playback

import android.net.Uri
import androidx.media3.common.PlaybackException

/** State of [LibraryPlayer], meant to be read by Compose. */
data class PlayerState(
    val currentUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** 0 until ExoPlayer knows it. */
    val durationMs: Long = 0,
    val error: PlayerError? = null,
)

/** The user-facing text is chosen by `:ui` in strings.xml. */
enum class PlayerError {
    /** File no longer present or not readable (e.g. WAV deleted from the folder). */
    SOURCE_UNAVAILABLE,
    PLAYBACK_FAILED,
}

internal fun playerErrorFor(errorCode: Int): PlayerError = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlayerError.SOURCE_UNAVAILABLE
    else -> PlayerError.PLAYBACK_FAILED
}
