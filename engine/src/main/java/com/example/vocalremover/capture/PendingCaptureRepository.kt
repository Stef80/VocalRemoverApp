package com.example.vocalremover.capture

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.UUID

/** Cattura ferma in attesa di elaborazione, con le modifiche salvate dall'editor. */
data class PendingCapture(
    val id: String,
    val rawFile: File,
    val trimmedFile: File?,
    val displayName: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    /** Della cattura originale. */
    val durationMs: Long,
) {
    val playableFile: File get() = trimmedFile ?: rawFile
}

/**
 * Facciata usata da `:ui` per la cattura in attesa: unisce [CaptureTerminalResultStore], la bozza
 * dell'editor, il taglio e la cancellazione dei file. Tutti i metodi sono bloccanti (I/O).
 */
interface PendingCaptureRepository {
    /** Cattura STOPPED in attesa, con la bozza (creata se manca). null se non c'è o se il WAV
     *  è sparito/illeggibile (in quel caso il risultato e la bozza vengono scartati). */
    fun load(): PendingCapture?
    fun peaks(buckets: Int): FloatArray
    /** Salva nome e maniglie; taglia dall'originale o rimuove il taglio. */
    fun saveEdit(displayName: String, startMs: Long, endMs: Long): PendingCapture
    /** PENDING → PROCESSING per questo proprietario. false se non acquisibile. */
    fun beginProcessing(): Boolean
    /** markSaved, cancella WAV originale e tagliato, completeSavedHandoff, svuota la bozza. */
    fun completeProcessing(savedUri: Uri)
    /** Rilascia la cattura dopo un errore; file e bozza restano. */
    fun abortProcessing()
    /** Cancella WAV originale e tagliato, libera lo store, svuota la bozza. */
    fun discard()
    /** Cancella da cacheDir i system_audio_capture_*.wav e *_tagliato.wav non della cattura corrente. */
    fun cleanOrphans()

    companion object {
        fun create(context: Context): PendingCaptureRepository = DefaultPendingCaptureRepository(context)
    }
}

internal class DefaultPendingCaptureRepository(
    context: Context,
    private val cacheDir: File = context.cacheDir,
    private val clock: () -> Long = System::currentTimeMillis,
) : PendingCaptureRepository {

    private companion object {
        const val TAG = "PendingCaptureRepo"
        val ORPHAN_PATTERN = Regex("""system_audio_capture_.*\.wav|.*_tagliato\.wav(\.tmp)?""")
    }

    private val resultStore = CaptureTerminalResultStore(context)
    private val draftStore = CaptureDraftStore(context)
    private val ownerId = UUID.randomUUID().toString()

    /** Id della cattura di cui questa istanza ha il lease nello store. */
    private var leasedResultId: String? = null

    @Synchronized
    override fun load(): PendingCapture? {
        val result = resultStore.peekStoppedResult() ?: return null
        val raw = File(checkNotNull(result.outputPath))
        if (result.state == CaptureTerminalState.SAVED) {
            // Elaborazione già salvata ma pulizia interrotta (es. app chiusa): la si completa.
            finishSavedHandoff(result.id, raw)
            return null
        }
        val durationMs = try {
            WavFileReader.durationMs(WavFileReader.readInfo(raw))
        } catch (failure: Exception) {
            Log.w(TAG, "WAV della cattura non leggibile, la scarto: ${raw.path}", failure)
            discardResult(result.id, raw)
            return null
        }
        return toPendingCapture(result.id, raw, draftFor(result.id, durationMs), durationMs)
    }

    @Synchronized
    override fun peaks(buckets: Int): FloatArray = WavWaveform.peaks(currentRawFile(), buckets)

    @Synchronized
    override fun saveEdit(displayName: String, startMs: Long, endMs: Long): PendingCapture {
        val name = displayName.trim()
        require(name.isNotEmpty()) { "Nome vuoto" }
        val result = checkNotNull(resultStore.peekStoppedResult()) { "Nessuna cattura in attesa" }
        val raw = File(checkNotNull(result.outputPath))
        val durationMs = WavFileReader.durationMs(WavFileReader.readInfo(raw))
        require(startMs in 0 until endMs && endMs <= durationMs) { "Intervallo non valido: $startMs..$endMs" }
        val previous = draftFor(result.id, durationMs)

        val trimmed = if (startMs == 0L && endMs == durationMs) {
            null
        } else {
            File(cacheDir, "${RecordingFileNaming.trimmedName(name)}.wav").also {
                WavTrimmer.trim(raw, it, startMs, endMs)
            }
        }
        val draft = CaptureDraft(result.id, name, trimmed?.path, startMs, endMs)
        draftStore.write(draft)
        // Il vecchio taglio si cancella solo dopo che il nuovo è scritto e registrato.
        previous.trimmedPath?.takeIf { it != trimmed?.path }?.let { File(it).delete() }
        return toPendingCapture(result.id, raw, draft, durationMs)
    }

    @Synchronized
    override fun beginProcessing(): Boolean {
        val result = resultStore.peekStoppedResult() ?: return false
        return result.state != CaptureTerminalState.SAVED && acquire(result.id)
    }

    @Synchronized
    override fun completeProcessing(savedUri: Uri) {
        val resultId = checkNotNull(leasedResultId) { "Elaborazione non iniziata" }
        val result = checkNotNull(resultStore.peekStoppedResult()?.takeIf { it.id == resultId }) {
            "Cattura non più presente: $resultId"
        }
        check(resultStore.markSaved(resultId, ownerId, savedUri.toString())) {
            "Impossibile registrare il salvataggio della cattura $resultId"
        }
        finishSavedHandoff(resultId, File(checkNotNull(result.outputPath)))
    }

    @Synchronized
    override fun abortProcessing() {
        val resultId = leasedResultId ?: return
        resultStore.releaseStoppedResult(resultId, ownerId)
        leasedResultId = null
    }

    @Synchronized
    override fun discard() {
        val result = resultStore.peekStoppedResult()
        if (result == null) {
            draftStore.clear()
            return
        }
        discardResult(result.id, File(checkNotNull(result.outputPath)))
    }

    @Synchronized
    override fun cleanOrphans() {
        val result = resultStore.peekStoppedResult()
        val keep = buildSet {
            result?.outputPath?.let { add(File(it).absolutePath) }
            result?.let { draftStore.read(it.id) }?.trimmedPath?.let { add(File(it).absolutePath) }
        }
        cacheDir.listFiles()
            ?.filter { it.isFile && ORPHAN_PATTERN.matches(it.name) && it.absolutePath !in keep }
            ?.forEach { orphan ->
                if (!orphan.delete()) Log.w(TAG, "Impossibile cancellare ${orphan.path}")
            }
    }

    private fun currentRawFile(): File =
        File(checkNotNull(resultStore.peekStoppedResult()?.outputPath) { "Nessuna cattura in attesa" })

    private fun draftFor(captureId: String, durationMs: Long): CaptureDraft {
        val existing = draftStore.read(captureId)
        val draft = when {
            existing == null ->
                CaptureDraft(captureId, RecordingFileNaming.defaultName(clock()), null, 0L, durationMs)
            existing.trimmedPath != null && !File(existing.trimmedPath).exists() ->
                existing.copy(trimmedPath = null, trimStartMs = 0L, trimEndMs = durationMs)
            else -> return existing
        }
        draftStore.write(draft)
        return draft
    }

    private fun toPendingCapture(id: String, raw: File, draft: CaptureDraft, durationMs: Long) =
        PendingCapture(
            id = id,
            rawFile = raw,
            trimmedFile = draft.trimmedPath?.let(::File),
            displayName = draft.displayName,
            trimStartMs = draft.trimStartMs,
            trimEndMs = draft.trimEndMs,
            durationMs = durationMs,
        )

    /** Lease dello store per [resultId]; true anche se questa istanza lo possiede già. */
    private fun acquire(resultId: String): Boolean {
        if (leasedResultId == resultId) return true
        val lease = resultStore.acquireStoppedResult(ownerId) ?: return false
        if (lease.result.id != resultId) {
            resultStore.releaseStoppedResult(lease.result.id, ownerId)
            return false
        }
        leasedResultId = resultId
        return true
    }

    private fun discardResult(resultId: String, raw: File) {
        val trimmedPath = draftStore.read(resultId)?.trimmedPath
        if (!acquire(resultId)) {
            Log.w(TAG, "Cattura $resultId in uso da un altro proprietario: non la scarto")
            return
        }
        deleteCaptureFiles(raw, trimmedPath)
        resultStore.discardStoppedResult(resultId, ownerId)
        draftStore.clear()
        leasedResultId = null
    }

    private fun finishSavedHandoff(resultId: String, raw: File) {
        val trimmedPath = draftStore.read(resultId)?.trimmedPath
        if (!acquire(resultId)) return
        deleteCaptureFiles(raw, trimmedPath)
        resultStore.completeSavedHandoff(resultId, ownerId)
        draftStore.clear()
        leasedResultId = null
    }

    private fun deleteCaptureFiles(raw: File, trimmedPath: String?) {
        listOfNotNull(raw, trimmedPath?.let(::File)).forEach { file ->
            if (file.exists() && !file.delete()) Log.w(TAG, "Impossibile cancellare ${file.path}")
        }
    }
}
