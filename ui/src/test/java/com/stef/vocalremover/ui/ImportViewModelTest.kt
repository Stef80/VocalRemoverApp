package com.stef.vocalremover.ui

import android.net.Uri
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.capture.PendingCapture
import com.example.vocalremover.capture.PendingCaptureRepository
import com.example.vocalremover.playback.PlayerState
import com.example.vocalremover.processing.InstrumentalExtractor
import com.example.vocalremover.processing.InstrumentalSaver
import com.example.vocalremover.processing.PcmDecoder
import com.example.vocalremover.processing.ProcessingError
import com.example.vocalremover.processing.SongProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val raw = File("/cache/system_audio_capture_1.wav")
    private val trimmed = File("/cache/Canzone_tagliato.wav")
    private val savedUri: Uri = Uri.parse("content://media/external/audio/media/9")
    private val captures = FakeCaptures()
    private val player = FakePlayer()
    private var extractorFails = false
    private var extractorGate: CompletableDeferred<Unit>? = null
    private var extractorCalls = 0
    private var savedInstrumentalName: String? = null

    private val pending = PendingCapture(
        id = "c1",
        rawFile = raw,
        trimmedFile = null,
        displayName = "Registrazione",
        trimStartMs = 0,
        trimEndMs = 5000,
        durationMs = 5000,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(io: kotlinx.coroutines.CoroutineDispatcher = dispatcher): ImportViewModel {
        val processor = SongProcessor(
            decoder = PcmDecoder { StereoPcm(FloatArray(4), FloatArray(4)) },
            extractor = InstrumentalExtractor { pcm, onProgress ->
                extractorCalls++
                if (extractorFails) throw IOException("ORT")
                extractorGate?.await()
                onProgress(50)
                pcm
            },
            saver = InstrumentalSaver { _, name -> savedInstrumentalName = name; savedUri },
            ioDispatcher = dispatcher,
            computeDispatcher = dispatcher,
        )
        return ImportViewModel(captures, processor, player, ioDispatcher = io)
    }

    private fun TestScope.recordEvents(vm: ImportViewModel): List<ImportEvent> {
        val events = mutableListOf<ImportEvent>()
        backgroundScope.launch(dispatcher) { vm.events.toList(events) }
        return events
    }

    @Test
    fun `no capture is Empty`() = runTest(dispatcher) {
        assertEquals(ImportUiState.Empty, viewModel().importState.value)
        assertTrue(captures.cleanedOrphans)
    }

    @Test
    fun `pending capture is CaptureReady with playable trimmed file`() = runTest(dispatcher) {
        captures.capture = pending.copy(trimmedFile = trimmed, trimStartMs = 1000, trimEndMs = 2000)

        val state = viewModel().importState.value

        assertEquals(ImportUiState.CaptureReady("Registrazione", Uri.fromFile(trimmed), 1000, true), state)
    }

    @Test
    fun `load from capture stop opens the editor`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel()

        vm.loadPendingCapture(openEditor = true)

        val editor = vm.editorState.value!!
        assertEquals("Registrazione", editor.name)
        assertEquals(1000, editor.peaks!!.size)
        assertEquals(0L, editor.startMs)
        assertEquals(5000L, editor.endMs)
    }

    @Test
    fun `load from capture stop without capture emits CaptureUnavailable`() = runTest(dispatcher) {
        val vm = viewModel()
        val events = recordEvents(vm)

        vm.loadPendingCapture(openEditor = true)

        assertNull(vm.editorState.value)
        assertEquals(listOf(ImportEvent.CaptureUnavailable), events)
    }

    @Test
    fun `blank name emits NameRequired and keeps editor open`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel().apply { loadPendingCapture(openEditor = true) }
        val events = recordEvents(vm)

        vm.onNameChange("   ")
        vm.saveEdit()

        assertNotNull(vm.editorState.value)
        assertEquals(listOf(ImportEvent.NameRequired), events)
        assertNull(captures.savedEdit)
    }

    @Test
    fun `save closes editor and updates CaptureReady`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel().apply { loadPendingCapture(openEditor = true) }
        val events = recordEvents(vm)

        vm.onNameChange("Canzone")
        vm.onHandlesChange(1000, 5000)
        vm.onHandlesChange(1000, 2000)
        vm.saveEdit()

        assertEquals(Triple("Canzone", 1000L, 2000L), captures.savedEdit)
        assertNull(vm.editorState.value)
        assertEquals(ImportUiState.CaptureReady("Canzone", Uri.fromFile(trimmed), 1000, true), vm.importState.value)
        assertEquals(listOf(ImportEvent.EditSaved), events)
    }

    @Test
    fun `save failure keeps editor open with EditFailed`() = runTest(dispatcher) {
        captures.capture = pending
        captures.saveError = IOException("disco pieno")
        val vm = viewModel().apply { loadPendingCapture(openEditor = true) }
        val events = recordEvents(vm)

        vm.saveEdit()

        assertEquals(false, vm.editorState.value!!.isSaving)
        assertEquals(listOf(ImportEvent.EditFailed), events)
    }

    @Test
    fun `playback pauses at end handle`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel().apply { loadPendingCapture(openEditor = true) }
        vm.onHandlesChange(0, 2000)
        player.calls.clear()

        player.state.value = PlayerState(currentUri = Uri.fromFile(raw), isPlaying = true, positionMs = 2010, durationMs = 5000)

        assertTrue("pause" in player.calls)
        assertTrue("seekTo(2000)" in player.calls)
    }

    @Test
    fun `successful processing returns to Empty and completes capture`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel()
        val states = mutableListOf<ImportUiState>()
        backgroundScope.launch(dispatcher) { vm.importState.toList(states) }
        val events = recordEvents(vm)

        vm.process()

        assertTrue(states.contains(ImportUiState.Processing("Registrazione", ProcessingPhase.Running(50))))
        assertEquals(ImportUiState.Empty, vm.importState.value)
        assertEquals(savedUri, captures.completed)
        assertEquals(listOf(ImportEvent.ProcessingDone), events)
    }

    @Test
    fun `process can be retried after a failure`() = runTest(dispatcher) {
        captures.capture = pending.copy(trimmedFile = trimmed, trimStartMs = 1000, trimEndMs = 2000)
        extractorFails = true
        val vm = viewModel()
        val events = recordEvents(vm)

        vm.process()

        assertEquals(listOf<ImportEvent>(ImportEvent.ProcessingFailed(ProcessingError.INFERENCE_FAILED)), events)
        assertEquals(1, captures.aborted)
        assertTrue(vm.importState.value is ImportUiState.CaptureReady)

        extractorFails = false
        vm.process()

        assertEquals(ImportUiState.Empty, vm.importState.value)
        assertEquals(savedUri, captures.completed)
    }

    @Test
    fun `begin refused emits CaptureUnavailable`() = runTest(dispatcher) {
        captures.capture = pending
        captures.beginResult = false
        val vm = viewModel()
        val events = recordEvents(vm)

        vm.process()

        assertEquals(listOf(ImportEvent.CaptureUnavailable), events)
        assertTrue(vm.importState.value is ImportUiState.CaptureReady)
    }

    @Test
    fun `discard returns to Empty`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel()

        vm.discard()

        assertTrue(captures.discarded)
        assertEquals(ImportUiState.Empty, vm.importState.value)
    }

    @Test
    fun `discard is ignored while processing`() = runTest(dispatcher) {
        captures.capture = pending
        val gate = CompletableDeferred<Unit>()
        extractorGate = gate
        val vm = viewModel()

        vm.process()
        assertTrue(vm.importState.value is ImportUiState.Processing)
        vm.discard()
        gate.complete(Unit)

        assertEquals(false, captures.discarded)
        assertEquals(ImportUiState.Empty, vm.importState.value)
    }

    @Test
    fun `double tap on process runs a single processing`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel(io = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()

        vm.process()
        vm.process()
        advanceUntilIdle()

        assertEquals(1, captures.beginCalls)
        assertEquals(1, extractorCalls)
        assertEquals(ImportUiState.Empty, vm.importState.value)
    }

    @Test
    fun `cleared view model releases the processing lease`() = runTest(dispatcher) {
        captures.capture = pending
        extractorGate = CompletableDeferred()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { viewModel() } })[ImportViewModel::class]

        vm.process()
        store.clear()

        assertEquals(1, captures.aborted)
    }

    @Test
    fun `resaving stops the player so the new trim is reloaded`() = runTest(dispatcher) {
        captures.capture = pending
        val vm = viewModel().apply { loadPendingCapture(openEditor = true) }
        vm.onHandlesChange(1000, 5000)

        vm.saveEdit()

        assertTrue("stop" in player.calls)
    }

    @Test
    fun `card seek before play is applied after loading the playable file`() = runTest(dispatcher) {
        captures.capture = pending.copy(trimmedFile = trimmed, trimStartMs = 1000, trimEndMs = 2000)
        val vm = viewModel()
        player.state.value = PlayerState(currentUri = Uri.fromFile(raw), durationMs = 5000)
        player.calls.clear()

        vm.onSeek(500)
        vm.togglePlayback()

        assertEquals(listOf("play(${Uri.fromFile(trimmed)})", "seekTo(500)"), player.calls)
    }

    @Test
    fun `instrumental name keeps dots of the title`() = runTest(dispatcher) {
        captures.capture = pending.copy(displayName = "Mr. Brightside")
        val vm = viewModel()

        vm.process()

        assertEquals("Mr. Brightside_strumentale", savedInstrumentalName)
    }

    private inner class FakeCaptures : PendingCaptureRepository {
        var capture: PendingCapture? = null
        var saveError: Exception? = null
        var beginResult = true
        var savedEdit: Triple<String, Long, Long>? = null
        var completed: Uri? = null
        var aborted = 0
        var discarded = false
        var cleanedOrphans = false

        override fun load(): PendingCapture? = capture
        override fun peaks(buckets: Int) = FloatArray(buckets)
        override fun saveEdit(displayName: String, startMs: Long, endMs: Long): PendingCapture {
            saveError?.let { throw it }
            savedEdit = Triple(displayName, startMs, endMs)
            val full = startMs == 0L && endMs == capture!!.durationMs
            return capture!!.copy(
                displayName = displayName,
                trimStartMs = startMs,
                trimEndMs = endMs,
                trimmedFile = if (full) null else File("/cache/${displayName}_tagliato.wav"),
            ).also { capture = it }
        }
        var beginCalls = 0
        override fun beginProcessing(): Boolean {
            beginCalls++
            return beginResult
        }
        override fun completeProcessing(savedUri: Uri) {
            completed = savedUri
            capture = null
        }
        override fun abortProcessing() {
            aborted++
        }
        override fun discard() {
            discarded = true
            capture = null
        }
        override fun cleanOrphans() {
            cleanedOrphans = true
        }
    }

    private class FakePlayer : PreviewPlayer {
        override val state = MutableStateFlow(PlayerState())
        val calls = mutableListOf<String>()
        override fun play(uri: Uri) {
            calls += "play($uri)"
        }
        override fun pause() {
            calls += "pause"
        }
        override fun seekTo(positionMs: Long) {
            calls += "seekTo($positionMs)"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun release() {
            calls += "release"
        }
    }
}
