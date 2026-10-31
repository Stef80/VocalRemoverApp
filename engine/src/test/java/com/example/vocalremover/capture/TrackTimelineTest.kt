package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Segments of a capture: building from the session data and choice on save. */
class TrackTimelineTest {

    private val ad = SessionMetadata("Brano", "Gruppo", null, 18_000, null)
    private val song = SessionMetadata("Brano", "Gruppo", "Album", 200_000, "id:1")
    private val next = SessionMetadata("Altro", "Gruppo", null, 180_000, "id:2")

    private fun segment(meta: SessionMetadata, startMs: Long, position: Long?) = TrackSegment(
        meta.title!!, meta.artist!!, meta.album, meta.durationMs, meta.mediaId, startMs, position,
    )

    // --- building (first frame of the file at 10 000 ms of elapsedRealtime) ---

    private fun build(
        entries: List<Pair<Long, SessionMetadata>>,
        playing: List<PlaybackSnapshot> = emptyList(),
        reliable: Boolean = true,
    ) = TrackTimelineBuilder.build("com.example.player", entries, playing, firstFrameElapsedMs = 10_000, positionsReliable = reliable)

    @Test
    fun unSoloBranoConPosizioneDallaRiproduzione() = assertEquals(
        CaptureTrackTimeline("com.example.player", listOf(segment(song, 0, 45_000))),
        build(listOf(5_000L to song), listOf(PlaybackSnapshot(true, 40_000, 5_000, 1f))),
    )

    @Test
    fun pubblicitaPoiCanzone() = assertEquals(
        listOf(segment(ad, 0, 3_000), segment(song, 8_000, 1_000)),
        build(
            listOf(9_000L to ad, 18_000L to song),
            listOf(PlaybackSnapshot(true, 2_000, 9_000, 1f), PlaybackSnapshot(true, 1_500, 18_500, 1f)),
        )!!.segments,
    )

    @Test
    fun branoSuccessivoSenzaStatoParteDaZero() =
        assertEquals(0L, build(listOf(9_000L to song, 70_000L to next))!!.segments[1].songPositionAtStartMs)

    @Test
    fun primoBranoSenzaStatoPosizioneIgnota() =
        assertNull(build(listOf(9_000L to song))!!.segments[0].songPositionAtStartMs)

    @Test
    fun cambioAnnunciatoSubitoDopoIlPrimoFrameVale() = assertEquals(
        listOf(segment(next, 0, null)),
        build(listOf(5_000L to song, 11_000L to next))!!.segments,
    )

    @Test
    fun cambioPrimaDelPrimoFrame() = assertEquals(
        listOf(segment(next, 0, null)),
        build(listOf(5_000L to song, 8_000L to next))!!.segments,
    )

    @Test
    fun senzaTimestampAudioPosizioniIgnote() = assertEquals(
        listOf(null, null),
        build(listOf(9_000L to ad, 18_000L to song), reliable = false)!!.segments.map { it.songPositionAtStartMs },
    )

    @Test
    fun nessunBrano() = assertNull(build(emptyList()))

    // --- proposed leading trim ---

    private val adThenSong = CaptureTrackTimeline("p", listOf(segment(ad, 0, 3_000), segment(song, 8_000, 0)))

    @Test
    fun pubblicitaIniziale_tagliataInAutomatico() =
        assertEquals(8_000L to 120_000L, TrackTimelineResolver.initialRange(adThenSong, durationMs = 120_000))

    @Test
    fun cambioOltreQuindiciSecondi_inizioIntatto_fineAlCambio() {
        val timeline = CaptureTrackTimeline("p", listOf(segment(song, 0, 0), segment(next, 90_000, 0)))
        assertEquals(0L to 90_000L, TrackTimelineResolver.initialRange(timeline, durationMs = 120_000))
    }

    @Test
    fun pubblicitaCanzoneEVideoSuccessivo() {
        val timeline = CaptureTrackTimeline(
            "p", listOf(segment(ad, 0, 0), segment(song, 8_000, 0), segment(next, 100_000, 0)),
        )
        assertEquals(8_000L to 100_000L, TrackTimelineResolver.initialRange(timeline, durationMs = 120_000))
    }

    @Test
    fun intervalloTroppoCortoNonSiTaglia() {
        val timeline = CaptureTrackTimeline("p", listOf(segment(ad, 0, 0), segment(song, 8_000, 0), segment(next, 10_000, 0)))
        assertEquals(0L to 12_000L, TrackTimelineResolver.initialRange(timeline, durationMs = 12_000))
    }

    @Test
    fun senzaSegmentiFileIntero() =
        assertEquals(0L to 120_000L, TrackTimelineResolver.initialRange(null, durationMs = 120_000))

    private fun tribute(durationMs: Long, startMs: Long) =
        TrackSegment("Tribute", "Tenacious D", null, durationMs, null, startMs, 0)

    @Test
    fun duePubblicitaDiFilaSiSaltanoEntrambe() {
        // Real case: two ads (7 s and 20 s declared) and then the song, same title.
        val timeline = CaptureTrackTimeline(
            "p", listOf(tribute(7_000, 0), tribute(20_000, 4_474), tribute(293_000, 10_004)),
        )
        assertEquals(10_004L to 84_961L, TrackTimelineResolver.initialRange(timeline, durationMs = 84_961))
    }

    @Test
    fun pubblicitaLungaOltreQuindiciSecondiSiSaltaLoStesso() {
        val timeline = CaptureTrackTimeline("p", listOf(tribute(30_000, 0), tribute(293_000, 25_000)))
        assertEquals(25_000L to 90_000L, TrackTimelineResolver.initialRange(timeline, durationMs = 90_000))
    }

    @Test
    fun pubblicitaAMetaVideoNonChiudeIlTaglio() {
        val timeline = CaptureTrackTimeline(
            "p", listOf(tribute(293_000, 0), tribute(15_000, 60_000), tribute(293_000, 75_000)),
        )
        assertEquals(0L to 120_000L, TrackTimelineResolver.initialRange(timeline, durationMs = 120_000))
    }

    @Test
    fun pubblicitaCanzonePoiVideoSuccessivoConPubblicita() {
        val other = TrackSegment("Altro", "Gruppo", null, 15_000, null, 100_000, 0)
        val timeline = CaptureTrackTimeline(
            "p", listOf(tribute(7_000, 0), tribute(293_000, 6_000), other),
        )
        assertEquals(6_000L to 100_000L, TrackTimelineResolver.initialRange(timeline, durationMs = 120_000))
    }

    // --- track chosen on save ---

    @Test
    fun sceltoIlSegmentoPiuPresenteConPosizioneAllInizioDelTaglio() = assertEquals(
        CapturedTrackInfo("Brano", "Gruppo", "Album", 200_000, 5_000, "p", "id:1"),
        TrackTimelineResolver.resolve(adThenSong, trimStartMs = 13_000, trimEndMs = 60_000),
    )

    @Test
    fun tenendoLaPubblicitaVinceLaCanzone_posizioneNegativa() = assertEquals(
        -8_000L,
        TrackTimelineResolver.resolve(adThenSong, trimStartMs = 0, trimEndMs = 60_000)!!.songOffsetMs,
    )

    @Test
    fun soloPubblicitaTenuta() = assertEquals(
        18_000L,
        TrackTimelineResolver.resolve(adThenSong, trimStartMs = 0, trimEndMs = 7_000)!!.trackDurationMs,
    )

    @Test
    fun posizioneIgnotaResta() {
        val timeline = CaptureTrackTimeline("p", listOf(segment(song, 0, null)))
        assertNull(TrackTimelineResolver.resolve(timeline, 1_000, 5_000)!!.songOffsetMs)
    }
}
