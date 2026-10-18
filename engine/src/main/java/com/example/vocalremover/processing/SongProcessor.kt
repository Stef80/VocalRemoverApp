package com.example.vocalremover.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.VocalRemover
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
}

fun interface InstrumentalSaver { fun save(pcm: StereoPcm, displayName: String): Uri }

/**
 * Dal file scelto allo strumentale salvato in Music/VocalRemover: decodifica → removeVocals →
 * salvataggio WAV. Una sola elaborazione alla volta; main-safe.
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

        /** Collega AudioDecoder, VocalRemover e RecordingSaver reali. */
        fun create(context: Context, remover: VocalRemover): SongProcessor {
            val appContext = context.applicationContext
            return SongProcessor(
                decoder = PcmDecoder(AudioDecoder(appContext)::decode),
                extractor = InstrumentalExtractor(remover::removeVocals),
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
     * Restituisce l'Uri salvato. In caso di errore imposta [ProcessingState.Failed] e lancia
     * [ProcessingException]; se annullata torna a [ProcessingState.Idle].
     */
    suspend fun process(source: Uri, displayName: String): Uri {
        // Rifiuto prima di toccare lo stato: quello dell'elaborazione in corso resta coerente.
        check(busy.compareAndSet(false, true)) { "Elaborazione già in corso" }
        var phase = ProcessingError.DECODE_FAILED
        try {
            _state.value = ProcessingState.Decoding
            // Riferimento azzerato appena estratto lo strumentale: niente due brani interi in RAM
            // durante il salvataggio.
            var mix: StereoPcm? = withContext(ioDispatcher) { decoder.decode(source) }

            phase = ProcessingError.INFERENCE_FAILED
            _state.value = ProcessingState.Processing(0)
            val decoded = mix!!
            val instrumental = withContext(computeDispatcher) {
                extractor.extract(decoded) { percent -> _state.value = ProcessingState.Processing(percent) }
            }
            @Suppress("UNUSED_VALUE")
            mix = null

            phase = ProcessingError.SAVE_FAILED
            _state.value = ProcessingState.Saving
            // Iniziato il salvataggio il file esiste: si finisce in Done anche se arriva
            // l'annullamento, altrimenti resterebbe un WAV che la UI non conosce.
            return withContext(NonCancellable + ioDispatcher) {
                saver.save(instrumental, displayName).also { _state.value = ProcessingState.Done(it) }
            }
        } catch (e: CancellationException) {
            if (_state.value !is ProcessingState.Done) _state.value = ProcessingState.Idle
            throw e
        } catch (e: Throwable) {
            // Anche gli Error (es. OutOfMemoryError su un brano molto lungo): lo stato non deve
            // restare bloccato a metà e il chiamante riceve una ProcessingException normale.
            Log.e(TAG, "Elaborazione fallita in fase $phase: $source", e)
            _state.value = ProcessingState.Failed(phase)
            throw ProcessingException(phase, e)
        } finally {
            busy.set(false)
        }
    }
}
