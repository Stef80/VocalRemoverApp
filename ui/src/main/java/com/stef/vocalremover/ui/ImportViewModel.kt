package com.stef.vocalremover.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vocalremover.VocalRemover
import com.example.vocalremover.capture.PendingCapture
import com.example.vocalremover.capture.PendingCaptureRepository
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.playback.LibraryPlayer
import com.example.vocalremover.playback.PlayerState
import com.example.vocalremover.processing.ProcessingException
import com.example.vocalremover.processing.ProcessingState
import com.example.vocalremover.processing.SongProcessor
import com.stef.vocalremover.ui.editor.TrimHandle
import com.stef.vocalremover.ui.editor.TrimHandles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pending capture → trim editor → processing, or file picked from storage →
 * immediate processing. Tied to `MainActivity`, shared by `ImportSongScreen` and
 * `TrimEditorScreen`: processing goes on even when switching screens.
 */
class ImportViewModel(
    private val captures: PendingCaptureRepository,
    private val processor: SongProcessor,
    private val player: PreviewPlayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Name of the picked file (`OpenableColumns.DISPLAY_NAME`); null if not available. */
    private val displayNameOf: (Uri) -> String? = { null },
    private val releaseResources: () -> Unit = {},
) : ViewModel() {

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Empty)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** null = editor closed. */
    private val _editorState = MutableStateFlow<EditorUiState?>(null)
    val editorState: StateFlow<EditorUiState?> = _editorState.asStateFlow()

    val playerState: StateFlow<PlayerState> = player.state

    private val _events = Channel<ImportEvent>(Channel.BUFFERED)
    val events: Flow<ImportEvent> = _events.receiveAsFlow()

    private var capture: PendingCapture? = null
    private var previewErrorReported = false
    private var processingJob: Job? = null

    /** Seek requested by the card before the player has loaded its file: applied after play(). */
    private var pendingCardSeekMs: Long? = null

    init {
        viewModelScope.launch {
            withContext(ioDispatcher) { captures.cleanOrphans() }
            load(openEditor = false)
        }
        viewModelScope.launch { player.state.collect(::onPlayerState) }
    }

    /** [openEditor]: called when a capture ends, opens the editor right away if there is a capture. */
    fun loadPendingCapture(openEditor: Boolean) {
        viewModelScope.launch { load(openEditor) }
    }

    fun openEditor() {
        if (_importState.value is ImportUiState.Processing) return
        capture?.let(::openEditorFor)
    }

    fun closeEditor() {
        player.pause()
        _editorState.value = null
    }

    /** When leaving the Capture tab from the footer. */
    fun pausePreview() = player.pause()

    fun onNameChange(name: String) {
        _editorState.update { it?.copy(name = name) }
    }

    /** While dragging: the handle whose value changed moves. */
    fun onHandlesChange(startMs: Long, endMs: Long) {
        _editorState.update { editor ->
            when {
                editor == null -> null
                startMs != editor.startMs -> editor.copy(startMs = TrimHandles.moveStart(startMs, editor.endMs))
                endMs != editor.endMs ->
                    editor.copy(endMs = TrimHandles.moveEnd(endMs, editor.startMs, editor.durationMs))
                else -> editor
            }
        }
    }

    /** Releasing a handle jumps playback there, to hear the cut point right away. */
    fun onHandleReleased(which: TrimHandle) {
        val editor = _editorState.value ?: return
        seekEditor(TrimHandles.seekAfterRelease(which, editor.startMs, editor.endMs))
    }

    fun onSeek(positionMs: Long) {
        if (_editorState.value != null) {
            seekEditor(positionMs)
            return
        }
        val playable = (_importState.value as? ImportUiState.CaptureReady)?.playableUri ?: return
        if (player.state.value.currentUri == playable) {
            player.seekTo(positionMs)
        } else {
            pendingCardSeekMs = positionMs
        }
    }

    fun togglePlayback() {
        val editor = _editorState.value
        if (player.state.value.isPlaying) {
            player.pause()
            return
        }
        if (editor != null) {
            val raw = capture?.let { Uri.fromFile(it.rawFile) } ?: return
            player.play(raw)
            player.seekTo(TrimHandles.playbackStart(editor.positionMs, editor.startMs, editor.endMs))
        } else {
            val ready = _importState.value as? ImportUiState.CaptureReady ?: return
            player.play(ready.playableUri)
            pendingCardSeekMs?.let(player::seekTo)
            pendingCardSeekMs = null
        }
    }

    fun saveEdit() {
        val editor = _editorState.value ?: return
        if (editor.isSaving) return
        if (editor.name.isBlank()) {
            _events.trySend(ImportEvent.NameRequired)
            return
        }
        _editorState.value = editor.copy(isSaving = true)
        viewModelScope.launch {
            try {
                val saved = withContext(ioDispatcher) {
                    captures.saveEdit(editor.name, editor.startMs, editor.endMs)
                }
                capture = saved
                // The trimmed file may have the same path as before: stop() forces a reload.
                player.stop()
                pendingCardSeekMs = null
                _editorState.value = null
                _importState.value = readyState(saved)
                _events.send(ImportEvent.EditSaved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Saving the trim failed", e)
                _editorState.update { it?.copy(isSaving = false) }
                _events.send(ImportEvent.EditFailed)
            }
        }
    }

    fun process() {
        // A second tap before the state becomes Processing must not start another processing run.
        if (processingJob?.isActive == true) return
        if (_importState.value !is ImportUiState.CaptureReady) return
        val current = capture ?: return
        player.pause()
        processingJob = viewModelScope.launch {
            if (!withContext(ioDispatcher) { captures.beginProcessing() }) {
                _events.send(ImportEvent.CaptureUnavailable)
                load(openEditor = false)
                return@launch
            }
            // The lease must be released on every exit other than success, even if the ViewModel
            // is destroyed midway: otherwise the capture stays locked until the process dies.
            var leaseHandled = false
            try {
                runProcessing(current)
                leaseHandled = true
            } catch (e: ProcessingException) {
                withContext(ioDispatcher) { captures.abortProcessing() }
                leaseHandled = true
                _importState.value = readyState(current)
                load(openEditor = false)
                _events.send(ImportEvent.ProcessingFailed(e.error))
            } finally {
                if (!leaseHandled) withContext(NonCancellable + ioDispatcher) { captures.abortProcessing() }
            }
        }
    }

    private suspend fun runProcessing(current: PendingCapture) {
        val name = current.displayName
        val savedUri = processWithProgress(
            Uri.fromFile(current.playableFile),
            name,
            RecordingFileNaming.instrumentalNameForTitle(name),
            fromFile = false,
        )
        try {
            withContext(ioDispatcher) { captures.completeProcessing(savedUri) }
        } catch (e: Exception) {
            // The instrumental is saved: report success anyway, the state is read again.
            Log.e(TAG, "Post-processing capture cleanup failed", e)
        }
        capture = null
        _importState.value = ImportUiState.Empty
        load(openEditor = false)
        _events.send(ImportEvent.ProcessingDone)
    }

    /**
     * File picked with «Sfoglia memoria»: it is processed right away and the instrumental ends up in the library,
     * as in `:engine`'s file loading. Any pending capture stays intact.
     */
    fun processFile(source: Uri) {
        if (processingJob?.isActive == true) return
        if (_importState.value is ImportUiState.Processing) return
        player.pause()
        processingJob = viewModelScope.launch {
            val fileName = withContext(ioDispatcher) {
                try {
                    displayNameOf(source)
                } catch (e: Exception) {
                    Log.w(TAG, "File name not available: $source", e)
                    null
                }
            }
            try {
                processWithProgress(
                    source,
                    fileName.orEmpty(),
                    RecordingFileNaming.instrumentalName(fileName),
                    fromFile = true,
                )
                _events.send(ImportEvent.ProcessingDone)
            } catch (e: ProcessingException) {
                _events.send(ImportEvent.ProcessingFailed(e.error, fromFile = true))
            } finally {
                // load() ignores the Processing state: reset it before reading the capture again.
                if (_importState.value is ImportUiState.Processing) _importState.value = ImportUiState.Empty
            }
            load(openEditor = false)
        }
    }

    /** Launches [SongProcessor.process], reporting its stages in [ImportUiState.Processing]. */
    private suspend fun processWithProgress(
        source: Uri,
        name: String,
        outputName: String,
        fromFile: Boolean,
    ): Uri = coroutineScope {
        _importState.value = ImportUiState.Processing(name, ProcessingPhase.Decoding, fromFile)
        val progress = launch {
            processor.state.collect { state ->
                phaseOf(state)?.let { _importState.value = ImportUiState.Processing(name, it, fromFile) }
            }
        }
        try {
            processor.process(source, outputName)
        } finally {
            progress.cancel()
        }
    }

    fun discard() {
        if (_importState.value is ImportUiState.Processing) return
        player.pause()
        _editorState.value = null
        viewModelScope.launch {
            withContext(ioDispatcher) { captures.discard() }
            capture = null
            _importState.value = ImportUiState.Empty
        }
    }

    override fun onCleared() {
        player.release()
        releaseResources()
    }

    private suspend fun load(openEditor: Boolean) {
        if (_importState.value is ImportUiState.Processing) return
        val loaded = withContext(ioDispatcher) { captures.load() }
        capture = loaded
        if (loaded == null) {
            _importState.value = ImportUiState.Empty
            _editorState.value = null
            if (openEditor) _events.send(ImportEvent.CaptureUnavailable)
            return
        }
        _importState.value = readyState(loaded)
        pendingCardSeekMs = null
        if (openEditor) openEditorFor(loaded)
    }

    private fun openEditorFor(loaded: PendingCapture) {
        player.pause()
        previewErrorReported = false
        _editorState.value = EditorUiState(
            name = loaded.displayName,
            peaks = null,
            durationMs = loaded.durationMs,
            startMs = loaded.trimStartMs,
            endMs = loaded.trimEndMs,
            positionMs = loaded.trimStartMs,
            isPlaying = false,
            isSaving = false,
            previewAvailable = true,
        )
        viewModelScope.launch {
            val peaks = withContext(ioDispatcher) {
                try {
                    captures.peaks(WAVEFORM_BUCKETS)
                } catch (e: Exception) {
                    Log.e(TAG, "Waveform not available", e)
                    FloatArray(0)
                }
            }
            _editorState.update { it?.copy(peaks = peaks) }
        }
    }

    /** The editor always plays the original capture: the handles are on its times. */
    private fun seekEditor(positionMs: Long) {
        _editorState.update { it?.copy(positionMs = positionMs) }
        if (player.state.value.currentUri == editorUri()) player.seekTo(positionMs)
    }

    private fun onPlayerState(state: PlayerState) {
        if (state.error != null) {
            if (!previewErrorReported) {
                previewErrorReported = true
                _editorState.update { it?.copy(previewAvailable = false, isPlaying = false) }
                _events.trySend(ImportEvent.PreviewUnavailable)
            }
            return
        }
        previewErrorReported = false
        val editor = _editorState.value ?: return
        if (state.currentUri == null || state.currentUri != editorUri()) return
        if (state.isPlaying && state.positionMs >= editor.endMs) {
            // Stops at the end handle: you hear exactly what will remain.
            player.pause()
            player.seekTo(editor.endMs)
            _editorState.update { it?.copy(isPlaying = false, positionMs = editor.endMs) }
            return
        }
        _editorState.update { it?.copy(isPlaying = state.isPlaying, positionMs = state.positionMs) }
    }

    private fun editorUri(): Uri? = capture?.let { Uri.fromFile(it.rawFile) }

    private fun readyState(loaded: PendingCapture) = ImportUiState.CaptureReady(
        name = loaded.displayName,
        playableUri = Uri.fromFile(loaded.playableFile),
        durationMs = if (loaded.trimmedFile != null) loaded.trimEndMs - loaded.trimStartMs else loaded.durationMs,
        isTrimmed = loaded.trimmedFile != null,
    )

    private fun phaseOf(state: ProcessingState): ProcessingPhase? = when (state) {
        ProcessingState.Decoding -> ProcessingPhase.Decoding
        is ProcessingState.Processing -> ProcessingPhase.Running(state.percent)
        ProcessingState.Saving -> ProcessingPhase.Saving
        else -> null
    }

    companion object {
        private const val TAG = "ImportViewModel"
        private const val WAVEFORM_BUCKETS = 1000

        private fun queryDisplayName(context: Context, uri: Uri): String? =
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val remover = VocalRemover(app)
                ImportViewModel(
                    captures = PendingCaptureRepository.create(app),
                    processor = SongProcessor.create(app, remover),
                    player = LibraryPreviewPlayer(LibraryPlayer(app)),
                    displayNameOf = { uri -> queryDisplayName(app, uri) },
                    releaseResources = remover::close,
                )
            }
        }
    }
}
