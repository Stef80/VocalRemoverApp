package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class RecordingFileNamingTest {

    @Test
    fun `instrumentalNameForTitle keeps dots of the title`() =
        assertEquals("Mr. Brightside_strumentale", RecordingFileNaming.instrumentalNameForTitle("Mr. Brightside"))

    @Test
    fun `trimmedName sanitizes and adds suffix`() =
        assertEquals("A_B_tagliato", RecordingFileNaming.trimmedName("A/B"))

    @Test
    fun `sanitize leaves a normal name untouched`() {
        assertEquals("My Song", RecordingFileNaming.sanitize("My Song"))
    }

    @Test
    fun `sanitize trims surrounding whitespace`() {
        assertEquals("Trimmed", RecordingFileNaming.sanitize("   Trimmed   "))
    }

    @Test
    fun `sanitize replaces path-invalid characters with underscore`() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i",
            RecordingFileNaming.sanitize("a/b:c*d?e\"f<g>h|i")
        )
    }

    @Test
    fun `sanitize falls back to generic name when input is empty`() {
        assertEquals("Registrazione", RecordingFileNaming.sanitize(""))
    }

    @Test
    fun `sanitize falls back to generic name when input is only whitespace or invalid chars`() {
        assertEquals("Registrazione", RecordingFileNaming.sanitize("   "))
        assertEquals("Registrazione", RecordingFileNaming.sanitize("///"))
    }

    @Test
    fun `defaultName formats a fixed timestamp as expected`() {
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals(
                "Registrazione_2026-09-12_1130",
                RecordingFileNaming.defaultName(1_789_212_600_000L)
            )
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun `rawCaptureName appends capture suffix before extension`() {
        assertEquals(
            "My Song_capture",
            RecordingFileNaming.rawCaptureName("My Song")
        )
    }

    @Test
    fun `instrumentalName strips extension and appends instrumental suffix`() {
        assertEquals(
            "My Song_strumentale",
            RecordingFileNaming.instrumentalName("My Song.mp3")
        )
    }

    @Test
    fun `instrumentalName keeps inner dots and sanitizes invalid characters`() {
        assertEquals(
            "Artist - Song v1.2_A_B_strumentale",
            RecordingFileNaming.instrumentalName("Artist - Song v1.2_A:B.flac")
        )
    }

    @Test
    fun `instrumentalName falls back to generic name when source name is missing`() {
        assertEquals("Registrazione_strumentale", RecordingFileNaming.instrumentalName(null))
        assertEquals("Registrazione_strumentale", RecordingFileNaming.instrumentalName(".mp3"))
    }

    @Test
    fun `titleFromLibraryName strips extension and suffix`() =
        assertEquals("Mr. Brightside", RecordingFileNaming.titleFromLibraryName("Mr. Brightside_strumentale.wav"))

    @Test
    fun `titleFromLibraryName ignores extension case`() =
        assertEquals("demo", RecordingFileNaming.titleFromLibraryName("demo.WAV"))

    @Test
    fun `titleFromLibraryName removes only one trailing suffix`() =
        assertEquals("x_strumentale", RecordingFileNaming.titleFromLibraryName("x_strumentale_strumentale.wav"))

    @Test
    fun `titleFromLibraryName keeps the suffix when nothing else is left`() =
        assertEquals("_strumentale", RecordingFileNaming.titleFromLibraryName("_strumentale.wav"))

    @Test
    fun `titleFromLibraryName leaves names without extension untouched`() =
        assertEquals("Canzone", RecordingFileNaming.titleFromLibraryName("Canzone"))
}
