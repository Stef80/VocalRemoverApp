package com.stef.vocalremover.ui

import java.util.Locale

/** Time as `m:ss` (e.g. 3:07), for editor and player. */
internal fun formatMinutesSeconds(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
