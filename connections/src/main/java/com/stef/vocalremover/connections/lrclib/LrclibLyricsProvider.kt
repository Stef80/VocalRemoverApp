package com.stef.vocalremover.connections.lrclib

import com.stef.vocalremover.connections.LyricsProvider
import com.stef.vocalremover.connections.LyricsResult
import com.stef.vocalremover.connections.TimedLyrics
import com.stef.vocalremover.connections.TrackQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Looks up lyrics on LRCLIB (https://lrclib.net/docs) in three steps, each only if the
 * previous one finds nothing: get with the raw data, get with the cleaned title, search
 * filtered by duration. Requests run in sequence with a pause, as the service asks.
 */
internal class LrclibLyricsProvider(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val userAgent: String,
    private val pauseMs: Long = 300,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val log: (String) -> Unit = {},
) : LyricsProvider {

    private companion object {
        const val DURATION_TOLERANCE_MS = 2_000L
        val json = Json { ignoreUnknownKeys = true }
    }

    /** Outcome of a single request: either a final result, or "not found, keep going". */
    private sealed interface Step {
        data class Done(val result: LyricsResult) : Step
        data class Body(val text: String) : Step
        data object Missing : Step
    }

    override suspend fun find(query: TrackQuery): LyricsResult {
        get(query, query.title).let { if (it !is Step.Missing) return finishGet(it) }

        val cleanTitle = TitleCleaner.clean(query.title, query.artist)
        if (cleanTitle.isNotEmpty() && cleanTitle != query.title) {
            delay(pauseMs)
            get(query, cleanTitle).let { if (it !is Step.Missing) return finishGet(it) }
        }

        delay(pauseMs)
        return when (val step = search(query, cleanTitle.ifEmpty { query.title })) {
            is Step.Done -> step.result
            Step.Missing -> LyricsResult.NotFound
            is Step.Body -> when (val decoded = decode { json.decodeFromString<List<LrclibTrack>>(step.text) }) {
                is Decoded.Ok -> decoded.value
                    .firstOrNull { matchesDuration(it, query.durationMs) }
                    ?.let(::toResult)
                    ?: LyricsResult.NotFound
                is Decoded.Error -> decoded.result
            }
        }
    }

    private suspend fun get(query: TrackQuery, title: String): Step {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", query.artist)
            .apply {
                query.album?.let { addQueryParameter("album_name", it) }
                durationSeconds(query.durationMs)?.let { addQueryParameter("duration", it.toString()) }
            }
            .build()
        return request(url)
    }

    private suspend fun search(query: TrackQuery, title: String): Step {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/search")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", query.artist)
            .build()
        return request(url)
    }

    private fun finishGet(step: Step): LyricsResult = when (step) {
        is Step.Done -> step.result
        is Step.Body -> when (val decoded = decode { json.decodeFromString<LrclibTrack>(step.text) }) {
            is Decoded.Ok -> toResult(decoded.value)
            is Decoded.Error -> decoded.result
        }
        Step.Missing -> LyricsResult.NotFound
    }

    private suspend fun request(url: HttpUrl): Step = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        try {
            client.newCall(request).execute().use { response ->
                log("LRCLIB ${url.encodedPath} → HTTP ${response.code}")
                val isJson = response.header("Content-Type")?.contains("application/json") == true
                when {
                    response.code == 404 -> Step.Missing
                    response.code == 429 || response.code >= 500 ->
                        Step.Done(LyricsResult.RetryLater(retryAfterMs(response.header("Retry-After"))))
                    !response.isSuccessful -> Step.Done(LyricsResult.Failed("HTTP ${response.code}"))
                    // Some errors come with 200 and plain text: JSON is parsed only when declared.
                    !isJson -> Step.Done(LyricsResult.Failed("Risposta non JSON"))
                    else -> Step.Body(response.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            log("LRCLIB ${url.encodedPath} → network error: ${e.javaClass.simpleName}")
            Step.Done(LyricsResult.RetryLater(null))
        }
    }

    private sealed interface Decoded<out T> {
        data class Ok<T>(val value: T) : Decoded<T>
        data class Error(val result: LyricsResult) : Decoded<Nothing>
    }

    private inline fun <T> decode(block: () -> T): Decoded<T> = try {
        Decoded.Ok(block())
    } catch (e: SerializationException) {
        Decoded.Error(LyricsResult.Failed("JSON non valido"))
    } catch (e: IllegalArgumentException) {
        Decoded.Error(LyricsResult.Failed("JSON non valido"))
    }

    private fun toResult(track: LrclibTrack): LyricsResult {
        if (track.instrumental) return LyricsResult.Instrumental
        val lines = track.syncedLyrics?.let(LrcParser::parse).orEmpty()
        val plain = track.plainLyrics?.takeIf { it.isNotBlank() }
        if (lines.isEmpty() && plain == null) return LyricsResult.NotFound
        return LyricsResult.Found(TimedLyrics(lines, plain), "lrclib:${track.id}")
    }

    private fun matchesDuration(track: LrclibTrack, durationMs: Long?): Boolean {
        if (durationMs == null) return true
        val seconds = track.duration ?: return false
        return abs((seconds * 1_000).roundToLong() - durationMs) <= DURATION_TOLERANCE_MS
    }

    /** LRCLIB accepts whole durations between 1 and 3600 seconds. */
    private fun durationSeconds(durationMs: Long?): Long? =
        durationMs?.let { (it / 1_000.0).roundToLong() }?.takeIf { it in 1..3_600 }

    /** Only the whole-seconds format; the HTTP-date form is ignored. */
    private fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000)
}
