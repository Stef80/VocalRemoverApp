package com.stef.vocalremover.ui.library

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.example.vocalremover.library.LibraryEditResult
import com.example.vocalremover.library.LibraryRepository
import com.example.vocalremover.library.LibraryTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val a = track(1, "A")
    private val b = track(2, "B")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial state is Loading and nothing is read`() {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository, dispatcher)
        assertEquals(LibraryUiState.Loading, viewModel.state.value)
        assertEquals(0, repository.calls)
    }

    @Test
    fun `refresh with tracks gives Tracks`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(FakeLibraryRepository(listOf(Result.success(listOf(a, b)))), dispatcher)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(LibraryUiState.Tracks(listOf(a, b)), viewModel.state.value)
    }

    @Test
    fun `refresh with empty list gives Empty`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(FakeLibraryRepository(listOf(Result.success(emptyList()))), dispatcher)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(LibraryUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `refresh failure gives Error`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(FakeLibraryRepository(listOf(Result.failure(IOException("boom")))), dispatcher)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(LibraryUiState.Error, viewModel.state.value)
    }

    @Test
    fun `later refresh keeps current state while loading`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(
            FakeLibraryRepository(listOf(Result.success(listOf(a)), Result.success(listOf(a, b)))),
            dispatcher,
        )
        viewModel.refresh()
        advanceUntilIdle()
        val states = mutableListOf<LibraryUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.toList(states) }
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(
            listOf(LibraryUiState.Tracks(listOf(a)), LibraryUiState.Tracks(listOf(a, b))),
            states,
        )
    }

    @Test
    fun `failure after tracks gives Error`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(
            FakeLibraryRepository(listOf(Result.success(listOf(a)), Result.failure(IOException("boom")))),
            dispatcher,
        )
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(LibraryUiState.Error, viewModel.state.value)
    }

    @Test
    fun `latest refresh wins`() = runTest(dispatcher) {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var calls = 0
        val repository = object : FakeLibraryRepository() {
            override fun load(): List<LibraryTrack> {
                calls++
                if (calls == 1) {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    return listOf(a)
                }
                return listOf(b)
            }
        }
        // Il primo load blocca un thread vero: serve un dispatcher reale per l'IO.
        val viewModel = LibraryViewModel(repository, Dispatchers.IO)
        val states = mutableListOf<LibraryUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.toList(states) }

        viewModel.refresh()
        advanceUntilIdle()
        withContext(Dispatchers.IO) { firstStarted.await(5, TimeUnit.SECONDS) }
        viewModel.refresh()
        releaseFirst.countDown()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { viewModel.state.first { it is LibraryUiState.Tracks } }
        }
        advanceUntilIdle()

        assertEquals(LibraryUiState.Tracks(listOf(b)), viewModel.state.value)
        assertFalse(states.contains(LibraryUiState.Tracks(listOf(a))))
        assertFalse(states.contains(LibraryUiState.Error))
    }

    private fun track(id: Long, title: String) =
        LibraryTrack(Uri.parse("content://media/external/audio/media/$id"), title, 1_000L)

    @Test
    fun `rename done emits Renamed and refreshes`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(listOf(Result.success(listOf(a))))
        val viewModel = LibraryViewModel(repository, dispatcher)
        val events = collectEvents(viewModel)
        viewModel.rename(a, "  Nuovo ")
        advanceUntilIdle()
        assertEquals(listOf("rename(${a.uri}, Nuovo)"), repository.edits)
        assertEquals(listOf(LibraryEvent.Renamed(a.uri, "Nuovo")), events)
        assertEquals(LibraryUiState.Tracks(listOf(a)), viewModel.state.value)
    }

    @Test
    fun `blank or unchanged title is ignored`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository()
        val viewModel = LibraryViewModel(repository, dispatcher)
        viewModel.rename(a, "   ")
        viewModel.rename(a, " A ")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), repository.edits)
    }

    @Test
    fun `delete done emits Deleted and refreshes`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(listOf(Result.success(emptyList())))
        val viewModel = LibraryViewModel(repository, dispatcher)
        val events = collectEvents(viewModel)
        viewModel.delete(a)
        advanceUntilIdle()
        assertEquals(listOf("delete(${a.uri})"), repository.edits)
        assertEquals(listOf(LibraryEvent.Deleted(a.uri)), events)
        assertEquals(LibraryUiState.Empty, viewModel.state.value)
    }

    @Test
    fun `consent is asked and the action retried when granted`() = runTest(dispatcher) {
        val sender = intentSender()
        val repository = FakeLibraryRepository(
            loads = listOf(Result.success(emptyList())),
            editResults = listOf(LibraryEditResult.NeedsConsent(sender), LibraryEditResult.Done),
        )
        val viewModel = LibraryViewModel(repository, dispatcher)
        val events = collectEvents(viewModel)
        viewModel.delete(a)
        advanceUntilIdle()
        assertEquals(listOf(LibraryEvent.ConsentRequired(sender)), events)

        viewModel.onConsentResult(granted = true)
        advanceUntilIdle()
        assertEquals(listOf("delete(${a.uri})", "delete(${a.uri})"), repository.edits)
        assertEquals(LibraryEvent.Deleted(a.uri), events.last())
    }

    @Test
    fun `denied consent does nothing`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(editResults = listOf(LibraryEditResult.NeedsConsent(intentSender())))
        val viewModel = LibraryViewModel(repository, dispatcher)
        val events = collectEvents(viewModel)
        viewModel.rename(a, "Nuovo")
        advanceUntilIdle()
        viewModel.onConsentResult(granted = false)
        viewModel.onConsentResult(granted = true)
        advanceUntilIdle()
        assertEquals(1, repository.edits.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `consent asked again after a retry is a failure`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(
            editResults = listOf(LibraryEditResult.NeedsConsent(intentSender()), LibraryEditResult.NeedsConsent(intentSender())),
        )
        val viewModel = LibraryViewModel(repository, dispatcher)
        val events = collectEvents(viewModel)
        viewModel.rename(a, "Nuovo")
        advanceUntilIdle()
        viewModel.onConsentResult(granted = true)
        advanceUntilIdle()
        assertEquals(LibraryEvent.RenameFailed, events.last())
    }

    @Test
    fun `failures emit the matching event`() = runTest(dispatcher) {
        val repository = FakeLibraryRepository(editFailure = IOException("boom"))
        val viewModel = LibraryViewModel(repository, dispatcher)
        val events = collectEvents(viewModel)
        viewModel.rename(a, "Nuovo")
        advanceUntilIdle()
        viewModel.delete(a)
        advanceUntilIdle()
        assertEquals(listOf(LibraryEvent.RenameFailed, LibraryEvent.DeleteFailed), events)
    }

    private fun kotlinx.coroutines.test.TestScope.collectEvents(viewModel: LibraryViewModel): MutableList<LibraryEvent> {
        val events = mutableListOf<LibraryEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        return events
    }

    private fun intentSender(): IntentSender {
        val context = RuntimeEnvironment.getApplication()
        return PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender
    }

    private open class FakeLibraryRepository(
        loads: List<Result<List<LibraryTrack>>> = emptyList(),
        editResults: List<LibraryEditResult> = emptyList(),
        private val editFailure: Exception? = null,
    ) : LibraryRepository {
        private val loads = ArrayDeque(loads)
        private val editResults = ArrayDeque(editResults)
        val edits = mutableListOf<String>()
        var calls = 0
            private set

        override fun load(): List<LibraryTrack> {
            calls++
            return loads.removeFirst().getOrThrow()
        }

        override fun delete(uri: Uri): LibraryEditResult = edit("delete($uri)")

        override fun rename(uri: Uri, title: String): LibraryEditResult = edit("rename($uri, $title)")

        private fun edit(call: String): LibraryEditResult {
            edits += call
            editFailure?.let { throw it }
            return editResults.removeFirstOrNull() ?: LibraryEditResult.Done
        }
    }
}
