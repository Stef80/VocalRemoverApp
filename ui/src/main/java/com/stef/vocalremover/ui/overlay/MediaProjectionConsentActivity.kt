package com.stef.vocalremover.ui.overlay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.vocalremover.capture.CaptureSessionController
import com.example.vocalremover.capture.NowPlayingAccess
import com.example.vocalremover.ui.R

/**
 * Transparent ("trampoline") Activity: the only reason it exists is that the
 * system `MediaProjectionManager` consent must be requested from
 * an Activity, while the floating widget lives in a Service
 * (`BubbleOverlayService`). It draws nothing: it asks for the needed permissions,
 * then the consent, starts the capture through `CaptureSessionController` on
 * success, reports the result with a local broadcast and closes right away.
 */
class MediaProjectionConsentActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_UID = "target_uid"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val ACTION_RESULT =
            "com.example.vocalremover.action.MEDIA_PROJECTION_CONSENT_RESULT"

        fun createIntent(context: Context, targetUid: Int): Intent =
            Intent(context, MediaProjectionConsentActivity::class.java).apply {
                putExtra(EXTRA_TARGET_UID, targetUid)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    private val captureSessionController by lazy { CaptureSessionController(this) }
    private var targetUid: Int = -1
    private val nowPlayingPrompt by lazy {
        NowPlayingAccessPrompt(getSharedPreferences("now_playing_access", MODE_PRIVATE)) {
            NowPlayingAccess.isGranted(this)
        }
    }

    // When coming back from Settings we go on anyway: the access is optional.
    private val notificationAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { requestMediaProjectionConsent() }

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionThenConsent()
        else finishWithFailure(getString(R.string.consent_error_record_audio_denied))
    }

    private val requestPostNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) askNowPlayingAccessThenConsent()
        else finishWithFailure(getString(R.string.consent_error_notifications_denied))
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            finishWithFailure(getString(R.string.consent_error_projection_denied))
            return@registerForActivityResult
        }
        try {
            captureSessionController.startCapture(result.resultCode, result.data!!, targetUid)
            finishWithSuccess()
        } catch (error: Exception) {
            finishWithFailure(error.message ?: getString(R.string.consent_error_service_start_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetUid = intent.getIntExtra(EXTRA_TARGET_UID, -1)
        if (targetUid < 0 || !captureSessionController.isSupported()) {
            finishWithFailure(getString(R.string.consent_error_unsupported))
            return
        }

        if (captureSessionController.hasRecordAudioPermission()) {
            requestNotificationPermissionThenConsent()
        } else {
            requestRecordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionThenConsent() {
        if (captureSessionController.needsPostNotificationsPermission()) {
            requestPostNotificationsPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askNowPlayingAccessThenConsent()
        }
    }

    /** On the first capture it explains what notification access is for; then asks for consent. */
    private fun askNowPlayingAccessThenConsent() {
        if (!nowPlayingPrompt.shouldAsk()) {
            requestMediaProjectionConsent()
            return
        }
        nowPlayingPrompt.markAsked()
        var openingSettings = false
        AlertDialog.Builder(this)
            .setIcon(R.drawable.logo_corallo_blu)
            .setTitle(R.string.now_playing_access_title)
            .setMessage(R.string.now_playing_access_message)
            .setPositiveButton(R.string.now_playing_access_open_settings) { _, _ ->
                openingSettings = true
                openSettingsOrContinue(
                    launch = {
                        notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onUnavailable = { requestMediaProjectionConsent() },
                )
            }
            .setNegativeButton(R.string.now_playing_access_later, null)
            .setOnDismissListener { if (!openingSettings) requestMediaProjectionConsent() }
            .show()
    }

    private fun requestMediaProjectionConsent() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjectionLauncher.launch(
            captureSessionController.createMediaProjectionConsentIntent(projectionManager)
        )
    }

    private fun finishWithSuccess() {
        sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName).putExtra(EXTRA_SUCCESS, true))
        finish()
    }

    private fun finishWithFailure(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        sendBroadcast(
            Intent(ACTION_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_SUCCESS, false)
                .putExtra(EXTRA_MESSAGE, message)
        )
        finish()
    }
}

