package com.example.vocalremover.capture

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** Real [SessionSource]: requires notification access for [NowPlayingListenerService]. */
internal class MediaSessionSource(context: Context) : SessionSource {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NowPlayingListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun activeSessions(): List<SessionHandle> =
        manager.getActiveSessions(component).map(::ControllerHandle)

    override fun observeSessions(onChange: (List<SessionHandle>) -> Unit) {
        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onChange(controllers.orEmpty().map(::ControllerHandle))
        }
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        listener = sessionsListener
    }

    override fun stopObservingSessions() {
        listener?.let(manager::removeOnActiveSessionsChangedListener)
        listener = null
    }

    private inner class ControllerHandle(private val controller: MediaController) : SessionHandle {
        private var callback: MediaController.Callback? = null

        override val packageName: String get() = controller.packageName

        override fun metadata(): SessionMetadata? = controller.metadata?.toSessionMetadata()

        override fun playback(): PlaybackSnapshot? = controller.playbackState?.toSnapshot()

        override fun observe(listener: SessionListener) {
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) =
                    listener.onMetadata(metadata?.toSessionMetadata())

                override fun onPlaybackStateChanged(state: PlaybackState?) =
                    listener.onPlayback(state?.toSnapshot())
            }
            controller.registerCallback(cb, handler)
            callback = cb
        }

        override fun stopObserving() {
            callback?.let(controller::unregisterCallback)
            callback = null
        }
    }
}

private fun MediaMetadata.toSessionMetadata() = SessionMetadata(
    title = getString(MediaMetadata.METADATA_KEY_TITLE),
    artist = getString(MediaMetadata.METADATA_KEY_ARTIST),
    album = getString(MediaMetadata.METADATA_KEY_ALBUM),
    durationMs = getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
    mediaId = getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
)

private fun PlaybackState.toSnapshot() = PlaybackSnapshot(
    playing = state == PlaybackState.STATE_PLAYING,
    positionMs = position,
    lastPositionUpdateTime = lastPositionUpdateTime,
    speed = playbackSpeed,
)
