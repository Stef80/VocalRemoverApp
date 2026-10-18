package com.example.vocalremover.playback

import android.net.Uri
import androidx.media3.common.PlaybackException

/** Stato di [LibraryPlayer], pensato per essere letto da Compose. */
data class PlayerState(
    val currentUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** 0 finché ExoPlayer non la conosce. */
    val durationMs: Long = 0,
    val error: PlayerError? = null,
)

/** Il testo per l'utente lo sceglie `:ui` in strings.xml. */
enum class PlayerError {
    /** File non più presente o non leggibile (es. WAV cancellato dalla cartella). */
    SOURCE_UNAVAILABLE,
    PLAYBACK_FAILED,
}

internal fun playerErrorFor(errorCode: Int): PlayerError = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlayerError.SOURCE_UNAVAILABLE
    else -> PlayerError.PLAYBACK_FAILED
}
