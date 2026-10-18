package com.example.vocalremover.capture

import android.content.Context

/** Modifiche dell'utente a una cattura in attesa, legate all'id del suo risultato terminale. */
internal data class CaptureDraft(
    val captureId: String,
    val displayName: String,
    val trimmedPath: String?,
    val trimStartMs: Long,
    val trimEndMs: Long
)

/**
 * Bozza dell'editor di taglio. Separata da [CaptureTerminalResultStore], che resta la fonte del
 * WAV originale e delle fasi di elaborazione.
 */
internal class CaptureDraftStore(context: Context) {
    companion object {
        private const val PREFERENCES_NAME = "capture_draft"
        private const val KEY_CAPTURE_ID = "capture_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TRIMMED_PATH = "trimmed_path"
        private const val KEY_TRIM_START_MS = "trim_start_ms"
        private const val KEY_TRIM_END_MS = "trim_end_ms"
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** La bozza di [captureId]; una bozza di un'altra cattura viene cancellata. */
    fun read(captureId: String): CaptureDraft? {
        val storedId = preferences.getString(KEY_CAPTURE_ID, null) ?: return null
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)
        if (storedId != captureId || displayName == null) {
            clear()
            return null
        }
        return CaptureDraft(
            captureId = storedId,
            displayName = displayName,
            trimmedPath = preferences.getString(KEY_TRIMMED_PATH, null),
            trimStartMs = preferences.getLong(KEY_TRIM_START_MS, 0L),
            trimEndMs = preferences.getLong(KEY_TRIM_END_MS, 0L)
        )
    }

    fun write(draft: CaptureDraft) {
        check(
            preferences.edit()
                .putString(KEY_CAPTURE_ID, draft.captureId)
                .putString(KEY_DISPLAY_NAME, draft.displayName)
                .putString(KEY_TRIMMED_PATH, draft.trimmedPath)
                .putLong(KEY_TRIM_START_MS, draft.trimStartMs)
                .putLong(KEY_TRIM_END_MS, draft.trimEndMs)
                .commit()
        ) { "Impossibile salvare la bozza della cattura" }
    }

    fun clear() {
        preferences.edit().clear().commit()
    }
}
