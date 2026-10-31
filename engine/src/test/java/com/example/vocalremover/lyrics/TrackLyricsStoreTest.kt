package com.example.vocalremover.lyrics

import com.example.vocalremover.capture.CapturedTrackInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Robolectric only for android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackLyricsStoreTest {

    private val dir = File("build/test-lyrics/${System.nanoTime()}")
    private val store = TrackLyricsStore(dir)
    private val uri = "content://media/external/audio/media/7"
    private val lyrics = TrackLyrics(
        savedUri = uri,
        track = CapturedTrackInfo("Brano", "Gruppo", null, 200_000, 45_000, "com.example.player", null),
        status = LyricsStatus.FOUND,
        sourceId = "lrclib:1",
        lines = listOf(TimedLine(1_000, "riga inventata")),
        plainText = "riga inventata",
        fetchedAt = 123L,
    )

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun scritturaERilettura() {
        store.write(lyrics)
        assertEquals(lyrics, store.read(uri))
        assertTrue(store.exists(uri))
    }

    @Test
    fun chiaveStabileDiQuarantaCaratteri() {
        assertEquals(store.key(uri), store.key(uri))
        assertEquals(40, store.key(uri).length)
    }

    @Test
    fun fileCorrottoComeAssente() {
        dir.mkdirs()
        File(dir, "${store.key(uri)}.json").writeText("{non json")
        assertNull(store.read(uri))
    }

    @Test
    fun cancellazione() {
        store.write(lyrics)
        store.delete(uri)
        assertNull(store.read(uri))
        assertFalse(store.exists(uri))
    }

    @Test
    fun uriMaiScritto() = assertNull(store.read("content://altro/1"))
}
