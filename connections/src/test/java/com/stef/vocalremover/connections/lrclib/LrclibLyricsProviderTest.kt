package com.stef.vocalremover.connections.lrclib

import com.stef.vocalremover.connections.LyricLine
import com.stef.vocalremover.connections.LyricsResult
import com.stef.vocalremover.connections.TimedLyrics
import com.stef.vocalremover.connections.TrackQuery
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** All lyrics here are made up. */
class LrclibLyricsProviderTest {

    private lateinit var server: MockWebServer
    private val waits = mutableListOf<Long>()
    private val query = TrackQuery("Brano Finto - 2024 Remaster", "Gruppo Finto", "Album", 189_000)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = LrclibLyricsProvider(
        client = OkHttpClient(),
        baseUrl = server.url("/"),
        userAgent = "VocalRemover/1.0 (test)",
        delay = { waits += it },
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun notFound() = json("""{"code":404,"name":"TrackNotFound","message":"x"}""", 404)

    private fun track(id: Long, duration: Double = 189.0, synced: String? = "[00:01.00]riga inventata", plain: String? = "riga inventata", instrumental: Boolean = false) =
        """{"id":$id,"trackName":"Brano Finto","artistName":"Gruppo Finto","albumName":"Album","duration":$duration,
           "instrumental":$instrumental,"plainLyrics":${plain?.let { "\"$it\"" } ?: "null"},
           "syncedLyrics":${synced?.let { "\"$it\"" } ?: "null"},"extra":1}"""

    @Test
    fun getTrovatoRestituisceRigheSincronizzate() = runTest {
        server.enqueue(json(track(42)))

        val result = provider().find(query)

        assertEquals(
            LyricsResult.Found(TimedLyrics(listOf(LyricLine(1_000, "riga inventata")), "riga inventata"), "lrclib:42"),
            result,
        )
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/get", url.encodedPath)
        assertEquals("Brano Finto - 2024 Remaster", url.queryParameter("track_name"))
        assertEquals("Gruppo Finto", url.queryParameter("artist_name"))
        assertEquals("Album", url.queryParameter("album_name"))
        assertEquals("189", url.queryParameter("duration"))
    }

    @Test
    fun userAgentInviato() = runTest {
        server.enqueue(json(track(1)))
        provider().find(query)
        assertEquals("VocalRemover/1.0 (test)", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun notFoundPoiGetRipulitoTrovato() = runTest {
        server.enqueue(notFound())
        server.enqueue(json(track(7)))

        val result = provider().find(query)

        assertEquals("lrclib:7", (result as LyricsResult.Found).sourceId)
        server.takeRequest()
        assertEquals("Brano Finto", server.takeRequest().requestUrl!!.queryParameter("track_name"))
        assertEquals(listOf(300L), waits)
    }

    @Test
    fun notFoundPoiSearchFiltraDurata() = runTest {
        server.enqueue(notFound())
        server.enqueue(notFound())
        server.enqueue(json("[${track(1, duration = 150.0)},${track(2, duration = 190.5)}]"))

        val result = provider().find(query)

        assertEquals("lrclib:2", (result as LyricsResult.Found).sourceId)
        server.takeRequest(); server.takeRequest()
        val search = server.takeRequest().requestUrl!!
        assertEquals("/api/search", search.encodedPath)
        assertEquals("Brano Finto", search.queryParameter("track_name"))
        assertEquals("Gruppo Finto", search.queryParameter("artist_name"))
        assertEquals(listOf(300L, 300L), waits)
    }

    @Test
    fun titoloGiaPulitoSaltaIlSecondoGet() = runTest {
        server.enqueue(notFound())
        server.enqueue(json("[]"))

        val result = provider().find(query.copy(title = "Brano Finto"))

        assertEquals(LyricsResult.NotFound, result)
        server.takeRequest()
        assertEquals("/api/search", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun searchSenzaDurataCompatibile() = runTest {
        server.enqueue(notFound())
        server.enqueue(notFound())
        server.enqueue(json("[${track(1, duration = 150.0)}]"))

        assertEquals(LyricsResult.NotFound, provider().find(query))
    }

    @Test
    fun searchSenzaDurataNellaQueryPrendeIlPrimo() = runTest {
        server.enqueue(notFound())
        server.enqueue(notFound())
        server.enqueue(json("[${track(5, duration = 150.0)},${track(6)}]"))

        val result = provider().find(query.copy(durationMs = null))

        assertEquals("lrclib:5", (result as LyricsResult.Found).sourceId)
    }

    @Test
    fun strumentale() = runTest {
        server.enqueue(json(track(3, synced = null, plain = null, instrumental = true)))
        assertEquals(LyricsResult.Instrumental, provider().find(query))
    }

    @Test
    fun soloTestoSemplice() = runTest {
        server.enqueue(json(track(4, synced = null, plain = "testo inventato")))
        assertEquals(
            LyricsResult.Found(TimedLyrics(emptyList(), "testo inventato"), "lrclib:4"),
            provider().find(query),
        )
    }

    @Test
    fun tooManyRequestsConRetryAfter() = runTest {
        server.enqueue(json("""{"message":"slow down"}""", 429).setHeader("Retry-After", "120"))

        assertEquals(LyricsResult.RetryLater(120_000), provider().find(query))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serviceUnavailableInTestoSemplice() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Content-Type", "text/plain").setBody("Service Unavailable"),
        )
        assertEquals(LyricsResult.RetryLater(null), provider().find(query))
    }

    @Test
    fun okNonJsonEFailed() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"))
        assertTrue(provider().find(query) is LyricsResult.Failed)
    }

    @Test
    fun jsonMalformatoEFailed() = runTest {
        server.enqueue(json("{non json"))
        assertTrue(provider().find(query) is LyricsResult.Failed)
    }

    @Test
    fun badRequestEFailed() = runTest {
        server.enqueue(json("""{"message":"bad"}""", 400))
        assertTrue(provider().find(query) is LyricsResult.Failed)
    }

    @Test
    fun connessioneInterrotta() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(LyricsResult.RetryLater(null), provider().find(query))
    }

    @Test
    fun caratteriSpecialiCodificati() = runTest {
        val title = "AC/DC & Sigur Rós ? #" + "x".repeat(300)
        server.enqueue(json(track(9)))

        provider().find(query.copy(title = title))

        assertEquals(title, server.takeRequest().requestUrl!!.queryParameter("track_name"))
    }

    @Test
    fun durataAssenteNonInviata() = runTest {
        server.enqueue(json(track(9)))
        provider().find(query.copy(durationMs = null, album = null))
        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("duration"))
        assertNull(url.queryParameter("album_name"))
    }

    @Test
    fun titoloYoutubeConArtistaRipulitoNelSecondoGet() = runTest {
        server.enqueue(notFound())
        server.enqueue(json(track(8)))

        provider().find(query.copy(title = "Gruppo Finto - 'Brano Finto' (Music Video)"))

        server.takeRequest()
        assertEquals("Brano Finto", server.takeRequest().requestUrl!!.queryParameter("track_name"))
    }
}
