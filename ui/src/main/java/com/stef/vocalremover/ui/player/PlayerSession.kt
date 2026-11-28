package com.stef.vocalremover.ui.player

import android.net.Uri
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.playback.LibraryPlayer
import com.stef.vocalremover.ui.PreviewPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide player of the Library tracks: it outlives the Activity so playback can go on without it.
 * Must be used from the main thread, like [LibraryPlayer].
 */
class PlayerSession(
    private val player: PreviewPlayer,
    /** Blocking (I/O): read on [ioDispatcher]. */
    private val loadLyrics: (Uri) -> TrackLyrics? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {

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
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Opens the track and plays it from the start, even if it was already the open one. If the lyrics are still
     * downloading, playback starts only when the download finishes (with or without lyrics).
     */
    fun open(track: LibraryTrack) {
        lyricsJob?.cancel()
        this.track.value = track
        lyrics.value = PlayerLyrics.Loading
        player.stop()
        lyricsJob = scope.launch {
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

    /**
     * Gives up a start still waiting for the lyrics (the user left the player): the polling stops and the open
     * track is closed, so the music cannot start later on its own. Coming back means opening the track again
     * with [open], which reads the lyrics state anew. No-op when the lyrics are already loaded.
     */
    fun cancelPendingStart() {
        if (lyrics.value != PlayerLyrics.Loading) return
        lyricsJob?.cancel()
        track.value = null
        lyrics.value = PlayerLyrics.Unavailable
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
        const val SKIP_MS = 10_000L
        /** How often the lyrics file is checked again while the download is in progress. */
        internal const val LYRICS_POLL_MS = 1_000L
    }
}
