package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class RecordingFileNamingTest {

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
}
