package com.example.vocalremover.lyrics

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stef.vocalremover.connections.Connections
import com.stef.vocalremover.connections.TrackQuery
import kotlin.random.Random

/** Looks up the lyrics of a saved track and updates its file. Decisions are in [LyricsFetchPolicy]. */
class LyricsFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private companion object {
        const val TAG = LYRICS_LOG_TAG
    }

    override suspend fun doWork(): Result {
        val savedUri = inputData.getString(WorkManagerLyricsScheduler.KEY_SAVED_URI) ?: return Result.failure()
        val attempt = inputData.getInt(WorkManagerLyricsScheduler.KEY_ATTEMPT, 1) + runAttemptCount
        val store = TrackLyricsRepository.lyricsStore(applicationContext)

        val pending = store.read(savedUri)?.takeIf { it.status == LyricsStatus.PENDING }
            ?: return Result.success() // track deleted or lyrics already downloaded
        // Look up with the session metadata, not with the name chosen by the user.
        val track = pending.track
        Log.i(TAG, "Lyrics lookup: ${track.artist} - ${track.title} (attempt $attempt)")
        val result = Connections.lyricsProvider(applicationContext) { Log.i(TAG, it) }
            .find(TrackQuery(track.title, track.artist, track.album, track.trackDurationMs))

        // Read again after the network call: the track may have been deleted meanwhile.
        val decision = LyricsFetchPolicy.decide(
            store.read(savedUri), result, attempt, System.currentTimeMillis(), Random::nextDouble,
        )
        return when (decision) {
            is FetchDecision.Save -> {
                store.write(decision.lyrics)
                // Never the lyrics in the logs: only status and source.
                Log.i(TAG, "Lyrics: ${decision.lyrics.status} ${decision.lyrics.sourceId.orEmpty()}")
                Result.success()
            }
            FetchDecision.Retry -> {
                Log.i(TAG, "Temporary error: new attempt later")
                Result.retry()
            }
            is FetchDecision.Reschedule -> {
                Log.i(TAG, "The service asks to wait ${decision.delayMs} ms")
                WorkManagerLyricsScheduler(applicationContext, store)
                    .reschedule(savedUri, decision.attempt, decision.delayMs)
                Result.success()
            }
            FetchDecision.Skip -> Result.success()
        }
    }
}
