package com.stef.vocalremover.connections.lrclib

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun remaster() = assertEquals(
        "Canzone Finta (Take Me Out)",
        TitleCleaner.clean("Canzone Finta (Take Me Out) - 2024 Remaster"),
    )

    @Test
    fun remasteredConAnnoDopo() = assertEquals("Brano", TitleCleaner.clean("Brano - Remastered 2011"))

    @Test
    fun videoUfficiale() = assertEquals("Brano", TitleCleaner.clean("Brano (Official Video)"))

    @Test
    fun parentesiQuadre() = assertEquals("Brano", TitleCleaner.clean("Brano [Official Audio]"))

    @Test
    fun featuring() = assertEquals("Brano", TitleCleaner.clean("Brano (feat. Qualcuno)"))

    @Test
    fun featSenzaParentesi() = assertEquals("Brano", TitleCleaner.clean("Brano feat. Qualcuno"))

    @Test
    fun parentesiNormaliConservate() = assertEquals("Brano (Parte 2)", TitleCleaner.clean("Brano (Parte 2)"))

    @Test
    fun titoloPulitoInvariato() = assertEquals("Brano", TitleCleaner.clean("Brano"))

    @Test
    fun prefissoArtistaEApiciDaYoutube() = assertEquals(
        "Brano Finto",
        TitleCleaner.clean("Gruppo Finto - 'Brano Finto' (Music Video)", artist = "Gruppo Finto"),
    )

    @Test
    fun prefissoArtistaConTrattinoLungoEVirgolette() =
        assertEquals("Brano", TitleCleaner.clean("gruppo – \"Brano\"", artist = "Gruppo"))

    @Test
    fun trattinoSenzaArtistaConservato() =
        assertEquals("Prima - Seconda", TitleCleaner.clean("Prima - Seconda", artist = "Gruppo"))
}
