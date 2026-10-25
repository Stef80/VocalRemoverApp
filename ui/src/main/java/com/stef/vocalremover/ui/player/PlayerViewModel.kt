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
import com.example.vocalremover.playback.LibraryPlayer
import com.example.vocalremover.playback.PlayerError
import com.stef.vocalremover.ui.LibraryPreviewPlayer
import com.stef.vocalremover.ui.PreviewPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Brano aperto nel player e stato della sua riproduzione. */
data class PlayerUiState(
    val track: LibraryTrack,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** Quella di ExoPlayer appena nota, prima quella della libreria (0 = sconosciuta). */
    val durationMs: Long,
    val error: PlayerError?,
)

/** Player dei brani della Libreria. Va usato dal main thread, come [LibraryPlayer]. */
class PlayerViewModel(private val player: PreviewPlayer) : ViewModel() {

    private val track = MutableStateFlow<LibraryTrack?>(null)

    /** `null` finché non si apre un brano (o dopo che il brano aperto è stato eliminato). */
    val state: StateFlow<PlayerUiState?> = combine(track, player.state) { track, playback ->
        track ?: return@combine null
        // Lo stato di un altro Uri (brano precedente non ancora scaricato) non conta.
        val current = playback.takeIf { it.currentUri == track.uri }
        PlayerUiState(
            track = track,
            isPlaying = current?.isPlaying ?: false,
            positionMs = current?.positionMs ?: 0L,
            durationMs = current?.durationMs?.takeIf { it > 0 } ?: track.durationMs,
            error = current?.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Apre il brano e lo riproduce da capo, anche se era già quello aperto. */
    fun open(track: LibraryTrack) {
        this.track.value = track
        player.stop()
        player.play(track.uri)
    }

    fun togglePlayback() {
        val current = state.value ?: return
        if (current.isPlaying) player.pause() else player.play(current.track.uri)
    }

    fun play() {
        val current = state.value ?: return
        player.play(current.track.uri)
    }

    fun pause() {
        if (state.value?.isPlaying == true) player.pause()
    }

    fun seekTo(positionMs: Long) {
        val current = state.value ?: return
        player.seekTo(clamp(positionMs, current.durationMs))
    }

    fun rewind() = seekBy(-SKIP_MS)

    fun forward() = seekBy(SKIP_MS)

    /** Uscendo dal player la musica si ferma: nella Libreria non ci sono controlli. */
    fun leave() = pause()

    fun onTrackDeleted(uri: Uri) {
        if (track.value?.uri != uri) return
        player.stop()
        track.value = null
    }

    fun onTrackRenamed(uri: Uri, title: String) {
        // Lo stesso titolo che la libreria ricaverà dal nuovo nome del file.
        track.update { if (it?.uri == uri) it.copy(title = RecordingFileNaming.sanitize(title)) else it }
    }

    override fun onCleared() {
        player.release()
    }

    private fun seekBy(deltaMs: Long) {
        val current = state.value ?: return
        player.seekTo(clamp(current.positionMs + deltaMs, current.durationMs))
    }

    private fun clamp(positionMs: Long, durationMs: Long): Long {
        val atLeastZero = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0) atLeastZero.coerceAtMost(durationMs) else atLeastZero
    }

    companion object {
        private const val SKIP_MS = 10_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlayerViewModel(LibraryPreviewPlayer(LibraryPlayer(checkNotNull(this[APPLICATION_KEY]))))
            }
        }
    }
}
