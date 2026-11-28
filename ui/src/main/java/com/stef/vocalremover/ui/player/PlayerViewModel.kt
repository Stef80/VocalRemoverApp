package com.stef.vocalremover.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.playback.PlayerError
import com.stef.vocalremover.ui.VocalRemoverApplication
import kotlinx.coroutines.flow.StateFlow

/** Track open in the player and its playback state. */
data class PlayerUiState(
    val track: LibraryTrack,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** ExoPlayer's as soon as it is known, before that the library's (0 = unknown). */
    val durationMs: Long,
    val error: PlayerError?,
    val lyrics: PlayerLyrics = PlayerLyrics.Unavailable,
)

/** Lyrics shown in the player. */
sealed interface PlayerLyrics {
    /** Download still in progress: the track does not start until it finishes. */
    data object Loading : PlayerLyrics
    /**
     * Lines with their time in the track; [songOffsetMs] is the position in the track at the start of the file
     * (active line = the one with `startMs - songOffsetMs <= position`).
     */
    data class Synced(val lines: List<String>, val startsMs: List<Long>, val songOffsetMs: Long) : PlayerLyrics {
        /** Index of the line to highlight, -1 before the first one. */
        fun activeLine(positionMs: Long): Int =
            startsMs.indexOfLast { it - songOffsetMs <= positionMs }
    }
    data class Plain(val text: String) : PlayerLyrics
    data object Instrumental : PlayerLyrics
    /** No lyrics: imported track, not found or permanent error. */
    data object Unavailable : PlayerLyrics

    companion object {
        /** `null` = lyrics file still pending ([LyricsStatus.PENDING]). */
        fun from(stored: TrackLyrics?): PlayerLyrics? = when (stored?.status) {
            null -> Unavailable
            LyricsStatus.PENDING -> null
            LyricsStatus.INSTRUMENTAL -> Instrumental
            LyricsStatus.NOT_FOUND, LyricsStatus.FAILED -> Unavailable
            LyricsStatus.FOUND -> when {
                stored.lines.isNotEmpty() -> Synced(
                    lines = stored.lines.map { it.text },
                    startsMs = stored.lines.map { it.startMs },
                    songOffsetMs = stored.track.songOffsetMs ?: 0L,
                )
                !stored.plainText.isNullOrBlank() -> Plain(stored.plainText!!)
                else -> Unavailable
            }
        }
    }
}

/** Activity-scoped facade over the process-wide [PlayerSession]. */
class PlayerViewModel(
    private val session: PlayerSession,
    private val isProjecting: () -> Boolean = { false },
) : ViewModel() {

    val state: StateFlow<PlayerUiState?> get() = session.state

    fun open(track: LibraryTrack) = session.open(track)

    fun togglePlayback() = session.togglePlayback()

    fun play() = session.play()

    fun pause() = session.pause()

    fun seekTo(positionMs: Long) = session.seekTo(positionMs)

    fun rewind() = session.rewind()

    fun forward() = session.forward()

    /** Leaving the player stops the music, unless it is being projected: then it keeps going. */
    fun leave() {
        if (!isProjecting()) session.pause()
    }

    fun onTrackDeleted(uri: Uri) = session.onTrackDeleted(uri)

    fun onTrackRenamed(uri: Uri, title: String) = session.onTrackRenamed(uri, title)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as VocalRemoverApplication
                PlayerViewModel(app.playerSession)
            }
        }
    }
}
