package com.stef.vocalremover.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.lyrics.TrackLyricsRepository
import com.example.vocalremover.playback.LibraryPlayer
import com.example.vocalremover.playback.PlayerError
import com.stef.vocalremover.ui.LibraryPreviewPlayer
import com.stef.vocalremover.ui.PreviewPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** Player of the Library tracks. Must be used from the main thread, like [LibraryPlayer]. */
class PlayerViewModel(
    private val player: PreviewPlayer,
    /** Blocking (I/O): read on [ioDispatcher]. */
    private val loadLyrics: (Uri) -> TrackLyrics? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val track = MutableStateFlow<LibraryTrack?>(null)
    private val lyrics = MutableStateFlow<PlayerLyrics>(PlayerLyrics.Unavailable)
    private var lyricsJob: Job? = null

    /** `null` until a track is opened (or after the open track has been deleted). */
    val state: StateFlow<PlayerUiState?> = combine(track, player.state, lyrics) { track, playback, lyrics ->
        track ?: return@combine null
        // The state of another Uri (previous track not yet unloaded) does not count.
        val current = playback.takeIf { it.currentUri == track.uri }
        PlayerUiState(
            track = track,
            isPlaying = current?.isPlaying ?: false,
            positionMs = current?.positionMs ?: 0L,
            durationMs = current?.durationMs?.takeIf { it > 0 } ?: track.durationMs,
            error = current?.error,
            lyrics = lyrics,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Opens the track and plays it from the start, even if it was already the open one. If the lyrics are still
     * downloading, playback starts only when the download finishes (with or without lyrics).
     */
    fun open(track: LibraryTrack) {
        lyricsJob?.cancel()
        this.track.value = track
        lyrics.value = PlayerLyrics.Loading
        player.stop()
        lyricsJob = viewModelScope.launch {
            while (true) {
                val loaded = PlayerLyrics.from(withContext(ioDispatcher) { loadLyrics(track.uri) })
                if (loaded != null) {
                    lyrics.value = loaded
                    player.play(track.uri)
                    return@launch
                }
                delay(LYRICS_POLL_MS)
            }
        }
    }

    fun togglePlayback() {
        val current = state.value ?: return
        if (current.lyrics == PlayerLyrics.Loading) return
        if (current.isPlaying) player.pause() else player.play(current.track.uri)
    }

    fun play() {
        val current = state.value ?: return
        if (current.lyrics == PlayerLyrics.Loading) return
        player.play(current.track.uri)
    }

    fun pause() {
        if (state.value?.isPlaying == true) player.pause()
    }

    fun seekTo(positionMs: Long) {
        val current = state.value ?: return
        if (current.lyrics == PlayerLyrics.Loading) return
        player.seekTo(clamp(positionMs, current.durationMs))
    }

    fun rewind() = seekBy(-SKIP_MS)

    fun forward() = seekBy(SKIP_MS)

    /** Leaving the player stops the music: there are no controls in the Library. */
    fun leave() = pause()

    fun onTrackDeleted(uri: Uri) {
        if (track.value?.uri != uri) return
        lyricsJob?.cancel()
        player.stop()
        track.value = null
        lyrics.value = PlayerLyrics.Unavailable
    }

    fun onTrackRenamed(uri: Uri, title: String) {
        // The same title the library will derive from the new file name.
        track.update { if (it?.uri == uri) it.copy(title = RecordingFileNaming.sanitize(title)) else it }
    }

    override fun onCleared() {
        player.release()
    }

    private fun seekBy(deltaMs: Long) {
        val current = state.value ?: return
        if (current.lyrics == PlayerLyrics.Loading) return
        player.seekTo(clamp(current.positionMs + deltaMs, current.durationMs))
    }

    private fun clamp(positionMs: Long, durationMs: Long): Long {
        val atLeastZero = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0) atLeastZero.coerceAtMost(durationMs) else atLeastZero
    }

    companion object {
        private const val SKIP_MS = 10_000L
        /** How often the lyrics file is checked again while the download is in progress. */
        internal const val LYRICS_POLL_MS = 1_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val repository = TrackLyricsRepository.create(app)
                PlayerViewModel(LibraryPreviewPlayer(LibraryPlayer(app)), loadLyrics = repository::get)
            }
        }
    }
}
