package com.example.vocalremover.lyrics

import com.stef.vocalremover.connections.LyricsResult

sealed interface FetchDecision {
    data class Save(val lyrics: TrackLyrics) : FetchDecision
    /** New attempt with WorkManager's backoff. */
    data object Retry : FetchDecision
    /** The service asks to wait longer than the backoff: new work with a delay. */
    data class Reschedule(val delayMs: Long, val attempt: Int) : FetchDecision
    /** Track deleted or lyrics already downloaded: nothing to do. */
    data object Skip : FetchDecision
}

/** Decides what to do with the outcome of a lookup. Pure logic, the worker applies it. */
object LyricsFetchPolicy {

    const val MAX_ATTEMPTS = 5
    const val RETRY_THRESHOLD_MS = 30_000L
    /** LRCLIB asks to add up to 30% of random wait to Retry-After. */
    private const val JITTER = 0.3

    fun decide(stored: TrackLyrics?, result: LyricsResult, attempt: Int, now: Long, random: () -> Double): FetchDecision {
        if (stored == null || stored.status != LyricsStatus.PENDING) return FetchDecision.Skip
        val done = stored.copy(fetchedAt = now)
        return when (result) {
            is LyricsResult.Found -> FetchDecision.Save(
                done.copy(
                    status = LyricsStatus.FOUND,
                    sourceId = result.sourceId,
                    lines = result.lyrics.lines.map { TimedLine(it.startMs, it.text) },
                    plainText = result.lyrics.plainText,
                )
            )
            LyricsResult.NotFound -> FetchDecision.Save(done.copy(status = LyricsStatus.NOT_FOUND))
            LyricsResult.Instrumental -> FetchDecision.Save(done.copy(status = LyricsStatus.INSTRUMENTAL))
            is LyricsResult.Failed -> FetchDecision.Save(done.copy(status = LyricsStatus.FAILED))
            is LyricsResult.RetryLater -> {
                val afterMs = result.afterMs
                when {
                    attempt >= MAX_ATTEMPTS -> FetchDecision.Save(done.copy(status = LyricsStatus.FAILED))
                    afterMs == null || afterMs <= RETRY_THRESHOLD_MS -> FetchDecision.Retry
                    else -> FetchDecision.Reschedule(
                        delayMs = afterMs + (afterMs * random() * JITTER).toLong(),
                        attempt = attempt + 1,
                    )
                }
            }
        }
    }
}
