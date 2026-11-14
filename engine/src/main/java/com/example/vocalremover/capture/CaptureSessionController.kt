package com.example.vocalremover.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Business logic (no Activity/lifecycle dependencies) to start and
 * stop system audio capture. It is the only implementation of "record
 * audio from the source app": every entry point that wants to start or stop
 * `SystemAudioCaptureService` must go through here instead of building its own
 * Intents to the service, so the logic does not diverge between entry
 * points (e.g. the floating widget in `:ui`).
 */
class CaptureSessionController(context: Context) {

    private val appContext = context.applicationContext

    /** System audio capture requires `MediaProjectionManager`, available since Android 10 (Q). */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Since Android 13 (Tiramisu) the foreground service notification channel requires the explicit permission. */
    fun needsPostNotificationsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** System consent Intent to launch with `registerForActivityResult`. */
    fun createMediaProjectionConsentIntent(manager: MediaProjectionManager): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }

    /**
     * Starts `SystemAudioCaptureService` with the MediaProjection consent just obtained
     * (`resultCode`/`resultData` returned to the Activity that launched
     * [createMediaProjectionConsentIntent]) for the source app identified by `targetUid`.
     */
    fun startCapture(resultCode: Int, resultData: Intent, targetUid: Int) {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, SystemAudioCaptureService::class.java).apply {
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_DATA, resultData)
                putExtra(SystemAudioCaptureService.EXTRA_TARGET_UID, targetUid)
            }
        )
    }

    /** Stops the capture in progress. Returns false if the service could not be reached. */
    fun stopCapture(): Boolean =
        appContext.startService(
            Intent(appContext, SystemAudioCaptureService::class.java)
                .setAction(SystemAudioCaptureService.ACTION_STOP)
        ) != null
}
