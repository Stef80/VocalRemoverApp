package com.example.vocalremover.library

import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCursorReaderTest {

    private val collection: Uri = Uri.parse("content://media/external/audio/media")

    @Test
    fun `converts rows keeping cursor order`() {
        val cursor = cursorOf(
            arrayOf(7L, "B_strumentale.wav", 180_000L, 1L),
            arrayOf(3L, "A_strumentale.wav", 120_000L, 1L),
        )
        val tracks = LibraryCursorReader.read(cursor, collection)
        assertEquals(listOf("B", "A"), tracks.map { it.title })
        assertEquals(Uri.parse("content://media/external/audio/media/7"), tracks[0].uri)
        assertEquals(180_000L, tracks[0].durationMs)
    }

    @Test
    fun `null duration falls back to size`() {
        val tracks = LibraryCursorReader.read(cursorOf(arrayOf(1L, "C.wav", null, 44L + 176_400L)), collection)
        assertEquals(1_000L, tracks.single().durationMs)
    }

    @Test
    fun `null duration and size give unknown duration`() {
        val tracks = LibraryCursorReader.read(cursorOf(arrayOf(1L, "D.wav", null, null)), collection)
        assertEquals(0L, tracks.single().durationMs)
    }

    @Test
    fun `empty cursor gives empty list`() =
        assertEquals(emptyList<LibraryTrack>(), LibraryCursorReader.read(cursorOf(), collection))

    private fun cursorOf(vararg rows: Array<Any?>) = MatrixCursor(
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
    ).apply { rows.forEach(::addRow) }
}
