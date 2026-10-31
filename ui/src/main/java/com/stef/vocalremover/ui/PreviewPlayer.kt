package com.stef.vocalremover.ui

import android.net.Uri
import com.example.vocalremover.playback.LibraryPlayer
import com.example.vocalremover.playback.PlayerState
import kotlinx.coroutines.flow.StateFlow

/** Audio preview of the editor and the Import screen; an interface so the ViewModel can be tested. */
interface PreviewPlayer {
    val state: StateFlow<PlayerState>
    fun play(uri: Uri)
    fun pause()
    fun seekTo(positionMs: Long)
    /** Unloads the track: the next play() reloads it from the file. */
    fun stop()
    fun release()
}

/** Main thread only, like [LibraryPlayer]. */
class LibraryPreviewPlayer(private val player: LibraryPlayer) : PreviewPlayer {
    override val state: StateFlow<PlayerState> get() = player.state
    override fun play(uri: Uri) = player.play(uri)
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun stop() = player.stop()
    override fun release() = player.release()
}
