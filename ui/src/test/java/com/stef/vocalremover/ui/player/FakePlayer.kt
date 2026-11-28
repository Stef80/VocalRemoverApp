package com.stef.vocalremover.ui.player

import android.net.Uri
import com.example.vocalremover.playback.PlayerState
import com.stef.vocalremover.ui.PreviewPlayer
import kotlinx.coroutines.flow.MutableStateFlow

internal class FakePlayer : PreviewPlayer {
    override val state = MutableStateFlow(PlayerState())
    val calls = mutableListOf<String>()
    val pauseCalls get() = calls.count { it == "pause" }
    override fun play(uri: Uri) { calls += "play($uri)" }
    override fun pause() { calls += "pause" }
    override fun seekTo(positionMs: Long) { calls += "seekTo($positionMs)" }
    override fun stop() { calls += "stop" }
    override fun release() { calls += "release" }
}
