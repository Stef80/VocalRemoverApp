package com.stef.vocalremover.connections.lrclib

import com.stef.vocalremover.connections.LyricsResult
import com.stef.vocalremover.connections.TrackQuery
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hostile-input tests for the LRCLIB client: the server (or anyone in between) and the
 * media session of other apps are untrusted. A failing test here documents an open issue.
 */
class LrclibSecurityTest {

    private companion object {
        /** Assumed upper bound for a legitimate LRCLIB response. */
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val ONE_DAY_MS = 24 * 60 * 60 * 1_000L
    }

    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val logs = mutableListOf<String>()
    private val query = TrackQuery("Brano Finto", "Gruppo Finto", "Album", 189_000)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        other.shutdown()
    }

    private fun provider() = LrclibLyricsProvider(
        client = OkHttpClient(),
        baseUrl = server.url("/"),
        userAgent = "VocalRemover/1.0 (contatto@example.com)",
        delay = {},
        log = { logs += it },
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun trackWithSynced(synced: String) =
        """{"id":1,"duration":189.0,"instrumental":false,"plainLyrics":null,"syncedLyrics":"$synced"}"""

    // --- Malformed LRC coming from the server ---------------------------------------

    @Test
    fun lrcConMinutiFuoriRangeNonFaFallireLaRicerca() = runTest {
        server.enqueue(json(trackWithSynced("[99999999999999999999:00.00]riga")))

        // Today a NumberFormatException escapes find() and the worker fails.
        val result = runCatching { provider().find(query) }

        assertTrue("find() threw: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun lrcConMinutiEnormiNonLancia() {
        val result = runCatching { LrcParser.parse("[99999999999999999999:00.00]riga") }
        assertTrue("parse() threw: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun lrcConOverflowNonProduceTempiNegativi() {
        val lines = LrcParser.parse("[9223372036854775807:00.00]riga\n[00:01.00]ok")
        assertTrue("negative start: $lines", lines.all { it.startMs >= 0 })
    }

    @Test(timeout = 3_000)
    fun lrcConMoltiTagSuUnaRigaNonEQuadratico() {
        // ~800 KB on one line: the substring-per-tag loop copies the rest each time.
        val line = "[00:01.00]".repeat(80_000) + "x"
        LrcParser.parse(line)
    }

    // --- Oversized responses --------------------------------------------------------

    @Test
    fun rispostaEnormeVieneRifiutata() = runTest {
        val huge = "a".repeat(MAX_BODY_BYTES * 10)
        server.enqueue(json("""{"id":1,"duration":189.0,"plainLyrics":"$huge"}"""))

        val result = provider().find(query)

        assertFalse("a ${huge.length}-byte body was accepted", result is LyricsResult.Found)
    }

    @Test
    fun searchConMoltissimiRisultatiVieneRifiutata() = runTest {
        server.enqueue(json("", 404))
        val entry = """{"id":1,"duration":10.0,"plainLyrics":"x"}"""
        val list = generateSequence { entry }.take(MAX_BODY_BYTES / entry.length * 5).joinToString(",", "[", "]")
        server.enqueue(json(list))

        val result = provider().find(query.copy(durationMs = null))

        assertFalse("a ${list.length}-byte search body was accepted", result is LyricsResult.Found)
    }

    // --- Redirects ------------------------------------------------------------------

    @Test
    fun redirectVersoAltroHostNonVieneSeguito() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/get")))
        other.enqueue(json("""{"id":666,"duration":189.0,"plainLyrics":"testo iniettato"}"""))

        val result = provider().find(query)

        assertEquals("request (and User-Agent contact) leaked to another host", 0, other.requestCount)
        assertFalse("lyrics from another host accepted: $result", result is LyricsResult.Found)
    }

    // --- Retry-After ------------------------------------------------------------------

    @Test
    fun retryAfterEnormeNonVaInOverflow() = runTest {
        server.enqueue(json("{}", 429).setHeader("Retry-After", Long.MAX_VALUE.toString()))

        val after = (provider().find(query) as LyricsResult.RetryLater).afterMs

        assertTrue("Retry-After became $after ms", after == null || after >= 0)
    }

    @Test
    fun retryAfterHaUnLimiteMassimo() = runTest {
        // Ten years: the server must not be able to park a track forever.
        server.enqueue(json("{}", 429).setHeader("Retry-After", "315360000"))

        val after = (provider().find(query) as LyricsResult.RetryLater).afterMs

        assertTrue("Retry-After of $after ms accepted", after == null || after <= ONE_DAY_MS)
    }

    // --- Request building and logs (regressions: these pass today) ---------------------

    @Test
    fun crlfNelTitoloNonIniettaHeaderNeParametri() = runTest {
        val title = "Brano\r\nX-Injected: 1&artist_name=altro"
        server.enqueue(json("""{"id":1,"duration":189.0,"plainLyrics":"x"}"""))

        provider().find(query.copy(title = title))

        val request = server.takeRequest()
        assertEquals(null, request.getHeader("X-Injected"))
        assertEquals(title, request.requestUrl!!.queryParameter("track_name"))
        assertEquals(listOf("Gruppo Finto"), request.requestUrl!!.queryParameterValues("artist_name"))
    }

    @Test
    fun logNonContengonoTitoloArtistaNeTesto() = runTest {
        server.enqueue(json("", 404))
        server.enqueue(json("""[{"id":1,"duration":189.0,"plainLyrics":"testo segreto"}]"""))

        provider().find(query)

        val all = logs.joinToString("\n")
        assertFalse(all, "Brano" in all || "Gruppo" in all || "segreto" in all)
    }

    @Test
    fun motivoDiErroreNonContieneIlCorpoDellaRisposta() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<script>dati-server</script>"))

        val result = provider().find(query) as LyricsResult.Failed

        assertFalse(result.reason, "dati-server" in result.reason)
    }
}
