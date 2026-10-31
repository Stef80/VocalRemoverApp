package com.example.vocalremover.capture

import android.content.Context

/** User edits to a pending capture, tied to the id of its terminal result. */
internal data class CaptureDraft(
    val captureId: String,
    val displayName: String,
    val trimmedPath: String?,
    val trimStartMs: Long,
    val trimEndMs: Long
)

/**
 * Trim editor draft. Separate from [CaptureTerminalResultStore], which remains the source of the
 * original WAV and of the processing stages.
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

    /** The draft of [captureId]; a draft of another capture is deleted. */
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
        ) { "Cannot save the capture draft" }
    }

    fun clear() {
        preferences.edit().clear().commit()
    }
}
