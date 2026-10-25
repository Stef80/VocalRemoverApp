package com.stef.vocalremover.ui.library

import android.content.IntentSender
import android.net.Uri
import com.example.vocalremover.library.LibraryTrack

/** Stato della lista dei brani elaborati. */
sealed interface LibraryUiState {
    /** Solo durante il primo caricamento. */
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Tracks(val tracks: List<LibraryTrack>) : LibraryUiState
    data object Error : LibraryUiState
}

/** Esiti una tantum di rinomina ed eliminazione. */
sealed interface LibraryEvent {
    /** Da lanciare con `StartIntentSenderForResult`; la risposta va a `onConsentResult`. */
    data class ConsentRequired(val intentSender: IntentSender) : LibraryEvent
    data class Renamed(val uri: Uri, val title: String) : LibraryEvent
    data class Deleted(val uri: Uri) : LibraryEvent
    data object RenameFailed : LibraryEvent
    data object DeleteFailed : LibraryEvent
}
