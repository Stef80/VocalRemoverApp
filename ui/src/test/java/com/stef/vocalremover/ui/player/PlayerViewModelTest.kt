package com.stef.vocalremover.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.playback.PlayerError
import com.example.vocalremover.playback.PlayerState
import com.stef.vocalremover.ui.PreviewPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerViewModelTest {

    private val player = FakePlayer()
    private val track = LibraryTrack(Uri.parse("content://media/external/audio/media/5"), "Canzone", 60_000L)
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PlayerViewModel(player)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no track before open`() = assertNull(viewModel.state.value)

    @Test
    fun `open plays the track from the start`() {
        viewModel.open(track)
        assertEquals(listOf("stop", "play(${track.uri})"), player.calls)
        assertEquals(track, viewModel.state.value?.track)
    }

    @Test
    fun `state follows the player for the open track`() {
        viewModel.open(track)
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true, positionMs = 1_500, durationMs = 61_000)
        val state = viewModel.state.value!!
        assertEquals(true, state.isPlaying)
        assertEquals(1_500L, state.positionMs)
        assertEquals(61_000L, state.durationMs)
    }

    @Test
    fun `duration falls back to the library one until the player knows it`() {
        viewModel.open(track)
        player.state.value = PlayerState(currentUri = track.uri)
        assertEquals(60_000L, viewModel.state.value?.durationMs)
    }

    @Test
    fun `player state of another uri is ignored`() {
        player.state.value = PlayerState(currentUri = Uri.parse("file:///cache/x.wav"), isPlaying = true, positionMs = 9_000)
        viewModel.open(track)
        val state = viewModel.state.value!!
        assertEquals(false, state.isPlaying)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `error is exposed`() {
        viewModel.open(track)
        player.state.value = PlayerState(currentUri = track.uri, error = PlayerError.SOURCE_UNAVAILABLE)
        assertEquals(PlayerError.SOURCE_UNAVAILABLE, viewModel.state.value?.error)
    }

    @Test
    fun `rewind goes back ten seconds and stops at zero`() {
        viewModel.open(track)
        player.state.value = PlayerState(currentUri = track.uri, positionMs = 25_000, durationMs = 60_000)
        viewModel.rewind()
        player.state.update { it.copy(positionMs = 4_000) }
        viewModel.rewind()
        assertEquals(listOf("seekTo(15000)", "seekTo(0)"), player.calls.filter { it.startsWith("seekTo") })
    }

    @Test
    fun `forward goes ahead ten seconds and stops at the end`() {
        viewModel.open(track)
        player.state.value = PlayerState(currentUri = track.uri, positionMs = 10_000, durationMs = 60_000)
        viewModel.forward()
        player.state.update { it.copy(positionMs = 55_000) }
        viewModel.forward()
        assertEquals(listOf("seekTo(20000)", "seekTo(60000)"), player.calls.filter { it.startsWith("seekTo") })
    }

    @Test
    fun `toggle pauses when playing and plays when paused`() {
        viewModel.open(track)
        player.calls.clear()
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true)
        viewModel.togglePlayback()
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = false)
        viewModel.togglePlayback()
        assertEquals(listOf("pause", "play(${track.uri})"), player.calls)
    }

    @Test
    fun `leave pauses only if playing`() {
        viewModel.open(track)
        player.calls.clear()
        viewModel.leave()
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true)
        viewModel.leave()
        assertEquals(listOf("pause"), player.calls)
    }

    @Test
    fun `deleting the open track stops and closes it`() {
        viewModel.open(track)
        player.calls.clear()
        viewModel.onTrackDeleted(Uri.parse("content://media/external/audio/media/99"))
        assertEquals(track, viewModel.state.value?.track)
        viewModel.onTrackDeleted(track.uri)
        assertEquals(listOf("stop"), player.calls)
        assertNull(viewModel.state.value)
    }

    @Test
    fun `renaming the open track updates the title`() {
        viewModel.open(track)
        viewModel.onTrackRenamed(track.uri, "  Nuovo: titolo ")
        assertEquals("Nuovo_ titolo", viewModel.state.value?.track?.title)
    }

    @Test
    fun `cleared releases the player`() {
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { PlayerViewModel(player) } })[PlayerViewModel::class]
        store.clear()
        assertEquals("release", player.calls.last())
    }

    private class FakePlayer : PreviewPlayer {
        override val state = MutableStateFlow(PlayerState())
        val calls = mutableListOf<String>()
        override fun play(uri: Uri) { calls += "play($uri)" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seekTo($positionMs)" }
        override fun stop() { calls += "stop" }
        override fun release() { calls += "release" }
    }
}
