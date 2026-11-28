package com.stef.vocalremover.ui

import android.app.Application
import android.util.Log
import com.example.vocalremover.backend.BackendSelector
import com.example.vocalremover.backend.ProbingBackendSelector
import com.example.vocalremover.lyrics.TrackLyricsRepository
import com.example.vocalremover.playback.LibraryPlayer
import com.stef.vocalremover.ui.player.PlayerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the process-wide [BackendSelector]. Nothing heavy happens in `onCreate`: the selector is
 * lazy and the probe starts only from [startBackendProbe], so Robolectric tests never load ORT.
 */
class VocalRemoverApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probeStarted = AtomicBoolean(false)

    val backendSelector: BackendSelector by lazy { ProbingBackendSelector.create(this) }

    /** Created on first use from the main thread, never in `onCreate`: it builds an ExoPlayer. */
    val playerSession: PlayerSession by lazy {
        PlayerSession(LibraryPreviewPlayer(LibraryPlayer(this)), TrackLyricsRepository.create(this)::get)
    }

    /** Picks the backend in the background (probing it on first launch). Idempotent. */
    fun startBackendProbe() {
        if (!probeStarted.compareAndSet(false, true)) return
        appScope.launch {
            try {
                Log.i(TAG, "Backend ready: ${backendSelector.current()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Backend probe failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "VocalRemoverApp"
    }
}
