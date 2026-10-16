package com.stef.vocalremover.ui.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.vocalremover.capture.CaptureSessionController
import com.example.vocalremover.ui.R

/**
 * Activity trasparente ("trampolino"): la sola ragione per cui esiste è che il
 * consenso di sistema `MediaProjectionManager` deve essere richiesto da
 * un'Activity, mentre il widget flottante vive in un Service
 * (`BubbleOverlayService`). Non disegna nulla: chiede i permessi necessari,
 * poi il consenso, avvia la cattura tramite `CaptureSessionController` in caso
 * di successo, notifica il risultato con un broadcast locale e si chiude subito.
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

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestNotificationPermissionThenConsent()
        else finishWithFailure(getString(R.string.consent_error_record_audio_denied))
    }

    private val requestPostNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestMediaProjectionConsent()
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
            requestMediaProjectionConsent()
        }
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

