package com.example.vocalremover.capture

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.vocalremover.lyrics.LYRICS_LOG_TAG
import com.example.vocalremover.lyrics.LyricsScheduler
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.lyrics.TrackLyricsRepository
import com.example.vocalremover.lyrics.TrackLyricsStore
import com.example.vocalremover.lyrics.WorkManagerLyricsScheduler
import java.io.File
import java.util.UUID

/** Stopped capture waiting for processing, with the edits saved by the editor. */
data class PendingCapture(
    val id: String,
    val rawFile: File,
    val trimmedFile: File?,
    val displayName: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    /** Of the original capture. */
    val durationMs: Long,
    /** Tracks seen during the capture, relative to the untrimmed file. */
    val trackTimeline: CaptureTrackTimeline? = null,
) {
    val playableFile: File get() = trimmedFile ?: rawFile
}

/**
 * Facade used by `:ui` for the pending capture: combines [CaptureTerminalResultStore], the editor
 * draft, trimming and file deletion. All methods are blocking (I/O).
 */
interface PendingCaptureRepository {
    /** Pending STOPPED capture, with its draft (created if missing). null if there is none or if the WAV
     *  is gone/unreadable (in that case the result and the draft are discarded). */
    fun load(): PendingCapture?
    fun peaks(buckets: Int): FloatArray
    /** Saves name and handles; trims from the original or removes the trim. */
    fun saveEdit(displayName: String, startMs: Long, endMs: Long): PendingCapture
    /** PENDING → PROCESSING for this owner. false if it cannot be acquired. */
    fun beginProcessing(): Boolean
    /** markSaved, deletes the original and trimmed WAV, completeSavedHandoff, clears the draft. */
    fun completeProcessing(savedUri: Uri)
    /** Releases the capture after an error; files and draft are kept. */
    fun abortProcessing()
    /** Deletes the original and trimmed WAV, frees the store, clears the draft. */
    fun discard()
    /** Deletes from cacheDir the system_audio_capture_*.wav and *_tagliato.wav not belonging to the current capture. */
    fun cleanOrphans()

    companion object {
        fun create(context: Context): PendingCaptureRepository = DefaultPendingCaptureRepository(context)
    }
}

internal class DefaultPendingCaptureRepository(
    context: Context,
    private val cacheDir: File = context.cacheDir,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lyricsStore: TrackLyricsStore = TrackLyricsRepository.lyricsStore(context),
    private val lyricsScheduler: LyricsScheduler = WorkManagerLyricsScheduler(context.applicationContext, lyricsStore),
) : PendingCaptureRepository {

    private companion object {
        const val TAG = "PendingCaptureRepo"
        val ORPHAN_PATTERN = Regex("""system_audio_capture_.*\.wav|.*_tagliato\.wav(\.tmp)?""")
    }

    private val resultStore = CaptureTerminalResultStore(context)
    private val draftStore = CaptureDraftStore(context)
    private val ownerId = UUID.randomUUID().toString()

    /** Id of the capture whose lease this instance holds in the store. */
    private var leasedResultId: String? = null

    @Synchronized
    override fun load(): PendingCapture? {
        val result = resultStore.peekStoppedResult() ?: return null
        val raw = File(checkNotNull(result.outputPath))
        if (result.state == CaptureTerminalState.SAVED) {
            // Processing already saved but cleanup interrupted (e.g. app closed): finish it.
            result.savedOutputUri?.let { scheduleLyrics(result, it) }
            finishSavedHandoff(result.id, raw)
            return null
        }
        val durationMs = try {
            WavFileReader.durationMs(WavFileReader.readInfo(raw))
        } catch (failure: Exception) {
            Log.w(TAG, "Capture WAV unreadable, discarding it: ${raw.path}", failure)
            discardResult(result.id, raw)
            return null
        }
        return toPendingCapture(result, raw, draftFor(result, durationMs), durationMs)
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
        val previous = draftFor(result, durationMs)

        val trimmed = if (startMs == 0L && endMs == durationMs) {
            null
        } else {
            File(cacheDir, "${RecordingFileNaming.trimmedName(name)}.wav").also {
                WavTrimmer.trim(raw, it, startMs, endMs)
            }
        }
        val draft = CaptureDraft(result.id, name, trimmed?.path, startMs, endMs)
        draftStore.write(draft)
        // The old trim is deleted only after the new one is written and recorded.
        previous.trimmedPath?.takeIf { it != trimmed?.path }?.let { File(it).delete() }
        return toPendingCapture(result, raw, draft, durationMs)
    }

    @Synchronized
    override fun beginProcessing(): Boolean {
        val result = resultStore.peekStoppedResult() ?: return false
        return result.state != CaptureTerminalState.SAVED && acquire(result.id)
    }

    @Synchronized
    override fun completeProcessing(savedUri: Uri) {
        val resultId = checkNotNull(leasedResultId) { "Processing not started" }
        val result = checkNotNull(resultStore.peekStoppedResult()?.takeIf { it.id == resultId }) {
            "Capture no longer present: $resultId"
        }
        check(resultStore.markSaved(resultId, ownerId, savedUri.toString())) {
            "Cannot record the save of capture $resultId"
        }
        scheduleLyrics(result, savedUri.toString())
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
                if (!orphan.delete()) Log.w(TAG, "Cannot delete ${orphan.path}")
            }
    }

    /**
     * Writes the pending lyrics file and queues the download. Called after markSaved and before
     * the cleanup, so the recovery branch of [load] repeats it if the app closes midway.
     * An error here must not fail the save, which has already happened.
     */
    private fun scheduleLyrics(result: CaptureTerminalResult, savedUri: String) {
        val timeline = result.trackTimeline ?: run {
            Log.i(LYRICS_LOG_TAG, "No track metadata: no lyrics lookup")
            return
        }
        try {
            // Without a draft (recovery before the editor) the whole file counts.
            val draft = draftStore.read(result.id)
            val track = TrackTimelineResolver.resolve(
                timeline, draft?.trimStartMs ?: 0L, draft?.trimEndMs ?: Long.MAX_VALUE
            ) ?: return
            if (!lyricsStore.exists(savedUri)) {
                lyricsStore.write(TrackLyrics(savedUri, track, LyricsStatus.PENDING))
            }
            // The worker does not search again if the lyrics were already downloaded.
            lyricsScheduler.schedule(savedUri)
            Log.i(
                LYRICS_LOG_TAG,
                "Lyrics lookup queued: ${track.artist} - ${track.title}, duration ${track.trackDurationMs} ms, " +
                    "position at the start of the file ${track.songOffsetMs} ms"
            )
        } catch (failure: Exception) {
            Log.w(TAG, "Cannot prepare the lyrics download", failure)
        }
    }

    private fun currentRawFile(): File =
        File(checkNotNull(resultStore.peekStoppedResult()?.outputPath) { "Nessuna cattura in attesa" })

    private fun draftFor(result: CaptureTerminalResult, durationMs: Long): CaptureDraft {
        val captureId = result.id
        val existing = draftStore.read(captureId)
        val draft = when {
            existing == null -> initialDraft(result, durationMs)
            existing.trimmedPath != null && !File(existing.trimmedPath).exists() ->
                existing.copy(trimmedPath = null, trimStartMs = 0L, trimEndMs = durationMs)
            else -> return existing
        }
        draftStore.write(draft)
        return draft
    }

    /**
     * First draft: name from the recognized track and trim proposed from the segments (leading
     * ad and following video excluded). The original file stays intact and editable.
     */
    private fun initialDraft(result: CaptureTerminalResult, durationMs: Long): CaptureDraft {
        val timeline = result.trackTimeline
        val (start, end) = TrackTimelineResolver.initialRange(timeline, durationMs)
        val name = timeline?.let { TrackTimelineResolver.resolve(it, start, end) }
            ?.let(RecordingFileNaming::nameFromTrack)
            ?: RecordingFileNaming.defaultName(clock())
        if (start == 0L && end == durationMs) return CaptureDraft(result.id, name, null, 0L, durationMs)
        return try {
            val trimmed = File(cacheDir, "${RecordingFileNaming.trimmedName(name)}.wav")
            WavTrimmer.trim(File(checkNotNull(result.outputPath)), trimmed, start, end)
            Log.i(LYRICS_LOG_TAG, "Trim proposed from the segments: $start-$end ms of $durationMs")
            CaptureDraft(result.id, name, trimmed.path, start, end)
        } catch (failure: Exception) {
            Log.w(TAG, "Auto-trim failed, proposing the whole file", failure)
            CaptureDraft(result.id, name, null, 0L, durationMs)
        }
    }

    private fun toPendingCapture(result: CaptureTerminalResult, raw: File, draft: CaptureDraft, durationMs: Long) =
        PendingCapture(
            id = result.id,
            rawFile = raw,
            trimmedFile = draft.trimmedPath?.let(::File),
            displayName = draft.displayName,
            trimStartMs = draft.trimStartMs,
            trimEndMs = draft.trimEndMs,
            durationMs = durationMs,
            trackTimeline = result.trackTimeline,
        )

    /** Store lease for [resultId]; true even if this instance already owns it. */
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
            Log.w(TAG, "Capture $resultId in use by another owner: not discarding it")
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
            if (file.exists() && !file.delete()) Log.w(TAG, "Cannot delete ${file.path}")
        }
    }
}
