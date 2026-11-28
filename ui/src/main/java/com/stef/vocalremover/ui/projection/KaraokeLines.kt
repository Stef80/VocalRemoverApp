package com.stef.vocalremover.ui.projection

import com.stef.vocalremover.ui.player.PlayerLyrics

/** Lines shown on the TV: [active] is `null` before the first line starts. */
data class KaraokeWindow(val previous: List<String>, val active: String?, val next: List<String>)

object KaraokeLines {
    const val BEFORE = 2
    const val AFTER = 2

    fun window(lyrics: PlayerLyrics.Synced, positionMs: Long): KaraokeWindow {
        val active = lyrics.activeLine(positionMs)
        return KaraokeWindow(
            previous = lyrics.lines.subList((active - BEFORE).coerceAtLeast(0), active.coerceAtLeast(0)),
            active = lyrics.lines.getOrNull(active),
            next = lyrics.lines.drop(active + 1).take(AFTER),
        )
    }

    /** Where the plain text should be scrolled, 0 (top) to 1 (bottom), proportional to the position in the track. */
    fun plainScrollFraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
