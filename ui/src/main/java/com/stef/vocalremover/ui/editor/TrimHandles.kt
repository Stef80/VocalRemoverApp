package com.stef.vocalremover.ui.editor

import kotlin.math.roundToLong

enum class TrimHandle { START, END }

/** Regole delle maniglie di taglio, separate dalla UI per poterle testare. Tempi in ms. */
internal object TrimHandles {
    const val STEP_MS = 10L
    const val MIN_GAP_MS = 1000L
    const val END_PREVIEW_MS = 3000L
    const val ACCESSIBILITY_STEP_MS = 1000L

    /** Arrotonda a STEP_MS, poi limita a [0, endMs - MIN_GAP_MS] (mai sotto 0). */
    fun moveStart(requestedMs: Long, endMs: Long): Long =
        snap(requestedMs).coerceIn(0L, maxOf(0L, endMs - MIN_GAP_MS))

    /** Arrotonda a STEP_MS, poi limita a [startMs + MIN_GAP_MS, durationMs] (mai sopra durationMs). */
    fun moveEnd(requestedMs: Long, startMs: Long, durationMs: Long): Long =
        snap(requestedMs).coerceIn(minOf(startMs + MIN_GAP_MS, durationMs), durationMs)

    fun pxToMs(px: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f) return 0L
        return (px / widthPx * durationMs).roundToLong().coerceIn(0L, durationMs)
    }

    fun msToPx(ms: Long, widthPx: Float, durationMs: Long): Float =
        if (durationMs <= 0L) 0f else ms.toFloat() / durationMs * widthPx

    /** START → startMs; END → max(startMs, endMs - END_PREVIEW_MS). */
    fun seekAfterRelease(handle: TrimHandle, startMs: Long, endMs: Long): Long = when (handle) {
        TrimHandle.START -> startMs
        TrimHandle.END -> maxOf(startMs, endMs - END_PREVIEW_MS)
    }

    /** positionMs se in [startMs, endMs), altrimenti startMs. */
    fun playbackStart(positionMs: Long, startMs: Long, endMs: Long): Long =
        if (positionMs in startMs until endMs) positionMs else startMs

    fun isFullRange(startMs: Long, endMs: Long, durationMs: Long): Boolean =
        startMs == 0L && endMs == durationMs

    private fun snap(ms: Long): Long = (ms.toDouble() / STEP_MS).roundToLong() * STEP_MS
}
