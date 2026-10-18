package com.example.vocalremover.processing

import android.net.Uri

/** Stato di [SongProcessor], pensato per essere letto da Compose. */
sealed interface ProcessingState {
    data object Idle : ProcessingState
    data object Decoding : ProcessingState
    /** [percent] 0–100, inoltrato da `VocalRemover.removeVocals`. */
    data class Processing(val percent: Int) : ProcessingState
    data object Saving : ProcessingState
    data class Done(val savedUri: Uri) : ProcessingState
    data class Failed(val error: ProcessingError) : ProcessingState
}

/** Fase fallita: il testo per l'utente lo sceglie `:ui` in strings.xml. */
enum class ProcessingError { DECODE_FAILED, INFERENCE_FAILED, SAVE_FAILED }

class ProcessingException(val error: ProcessingError, cause: Throwable) : Exception(cause)
