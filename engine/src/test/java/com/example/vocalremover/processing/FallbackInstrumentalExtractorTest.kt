package com.example.vocalremover.processing

import ai.onnxruntime.OrtException
import com.example.vocalremover.BackendOutputException
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.ExecutionBackend.CPU
import com.example.vocalremover.ExecutionBackend.QNN
import com.example.vocalremover.ExecutionBackend.WEBGPU
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.backend.BackendSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FallbackInstrumentalExtractorTest {

    private val mix = StereoPcm(FloatArray(4) { 0.1f }, FloatArray(4) { 0.2f })

    private class FakeSelector(var chosen: ExecutionBackend, private val next: Map<ExecutionBackend, ExecutionBackend>) :
        BackendSelector {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Pair<ExecutionBackend, Throwable>>()
        var ready = true

        override suspend fun current(): ExecutionBackend {
            events += "current"
            return chosen
        }

        override suspend fun reportFailure(backend: ExecutionBackend, cause: Throwable): ExecutionBackend {
            failures += backend to cause
            chosen = next.getValue(backend)
            return chosen
        }

        override fun isReady() = ready

        override fun markInProgress(backend: ExecutionBackend) {
            events += "inProgress:$backend"
        }

        override fun markCompleted(backend: ExecutionBackend) {
            events += "completed:$backend"
        }
    }

    private class FakeRemover(
        val backend: ExecutionBackend,
        private val events: MutableList<String>,
        private val behavior: suspend (StereoPcm, (Int) -> Unit) -> StereoPcm,
    ) : InstrumentalRemover {
        var closed = false

        override suspend fun removeVocals(signal: StereoPcm, onProgress: (Int) -> Unit): StereoPcm {
            events += "run:$backend"
            return behavior(signal, onProgress)
        }

        override fun close() {
            closed = true
        }
    }

    private fun output(value: Float) = StereoPcm(FloatArray(4) { value }, FloatArray(4) { value })

    @Test
    fun `uses selected backend`() = runTest {
        val selector = FakeSelector(WEBGPU, emptyMap())
        val created = mutableListOf<ExecutionBackend>()
        val expected = output(0.5f)
        val extractor = FallbackInstrumentalExtractor(selector) { backend ->
            created += backend
            FakeRemover(backend, selector.events) { _, _ -> expected }
        }

        assertSame(expected, extractor.extract(mix) {})
        assertSame(expected, extractor.extract(mix) {})

        // The remover is reused while the backend does not change.
        assertEquals(listOf(WEBGPU), created)
    }

    @Test
    fun `retries whole song on next backend after failure`() = runTest {
        for (failure in listOf(OrtException("QNN graph failed"), BackendOutputException("QNN: silent output"))) {
            val selector = FakeSelector(QNN, mapOf(QNN to WEBGPU))
            val expected = output(0.7f)
            val removers = mutableListOf<FakeRemover>()
            val extractor = FallbackInstrumentalExtractor(selector) { backend ->
                FakeRemover(backend, selector.events) { _, onProgress ->
                    if (backend == QNN) {
                        onProgress(40)
                        throw failure
                    }
                    onProgress(100)
                    expected
                }.also { removers += it }
            }
            val progress = mutableListOf<Int>()

            val result = extractor.extract(mix) { progress += it }

            assertSame(expected, result)
            assertEquals(1, selector.failures.size)
            assertEquals(QNN, selector.failures.single().first)
            assertSame(failure, selector.failures.single().second)
            assertEquals(listOf(QNN, WEBGPU), removers.map { it.backend })
            assertTrue(removers[0].closed)
            assertFalse(removers[1].closed)
            assertEquals(listOf(40, 0, 100), progress)
        }
    }

    @Test
    fun `signals the backend retry instead of resetting the progress`() = runTest {
        val selector = FakeSelector(QNN, mapOf(QNN to WEBGPU))
        val extractor = FallbackInstrumentalExtractor(selector) { backend ->
            FakeRemover(backend, selector.events) { pcm, onProgress ->
                onProgress(40)
                if (backend == QNN) throw OrtException("QNN graph failed")
                onProgress(100)
                pcm
            }
        }
        val events = mutableListOf<String>()

        extractor.extract(mix, onProgress = { events += "$it" }, onBackendRetry = { events += "retry" })

        assertEquals(listOf("40", "retry", "40", "100"), events)
    }

    @Test
    fun `marks in progress and completed around a run`() = runTest {
        val selector = FakeSelector(QNN, emptyMap())
        val extractor = FallbackInstrumentalExtractor(selector) { backend ->
            FakeRemover(backend, selector.events) { pcm, _ -> pcm }
        }

        extractor.extract(mix) {}

        assertEquals(listOf("current", "inProgress:QNN", "run:QNN", "completed:QNN"), selector.events)
    }

    @Test
    fun `cancellation is not a backend failure`() = runTest {
        val selector = FakeSelector(QNN, mapOf(QNN to WEBGPU))
        val extractor = FallbackInstrumentalExtractor(selector) { backend ->
            FakeRemover(backend, selector.events) { _, _ -> throw CancellationException("user left") }
        }

        try {
            extractor.extract(mix) {}
            fail("CancellationException expected")
        } catch (e: CancellationException) {
            assertEquals("user left", e.message)
        }
        assertTrue(selector.failures.isEmpty())
    }

    @Test
    fun `cpu failure propagates`() = runTest {
        val selector = FakeSelector(WEBGPU, mapOf(WEBGPU to CPU))
        val extractor = FallbackInstrumentalExtractor(selector) { backend ->
            FakeRemover(backend, selector.events) { _, _ -> throw OrtException("$backend failed") }
        }
        val processor = SongProcessor(
            decoder = PcmDecoder { mix },
            extractor = extractor,
            saver = InstrumentalSaver { _, _ -> fail("must not save"); throw AssertionError() },
            ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
            computeDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )

        try {
            processor.process(android.net.Uri.parse("content://media/1"), "brano")
            fail("ProcessingException expected")
        } catch (e: ProcessingException) {
            assertEquals(ProcessingError.INFERENCE_FAILED, e.error)
            assertTrue(e.cause is OrtException)
        }
        assertEquals(ProcessingState.Failed(ProcessingError.INFERENCE_FAILED), processor.state.value)
        // Only the accelerator is reported; CPU is the last resort.
        assertEquals(listOf(WEBGPU), selector.failures.map { it.first })
    }

    @Test
    fun `other errors are not backend failures`() = runTest {
        val selector = FakeSelector(QNN, mapOf(QNN to WEBGPU))
        val extractor = FallbackInstrumentalExtractor(selector) { backend ->
            FakeRemover(backend, selector.events) { _, _ -> throw OutOfMemoryError() }
        }

        try {
            extractor.extract(mix) {}
            fail("OutOfMemoryError expected")
        } catch (_: OutOfMemoryError) {
        }
        assertTrue(selector.failures.isEmpty())
        assertEquals("completed:QNN", selector.events.last())
    }

    @Test
    fun `readiness and prepare delegate to the selector`() = runTest {
        val selector = FakeSelector(QNN, emptyMap())
        val extractor = FallbackInstrumentalExtractor(selector) { error("no remover needed") }

        selector.ready = false
        assertFalse(extractor.isReady())
        extractor.prepare()
        assertEquals(listOf("current"), selector.events)
    }
}
