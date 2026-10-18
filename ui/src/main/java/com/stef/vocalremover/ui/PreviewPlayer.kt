package com.stef.vocalremover.ui

import android.net.Uri
import com.example.vocalremover.playback.LibraryPlayer
import com.example.vocalremover.playback.PlayerState
import kotlinx.coroutines.flow.StateFlow

/** Anteprima audio di editor e schermata Import; interfaccia per poter testare il ViewModel. */
interface PreviewPlayer {
    val state: StateFlow<PlayerState>
    fun play(uri: Uri)
    fun pause()
    fun seekTo(positionMs: Long)
    /** Scarica il brano: il prossimo play() lo ricarica dal file. */
    fun stop()
    fun release()
}

/** Solo main thread, come [LibraryPlayer]. */
class LibraryPreviewPlayer(private val player: LibraryPlayer) : PreviewPlayer {
    override val state: StateFlow<PlayerState> get() = player.state
    override fun play(uri: Uri) = player.play(uri)
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun stop() = player.stop()
    override fun release() = player.release()
}
