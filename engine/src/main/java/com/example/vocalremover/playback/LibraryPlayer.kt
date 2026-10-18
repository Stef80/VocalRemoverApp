package com.example.vocalremover.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Riproduce i WAV salvati nella libreria (Music/VocalRemover) con ExoPlayer.
 * Si costruisce e si usa solo dal main thread, come richiede ExoPlayer.
 */
class LibraryPlayer(context: Context) {

    companion object {
        private const val POSITION_POLL_MS = 200L
    }

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionJob: Job? = null
    private var released = false

    private val player: ExoPlayer

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying, positionMs = currentPositionMs()) }
            // ExoPlayer non notifica la posizione: la si legge periodicamente solo mentre suona.
            positionJob?.cancel()
            positionJob = if (isPlaying) scope.launch {
                while (isActive) {
                    _state.update { it.copy(positionMs = currentPositionMs()) }
                    delay(POSITION_POLL_MS)
                }
            } else null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> _state.update { it.copy(durationMs = knownDurationMs()) }
                Player.STATE_ENDED -> _state.update {
                    val duration = knownDurationMs()
                    it.copy(isPlaying = false, durationMs = duration, positionMs = duration)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(isPlaying = false, error = playerErrorFor(error.errorCode)) }
        }
    }

    init {
        checkMainThread()
        player = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Pausa quando si staccano le cuffie.
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(listener)
    }

    /** Brano diverso (o in errore): carica e suona. Stesso brano: riprende, da 0 se era finito. */
    fun play(uri: Uri) {
        checkUsable()
        if (uri != _state.value.currentUri || player.playbackState == Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            _state.value = PlayerState(currentUri = uri)
        } else if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            _state.update { it.copy(positionMs = 0) }
        }
        _state.update { it.copy(error = null) }
        player.play()
    }

    fun pause() {
        checkUsable()
        player.pause()
        _state.update { it.copy(positionMs = currentPositionMs()) }
    }

    /** Ferma e scarica il brano: il prossimo play() lo ricarica anche se l'Uri è lo stesso
     *  (es. file riscritto con lo stesso nome). */
    fun stop() {
        checkUsable()
        player.stop()
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
    }

    /** Limitata a [0, durata]; finché la durata non è nota si limita solo in basso. */
    fun seekTo(positionMs: Long) {
        checkUsable()
        val duration = knownDurationMs()
        var target = positionMs.coerceAtLeast(0)
        if (duration > 0) target = target.coerceAtMost(duration)
        player.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** Idempotente. Dopo il rilascio ogni altro metodo lancia IllegalStateException. */
    fun release() {
        checkMainThread()
        if (released) return
        released = true
        scope.cancel()
        player.removeListener(listener)
        player.release()
        _state.update { it.copy(isPlaying = false) }
    }

    private fun knownDurationMs(): Long = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    private fun currentPositionMs(): Long =
        if (player.playbackState == Player.STATE_ENDED) knownDurationMs() else player.currentPosition

    private fun checkUsable() {
        checkMainThread()
        check(!released) { "LibraryPlayer già rilasciato" }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "LibraryPlayer va usato dal main thread" }
    }
}
