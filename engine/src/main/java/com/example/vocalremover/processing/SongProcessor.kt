package com.example.vocalremover.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.capture.RecordingSaver
import com.example.vocalremover.decode.AudioDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

fun interface PcmDecoder { fun decode(uri: Uri): StereoPcm }

fun interface InstrumentalExtractor {
    suspend fun extract(mix: StereoPcm, onProgress: (Int) -> Unit): StereoPcm

    /**
     * Like [extract], and calls [onBackendRetry] when the song restarts from the beginning on
     * another backend; the progress reported afterwards belongs to the new run.
     */
    suspend fun extract(mix: StereoPcm, onProgress: (Int) -> Unit, onBackendRetry: () -> Unit): StereoPcm =
        extract(mix, onProgress)

    /** False while [prepare] still has slow one-off work to do (e.g. the backend probe). */
    fun isReady(): Boolean = true

    /** Does the one-off work [isReady] reports as pending; may take tens of seconds. */
    suspend fun prepare() {}
}

fun interface InstrumentalSaver { fun save(pcm: StereoPcm, displayName: String): Uri }

/**
 * From the chosen file to the instrumental saved in Music/VocalRemover: decode → removeVocals →
 * WAV save. One processing run at a time; main-safe.
 */
class SongProcessor(
    private val decoder: PcmDecoder,
    private val extractor: InstrumentalExtractor,
    private val saver: InstrumentalSaver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    companion object {
        private const val TAG = "SongProcessor"

        /** Wires the real AudioDecoder and RecordingSaver around [extractor]. */
        fun create(context: Context, extractor: InstrumentalExtractor): SongProcessor {
            val appContext = context.applicationContext
            return SongProcessor(
                decoder = PcmDecoder(AudioDecoder(appContext)::decode),
                extractor = extractor,
                saver = InstrumentalSaver { pcm, name ->
                    RecordingSaver.saveProcessedStereoAsWav(appContext, pcm, name)
                },
            )
        }
    }

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state.asStateFlow()

    private val busy = AtomicBoolean(false)

    /**
     * Returns the saved Uri. On error it sets [ProcessingState.Failed] and throws
     * [ProcessingException]; if cancelled it goes back to [ProcessingState.Idle].
     */
    suspend fun process(source: Uri, displayName: String): Uri {
        // Reject before touching the state: the state of the running processing stays consistent.
        check(busy.compareAndSet(false, true)) { "Processing already in progress" }
        var phase = ProcessingError.DECODE_FAILED
        try {
            _state.value = ProcessingState.Decoding
            // Reference cleared as soon as the instrumental is extracted: no two whole tracks in RAM
            // while saving.
            var mix: StereoPcm? = withContext(ioDispatcher) { decoder.decode(source) }

            phase = ProcessingError.INFERENCE_FAILED
            if (!extractor.isReady()) {
                _state.value = ProcessingState.PreparingBackend
                withContext(computeDispatcher) { extractor.prepare() }
            }
            _state.value = ProcessingState.Processing(0)
            val decoded = mix!!
            val instrumental = withContext(computeDispatcher) {
                // After a backend switch the retry text stays until the new run really advances:
                // its first report only says it started (VocalRemover reports 2% before the first chunk).
                var retryStart: Int? = null
                var retrying = false
                extractor.extract(
                    decoded,
                    onProgress = progress@{ percent ->
                        if (retrying) {
                            val start = retryStart
                            if (start == null && percent < 100) {
                                retryStart = percent
                                return@progress
                            }
                            if (start != null && percent <= start) return@progress
                            retrying = false
                        }
                        _state.value = ProcessingState.Processing(percent)
                    },
                    onBackendRetry = {
                        retrying = true
                        retryStart = null
                        _state.value = ProcessingState.RetryingBackend
                    },
                )
            }
            @Suppress("UNUSED_VALUE")
            mix = null

            phase = ProcessingError.SAVE_FAILED
            _state.value = ProcessingState.Saving
            // Once saving has started the file exists: we end in Done even if a cancellation
            // arrives, otherwise a WAV unknown to the UI would be left behind.
            return withContext(NonCancellable + ioDispatcher) {
                saver.save(instrumental, displayName).also { _state.value = ProcessingState.Done(it) }
            }
        } catch (e: CancellationException) {
            if (_state.value !is ProcessingState.Done) _state.value = ProcessingState.Idle
            throw e
        } catch (e: Throwable) {
            // Errors too (e.g. OutOfMemoryError on a very long track): the state must not
            // stay stuck halfway and the caller gets a normal ProcessingException.
            Log.e(TAG, "Processing failed at stage $phase: $source", e)
            _state.value = ProcessingState.Failed(phase)
            throw ProcessingException(phase, e)
        } finally {
            busy.set(false)
        }
    }
}
