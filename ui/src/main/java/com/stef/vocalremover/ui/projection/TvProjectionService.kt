package com.stef.vocalremover.ui.projection

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ServiceCompat
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.MainActivity
import com.stef.vocalremover.ui.VocalRemoverApplication
import com.stef.vocalremover.ui.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground `mediaPlayback` service that shows the karaoke on the presentation display and publishes a
 * `MediaSession` with its notification. Not sticky: after a process kill nothing is restored. It is driven by
 * [ServiceProjectionHost]; the state lives in [com.stef.vocalremover.ui.projection.ProjectionController].
 */
class TvProjectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var presentation: TvPresentation? = null
    private var mediaSession: MediaSession? = null
    private var stateJob: Job? = null
    private var displayName = ""

    private val app get() = application as VocalRemoverApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> show(intent.getIntExtra(EXTRA_DISPLAY_ID, -1), startId)
            // Internal, from ServiceProjectionHost.hide(): never touches the controller, so a SHOW queued after it
            // is not cancelled. stopSelf(startId) does nothing if a newer start is pending: that SHOW rebuilds it.
            ACTION_HIDE -> {
                release()
                stopSelf(startId)
            }
            // From the notification: the controller ends the projection and sends HIDE through the host.
            ACTION_STOP -> {
                val wasProjecting = app.projectionController.isProjecting
                app.projectionController.stop()
                if (!wasProjecting) {
                    release()
                    stopSelf(startId)
                }
            }
            ACTION_TOGGLE, ACTION_REWIND, ACTION_FORWARD -> {
                when (intent.action) {
                    ACTION_TOGGLE -> app.playerSession.togglePlayback()
                    ACTION_REWIND -> app.playerSession.rewind()
                    else -> app.playerSession.forward()
                }
                // Without a presentation this is a plain service: do not leave it up.
                if (presentation == null) stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        release()
        scope.cancel()
    }

    // The constant is inlined at compile time; below API 29 ServiceCompat ignores the type.
    @SuppressLint("InlinedApi")
    private fun show(displayId: Int, startId: Int) {
        val session = ensureMediaSession()
        val display = getSystemService(DisplayManager::class.java)
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId == displayId }
        displayName = display?.name.orEmpty()
        // Always called, within 5 seconds of startForegroundService, even if the display has vanished.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(session, app.playerSession.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (display == null) {
            Log.w(TAG, "Presentation display $displayId is gone")
            abort(startId)
            return
        }
        // Stopped between the start request and now: do not flash the view on the TV.
        if (!app.projectionController.isProjecting) {
            release()
            stopSelf(startId)
            return
        }
        replacePresentation(null)
        val shown = TvPresentation(this, display) {
            val state by app.playerSession.state.collectAsState()
            TvLyricsScreen(state)
        }
        // Dismissed by the system (not by release(), which clears the field first): the projection is over.
        // When the display goes away the controller already handled it (the source's DisplayListener is registered
        // first: pause + DISCONNECTED), so this stop is a no-op then; the instrumented test covers that order.
        shown.setOnDismissListener {
            if (presentation === shown) {
                presentation = null
                app.projectionController.stop()
            }
        }
        try {
            shown.show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "Presentation rejected", e)
            abort(startId)
            return
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Presentation display invalid", e)
            abort(startId)
            return
        }
        presentation = shown
        startPublishing(session)
    }

    /** Closes the current presentation on purpose: the field is cleared first so the dismiss listener stays quiet. */
    private fun replacePresentation(next: TvPresentation?) {
        val old = presentation
        presentation = next
        old?.dismiss()
    }

    private fun abort(startId: Int) {
        app.projectionController.stop()
        release()
        stopSelf(startId)
    }

    private fun release() {
        stateJob?.cancel()
        stateJob = null
        replacePresentation(null)
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureMediaSession(): MediaSession = mediaSession ?: MediaSession(this, MEDIA_SESSION_TAG).also {
        it.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = app.playerSession.play()
            override fun onPause() = app.playerSession.pause()
            override fun onRewind() = app.playerSession.rewind()
            override fun onFastForward() = app.playerSession.forward()
            override fun onSeekTo(pos: Long) = app.playerSession.seekTo(pos)
            override fun onStop() = app.projectionController.stop()
            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    ACTION_REWIND -> app.playerSession.rewind()
                    ACTION_FORWARD -> app.playerSession.forward()
                    ACTION_STOP -> app.projectionController.stop()
                }
            }
        })
        it.isActive = true
        mediaSession = it
    }

    /** Position goes to the session on every change, the notification is rebuilt only when it would look different. */
    private fun startPublishing(session: MediaSession) {
        stateJob?.cancel()
        stateJob = scope.launch {
            var last: PublishedPlayback? = null
            var published: Triple<String?, Int, Long>? = null
            var metadata: Pair<String?, Long>? = null
            app.playerSession.state.collect { ui ->
                val playback = sessionPlaybackFor(ui)
                val now = SystemClock.elapsedRealtime()
                if (shouldPublish(last, playback, now)) {
                    last = PublishedPlayback(playback, now)
                    session.setPlaybackState(playbackState(playback))
                }
                val meta = ui?.track?.title to (ui?.durationMs ?: 0L)
                if (meta != metadata) {
                    metadata = meta
                    session.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, meta.first)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.second)
                            .build()
                    )
                }
                val look = Triple(ui?.track?.title, playback.state, playback.actions)
                if (look != published) {
                    published = look
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(session, ui))
                }
            }
        }
    }

    /**
     * Speed 1 while playing so the system moves the position itself. Notification actions show only up to API 32;
     * from API 33 the media controls are built from the custom actions.
     */
    private fun playbackState(playback: SessionPlayback): PlaybackState {
        val builder = PlaybackState.Builder()
            .setState(playback.state, playback.positionMs, if (playback.state == PlaybackState.STATE_PLAYING) 1f else 0f)
            .setActions(playback.actions)
        if (playback.state != PlaybackState.STATE_NONE) {
            if (playback.actions != 0L) {
                builder.addCustomAction(customAction(ACTION_REWIND, R.string.control_rewind_description, R.drawable.ic_notification_rewind))
                builder.addCustomAction(customAction(ACTION_FORWARD, R.string.control_forward_description, R.drawable.ic_notification_forward))
            }
            builder.addCustomAction(customAction(ACTION_STOP, R.string.tv_projection_action_stop, R.drawable.ic_notification_stop_projection))
        }
        return builder.build()
    }

    private fun customAction(action: String, @StringRes title: Int, @DrawableRes icon: Int) =
        PlaybackState.CustomAction.Builder(action, getString(title), icon).build()

    private fun buildNotification(session: MediaSession, ui: PlayerUiState?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.tv_projection_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val playing = ui?.isPlaying == true
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_projection)
            .setContentTitle(ui?.track?.title ?: getString(R.string.app_name))
            .apply { if (displayName.isNotEmpty()) setContentText(getString(R.string.tv_projection_notification_text, displayName)) }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_notification_rewind, R.string.control_rewind_description, ACTION_REWIND))
            .addAction(
                if (playing) action(R.drawable.ic_notification_pause, R.string.control_pause_description, ACTION_TOGGLE)
                else action(R.drawable.ic_notification_play, R.string.control_play_description, ACTION_TOGGLE)
            )
            .addAction(action(R.drawable.ic_notification_forward, R.string.control_forward_description, ACTION_FORWARD))
            .addAction(action(R.drawable.ic_notification_stop_projection, R.string.tv_projection_action_stop, ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(@DrawableRes icon: Int, title: Int, action: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(title),
            PendingIntent.getService(
                this, action.hashCode(), commandIntent(this, action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    companion object {
        const val ACTION_SHOW = "com.stef.vocalremover.ui.projection.SHOW"
        /** Notification button "end projection": goes through the controller. */
        const val ACTION_STOP = "com.stef.vocalremover.ui.projection.STOP"
        /** Internal, sent by [ServiceProjectionHost.hide]: closes the view and the service, nothing else. */
        const val ACTION_HIDE = "com.stef.vocalremover.ui.projection.HIDE"
        const val ACTION_TOGGLE = "com.stef.vocalremover.ui.projection.TOGGLE"
        const val ACTION_REWIND = "com.stef.vocalremover.ui.projection.REWIND"
        const val ACTION_FORWARD = "com.stef.vocalremover.ui.projection.FORWARD"

        /** Id of the display for this connection only: it changes at every connection and is never stored. */
        const val EXTRA_DISPLAY_ID = "displayId"

        private const val TAG = "TvProjection"
        private const val CHANNEL_ID = "tv_projection"
        private const val NOTIFICATION_ID = 4344
        private const val MEDIA_SESSION_TAG = "VocalRemoverTv"

        fun showIntent(context: Context, displayId: Int): Intent =
            commandIntent(context, ACTION_SHOW).putExtra(EXTRA_DISPLAY_ID, displayId)

        fun hideIntent(context: Context): Intent = commandIntent(context, ACTION_HIDE)

        private fun commandIntent(context: Context, action: String): Intent =
            Intent(context, TvProjectionService::class.java).setAction(action)
    }
}
