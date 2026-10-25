package com.example.vocalremover.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDurationTest {

    @Test
    fun `uses the MediaStore duration when positive`() =
        assertEquals(225_000L, LibraryDuration.resolve(225_000L, 10L))

    @Test
    fun `falls back to the size when duration is missing`() =
        assertEquals(2_000L, LibraryDuration.resolve(null, 44L + 352_800L))

    @Test
    fun `falls back to the size when duration is zero`() =
        assertEquals(1_000L, LibraryDuration.resolve(0L, 44L + 176_400L))

    @Test
    fun `unknown when size is header only`() =
        assertEquals(0L, LibraryDuration.resolve(null, 44L))

    @Test
    fun `unknown when both are missing`() =
        assertEquals(0L, LibraryDuration.resolve(null, null))
}
