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
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        /** Se il servizio di cattura non conferma l'annullamento entro questo tempo si chiude comunque. */
        private const val DISMISS_CANCEL_TIMEOUT_MS = 2_000L
        private const val DISMISS_TARGET_BOTTOM_MARGIN_DP = 48
        private const val DISMISS_HIT_RADIUS_DP = 72
        /** Metà del lato del bubble collassato (64.dp in `BubbleOverlayContent`). */
        private const val BUBBLE_RADIUS_DP = 32
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
    private var dismissView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultStore by lazy { CaptureTerminalResultStore(this) }
    private val captureSessionController by lazy { CaptureSessionController(this) }

    private var state by mutableStateOf(BubbleOverlayState())
    private var suggestions by mutableStateOf(emptyList<String>())
    private var dismissHovering by mutableStateOf(false)
    private var draggingWithDismissTarget = false
    /** Chiusura richiesta trascinando sulla X durante l'ascolto: si attende l'annullamento. */
    private var closeAfterCancel = false

    private val captureResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Durante l'annullamento l'esito "blocked"/"error" è atteso (nessun audio
            // acquisito): si chiude in silenzio invece di mostrare un errore.
            if (state.phase == BubbleOverlayPhase.CANCELLING &&
                intent.action != SystemAudioCaptureService.ACTION_CAPTURE_STOPPED &&
                intent.action != SystemAudioCaptureService.ACTION_CAPTURE_RECORDING_STARTED
            ) {
                onCaptureCancelled()
                if (closeAfterCancel) stopSelf()
                return
            }
            when (intent.action) {
                SystemAudioCaptureService.ACTION_CAPTURE_RECORDING_STARTED ->
                    transition { BubbleOverlayTransitions.audioDetected(it) }
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
                transition { BubbleOverlayTransitions.startListening(it) }
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
                addAction(SystemAudioCaptureService.ACTION_CAPTURE_RECORDING_STARTED)
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
        dismissView?.disposeComposition()
        dismissView?.let { runCatching { windowManager.removeView(it) } }
        dismissView = null
        mainHandler.removeCallbacksAndMessages(null)
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
                        onDragStart = ::onBubbleDragStart,
                        onDragEnd = ::onBubbleDragEnd,
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
            addDismissTarget(owner)
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
        if (draggingWithDismissTarget) dismissHovering = isBubbleOverDismissTarget()
    }

    /**
     * Finestra con la X in basso al centro, non toccabile: solo grafica. Resta nascosta
     * e compare durante il trascinamento del bubble.
     */
    private fun addDismissTarget(owner: OverlayLifecycleOwner) {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            visibility = View.GONE
            setContent {
                VocalRemoverTheme {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BubbleDismissTarget(hovering = dismissHovering)
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(DISMISS_TARGET_BOTTOM_MARGIN_DP).toInt()
        }
        // Senza la X il bubble funziona lo stesso: un errore qui non deve chiudere il widget.
        runCatching { windowManager.addView(view, params) }
            .onSuccess { dismissView = view }
    }

    private fun onBubbleDragStart() {
        if (!BubbleDismissPolicy.canDismiss(state.phase)) return
        val target = dismissView ?: return
        draggingWithDismissTarget = true
        dismissHovering = false
        target.visibility = View.VISIBLE
    }

    private fun onBubbleDragEnd() {
        if (!draggingWithDismissTarget) return
        val overTarget = isBubbleOverDismissTarget()
        draggingWithDismissTarget = false
        dismissHovering = false
        dismissView?.visibility = View.GONE
        // La registrazione può essere partita durante il trascinamento: si ricontrolla qui.
        if (overTarget && BubbleDismissPolicy.canDismiss(state.phase)) dismissBubble()
    }

    private fun isBubbleOverDismissTarget(): Boolean {
        val bubble = composeView ?: return false
        val target = dismissView ?: return false
        if (target.width == 0 || target.height == 0) return false
        val bubbleLocation = IntArray(2).also { bubble.getLocationOnScreen(it) }
        val targetLocation = IntArray(2).also { target.getLocationOnScreen(it) }
        val bubbleRadius = dpToPx(BUBBLE_RADIUS_DP)
        // Il bubble è in basso a sinistra della sua finestra (sopra c'è il fumetto).
        return BubbleDismissPolicy.isOverTarget(
            bubbleCenterX = bubbleLocation[0] + bubbleRadius,
            bubbleCenterY = bubbleLocation[1] + bubble.height - bubbleRadius,
            targetCenterX = targetLocation[0] + target.width / 2f,
            targetCenterY = targetLocation[1] + target.height / 2f,
            radius = dpToPx(DISMISS_HIT_RADIUS_DP)
        )
    }

    /** Chiude il widget senza aprire l'app; in ascolto annulla prima la cattura. */
    private fun dismissBubble() {
        if (!BubbleDismissPolicy.mustCancelCapture(state.phase)) {
            stopSelf()
            return
        }
        closeAfterCancel = true
        composeView?.visibility = View.GONE
        requestCaptureStop()
        mainHandler.postDelayed({ stopSelf() }, DISMISS_CANCEL_TIMEOUT_MS)
    }

    private fun dpToPx(dp: Int): Float = dp * resources.displayMetrics.density

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

    /** In registrazione ferma ed elabora; in ascolto annulla senza produrre nulla. */
    private fun requestCaptureStop() {
        transition {
            BubbleOverlayTransitions.requestStop(it) ?: BubbleOverlayTransitions.cancelListening(it)
        }
        captureSessionController.stopCapture()
    }

    private fun onCaptureCancelled() {
        resultStore.claim()?.let { claimedResult ->
            if (claimedResult.kind != CaptureTerminalKind.STOPPED) {
                resultStore.clearClaim(claimedResult.id)
            }
        }
        transition { BubbleOverlayTransitions.cancelled(it) }
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
        // Anche l'ascolto è una cattura attiva: le altre schermate devono poterla fermare.
        CaptureOverlayStatus.update(
            next.phase == BubbleOverlayPhase.LISTENING || next.phase == BubbleOverlayPhase.RECORDING
        )
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
