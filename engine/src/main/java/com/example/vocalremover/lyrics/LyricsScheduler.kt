package com.example.vocalremover.lyrics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Queues or cancels the lyrics download of a saved track. */
interface LyricsScheduler {
    fun schedule(savedUri: String)
    fun cancel(savedUri: String)
}

internal class WorkManagerLyricsScheduler(
    private val context: Context,
    private val store: TrackLyricsStore,
) : LyricsScheduler {

    companion object {
        const val KEY_SAVED_URI = "saved_uri"
        const val KEY_ATTEMPT = "attempt"
        private const val BACKOFF_SECONDS = 30L
    }

    override fun schedule(savedUri: String) =
        enqueue(savedUri, attempt = 1, delayMs = 0, ExistingWorkPolicy.KEEP)

    /** From the running worker: the new work starts after the current one has finished. */
    fun reschedule(savedUri: String, attempt: Int, delayMs: Long) =
        enqueue(savedUri, attempt, delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun cancel(savedUri: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(savedUri))
    }

    private fun enqueue(savedUri: String, attempt: Int, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<LyricsFetchWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SAVED_URI to savedUri, KEY_ATTEMPT to attempt))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(savedUri), policy, request)
    }

    private fun workName(savedUri: String) = "lyrics-${store.key(savedUri)}"
}
