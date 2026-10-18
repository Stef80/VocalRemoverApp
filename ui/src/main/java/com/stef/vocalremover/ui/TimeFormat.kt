package com.stef.vocalremover.ui

import java.util.Locale

/** Tempo come `m:ss` (es. 3:07), per editor e player. */
internal fun formatMinutesSeconds(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
