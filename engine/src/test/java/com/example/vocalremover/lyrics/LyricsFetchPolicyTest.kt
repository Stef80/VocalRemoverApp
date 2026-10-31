package com.example.vocalremover.lyrics

import com.example.vocalremover.capture.CapturedTrackInfo
import com.stef.vocalremover.connections.LyricLine
import com.stef.vocalremover.connections.LyricsResult
import com.stef.vocalremover.connections.TimedLyrics
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsFetchPolicyTest {

    private val pending = TrackLyrics(
        savedUri = "content://media/external/audio/media/7",
        track = CapturedTrackInfo("Brano", "Gruppo", null, 200_000, 45_000, "com.example.player", null),
        status = LyricsStatus.PENDING,
    )

    private fun decide(result: LyricsResult, attempt: Int = 1, stored: TrackLyrics? = pending) =
        LyricsFetchPolicy.decide(stored, result, attempt, now = 999L, random = { 0.5 })

    @Test
    fun trovatoSalvaRigheEFonte() = assertEquals(
        FetchDecision.Save(
            pending.copy(
                status = LyricsStatus.FOUND,
                sourceId = "lrclib:1",
                lines = listOf(TimedLine(1_000, "riga inventata")),
                plainText = "riga inventata",
                fetchedAt = 999L,
            )
        ),
        decide(LyricsResult.Found(TimedLyrics(listOf(LyricLine(1_000, "riga inventata")), "riga inventata"), "lrclib:1")),
    )

    @Test
    fun nonTrovato() = assertEquals(
        FetchDecision.Save(pending.copy(status = LyricsStatus.NOT_FOUND, fetchedAt = 999L)),
        decide(LyricsResult.NotFound),
    )

    @Test
    fun strumentale() = assertEquals(
        FetchDecision.Save(pending.copy(status = LyricsStatus.INSTRUMENTAL, fetchedAt = 999L)),
        decide(LyricsResult.Instrumental),
    )

    @Test
    fun erroreDefinitivo() = assertEquals(
        FetchDecision.Save(pending.copy(status = LyricsStatus.FAILED, fetchedAt = 999L)),
        decide(LyricsResult.Failed("HTTP 400")),
    )

    @Test
    fun riprovaSenzaAttesaIndicata() = assertEquals(FetchDecision.Retry, decide(LyricsResult.RetryLater(null)))

    @Test
    fun riprovaConAttesaBreve() = assertEquals(FetchDecision.Retry, decide(LyricsResult.RetryLater(20_000)))

    @Test
    fun attesaLungaRiaccodaConMargineCasuale() =
        assertEquals(FetchDecision.Reschedule(138_000, 2), decide(LyricsResult.RetryLater(120_000)))

    @Test
    fun ultimoTentativoFallito() = assertEquals(
        FetchDecision.Save(pending.copy(status = LyricsStatus.FAILED, fetchedAt = 999L)),
        decide(LyricsResult.RetryLater(null), attempt = 5),
    )

    @Test
    fun branoCancellatoNelFrattempo() = assertEquals(FetchDecision.Skip, decide(LyricsResult.NotFound, stored = null))

    @Test
    fun giaCompletatoNonSiSovrascrive() = assertEquals(
        FetchDecision.Skip,
        decide(LyricsResult.NotFound, stored = pending.copy(status = LyricsStatus.FOUND)),
    )
}
