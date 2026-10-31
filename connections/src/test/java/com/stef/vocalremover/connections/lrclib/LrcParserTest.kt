package com.stef.vocalremover.connections.lrclib

import com.stef.vocalremover.connections.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun righeConCentesimiEMillesimi() = assertEquals(
        listOf(LyricLine(12_340, "prima riga inventata"), LyricLine(65_007, "seconda riga")),
        LrcParser.parse("[00:12.34] prima riga inventata\n[01:05.007]seconda riga"),
    )

    @Test
    fun piuTagSullaStessaRigaGeneranoPiuRigheOrdinate() = assertEquals(
        listOf(LyricLine(1_000, "ritornello"), LyricLine(2_000, "strofa"), LyricLine(30_000, "ritornello")),
        LrcParser.parse("[00:01.00][00:30.00]ritornello\n[00:02.00]strofa"),
    )

    @Test
    fun tagDiMetadatiIgnorati() = assertEquals(
        listOf(LyricLine(500, "riga")),
        LrcParser.parse("[ar:Artista]\n[ti:Titolo]\n[offset:+100]\n[00:00.50]riga"),
    )

    @Test
    fun rigaVuotaConservataComePausa() = assertEquals(
        listOf(LyricLine(1_000, "a"), LyricLine(4_000, "")),
        LrcParser.parse("[00:01.00]a\n[00:04.00]\n\nrumore senza tag"),
    )

    @Test
    fun fineRigaWindows() = assertEquals(
        listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b")),
        LrcParser.parse("[00:01.00]a\r\n[00:02.00]b\r\n"),
    )
}
