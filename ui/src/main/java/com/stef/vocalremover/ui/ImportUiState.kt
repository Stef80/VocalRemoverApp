package com.stef.vocalremover.ui

import android.net.Uri
import com.example.vocalremover.processing.ProcessingError

/** State of the Import screen for the pending capture or the file picked from storage. */
sealed interface ImportUiState {
    data object Empty : ImportUiState
    data class CaptureReady(
        val name: String,
        val playableUri: Uri,
        val durationMs: Long,
        val isTrimmed: Boolean,
    ) : ImportUiState
    /** [fromFile]: a file picked with «Sfoglia memoria» is being processed, not the capture. */
    data class Processing(
        val name: String,
        val phase: ProcessingPhase,
        val fromFile: Boolean = false,
    ) : ImportUiState
}

/** Stages shown by the progress bar, from `SongProcessor.state`. */
sealed interface ProcessingPhase {
    data object Decoding : ProcessingPhase
    data class Running(val percent: Int) : ProcessingPhase
    data object Saving : ProcessingPhase
}

/** State of the trim editor; times in ms relative to the original capture. */
data class EditorUiState(
    val name: String,
    /** null while they are being computed. */
    val peaks: FloatArray?,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isSaving: Boolean,
    val previewAvailable: Boolean,
) {
    // FloatArray has no structural equals: it is compared by content.
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

/** One-off outcomes, shown as snackbars (texts in strings.xml). */
sealed interface ImportEvent {
    data object EditSaved : ImportEvent
    data object EditFailed : ImportEvent
    data object NameRequired : ImportEvent
    data object CaptureUnavailable : ImportEvent
    data object ProcessingDone : ImportEvent
    data class ProcessingFailed(val error: ProcessingError, val fromFile: Boolean = false) : ImportEvent
    data object PreviewUnavailable : ImportEvent
}
