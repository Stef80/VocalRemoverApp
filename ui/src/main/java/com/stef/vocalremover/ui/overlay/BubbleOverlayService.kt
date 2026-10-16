package com.stef.vocalremover.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.vocalremover.capture.CapturePackageDirectory
import com.example.vocalremover.capture.CapturePackageSuggestions
import com.example.vocalremover.capture.CaptureSessionController
import com.example.vocalremover.capture.CaptureTerminalKind
import com.example.vocalremover.capture.CaptureTerminalResultStore
import com.example.vocalremover.capture.SystemAudioCaptureService
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.MainActivity as LauncherMainActivity
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Foreground service che disegna il widget flottante di registrazione sopra
 * le altre app. Orchestration-only: non implementa la cattura audio (delegata
 * interamente a `SystemAudioCaptureService`, invariato) né il consenso
 * MediaProjection (delegato a `MediaProjectionConsentActivity`).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class BubbleOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "bubble_overlay_control"
        private const val NOTIFICATION_ID = 4343
        private const val CLOSE_REQUEST_CODE = 1
        private const val ERROR_DISPLAY_MS = 3_000L
        const val ACTION_CLOSE = "com.example.vocalremover.action.CLOSE_BUBBLE_OVERLAY"
        const val ACTION_REQUEST_STOP =
            "com.example.vocalremover.action.REQUEST_STOP_BUBBLE_CAPTURE"

        /** True se il permesso di overlay è già concesso su questo device. */
        fun canShowOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * Intent per chiedere al servizio di fermare la cattura in corso da un
         * punto di ingresso diverso dal bubble stesso (es. il pulsante "Ferma
         * registrazione" in `ImportSongScreen`).
         */
        fun requestStopIntent(context: Context): Intent =
            Intent(context, BubbleOverlayService::class.java).setAction(ACTION_REQUEST_STOP)

        /** Intent per chiedere all'utente il permesso di overlay nelle impostazioni di sistema. */
        fun overlayPermissionSettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var packageDirectory: CapturePackageDirectory
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var composeView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultStore by lazy { CaptureTerminalResultStore(this) }
    private val captureSessionController by lazy { CaptureSessionController(this) }

    private var state by mutableStateOf(BubbleOverlayState())
    private var suggestions by mutableStateOf(emptyList<String>())

    private val captureResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SystemAudioCaptureService.ACTION_CAPTURE_STOPPED -> onCaptureStopped()
                SystemAudioCaptureService.ACTION_CAPTURE_BLOCKED ->
                    onCaptureFailed(getString(R.string.bubble_error_capture_blocked))
                SystemAudioCaptureService.ACTION_CAPTURE_ERROR ->
                    onCaptureFailed(
                        intent.getStringExtra(SystemAudioCaptureService.EXTRA_ERROR_MESSAGE)
                            ?: getString(R.string.bubble_error_capture_failed)
                    )
            }
        }
    }

    private val consentResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(MediaProjectionConsentActivity.EXTRA_SUCCESS, false)
            if (success) {
                transition { BubbleOverlayTransitions.startRecording(it) }
            } else {
                onCaptureFailed(
                    intent.getStringExtra(MediaProjectionConsentActivity.EXTRA_MESSAGE)
                        ?: getString(R.string.consent_error_projection_denied)
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        packageDirectory = CapturePackageDirectory(this)
        suggestions = currentSuggestions()
        startForeground(NOTIFICATION_ID, buildNotification())
        ContextCompat.registerReceiver(
            this,
            captureResultReceiver,
            IntentFilter().apply {
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_STOPPED)
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_BLOCKED)
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_ERROR)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            consentResultReceiver,
            IntentFilter(MediaProjectionConsentActivity.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> stopSelf()
            ACTION_REQUEST_STOP -> requestCaptureStop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(captureResultReceiver) }
        runCatching { unregisterReceiver(consentResultReceiver) }
        composeView?.disposeComposition()
        composeView?.let { runCatching { windowManager.removeView(it) } }
        overlayLifecycleOwner?.detach()
        composeView = null
        overlayLifecycleOwner = null
        CaptureOverlayStatus.update(false)
        super.onDestroy()
    }

    private fun showBubble() {
        val owner = OverlayLifecycleOwner().apply { attach() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                VocalRemoverTheme {
                    BubbleOverlayContent(
                        state = state,
                        suggestions = suggestions,
                        onDragDelta = ::moveBubble,
                        onPackagePicked = ::onAppSelected,
                        onStartRecording = ::requestMediaProjectionConsent,
                        onStopRecording = ::requestCaptureStop,
                    )
                }
            }
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        try {
            windowManager.addView(view, layoutParams)
            overlayLifecycleOwner = owner
            composeView = view
        } catch (error: Exception) {
            // Overlay revocato tra la richiesta di avvio e questo punto (TOCTOU):
            // senza questo guard l'eccezione risalirebbe fuori da onCreate() e
            // farebbe crashare il processo del servizio lasciando la notifica
            // foreground e i receiver registrati senza ripulirli.
            owner.detach()
            Toast.makeText(
                this,
                getString(R.string.bubble_error_overlay_unavailable),
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun moveBubble(deltaX: Float, deltaY: Float) {
        val view = composeView ?: return
        layoutParams.x += deltaX.toInt()
        layoutParams.y += deltaY.toInt()
        windowManager.updateViewLayout(view, layoutParams)
    }

    private fun currentSuggestions(): List<String> =
        CapturePackageSuggestions.orderedDisplayTexts(
            packageDirectory.readRecentPackages(),
            packageDirectory.candidateDisplayNames()
        )

    private fun onAppSelected(packageName: String) {
        val targetUid = packageDirectory.resolveTargetUid(packageName)
        if (targetUid == null) {
            onCaptureFailed(getString(R.string.bubble_error_app_not_found, packageName))
            return
        }
        packageDirectory.rememberPackage(packageName)
        transition { BubbleOverlayTransitions.selectApp(it, packageName, targetUid) }
        launchTargetApp(packageName)
    }

    private fun launchTargetApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    private fun requestMediaProjectionConsent() {
        state.packageName ?: return
        val targetUid = state.targetUid ?: return
        transition { BubbleOverlayTransitions.requestConsent(it) }
        startActivity(MediaProjectionConsentActivity.createIntent(this, targetUid))
    }

    private fun requestCaptureStop() {
        transition { BubbleOverlayTransitions.requestStop(it) }
        captureSessionController.stopCapture()
    }

    private fun onCaptureStopped() {
        transition { BubbleOverlayTransitions.stopped(it) }
        startActivity(
            Intent(this, LauncherMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LauncherMainActivity.EXTRA_OPEN_IMPORT_SCREEN, true)
        )
        stopSelf()
    }

    private fun onCaptureFailed(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        resultStore.claim()?.let { claimedResult ->
            if (claimedResult.kind != CaptureTerminalKind.STOPPED) {
                resultStore.clearClaim(claimedResult.id)
            }
        }
        transition { BubbleOverlayTransitions.failed(it, message) }
        mainHandler.postDelayed({
            transition { BubbleOverlayTransitions.dismissError(it) }
        }, ERROR_DISPLAY_MS)
    }

    private fun transition(update: (BubbleOverlayState) -> BubbleOverlayState?) {
        val next = update(state) ?: return
        state = next
        CaptureOverlayStatus.update(next.phase == BubbleOverlayPhase.RECORDING)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bubble_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val closeIntent = PendingIntent.getService(
            this,
            CLOSE_REQUEST_CODE,
            Intent(this, BubbleOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.bubble_notification_close), closeIntent)
            .build()
    }
}
