package com.stef.vocalremover.ui.overlay

import android.content.ActivityNotFoundException
import android.content.SharedPreferences

/**
 * Decides whether to explain notification access (used to recognize the recorded track).
 * It is asked only once: if the user declines, the capture works without metadata.
 */
class NowPlayingAccessPrompt(
    private val prefs: SharedPreferences,
    private val isGranted: () -> Boolean,
) {
    private companion object {
        const val KEY_ASKED = "now_playing_access_asked"
    }

    fun shouldAsk(): Boolean = !prefs.getBoolean(KEY_ASKED, false) && !isGranted()

    fun markAsked() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }
}

/**
 * Opens the notification access Settings; on some devices (e.g. Android Go) the
 * page does not exist: in that case we go on without it, the access is optional.
 */
fun openSettingsOrContinue(launch: () -> Unit, onUnavailable: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        onUnavailable()
    }
}
