package com.example.vocalremover.backend

import android.content.Context
import android.util.Log
import com.example.vocalremover.ExecutionBackend
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted outcome of the backend probe for one [key] (app install time + device fingerprint). */
@Serializable
data class BackendChoice(
    val key: String,
    val chosen: ExecutionBackend? = null,
    val failed: Map<ExecutionBackend, String> = emptyMap(),
    val timingsMs: Map<ExecutionBackend, Long> = emptyMap(),
    val inProgress: ExecutionBackend? = null,
    val unexplainedExits: Map<ExecutionBackend, Int> = emptyMap(),
)

interface BackendChoiceStore {
    fun load(): BackendChoice?
    fun save(choice: BackendChoice)

    /** Like [save] but on disk when it returns: used for the crash marker, which a native crash may follow. */
    fun saveNow(choice: BackendChoice) = save(choice)
}

class SharedPrefsBackendChoiceStore(context: Context) : BackendChoiceStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): BackendChoice? {
        val json = prefs.getString(KEY_CHOICE, null) ?: return null
        return try {
            Json.decodeFromString<BackendChoice>(json)
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable backend choice", e)
            null
        }
    }

    override fun save(choice: BackendChoice) {
        prefs.edit().putString(KEY_CHOICE, Json.encodeToString(choice)).apply()
    }

    override fun saveNow(choice: BackendChoice) {
        if (!prefs.edit().putString(KEY_CHOICE, Json.encodeToString(choice)).commit()) {
            Log.w(TAG, "Cannot write the backend choice to disk")
        }
    }

    private companion object {
        const val TAG = "BackendChoiceStore"
        const val PREFS_NAME = "backend_choice"
        const val KEY_CHOICE = "choice"
    }
}
