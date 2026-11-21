package com.example.vocalremover.processing

import android.net.Uri

/** State of [SongProcessor], meant to be read by Compose. */
sealed interface ProcessingState {
    data object Idle : ProcessingState
    data object Decoding : ProcessingState
    /** First launch on this device/build: the backend probe is still running before inference. */
    data object PreparingBackend : ProcessingState
    /** The backend failed mid-song: the song restarts on the next one, until it reports progress. */
    data object RetryingBackend : ProcessingState
    /** [percent] 0–100, forwarded from `VocalRemover.removeVocals`. */
    data class Processing(val percent: Int) : ProcessingState
    data object Saving : ProcessingState
    data class Done(val savedUri: Uri) : ProcessingState
    data class Failed(val error: ProcessingError) : ProcessingState
}

/** Failed stage: the user-facing text is chosen by `:ui` in strings.xml. */
enum class ProcessingError { DECODE_FAILED, INFERENCE_FAILED, SAVE_FAILED }

class ProcessingException(val error: ProcessingError, cause: Throwable) : Exception(cause)
