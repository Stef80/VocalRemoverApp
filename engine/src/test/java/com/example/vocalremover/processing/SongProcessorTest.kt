package com.example.vocalremover.processing

import android.net.Uri
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.decode.DecodeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongProcessorTest {

    private val source: Uri = Uri.parse("content://media/external/audio/media/source")
    private val saved: Uri = Uri.parse("content://media/external/audio/media/1")
    private val mix = StereoPcm(FloatArray(10) { it * 0.1f }, FloatArray(10))

    private val okDecoder = PcmDecoder { mix }
    private val okExtractor = InstrumentalExtractor { pcm, onProgress -> onProgress(50); pcm }
    private val okSaver = InstrumentalSaver { _, _ -> saved }

    private fun TestScope.processor(
        decoder: PcmDecoder = okDecoder,
        extractor: InstrumentalExtractor = okExtractor,
        saver: InstrumentalSaver = okSaver,
    ): SongProcessor {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return SongProcessor(decoder, extractor, saver, ioDispatcher = dispatcher, computeDispatcher = dispatcher)
    }

    private fun TestScope.recordStates(processor: SongProcessor): MutableList<ProcessingState> {
        val states = mutableListOf<ProcessingState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { processor.state.toList(states) }
        return states
    }

    private suspend fun expectFailure(processor: SongProcessor): ProcessingException =
        try {
            processor.process(source, "brano")
            fail("ProcessingException attesa")
            throw AssertionError()
        } catch (e: ProcessingException) {
            e
        }

    @Test
    fun `successful run goes through every state in order`() = runTest {
        val processor = processor()
        val states = recordStates(processor)

        val result = processor.process(source, "brano")

        assertEquals(saved, result)
        assertEquals(
            listOf(
                ProcessingState.Idle,
                ProcessingState.Decoding,
                ProcessingState.Processing(0),
                ProcessingState.Processing(50),
                ProcessingState.Saving,
                ProcessingState.Done(saved),
            ),
            states
        )
    }

    @Test
    fun `decode failure reports DECODE_FAILED`() = runTest {
        val processor = processor(decoder = PcmDecoder { throw DecodeException("rotto") })

        val error = expectFailure(processor)

        assertEquals(ProcessingError.DECODE_FAILED, error.error)
        assertEquals(ProcessingState.Failed(ProcessingError.DECODE_FAILED), processor.state.value)
    }

    @Test
    fun `inference failure reports INFERENCE_FAILED`() = runTest {
        val processor = processor(extractor = InstrumentalExtractor { _, _ -> throw RuntimeException("ORT") })

        val error = expectFailure(processor)

        assertEquals(ProcessingError.INFERENCE_FAILED, error.error)
        assertEquals(ProcessingState.Failed(ProcessingError.INFERENCE_FAILED), processor.state.value)
    }

    @Test
    fun `save failure reports SAVE_FAILED`() = runTest {
        val processor = processor(saver = InstrumentalSaver { _, _ -> throw IOException("disco pieno") })

        val error = expectFailure(processor)

        assertEquals(ProcessingError.SAVE_FAILED, error.error)
        assertEquals(ProcessingState.Failed(ProcessingError.SAVE_FAILED), processor.state.value)
    }

    @Test
    fun `out of memory while decoding reports DECODE_FAILED instead of escaping`() = runTest {
        val processor = processor(decoder = PcmDecoder { throw OutOfMemoryError("brano troppo lungo") })

        val error = expectFailure(processor)

        assertEquals(ProcessingError.DECODE_FAILED, error.error)
        assertEquals(ProcessingState.Failed(ProcessingError.DECODE_FAILED), processor.state.value)
    }

    @Test
    fun `out of memory while extracting reports INFERENCE_FAILED instead of escaping`() = runTest {
        val processor = processor(extractor = InstrumentalExtractor { _, _ -> throw OutOfMemoryError() })

        val error = expectFailure(processor)

        assertEquals(ProcessingError.INFERENCE_FAILED, error.error)
        assertEquals(ProcessingState.Failed(ProcessingError.INFERENCE_FAILED), processor.state.value)
    }

    @Test
    fun `cancellation during extraction returns to Idle`() = runTest {
        var saverCalled = false
        val processor = processor(
            extractor = InstrumentalExtractor { _, _ -> awaitCancellation() },
            saver = InstrumentalSaver { _, _ -> saverCalled = true; saved },
        )
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { processor.process(source, "brano") }
        assertEquals(ProcessingState.Processing(0), processor.state.value)

        job.cancel()
        job.join()

        assertEquals(ProcessingState.Idle, processor.state.value)
        assertFalse(saverCalled)
    }

    @Test
    fun `cancellation during saving still reports Done`() = runTest {
        lateinit var job: Job
        val processor = processor(saver = InstrumentalSaver { _, _ -> job.cancel(); saved })

        job = launch(UnconfinedTestDispatcher(testScheduler), start = kotlinx.coroutines.CoroutineStart.LAZY) {
            processor.process(source, "brano")
        }
        job.start()
        job.join()

        assertEquals(ProcessingState.Done(saved), processor.state.value)
    }

    @Test
    fun `second concurrent call is rejected`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val processor = processor(extractor = InstrumentalExtractor { pcm, _ -> gate.await(); pcm })
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { processor.process(source, "brano") }

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { processor.process(source, "altro") }
        }
        assertEquals(ProcessingState.Processing(0), processor.state.value)

        gate.complete(Unit)
        first.join()
        assertEquals(ProcessingState.Done(saved), processor.state.value)
    }

    @Test
    fun `process can run again after a failure`() = runTest {
        var fail = true
        val processor = processor(decoder = PcmDecoder { if (fail) throw DecodeException("rotto") else mix })
        expectFailure(processor)

        fail = false
        val result = coroutineScope { async { processor.process(source, "brano") }.await() }

        assertEquals(saved, result)
        assertEquals(ProcessingState.Done(saved), processor.state.value)
    }
}
