package com.stef.vocalremover.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vocalremover.capture.CapturedTrackInfo
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.playback.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var session: PlayerSession
    private var storedLyrics: TrackLyrics? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        session = PlayerSession(player, loadLyrics = { storedLyrics }, ioDispatcher = dispatcher, scope = CoroutineScope(dispatcher))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun openAndPlay(vm: PlayerViewModel) {
        vm.open(track)
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true)
    }

    @Test
    fun `leave without projection pauses`() {
        val vm = PlayerViewModel(session, isProjecting = { false })
        openAndPlay(vm)
        vm.leave()
        assertEquals(1, player.pauseCalls)
    }

    @Test
    fun `leave while projecting does not pause`() {
        val vm = PlayerViewModel(session, isProjecting = { true })
        openAndPlay(vm)
        vm.leave()
        assertEquals(0, player.pauseCalls)
    }

    @Test
    fun `clearing the ViewModel does not release the player`() {
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { PlayerViewModel(session) } })[PlayerViewModel::class]
        store.clear()
        assertFalse("release" in player.calls)
    }

    @Test
    fun `leave while lyrics pending does not start playback when they arrive`() {
        storedLyrics = pending()
        val vm = PlayerViewModel(session, isProjecting = { false })
        vm.open(track)
        vm.leave()
        storedLyrics = null
        dispatcher.scheduler.advanceTimeBy(PlayerSession.LYRICS_POLL_MS * 3)
        assertEquals(listOf("stop"), player.calls)
        assertNull(vm.state.value)
    }

    @Test
    fun `leave while projecting keeps waiting for the lyrics`() {
        storedLyrics = pending()
        val vm = PlayerViewModel(session, isProjecting = { true })
        vm.open(track)
        vm.leave()
        storedLyrics = null
        dispatcher.scheduler.advanceTimeBy(PlayerSession.LYRICS_POLL_MS + 1)
        assertEquals(listOf("stop", "play(${track.uri})"), player.calls)
    }

    @Test
    fun `reopening after leaving during the wait plays when lyrics are ready`() {
        storedLyrics = pending()
        val vm = PlayerViewModel(session, isProjecting = { false })
        vm.open(track)
        vm.leave()
        storedLyrics = null
        vm.open(track)
        assertEquals(listOf("stop", "stop", "play(${track.uri})"), player.calls)
    }

    private fun pending() = TrackLyrics(
        savedUri = track.uri.toString(),
        track = CapturedTrackInfo("T", "A", null, null, null, "pkg", null),
        status = LyricsStatus.PENDING,
        lines = emptyList(),
        plainText = null,
    )
}
