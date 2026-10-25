package com.stef.vocalremover.ui

import android.net.Uri
import com.example.vocalremover.processing.ProcessingError

/** Stato della schermata Import rispetto alla cattura in attesa o al file scelto dalla memoria. */
sealed interface ImportUiState {
    data object Empty : ImportUiState
    data class CaptureReady(
        val name: String,
        val playableUri: Uri,
        val durationMs: Long,
        val isTrimmed: Boolean,
    ) : ImportUiState
    /** [fromFile]: si sta elaborando un file scelto con «Sfoglia memoria», non la cattura. */
    data class Processing(
        val name: String,
        val phase: ProcessingPhase,
        val fromFile: Boolean = false,
    ) : ImportUiState
}

/** Fasi mostrate dalla progress bar, da `SongProcessor.state`. */
sealed interface ProcessingPhase {
    data object Decoding : ProcessingPhase
    data class Running(val percent: Int) : ProcessingPhase
    data object Saving : ProcessingPhase
}

/** Stato dell'editor di taglio; tempi in ms rispetto alla cattura originale. */
data class EditorUiState(
    val name: String,
    /** null mentre si calcolano. */
    val peaks: FloatArray?,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isSaving: Boolean,
    val previewAvailable: Boolean,
) {
    // FloatArray non ha equals strutturale: lo si confronta per contenuto.
    override fun equals(other: Any?): Boolean =
        other is EditorUiState &&
            name == other.name &&
            (peaks?.contentEquals(other.peaks) ?: (other.peaks == null)) &&
            durationMs == other.durationMs &&
            startMs == other.startMs &&
            endMs == other.endMs &&
            positionMs == other.positionMs &&
            isPlaying == other.isPlaying &&
            isSaving == other.isSaving &&
            previewAvailable == other.previewAvailable

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (peaks?.contentHashCode() ?: 0)
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        result = 31 * result + positionMs.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + isSaving.hashCode()
        result = 31 * result + previewAvailable.hashCode()
        return result
    }
}

/** Esiti una tantum, mostrati come snackbar (testi in strings.xml). */
sealed interface ImportEvent {
    data object EditSaved : ImportEvent
    data object EditFailed : ImportEvent
    data object NameRequired : ImportEvent
    data object CaptureUnavailable : ImportEvent
    data object ProcessingDone : ImportEvent
    data class ProcessingFailed(val error: ProcessingError, val fromFile: Boolean = false) : ImportEvent
    data object PreviewUnavailable : ImportEvent
}
