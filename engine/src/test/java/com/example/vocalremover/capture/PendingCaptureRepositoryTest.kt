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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingCaptureRepositoryTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var cacheDir: File
    private lateinit var raw: File
    private val savedUri: Uri = Uri.parse("content://media/external/audio/media/7")

    @Before
    fun setUp() {
        // Lo store rifiuta pid 0, che è il default di Robolectric.
        ShadowProcess.setPid(1234)
        cacheDir = File("build/test-pending-capture/${System.nanoTime()}").apply { mkdirs() }
        raw = File(cacheDir, "system_audio_capture_1.wav")
        writePcm16StereoWav(raw, rampSamples(44_100 * 3)) // 3000 ms
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun repository(clock: Long = 0L) =
        DefaultPendingCaptureRepository(context, cacheDir = cacheDir, clock = { clock })

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
}
