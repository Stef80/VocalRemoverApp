package com.stef.vocalremover.ui.player

import android.net.Uri
import com.example.vocalremover.capture.CapturedTrackInfo
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TimedLine
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.playback.PlayerError
import com.example.vocalremover.playback.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PlayerSessionTest {

    private val player = FakePlayer()
    private val track = LibraryTrack(Uri.parse("content://media/external/audio/media/5"), "Canzone", 60_000L)
    private lateinit var session: PlayerSession

    private val dispatcher = UnconfinedTestDispatcher()
    private var storedLyrics: TrackLyrics? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        session = PlayerSession(player, loadLyrics = { storedLyrics }, ioDispatcher = dispatcher, scope = CoroutineScope(dispatcher))
    }

    private fun lyrics(status: LyricsStatus, lines: List<TimedLine> = emptyList(), plain: String? = null, offset: Long? = 5_000) =
        TrackLyrics(
            savedUri = track.uri.toString(),
            track = CapturedTrackInfo("T", "A", null, null, offset, "pkg", null),
            status = status,
            lines = lines,
            plainText = plain,
        )

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no track before open`() = assertNull(session.state.value)

    @Test
    fun `open plays the track from the start`() {
        session.open(track)
        assertEquals(listOf("stop", "play(${track.uri})"), player.calls)
        assertEquals(track, session.state.value?.track)
    }

    @Test
    fun `state follows the player for the open track`() {
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true, positionMs = 1_500, durationMs = 61_000)
        val state = session.state.value!!
        assertEquals(true, state.isPlaying)
        assertEquals(1_500L, state.positionMs)
        assertEquals(61_000L, state.durationMs)
    }

    @Test
    fun `duration falls back to the library one until the player knows it`() {
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri)
        assertEquals(60_000L, session.state.value?.durationMs)
    }

    @Test
    fun `player state of another uri is ignored`() {
        player.state.value = PlayerState(currentUri = Uri.parse("file:///cache/x.wav"), isPlaying = true, positionMs = 9_000)
        session.open(track)
        val state = session.state.value!!
        assertEquals(false, state.isPlaying)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `error is exposed`() {
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri, error = PlayerError.SOURCE_UNAVAILABLE)
        assertEquals(PlayerError.SOURCE_UNAVAILABLE, session.state.value?.error)
    }

    @Test
    fun `rewind goes back ten seconds and stops at zero`() {
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri, positionMs = 25_000, durationMs = 60_000)
        session.rewind()
        player.state.update { it.copy(positionMs = 4_000) }
        session.rewind()
        assertEquals(listOf("seekTo(15000)", "seekTo(0)"), player.calls.filter { it.startsWith("seekTo") })
    }

    @Test
    fun `forward goes ahead ten seconds and stops at the end`() {
        session.open(track)
        player.state.value = PlayerState(currentUri = track.uri, positionMs = 10_000, durationMs = 60_000)
        session.forward()
        player.state.update { it.copy(positionMs = 55_000) }
        session.forward()
        assertEquals(listOf("seekTo(20000)", "seekTo(60000)"), player.calls.filter { it.startsWith("seekTo") })
    }

    @Test
    fun `toggle pauses when playing and plays when paused`() {
        session.open(track)
        player.calls.clear()
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true)
        session.togglePlayback()
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = false)
        session.togglePlayback()
        assertEquals(listOf("pause", "play(${track.uri})"), player.calls)
    }

    @Test
    fun `pause only if playing`() {
        session.open(track)
        session.pause()
        assertEquals(0, player.pauseCalls)
        player.state.value = PlayerState(currentUri = track.uri, isPlaying = true)
        session.pause()
        assertEquals(1, player.pauseCalls)
    }

    @Test
    fun `pause while lyrics pending keeps the track open without autoplay`() {
        storedLyrics = lyrics(LyricsStatus.PENDING)
        session.open(track)
        session.pause()
        storedLyrics = lyrics(LyricsStatus.FOUND, lines = listOf(TimedLine(6_000, "uno")))
        dispatcher.scheduler.advanceTimeBy(PlayerSession.LYRICS_POLL_MS + 1)
        assertEquals(listOf("stop"), player.calls)
        assertEquals(track, session.state.value?.track)
        assertEquals(true, session.state.value?.lyrics is PlayerLyrics.Synced)
        session.play()
        assertEquals(listOf("stop", "play(${track.uri})"), player.calls)
    }

    @Test
    fun `reopening after a pause during the wait autoplays again`() {
        storedLyrics = lyrics(LyricsStatus.PENDING)
        session.open(track)
        session.pause()
        storedLyrics = null
        session.open(track)
        assertEquals(listOf("stop", "stop", "play(${track.uri})"), player.calls)
    }

    @Test
    fun `deleting the open track stops and closes it`() {
        session.open(track)
        player.calls.clear()
        session.onTrackDeleted(Uri.parse("content://media/external/audio/media/99"))
        assertEquals(track, session.state.value?.track)
        session.onTrackDeleted(track.uri)
        assertEquals(listOf("stop"), player.calls)
        assertNull(session.state.value)
    }

    @Test
    fun `renaming the open track updates the title`() {
        session.open(track)
        session.onTrackRenamed(track.uri, "  Nuovo: titolo ")
        assertEquals("Nuovo_ titolo", session.state.value?.track?.title)
    }

    @Test
    fun `track without lyrics file plays at once`() {
        session.open(track)
        assertEquals(PlayerLyrics.Unavailable, session.state.value?.lyrics)
        assertEquals(listOf("stop", "play(${track.uri})"), player.calls)
    }

    @Test
    fun `pending lyrics keep the track waiting until downloaded`() {
        storedLyrics = lyrics(LyricsStatus.PENDING)
        session.open(track)
        assertEquals(PlayerLyrics.Loading, session.state.value?.lyrics)
        assertEquals(listOf("stop"), player.calls)

        dispatcher.scheduler.advanceTimeBy(PlayerSession.LYRICS_POLL_MS * 3)
        assertEquals(listOf("stop"), player.calls)

        storedLyrics = lyrics(LyricsStatus.FOUND, lines = listOf(TimedLine(6_000, "uno"), TimedLine(9_000, "due")))
        dispatcher.scheduler.advanceTimeBy(PlayerSession.LYRICS_POLL_MS + 1)
        assertEquals(listOf("stop", "play(${track.uri})"), player.calls)
        val shown = session.state.value?.lyrics as PlayerLyrics.Synced
        assertEquals(listOf("uno", "due"), shown.lines)
        // With a 5 s offset "uno" starts at 1 s into the file, "due" at 4 s.
        assertEquals(-1, shown.activeLine(500))
        assertEquals(0, shown.activeLine(1_000))
        assertEquals(1, shown.activeLine(4_500))
    }

    @Test
    fun `controls do nothing while lyrics are loading`() {
        storedLyrics = lyrics(LyricsStatus.PENDING)
        session.open(track)
        player.calls.clear()
        session.play()
        session.togglePlayback()
        session.forward()
        session.seekTo(3_000)
        assertEquals(emptyList<String>(), player.calls)
    }

    @Test
    fun `reopening another track stops waiting for the previous lyrics`() {
        storedLyrics = lyrics(LyricsStatus.PENDING)
        session.open(track)
        val other = LibraryTrack(Uri.parse("content://media/external/audio/media/6"), "Altra", 30_000L)
        storedLyrics = null
        session.open(other)
        dispatcher.scheduler.advanceTimeBy(PlayerSession.LYRICS_POLL_MS * 2)
        assertEquals(listOf("stop", "stop", "play(${other.uri})"), player.calls)
    }

    @Test
    fun `stored statuses map to what the player shows`() {
        assertEquals(PlayerLyrics.Instrumental, PlayerLyrics.from(lyrics(LyricsStatus.INSTRUMENTAL)))
        assertEquals(PlayerLyrics.Unavailable, PlayerLyrics.from(lyrics(LyricsStatus.NOT_FOUND)))
        assertEquals(PlayerLyrics.Unavailable, PlayerLyrics.from(lyrics(LyricsStatus.FAILED)))
        assertEquals(PlayerLyrics.Plain("testo"), PlayerLyrics.from(lyrics(LyricsStatus.FOUND, plain = "testo")))
        assertEquals(PlayerLyrics.Unavailable, PlayerLyrics.from(lyrics(LyricsStatus.FOUND)))
        assertEquals(0L, (PlayerLyrics.from(lyrics(LyricsStatus.FOUND, listOf(TimedLine(1, "x")), offset = null)) as PlayerLyrics.Synced).songOffsetMs)
    }

}
