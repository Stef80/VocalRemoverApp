package com.stef.vocalremover.ui.projection

import android.net.Uri
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.playback.PlayerError
import com.example.vocalremover.playback.PlayerState
import com.stef.vocalremover.ui.player.FakePlayer
import com.stef.vocalremover.ui.player.PlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectionControllerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val player = FakePlayer()
    private val source = FakeDisplaySource()
    private val host = FakeHost()
    private val track = LibraryTrack(Uri.parse("content://media/external/audio/media/5"), "Canzone", 60_000L)
    private val tv = ProjectionDisplay(2, "LG TV")
    private lateinit var session: PlayerSession
    private lateinit var controller: ProjectionController
    private val events = mutableListOf<ProjectionEvent>()

    private fun collectEvents() {
        scope.launch { controller.events.collect { events += it } }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        session = PlayerSession(player, ioDispatcher = dispatcher, scope = scope)
        controller = ProjectionController(source, host, session, scope)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun openAndPlay() {
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true)
    }

    @Test
    fun `no display means NoDisplay and start returns NO_DISPLAY`() {
        assertEquals(ProjectionState.NoDisplay, controller.state.value)
        session.open(track)
        assertEquals(ProjectionStartResult.NO_DISPLAY, controller.start())
        assertTrue(host.shown.isEmpty())
    }

    @Test
    fun `connected display becomes Ready`() {
        source.display.value = tv
        assertEquals(ProjectionState.Ready(tv), controller.state.value)
    }

    @Test
    fun `start without an open track returns NO_TRACK`() {
        source.display.value = tv
        assertEquals(ProjectionStartResult.NO_TRACK, controller.start())
        assertTrue(host.shown.isEmpty())
    }

    @Test
    fun `start with display and track shows on the display and becomes Projecting`() {
        source.display.value = tv
        session.open(track)
        assertEquals(ProjectionStartResult.STARTED, controller.start())
        assertEquals(listOf(tv), host.shown)
        assertEquals(ProjectionState.Projecting(tv), controller.state.value)
        assertTrue(controller.isProjecting)
    }

    @Test
    fun `start while projecting is a no-op`() {
        source.display.value = tv
        session.open(track)
        controller.start()
        assertEquals(ProjectionStartResult.STARTED, controller.start())
        assertEquals(listOf(tv), host.shown)
    }

    @Test
    fun `display removed while projecting pauses and ends`() {
        collectEvents()
        source.display.value = tv
        openAndPlay()
        controller.start()
        source.display.value = null
        assertEquals(1, player.pauseCalls)
        assertEquals(1, host.hideCalls)
        assertEquals(ProjectionState.NoDisplay, controller.state.value)
        assertEquals(listOf(ProjectionEvent.DISCONNECTED), events)
        assertEquals("pause", player.calls.last())
    }

    @Test
    fun `display replaced by another while projecting counts as disconnection`() {
        collectEvents()
        val other = ProjectionDisplay(3, "Altra")
        source.display.value = tv
        openAndPlay()
        controller.start()
        source.display.value = other
        assertEquals(ProjectionState.Ready(other), controller.state.value)
        assertEquals(listOf(ProjectionEvent.DISCONNECTED), events)
        assertEquals(1, host.hideCalls)
    }

    @Test
    fun `display removed while not projecting does not pause`() {
        collectEvents()
        source.display.value = tv
        openAndPlay()
        source.display.value = null
        assertEquals(0, player.pauseCalls)
        assertEquals(ProjectionState.NoDisplay, controller.state.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `stop is idempotent and hides once`() {
        source.display.value = tv
        session.open(track)
        controller.start()
        controller.stop()
        controller.stop()
        assertEquals(1, host.hideCalls)
        assertEquals(ProjectionState.Ready(tv), controller.state.value)
    }

    @Test
    fun `new track while projecting stays projecting`() {
        source.display.value = tv
        session.open(track)
        controller.start()
        session.open(LibraryTrack(Uri.parse("content://media/external/audio/media/6"), "Altra", 30_000L))
        assertEquals(ProjectionState.Projecting(tv), controller.state.value)
        assertEquals(0, host.hideCalls)
    }

    @Test
    fun `player error while projecting ends with PLAYBACK_ERROR`() {
        collectEvents()
        source.display.value = tv
        session.open(track)
        controller.start()
        player.state.value = PlayerState(currentUri = track.uri, error = PlayerError.SOURCE_UNAVAILABLE)
        assertEquals(1, host.hideCalls)
        assertEquals(ProjectionState.Ready(tv), controller.state.value)
        assertEquals(listOf(ProjectionEvent.PLAYBACK_ERROR), events)
    }

    @Test
    fun `player error while not projecting emits nothing`() {
        collectEvents()
        source.display.value = tv
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri, error = PlayerError.SOURCE_UNAVAILABLE)
        assertTrue(events.isEmpty())
        assertEquals(0, host.hideCalls)
        assertEquals(ProjectionState.Ready(tv), controller.state.value)
    }

    @Test
    fun `delete is disabled only while projecting`() {
        assertTrue(deleteEnabled(ProjectionState.NoDisplay))
        assertTrue(deleteEnabled(ProjectionState.Ready(tv)))
        assertFalse(deleteEnabled(ProjectionState.Projecting(tv)))
    }

    @Test
    fun `delete is enabled again after stop and after disconnection`() {
        source.display.value = tv
        session.open(track)
        controller.start()
        assertFalse(deleteEnabled(controller.state.value))
        controller.stop()
        assertTrue(deleteEnabled(controller.state.value))
        controller.start()
        source.display.value = null
        assertTrue(deleteEnabled(controller.state.value))
    }

    @Test
    fun `disconnection without collector is delivered to a later collector`() {
        source.display.value = tv
        openAndPlay()
        controller.start()
        source.display.value = null
        collectEvents()
        assertEquals(listOf(ProjectionEvent.DISCONNECTED), events)
    }

    @Test
    fun `player error without collector is delivered to a later collector`() {
        source.display.value = tv
        session.open(track)
        controller.start()
        player.state.value = PlayerState(currentUri = track.uri, error = PlayerError.SOURCE_UNAVAILABLE)
        collectEvents()
        assertEquals(listOf(ProjectionEvent.PLAYBACK_ERROR), events)
    }

    @Test
    fun `same display with a new name stays projecting without hiding`() {
        source.display.value = tv
        session.open(track)
        controller.start()
        val renamed = tv.copy(name = "LG OLED")
        source.display.value = renamed
        assertEquals(ProjectionState.Projecting(renamed), controller.state.value)
        assertEquals(0, host.hideCalls)
    }

    @Test
    fun `stop right after the display vanished hides once and emits no disconnection`() {
        val standard = StandardTestDispatcher()
        val lazyController = ProjectionController(source, host, session, CoroutineScope(standard))
        standard.scheduler.runCurrent()
        source.display.value = tv
        standard.scheduler.runCurrent()
        session.open(track)
        lazyController.start()
        source.display.value = null
        lazyController.stop()
        standard.scheduler.runCurrent()
        assertEquals(1, host.hideCalls)
        assertEquals(ProjectionState.NoDisplay, lazyController.state.value)
        val received = mutableListOf<ProjectionEvent>()
        scope.launch { lazyController.events.collect { received += it } }
        assertTrue(received.isEmpty())
    }

    @Test
    fun `start again after a disconnection when the TV comes back`() {
        source.display.value = tv
        openAndPlay()
        controller.start()
        source.display.value = null
        val back = ProjectionDisplay(5, "LG TV")
        source.display.value = back
        assertEquals(ProjectionStartResult.STARTED, controller.start())
        assertEquals(listOf(tv, back), host.shown)
        assertEquals(ProjectionState.Projecting(back), controller.state.value)
    }

    private class FakeDisplaySource : PresentationDisplaySource {
        override val display = MutableStateFlow<ProjectionDisplay?>(null)
    }

    private class FakeHost : ProjectionHost {
        val shown = mutableListOf<ProjectionDisplay>()
        var hideCalls = 0
        override fun show(display: ProjectionDisplay) { shown += display }
        override fun hide() { hideCalls++ }
    }
}
