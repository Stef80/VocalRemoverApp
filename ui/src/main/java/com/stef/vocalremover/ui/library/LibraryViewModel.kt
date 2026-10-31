package com.stef.vocalremover.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.library.LibraryEditResult
import com.example.vocalremover.library.LibraryRepository
import com.example.vocalremover.library.LibraryTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads the library when the screen asks (every time it comes back to the foreground). */
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    private var refreshJob: Job? = null

    /** Action rejected by MediaStore pending user consent. */
    private var awaitingConsent: Edit? = null

    private sealed interface Edit {
        val uri: Uri
        data class Rename(override val uri: Uri, val title: String) : Edit
        data class Delete(override val uri: Uri) : Edit
    }

    /**
     * Reads the library again; cancels a read still in progress (the last one wins). During reads
     * after the first one the state stays the current one, without going back to `Loading`.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val result = try {
                Result.success(withContext(ioDispatcher) { repository.load() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.value = result.fold(
                onSuccess = { tracks ->
                    if (tracks.isEmpty()) LibraryUiState.Empty else LibraryUiState.Tracks(tracks)
                },
                onFailure = { error ->
                    Log.w(TAG, "Reading the library failed", error)
                    LibraryUiState.Error
                },
            )
        }
    }

    /** Empty title, or one that would not change the file name: does nothing. */
    fun rename(track: LibraryTrack, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || RecordingFileNaming.sanitize(trimmed) == track.title) return
        perform(Edit.Rename(track.uri, trimmed), canAskConsent = true)
    }

    fun delete(track: LibraryTrack) = perform(Edit.Delete(track.uri), canAskConsent = true)

    /** Answer to the `LibraryEvent.ConsentRequired` dialog: retries only once. */
    fun onConsentResult(granted: Boolean) {
        val edit = awaitingConsent ?: return
        awaitingConsent = null
        if (granted) perform(edit, canAskConsent = false)
    }

    private fun perform(edit: Edit, canAskConsent: Boolean) {
        viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    when (edit) {
                        is Edit.Rename -> repository.rename(edit.uri, edit.title)
                        is Edit.Delete -> repository.delete(edit.uri)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Library change failed", e)
                null
            }
            when {
                result == LibraryEditResult.Done -> {
                    _events.send(
                        when (edit) {
                            is Edit.Rename -> LibraryEvent.Renamed(edit.uri, edit.title)
                            is Edit.Delete -> LibraryEvent.Deleted(edit.uri)
                        }
                    )
                    refresh()
                }
                result is LibraryEditResult.NeedsConsent && canAskConsent -> {
                    awaitingConsent = edit
                    _events.send(LibraryEvent.ConsentRequired(result.intentSender))
                }
                // Error, or consent asked again after it was already given.
                else -> _events.send(
                    when (edit) {
                        is Edit.Rename -> LibraryEvent.RenameFailed
                        is Edit.Delete -> LibraryEvent.DeleteFailed
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "LibraryViewModel"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(LibraryRepository.create(checkNotNull(this[APPLICATION_KEY])))
            }
        }
    }
}
