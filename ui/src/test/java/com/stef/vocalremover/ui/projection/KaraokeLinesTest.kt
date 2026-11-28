package com.stef.vocalremover.ui.projection

import com.stef.vocalremover.ui.player.PlayerLyrics
import org.junit.Assert.assertEquals
import org.junit.Test

class KaraokeLinesTest {

    private val synced = PlayerLyrics.Synced(
        lines = listOf("a", "b", "c", "d", "e", "f"),
        startsMs = listOf(10_000, 20_000, 30_000, 40_000, 50_000, 60_000),
        songOffsetMs = 0,
    )

    @Test fun `before the first line none is active and the first ones are next`() =
        assertEquals(KaraokeWindow(emptyList(), null, listOf("a", "b")), KaraokeLines.window(synced, 5_000))

    @Test fun `middle line with two before and two after`() =
        assertEquals(KaraokeWindow(listOf("b", "c"), "d", listOf("e", "f")), KaraokeLines.window(synced, 41_000))

    @Test fun `first line has no previous ones`() =
        assertEquals(KaraokeWindow(emptyList(), "a", listOf("b", "c")), KaraokeLines.window(synced, 10_000))

    @Test fun `last line has no next ones`() =
        assertEquals(KaraokeWindow(listOf("d", "e"), "f", emptyList()), KaraokeLines.window(synced, 99_000))

    @Test fun `songOffset shifts the active line`() =
        assertEquals("a", KaraokeLines.window(synced.copy(songOffsetMs = 5_000), 5_000).active)

    @Test fun `plain text scroll is proportional and clamped`() {
        assertEquals(0f, KaraokeLines.plainScrollFraction(0, 200_000))
        assertEquals(0.5f, KaraokeLines.plainScrollFraction(100_000, 200_000))
        assertEquals(1f, KaraokeLines.plainScrollFraction(300_000, 200_000))
        assertEquals(0f, KaraokeLines.plainScrollFraction(10_000, 0))
    }
}
