package com.stef.vocalremover.connections.lrclib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The title comes from the media session of any installed app, so it is untrusted input:
 * the regexes must stay fast on long, adversarial strings (ReDoS).
 */
class TitleCleanerSecurityTest {

    @Test(timeout = 2_000)
    fun parentesiApertaSenzaChiusura() {
        TitleCleaner.clean("(" + "video ".repeat(5_000))
    }

    @Test(timeout = 2_000)
    fun moltieParentesiAperte() {
        TitleCleaner.clean("(".repeat(10_000) + "official")
    }

    @Test(timeout = 2_000)
    fun moltiSpaziPrimaDelTrattino() {
        TitleCleaner.clean("a" + " ".repeat(20_000) + "-" + " ".repeat(20_000) + "x")
    }

    @Test(timeout = 2_000)
    fun moltiFeatSenzaSpazioFinale() {
        TitleCleaner.clean(" feat.".repeat(10_000))
    }

    @Test(timeout = 2_000)
    fun artistaLungoConCaratteriRegex() {
        val artist = ".*(a+)+$[".repeat(2_000)
        TitleCleaner.clean("$artist - Brano", artist)
    }

    @Test
    fun artistaConMetacaratteriTrattatoComeTesto() {
        // Regression: the artist is escaped, so ".*" must not swallow the title.
        assertEquals("Brano", TitleCleaner.clean("A.*B - Brano", "A.*B"))
        assertEquals("X - Brano", TitleCleaner.clean("X - Brano", ".*"))
    }
}
