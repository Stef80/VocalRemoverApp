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
 * Logica di business (nessuna dipendenza da Activity/lifecycle) per avviare e
 * fermare la cattura audio di sistema. È l'unica implementazione di "registra
 * audio dall'app sorgente": ogni punto di ingresso che vuole avviare o fermare
 * `SystemAudioCaptureService` deve passare da qui invece di costruire i propri
 * Intent verso il servizio, per evitare che la logica diverga tra i punti di
 * ingresso (es. il widget flottante di `:ui` e la legacy MainActivity di
 * `:engine`, mantenuta solo come riferimento e destinata alla rimozione).
 */
class CaptureSessionController(context: Context) {

    private val appContext = context.applicationContext

    /** La cattura audio di sistema richiede `MediaProjectionManager`, disponibile da Android 10 (Q). */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Da Android 13 (Tiramisu) il canale di notifica del servizio foreground richiede il permesso esplicito. */
    fun needsPostNotificationsPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /** Intent di consenso di sistema da lanciare con `registerForActivityResult`. */
    fun createMediaProjectionConsentIntent(manager: MediaProjectionManager): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }

    /**
     * Avvia `SystemAudioCaptureService` con il consenso MediaProjection appena ottenuto
     * (`resultCode`/`resultData` restituiti dall'Activity che ha lanciato
     * [createMediaProjectionConsentIntent]) per l'app sorgente identificata da `targetUid`.
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

    /** Ferma la cattura in corso. Ritorna false se il servizio non ha potuto essere raggiunto. */
    fun stopCapture(): Boolean =
        appContext.startService(
            Intent(appContext, SystemAudioCaptureService::class.java)
                .setAction(SystemAudioCaptureService.ACTION_STOP)
        ) != null
}
