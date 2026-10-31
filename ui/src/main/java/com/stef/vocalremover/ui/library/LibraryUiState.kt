package com.stef.vocalremover.ui.library

import android.content.IntentSender
import android.net.Uri
import com.example.vocalremover.library.LibraryTrack

/** State of the list of processed tracks. */
sealed interface LibraryUiState {
    /** Only during the first load. */
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Tracks(val tracks: List<LibraryTrack>) : LibraryUiState
    data object Error : LibraryUiState
}

/** One-off outcomes of rename and delete. */
sealed interface LibraryEvent {
    /** To launch with `StartIntentSenderForResult`; the answer goes to `onConsentResult`. */
    data class ConsentRequired(val intentSender: IntentSender) : LibraryEvent
    data class Renamed(val uri: Uri, val title: String) : LibraryEvent
    data class Deleted(val uri: Uri) : LibraryEvent
    data object RenameFailed : LibraryEvent
    data object DeleteFailed : LibraryEvent
}
