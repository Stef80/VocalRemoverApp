package com.example.vocalremover.capture

import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File
import com.example.vocalremover.lyrics.LyricsScheduler
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TrackLyricsStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingCaptureRepositoryTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var cacheDir: File
    private lateinit var raw: File
    private val savedUri: Uri = Uri.parse("content://media/external/audio/media/7")

    @Before
    fun setUp() {
        // The store rejects pid 0, which is Robolectric's default.
        ShadowProcess.setPid(1234)
        cacheDir = File("build/test-pending-capture/${System.nanoTime()}").apply { mkdirs() }
        raw = File(cacheDir, "system_audio_capture_1.wav")
        writePcm16StereoWav(raw, rampSamples(44_100 * 3)) // 3000 ms
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private val scheduled = mutableListOf<String>()
    private val scheduler = object : LyricsScheduler {
        override fun schedule(savedUri: String) { scheduled += savedUri }
        override fun cancel(savedUri: String) = Unit
    }
    private val lyricsStore by lazy { TrackLyricsStore(File(cacheDir, "lyrics")) }

    private fun repository(clock: Long = 0L) = DefaultPendingCaptureRepository(
        context, cacheDir = cacheDir, clock = { clock }, lyricsStore = lyricsStore, lyricsScheduler = scheduler,
    )

    private fun persistCapture() {
        CaptureTerminalResultStore(context).persist(CaptureTerminalKind.STOPPED, outputPath = raw.path)
    }

    @Test
    fun `no stopped capture loads null`() {
        assertNull(repository().load())
    }

    @Test
    fun `first load creates the default draft`() {
        persistCapture()

        val capture = repository().load()!!

        assertEquals(RecordingFileNaming.defaultName(0L), capture.displayName)
        assertEquals(0L, capture.trimStartMs)
        assertEquals(3000L, capture.trimEndMs)
        assertEquals(3000L, capture.durationMs)
        assertNull(capture.trimmedFile)
        assertEquals(raw, capture.playableFile)
    }

    @Test
    fun `second load reuses the draft created by the first`() {
        persistCapture()
        val first = repository(clock = 0L).load()!!

        val second = repository(clock = 1_000_000_000_000L).load()!!

        assertEquals(first.displayName, second.displayName)
    }

    @Test
    fun `missing wav discards capture and draft`() {
        persistCapture()
        raw.delete()

        assertNull(repository().load())
        assertNull(CaptureTerminalResultStore(context).peekStoppedResult())
    }

    @Test
    fun `saveEdit trims into cacheDir with suffix`() {
        persistCapture()
        val repo = repository().apply { load() }

        val capture = repo.saveEdit("Canzone", 1000, 2000)

        assertEquals(File(cacheDir, "Canzone_tagliato.wav"), capture.trimmedFile)
        assertEquals(1000L, WavFileReader.durationMs(WavFileReader.readInfo(capture.trimmedFile!!)))
        assertEquals("Canzone", capture.displayName)
        assertEquals(capture, repository().load())
    }

    @Test
    fun `saveEdit with full range removes trim and keeps name`() {
        persistCapture()
        val repo = repository().apply { load() }
        val trimmed = repo.saveEdit("Canzone", 1000, 2000).trimmedFile!!

        val capture = repo.saveEdit("X", 0, 3000)

        assertNull(capture.trimmedFile)
        assertEquals("X", capture.displayName)
        assertFalse(trimmed.exists())
    }

    @Test
    fun `renaming deletes the old trimmed file`() {
        persistCapture()
        val repo = repository().apply { load() }
        val old = repo.saveEdit("A", 1000, 2000).trimmedFile!!

        val renamed = repo.saveEdit("B", 1000, 2500).trimmedFile!!

        assertFalse(old.exists())
        assertTrue(renamed.exists())
    }

    @Test
    fun `blank name is rejected`() {
        persistCapture()
        val repo = repository().apply { load() }
        assertThrows(IllegalArgumentException::class.java) { repo.saveEdit("  ", 0, 3000) }
    }

    @Test
    fun `failed trim keeps previous trimmed file and draft`() {
        persistCapture()
        val repo = repository().apply { load() }
        val first = repo.saveEdit("A", 1000, 2000)

        assertThrows(IllegalArgumentException::class.java) { repo.saveEdit("B", 1000, 1999) }

        assertEquals(first, repository().load())
        assertTrue(first.trimmedFile!!.exists())
        assertTrue(cacheDir.listFiles()!!.none { it.name.startsWith("B_tagliato.wav") })
    }

    @Test
    fun `complete processing deletes files and clears everything`() {
        persistCapture()
        val repo = repository().apply { load() }
        val trimmed = repo.saveEdit("A", 1000, 2000).trimmedFile!!

        assertTrue(repo.beginProcessing())
        repo.completeProcessing(savedUri)

        assertFalse(raw.exists())
        assertFalse(trimmed.exists())
        assertNull(repository().load())
        assertNull(CaptureTerminalResultStore(context).peekStoppedResult())
    }

    @Test
    fun `abort keeps files and allows a new begin`() {
        persistCapture()
        val repo = repository().apply { load() }
        assertTrue(repo.beginProcessing())

        repo.abortProcessing()

        assertTrue(raw.exists())
        assertNotNull(repository().load())
        assertTrue(repo.beginProcessing())
    }

    @Test
    fun `discard deletes files and clears store`() {
        persistCapture()
        val repo = repository().apply { load() }
        val trimmed = repo.saveEdit("A", 1000, 2000).trimmedFile!!

        repo.discard()

        assertFalse(raw.exists())
        assertFalse(trimmed.exists())
        assertNull(repository().load())
    }

    @Test
    fun `cleanOrphans keeps only the current capture files`() {
        persistCapture()
        val repo = repository().apply { load() }
        val trimmed = repo.saveEdit("A", 1000, 2000).trimmedFile!!
        val orphans = listOf(
            "system_audio_capture_0.wav", "Vecchia_tagliato.wav", "Vecchia_tagliato.wav.tmp"
        ).map { File(cacheDir, it).apply { writeText("x") } }
        val other = File(cacheDir, "altro.txt").apply { writeText("x") }

        repo.cleanOrphans()

        assertTrue(raw.exists())
        assertTrue(trimmed.exists())
        assertTrue(other.exists())
        assertTrue(orphans.none { it.exists() })
    }

    private fun segment(title: String, durationMs: Long, startMs: Long, position: Long?) =
        TrackSegment(title, "Gruppo", null, durationMs, null, startMs, position)

    /** A single track, started 40 s before the file. */
    private val songOnly = CaptureTrackTimeline("com.example.player", listOf(segment("Brano", 200_000, 0, 40_000)))

    private fun persistWithTimeline(timeline: CaptureTrackTimeline = songOnly) =
        CaptureTerminalResultStore(context).persist(CaptureTerminalKind.STOPPED, outputPath = raw.path, trackTimeline = timeline)

    @Test
    fun `capture with a recognized track proposes artist and title as name`() {
        persistWithTimeline()

        val capture = repository().load()!!

        assertEquals("Gruppo - Brano", capture.displayName)
        assertEquals(songOnly, capture.trackTimeline)
        assertNull(capture.trimmedFile)
    }

    @Test
    fun `capture without track info has no timeline`() {
        persistCapture()
        assertNull(repository().load()!!.trackTimeline)
    }

    @Test
    fun `corrupted timeline is ignored`() {
        persistWithTimeline()
        context.getSharedPreferences("capture_terminal_result", Context.MODE_PRIVATE)
            .edit().putString("track_timeline", "{non json").commit()

        val capture = repository().load()!!

        assertNull(capture.trackTimeline)
        assertEquals(RecordingFileNaming.defaultName(0L), capture.displayName)
    }

    @Test
    fun `initial advertisement is trimmed automatically`() {
        writePcm16StereoWav(raw, rampSamples(44_100 * 20)) // 20 s
        persistWithTimeline(
            CaptureTrackTimeline(
                "com.example.player",
                listOf(segment("Pubblicità", 6_000, 0, 0), segment("Brano", 200_000, 6_000, 0)),
            )
        )

        val capture = repository().load()!!

        assertEquals(6_000L, capture.trimStartMs)
        assertEquals(20_000L, capture.trimEndMs)
        assertEquals("Gruppo - Brano", capture.displayName)
        assertEquals(14_000L, WavFileReader.durationMs(WavFileReader.readInfo(capture.trimmedFile!!)))
    }

    @Test
    fun `complete processing writes pending lyrics with the offset at the trim start`() {
        persistWithTimeline()
        val repo = repository().apply { load() }
        repo.saveEdit("Gruppo - Brano", 1000, 2000)
        assertTrue(repo.beginProcessing())

        repo.completeProcessing(savedUri)

        val stored = lyricsStore.read(savedUri.toString())!!
        assertEquals(LyricsStatus.PENDING, stored.status)
        assertEquals(41_000L, stored.track.songOffsetMs)
        assertEquals(listOf(savedUri.toString()), scheduled)
    }

    @Test
    fun `complete processing uses the track kept after the trim`() {
        persistWithTimeline(
            CaptureTrackTimeline(
                "com.example.player",
                listOf(segment("Pubblicità", 18_000, 0, 0), segment("Brano", 200_000, 1_000, 0)),
            )
        )
        val repo = repository().apply { load() }
        repo.saveEdit("Gruppo - Brano", 1500, 3000)
        assertTrue(repo.beginProcessing())

        repo.completeProcessing(savedUri)

        val track = lyricsStore.read(savedUri.toString())!!.track
        assertEquals("Brano", track.title)
        assertEquals(200_000L, track.trackDurationMs)
        assertEquals(500L, track.songOffsetMs)
    }

    @Test
    fun `complete processing without track info writes nothing`() {
        persistCapture()
        val repo = repository().apply { load() }
        assertTrue(repo.beginProcessing())

        repo.completeProcessing(savedUri)

        assertNull(lyricsStore.read(savedUri.toString()))
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `recovery of a saved capture schedules the download`() {
        persistWithTimeline()
        val store = CaptureTerminalResultStore(context)
        val id = store.peekStoppedResult()!!.id
        val lease = store.acquireStoppedResult("vecchio-proprietario")!!
        assertTrue(store.markSaved(id, "vecchio-proprietario", savedUri.toString()))
        assertTrue(store.releaseStoppedResult(lease.result.id, "vecchio-proprietario"))

        assertNull(repository().load())

        assertEquals(LyricsStatus.PENDING, lyricsStore.read(savedUri.toString())!!.status)
        assertEquals(listOf(savedUri.toString()), scheduled)
    }

    @Test
    fun `recovery does not overwrite lyrics already downloaded`() {
        persistWithTimeline()
        val store = CaptureTerminalResultStore(context)
        val id = store.peekStoppedResult()!!.id
        store.acquireStoppedResult("vecchio-proprietario")!!
        store.markSaved(id, "vecchio-proprietario", savedUri.toString())
        store.releaseStoppedResult(id, "vecchio-proprietario")
        val done = com.example.vocalremover.lyrics.TrackLyrics(
            savedUri.toString(),
            CapturedTrackInfo("Brano", "Gruppo", null, 200_000, 40_000, "com.example.player", null),
            LyricsStatus.FOUND,
            "lrclib:1",
        )
        lyricsStore.write(done)

        repository().load()

        assertEquals(done, lyricsStore.read(savedUri.toString()))
    }
}
