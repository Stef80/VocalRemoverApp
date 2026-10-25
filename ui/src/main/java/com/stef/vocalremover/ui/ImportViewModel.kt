package com.stef.vocalremover.ui

import android.net.Uri
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
 * Cattura in attesa → editor di taglio → elaborazione. Legato a `MainActivity`, condiviso da
 * `ImportSongScreen` e `TrimEditorScreen`: l'elaborazione continua anche cambiando schermata.
 */
class ImportViewModel(
    private val captures: PendingCaptureRepository,
    private val processor: SongProcessor,
    private val player: PreviewPlayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val releaseResources: () -> Unit = {},
) : ViewModel() {

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Empty)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** null = editor chiuso. */
    private val _editorState = MutableStateFlow<EditorUiState?>(null)
    val editorState: StateFlow<EditorUiState?> = _editorState.asStateFlow()

    val playerState: StateFlow<PlayerState> = player.state

    private val _events = Channel<ImportEvent>(Channel.BUFFERED)
    val events: Flow<ImportEvent> = _events.receiveAsFlow()

    private var capture: PendingCapture? = null
    private var previewErrorReported = false
    private var processingJob: Job? = null

    /** Seek chiesto dalla card prima che il player abbia caricato il suo file: si applica dopo play(). */
    private var pendingCardSeekMs: Long? = null

    init {
        viewModelScope.launch {
            withContext(ioDispatcher) { captures.cleanOrphans() }
            load(openEditor = false)
        }
        viewModelScope.launch { player.state.collect(::onPlayerState) }
    }

    /** [openEditor]: chiamata a fine cattura, apre subito l'editor se c'è una cattura. */
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

    /** Uscendo dalla scheda Cattura dal footer. */
    fun pausePreview() = player.pause()

    fun onNameChange(name: String) {
        _editorState.update { it?.copy(name = name) }
    }

    /** Durante il trascinamento: si muove la maniglia il cui valore è cambiato. */
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

    /** Rilasciando una maniglia l'ascolto salta lì, per sentire subito il punto di taglio. */
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
                // Il file tagliato può avere lo stesso percorso di prima: stop() obbliga a ricaricarlo.
                player.stop()
                pendingCardSeekMs = null
                _editorState.value = null
                _importState.value = readyState(saved)
                _events.send(ImportEvent.EditSaved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Salvataggio del taglio fallito", e)
                _editorState.update { it?.copy(isSaving = false) }
                _events.send(ImportEvent.EditFailed)
            }
        }
    }

    fun process() {
        // Un secondo tocco prima che lo stato diventi Processing non deve avviare un'altra elaborazione.
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
            // Il lease va rilasciato su ogni uscita che non sia la riuscita, anche se il ViewModel
            // viene distrutto a metà: altrimenti la cattura resta bloccata fino alla morte del processo.
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

    private suspend fun runProcessing(current: PendingCapture) = coroutineScope {
        val name = current.displayName
        _importState.value = ImportUiState.Processing(name, ProcessingPhase.Decoding)
        val progress = launch {
            processor.state.collect { state ->
                phaseOf(state)?.let { _importState.value = ImportUiState.Processing(name, it) }
            }
        }
        try {
            val savedUri = processor.process(
                Uri.fromFile(current.playableFile),
                RecordingFileNaming.instrumentalNameForTitle(name),
            )
            try {
                withContext(ioDispatcher) { captures.completeProcessing(savedUri) }
            } catch (e: Exception) {
                // Lo strumentale è salvato: si segnala comunque la riuscita, lo stato si rilegge.
                Log.e(TAG, "Pulizia della cattura dopo l'elaborazione fallita", e)
            }
        } finally {
            progress.cancel()
        }
        capture = null
        _importState.value = ImportUiState.Empty
        load(openEditor = false)
        _events.send(ImportEvent.ProcessingDone)
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
                    Log.e(TAG, "Forma d'onda non disponibile", e)
                    FloatArray(0)
                }
            }
            _editorState.update { it?.copy(peaks = peaks) }
        }
    }

    /** L'editor suona sempre la cattura originale: le maniglie sono sui suoi tempi. */
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
            // Si ferma alla maniglia di fine: si sente esattamente cosa resterà.
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

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val remover = VocalRemover(app)
                ImportViewModel(
                    captures = PendingCaptureRepository.create(app),
                    processor = SongProcessor.create(app, remover),
                    player = LibraryPreviewPlayer(LibraryPlayer(app)),
                    releaseResources = remover::close,
                )
            }
        }
    }
}
