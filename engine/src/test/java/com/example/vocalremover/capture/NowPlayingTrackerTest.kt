package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NowPlayingTrackerTest {

    private var now = 1_000L
    private val first = SessionMetadata("Brano", "Gruppo", "Album", 200_000, "id:1")
    private val second = SessionMetadata("Altro", "Gruppo", null, 180_000, "id:2")

    private class FakeSession(
        override val packageName: String,
        var meta: SessionMetadata?,
        var state: PlaybackSnapshot? = null,
    ) : SessionHandle {
        var listener: SessionListener? = null
        override fun metadata() = meta
        override fun playback() = state
        override fun observe(listener: SessionListener) { this.listener = listener }
        override fun stopObserving() { listener = null }
    }

    private class FakeSource(var sessions: List<SessionHandle> = emptyList(), var denied: Boolean = false) : SessionSource {
        var onChange: ((List<SessionHandle>) -> Unit)? = null
        override fun activeSessions(): List<SessionHandle> {
            if (denied) throw SecurityException("accesso alle notifiche mancante")
            return sessions
        }
        override fun observeSessions(onChange: (List<SessionHandle>) -> Unit) {
            if (denied) throw SecurityException("accesso alle notifiche mancante")
            this.onChange = onChange
        }
        override fun stopObservingSessions() { onChange = null }
    }

    private fun tracker(source: SessionSource) =
        NowPlayingTracker(source, setOf("com.example.player"), clock = { now })

    /** First frame of the file at 2 000 ms of elapsedRealtime. */
    private fun NowPlayingTracker.timeline(): CaptureTrackTimeline? = timeline(firstFrameElapsedMs = 2_000, positionsReliable = true)

    private fun titles(timeline: CaptureTrackTimeline?) = timeline?.segments?.map { it.title to it.startMs }

    @Test
    fun permessoMancanteNessunErroreENessunBrano() {
        val tracker = tracker(FakeSource(denied = true))
        tracker.start()
        assertNull(tracker.timeline())
    }

    @Test
    fun sessioneDiUnAltraAppIgnorata() {
        val tracker = tracker(FakeSource(listOf(FakeSession("com.altro", first))))
        tracker.start()
        assertNull(tracker.timeline())
    }

    @Test
    fun sessioneDellAppCatturata() {
        val tracker = tracker(FakeSource(listOf(FakeSession("com.altro", second), FakeSession("com.example.player", first))))
        tracker.start()

        val timeline = tracker.timeline()!!

        assertEquals("com.example.player", timeline.sourcePackage)
        assertEquals(listOf("Brano" to 0L), titles(timeline))
        assertEquals(200_000L, timeline.segments[0].trackDurationMs)
    }

    @Test
    fun sessioneCompariaDopoLAvvio() {
        val source = FakeSource()
        val tracker = tracker(source)
        tracker.start()

        source.onChange!!(listOf(FakeSession("com.example.player", first)))

        assertEquals(listOf("Brano" to 0L), titles(tracker.timeline()))
    }

    @Test
    fun permessoRevocatoDuranteLaCatturaNessunErrore() {
        val source = FakeSource()
        val tracker = tracker(source)
        tracker.start()
        source.denied = true

        source.onChange!!(emptyList())
        tracker.retryAttach()

        assertNull(tracker.timeline())
    }

    @Test
    fun cambioBranoDopoIlPrimoAudioCreaUnSegmento() {
        val session = FakeSession("com.example.player", first)
        val tracker = tracker(FakeSource(listOf(session)))
        tracker.start()

        now = 60_000
        session.listener!!.onMetadata(second)

        assertEquals(listOf("Brano" to 0L, "Altro" to 58_000L), titles(tracker.timeline()))
    }

    @Test
    fun stessoTitoloConDurataDiversaEUnNuovoSegmento() {
        // YouTube: during the ad, the video title but the ad duration.
        val session = FakeSession("com.example.player", first.copy(durationMs = 18_000))
        val tracker = tracker(FakeSource(listOf(session)))
        tracker.start()

        now = 20_000
        session.listener!!.onMetadata(first)

        val timeline = tracker.timeline()!!
        assertEquals(listOf("Brano" to 0L, "Brano" to 18_000L), titles(timeline))
        assertEquals(listOf(18_000L, 200_000L), timeline.segments.map { it.trackDurationMs })
    }

    @Test
    fun durataAssenteNonEUnCambio() {
        val session = FakeSession("com.example.player", first)
        val tracker = tracker(FakeSource(listOf(session)))
        tracker.start()

        now = 60_000
        session.listener!!.onMetadata(first.copy(durationMs = null))

        val timeline = tracker.timeline()!!
        assertEquals(1, timeline.segments.size)
        assertEquals(200_000L, timeline.segments[0].trackDurationMs)
    }

    @Test
    fun albumAggiornatoNonEUnCambio() {
        val session = FakeSession("com.example.player", first)
        val tracker = tracker(FakeSource(listOf(session)))
        tracker.start()

        now = 60_000
        session.listener!!.onMetadata(first.copy(album = "Altra copertina"))

        val timeline = tracker.timeline()!!
        assertEquals(1, timeline.segments.size)
        assertEquals("Altra copertina", timeline.segments[0].album)
    }

    @Test
    fun artistaVuotoNessunBrano() {
        val tracker = tracker(FakeSource(listOf(FakeSession("com.example.player", first.copy(artist = " ")))))
        tracker.start()
        assertNull(tracker.timeline())
    }

    @Test
    fun metadatiArrivatiAPezziNonSonoUnCambioBrano() {
        val session = FakeSession("com.example.player", first.copy(artist = null))
        val tracker = tracker(FakeSource(listOf(session)))
        tracker.start()

        now = 1_300
        session.listener!!.onMetadata(first)

        assertEquals(listOf("Brano" to 0L), titles(tracker.timeline()))
    }

    @Test
    fun posizioneDalloStatoDiRiproduzione() {
        val session = FakeSession("com.example.player", first, PlaybackSnapshot(true, 5_000, 900, 1f))
        val tracker = tracker(FakeSource(listOf(session)))
        tracker.start()

        now = 30_000
        session.listener!!.onMetadata(second)
        session.listener!!.onPlayback(PlaybackSnapshot(true, 2_000, 32_000, 1f))

        assertEquals(listOf(6_100L, 0L), tracker.timeline()!!.segments.map { it.songPositionAtStartMs })
    }

    @Test
    fun ilBranoRestaLeggibileDopoStop() {
        val tracker = tracker(FakeSource(listOf(FakeSession("com.example.player", first))))
        tracker.start()

        tracker.stop()

        assertEquals(listOf("Brano" to 0L), titles(tracker.timeline()))
    }

    @Test
    fun stopSmetteDiAscoltare() {
        val session = FakeSession("com.example.player", first)
        val source = FakeSource(listOf(session))
        val tracker = tracker(source)
        tracker.start()

        tracker.stop()

        assertNull(session.listener)
        assertNull(source.onChange)
    }

    @Test
    fun nuovoTentativoAgganciaLaSessioneComparsaSenzaNotifica() {
        val source = FakeSource()
        val tracker = tracker(source)
        tracker.start()
        source.sessions = listOf(FakeSession("com.example.player", first))

        tracker.retryAttach()

        assertEquals(listOf("Brano" to 0L), titles(tracker.timeline()))
    }

    @Test
    fun nuovoTentativoSenzaPermessoNessunErrore() {
        val tracker = tracker(FakeSource(denied = true))
        tracker.start()

        tracker.retryAttach()

        assertNull(tracker.timeline())
    }
}
