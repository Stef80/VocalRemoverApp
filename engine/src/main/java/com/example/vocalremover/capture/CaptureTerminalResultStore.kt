package com.example.vocalremover.capture

import android.content.Context
import android.os.Process
import java.util.UUID

internal enum class CaptureTerminalKind {
    BLOCKED,
    STOPPED,
    ERROR
}

internal enum class CaptureTerminalState {
    PENDING,
    PROCESSING,
    SAVED
}

internal data class CaptureTerminalResult(
    val id: String,
    val kind: CaptureTerminalKind,
    val outputPath: String? = null,
    val errorMessage: String? = null,
    val state: CaptureTerminalState = CaptureTerminalState.PENDING,
    val savedOutputUri: String? = null
)

internal data class CaptureWorkState(
    val state: CaptureTerminalState,
    val ownerId: String? = null,
    val ownerProcessId: Int = 0,
    val savedOutputUri: String? = null
)

internal object CaptureProcessingStateMachine {
    fun acquire(
        current: CaptureWorkState,
        requesterId: String,
        requesterProcessId: Int
    ): CaptureWorkState? {
        require(requesterId.isNotBlank())
        require(requesterProcessId > 0)
        if (
            current.ownerId != null &&
            current.ownerProcessId == requesterProcessId
        ) {
            return null
        }
        return current.copy(
            state = if (current.state == CaptureTerminalState.PENDING) {
                CaptureTerminalState.PROCESSING
            } else {
                current.state
            },
            ownerId = requesterId,
            ownerProcessId = requesterProcessId
        )
    }

    fun release(current: CaptureWorkState, ownerId: String): CaptureWorkState? {
        if (current.ownerId != ownerId) return null
        return current.copy(ownerId = null, ownerProcessId = 0)
    }

    fun markSaved(
        current: CaptureWorkState,
        ownerId: String,
        savedOutputUri: String
    ): CaptureWorkState? {
        if (
            current.state != CaptureTerminalState.PROCESSING ||
            current.ownerId != ownerId ||
            savedOutputUri.isBlank()
        ) {
            return null
        }
        return current.copy(
            state = CaptureTerminalState.SAVED,
            savedOutputUri = savedOutputUri
        )
    }

    fun canCompleteSavedHandoff(current: CaptureWorkState, ownerId: String): Boolean =
        current.state == CaptureTerminalState.SAVED && current.ownerId == ownerId
}

internal data class CaptureProcessingLease(
    val result: CaptureTerminalResult,
    val state: CaptureTerminalState
)

/**
 * Stores the one terminal capture outcome until an activity finishes its durable handoff.
 * Broadcasts are only a prompt to read this record, not the source of capture data.
 */
internal class CaptureTerminalResultStore(context: Context) {
    companion object {
        private const val PREFERENCES_NAME = "capture_terminal_result"
        private const val KEY_ID = "id"
        private const val KEY_KIND = "kind"
        private const val KEY_OUTPUT_PATH = "output_path"
        private const val KEY_ERROR_MESSAGE = "error_message"
        private const val KEY_CLAIMED_PROCESS_ID = "claimed_process_id"
        private const val KEY_STATE = "state"
        private const val KEY_OWNER_ID = "owner_id"
        private const val KEY_OWNER_PROCESS_ID = "owner_process_id"
        private const val KEY_SAVED_OUTPUT_URI = "saved_output_uri"
        private val lock = Any()
    }

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun persist(
        kind: CaptureTerminalKind,
        outputPath: String? = null,
        errorMessage: String? = null
    ): CaptureTerminalResult {
        require(kind != CaptureTerminalKind.STOPPED || !outputPath.isNullOrBlank()) {
            "A stopped capture requires an output path"
        }
        require(kind != CaptureTerminalKind.ERROR || !errorMessage.isNullOrBlank()) {
            "A failed capture requires an error message"
        }

        val result = CaptureTerminalResult(
            id = UUID.randomUUID().toString(),
            kind = kind,
            outputPath = outputPath,
            errorMessage = errorMessage
        )
        synchronized(lock) {
            check(
                preferences.edit()
                    .clear()
                    .putString(KEY_ID, result.id)
                    .putString(KEY_KIND, result.kind.name)
                    .putString(KEY_OUTPUT_PATH, result.outputPath)
                    .putString(KEY_ERROR_MESSAGE, result.errorMessage)
                    .putInt(KEY_CLAIMED_PROCESS_ID, 0)
                    .putString(KEY_STATE, CaptureTerminalState.PENDING.name)
                    .remove(KEY_OWNER_ID)
                    .remove(KEY_OWNER_PROCESS_ID)
                    .remove(KEY_SAVED_OUTPUT_URI)
                    .commit()
            ) { "Impossibile salvare il risultato della cattura" }
        }
        return result
    }

    /**
     * Claims an informational terminal result. Stopped captures use
     * [acquireStoppedResult] so their WAV remains available through processing.
     */
    fun claim(): CaptureTerminalResult? = synchronized(lock) {
        val result = readLocked() ?: return@synchronized null
        if (result.kind == CaptureTerminalKind.STOPPED) return@synchronized null
        val currentProcessId = Process.myPid()
        val claimedProcessId = preferences.getInt(KEY_CLAIMED_PROCESS_ID, 0)
        if (claimedProcessId == currentProcessId) {
            return@synchronized null
        }
        if (
            !preferences.edit()
                .putInt(KEY_CLAIMED_PROCESS_ID, currentProcessId)
                .commit()
        ) {
            return@synchronized null
        }
        result
    }

    /**
     * Acquires stopped-capture work for one activity instance. Work owned by
     * another activity in this process remains unavailable until that activity
     * releases it after cancellation; a dead process's lease is reclaimed.
     */
    fun acquireStoppedResult(ownerId: String): CaptureProcessingLease? = synchronized(lock) {
        val result = readLocked() ?: return@synchronized null
        if (result.kind != CaptureTerminalKind.STOPPED) return@synchronized null

        val nextState = CaptureProcessingStateMachine.acquire(
            workStateFrom(result),
            ownerId,
            Process.myPid()
        ) ?: return@synchronized null
        if (!writeWorkStateLocked(nextState)) return@synchronized null

        CaptureProcessingLease(
            result.copy(
                state = nextState.state,
                savedOutputUri = nextState.savedOutputUri
            ),
            nextState.state
        )
    }

    fun markSaved(resultId: String, ownerId: String, savedOutputUri: String): Boolean =
        synchronized(lock) {
            val result = readStoppedResultLocked(resultId) ?: return@synchronized false
            val nextState = CaptureProcessingStateMachine.markSaved(
                workStateFrom(result),
                ownerId,
                savedOutputUri
            ) ?: return@synchronized false
            writeWorkStateLocked(nextState)
        }

    /**
     * Releases an interrupted activity's lease while retaining the WAV/result
     * so a recreated activity can resume the same stage.
     */
    fun releaseStoppedResult(resultId: String, ownerId: String): Boolean = synchronized(lock) {
        val result = readStoppedResultLocked(resultId) ?: return@synchronized false
        val nextState = CaptureProcessingStateMachine.release(
            workStateFrom(result),
            ownerId
        ) ?: return@synchronized false
        writeWorkStateLocked(nextState)
    }

    /**
     * Clears a stopped capture only after its MediaStore output is playable.
     */
    fun completeSavedHandoff(resultId: String, ownerId: String): Boolean = synchronized(lock) {
        val result = readStoppedResultLocked(resultId) ?: return@synchronized false
        if (!CaptureProcessingStateMachine.canCompleteSavedHandoff(workStateFrom(result), ownerId)) {
            return@synchronized false
        }
        preferences.edit().clear().commit()
    }

    /**
     * Clears a stopped capture after its raw WAV has been discarded following
     * a terminal processing failure or an explicit cancellation.
     */
    fun discardStoppedResult(resultId: String, ownerId: String): Boolean = synchronized(lock) {
        val result = readStoppedResultLocked(resultId) ?: return@synchronized false
        if (workStateFrom(result).ownerId != ownerId) return@synchronized false
        preferences.edit().clear().commit()
    }

    fun clearClaim(resultId: String) {
        synchronized(lock) {
            if (
                preferences.getString(KEY_ID, null) == resultId &&
                preferences.getInt(KEY_CLAIMED_PROCESS_ID, 0) == Process.myPid()
            ) {
                preferences.edit().clear().commit()
            }
        }
    }

    private fun readLocked(): CaptureTerminalResult? {
        val id = preferences.getString(KEY_ID, null)
        val kind = preferences.getString(KEY_KIND, null)
            ?.let { runCatching { CaptureTerminalKind.valueOf(it) }.getOrNull() }
        if (id.isNullOrBlank() || kind == null) {
            if (id != null || kind != null) preferences.edit().clear().commit()
            return null
        }

        val outputPath = preferences.getString(KEY_OUTPUT_PATH, null)
        val errorMessage = preferences.getString(KEY_ERROR_MESSAGE, null)
        val state = if (kind == CaptureTerminalKind.STOPPED) {
            preferences.getString(KEY_STATE, null)
                ?.let { runCatching { CaptureTerminalState.valueOf(it) }.getOrNull() }
                ?: CaptureTerminalState.PENDING
        } else {
            CaptureTerminalState.PENDING
        }
        val savedOutputUri = preferences.getString(KEY_SAVED_OUTPUT_URI, null)
        if (
            (kind == CaptureTerminalKind.STOPPED && outputPath.isNullOrBlank()) ||
            (kind == CaptureTerminalKind.ERROR && errorMessage.isNullOrBlank()) ||
            (kind == CaptureTerminalKind.STOPPED &&
                state == CaptureTerminalState.SAVED &&
                savedOutputUri.isNullOrBlank())
        ) {
            preferences.edit().clear().commit()
            return null
        }
        return CaptureTerminalResult(id, kind, outputPath, errorMessage, state, savedOutputUri)
    }

    private fun readStoppedResultLocked(resultId: String): CaptureTerminalResult? =
        readLocked()?.takeIf { it.id == resultId && it.kind == CaptureTerminalKind.STOPPED }

    private fun workStateFrom(result: CaptureTerminalResult): CaptureWorkState {
        val ownerId = preferences.getString(KEY_OWNER_ID, null)?.takeIf { it.isNotBlank() }
        return CaptureWorkState(
            state = result.state,
            ownerId = ownerId,
            ownerProcessId = if (ownerId == null) 0 else {
                preferences.getInt(KEY_OWNER_PROCESS_ID, 0)
            },
            savedOutputUri = result.savedOutputUri
        )
    }

    private fun writeWorkStateLocked(state: CaptureWorkState): Boolean =
        preferences.edit()
            .putString(KEY_STATE, state.state.name)
            .putString(KEY_OWNER_ID, state.ownerId)
            .putInt(KEY_OWNER_PROCESS_ID, state.ownerProcessId)
            .putString(KEY_SAVED_OUTPUT_URI, state.savedOutputUri)
            .commit()
}
