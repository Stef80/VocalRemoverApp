package com.stef.vocalremover.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.example.vocalremover.processing.ProcessingError
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.editor.TrimEditorScreen
import com.stef.vocalremover.ui.library.LibraryRoute
import com.stef.vocalremover.ui.player.PlayerViewModel
import com.stef.vocalremover.ui.theme.VocalRemoverTheme
import kotlinx.coroutines.delay

/** Punto di accesso dell'app: splash, poi le schede Libreria / Cattura / Player del footer. */
class MainActivity : ComponentActivity() {

    /**
     * Trigger per passare alla scheda Cattura, aggiornabile sia da `onCreate`
     * (avvio "a freddo", es. quando il bubble riapre l'app con un nuovo task dopo lo
     * stop della registrazione) sia da `onNewIntent` (istanza già viva, riusata grazie
     * a `FLAG_ACTIVITY_SINGLE_TOP`). Separato dagli state `rememberSaveable` della UI
     * perché questi ultimi vivono solo dentro `setContent` e non sono raggiungibili da
     * `onNewIntent`.
     */
    private var openImportScreenRequest by mutableStateOf(false)

    /** Cattura in attesa, editor ed elaborazione: sopravvive alle rotazioni dello schermo. */
    private val importViewModel: ImportViewModel by viewModels { ImportViewModel.Factory }

    /** Brano della Libreria aperto nel player. */
    private val playerViewModel: PlayerViewModel by viewModels { PlayerViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dopo la morte del processo il sistema riconsegna l'Intent originale (l'extra rimosso non
        // viene salvato): senza questo controllo la ricreazione riaprirebbe Cattura e l'editor.
        if (savedInstanceState == null && intent.consumeOpenImportScreenExtra()) {
            openImportScreenRequest = true
            importViewModel.loadPendingCapture(openEditor = true)
        }
        val midnightBlue = android.graphics.Color.rgb(25, 38, 59)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(midnightBlue),
            navigationBarStyle = SystemBarStyle.dark(midnightBlue)
        )
        setContent {
            VocalRemoverTheme {
                var showIntroduction by rememberSaveable { mutableStateOf(true) }
                var tab by rememberSaveable { mutableStateOf(AppTab.Library) }
                val player by playerViewModel.state.collectAsState()
                val importState by importViewModel.importState.collectAsState()
                val editorState by importViewModel.editorState.collectAsState()
                val playerState by importViewModel.playerState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val current = AppNavigation.resolve(tab, hasTrack = player != null)
                // Scheda letta al momento della chiamata, non `current`: BackHandler (activity 1.13)
                // ed effetti possono invocare una closure di una composizione precedente.
                fun liveTab() = AppNavigation.resolve(tab, hasTrack = playerViewModel.state.value != null)
                // Unico punto di cambio scheda: la riproduzione della scheda lasciata va in pausa.
                fun selectTab(new: AppTab) {
                    val from = liveTab()
                    if (new == from) return
                    when (from) {
                        AppTab.Player -> playerViewModel.leave()
                        AppTab.Capture -> importViewModel.pausePreview()
                        AppTab.Library -> Unit
                    }
                    tab = new
                }
                val playerEnabled = player != null
                // Stabile tra un tick di posizione e l'altro del player, che ricompone questa root.
                val bottomBar: @Composable () -> Unit = remember(current, playerEnabled) {
                    { AppBottomBar(selected = current, playerEnabled = playerEnabled, onSelect = ::selectTab) }
                }
                // Ogni scheda conserva il proprio stato salvabile (scroll, finestre) quando si cambia scheda.
                val tabStateHolder = rememberSaveableStateHolder()
                LaunchedEffect(Unit) {
                    importViewModel.events.collect { event ->
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(getString(event.messageRes()))
                    }
                }
                LaunchedEffect(showIntroduction) {
                    if (showIntroduction) {
                        delay(1_500L)
                        showIntroduction = false
                    }
                }
                LaunchedEffect(openImportScreenRequest) {
                    if (openImportScreenRequest) {
                        showIntroduction = false
                        selectTab(AppTab.Capture)
                        openImportScreenRequest = false
                    }
                }
                val editor = editorState
                BackHandler(enabled = !showIntroduction && editor == null && AppNavigation.back(current) != null) {
                    AppNavigation.back(liveTab())?.let(::selectTab)
                }
                if (showIntroduction) {
                    SplashScreen()
                } else if (editor != null) {
                    TrimEditorScreen(
                        state = editor,
                        onNameChange = importViewModel::onNameChange,
                        onHandlesChange = importViewModel::onHandlesChange,
                        onHandleReleased = importViewModel::onHandleReleased,
                        onSeek = importViewModel::onSeek,
                        onTogglePlayback = importViewModel::togglePlayback,
                        onSave = importViewModel::saveEdit,
                        onClose = importViewModel::closeEditor,
                        snackbarHostState = snackbarHostState,
                    )
                } else tabStateHolder.SaveableStateProvider(current.name) {
                    when (current) {
                        AppTab.Library -> LibraryRoute(
                            onTrackClick = { track ->
                                playerViewModel.open(track)
                                selectTab(AppTab.Player)
                            },
                            onTrackRenamed = playerViewModel::onTrackRenamed,
                            onTrackDeleted = playerViewModel::onTrackDeleted,
                            bottomBar = bottomBar,
                        )
                        AppTab.Capture -> ImportSongScreen(
                            state = importState,
                            playerState = playerState,
                            onEdit = importViewModel::openEditor,
                            onDiscard = importViewModel::discard,
                            onProcess = importViewModel::process,
                            onPickFile = importViewModel::processFile,
                            onTogglePlayback = importViewModel::togglePlayback,
                            onSeek = importViewModel::onSeek,
                            snackbarHostState = snackbarHostState,
                            bottomBar = bottomBar,
                        )
                        AppTab.Player -> PlayerScreen(
                            state = player,
                            onRewind = playerViewModel::rewind,
                            onPlay = playerViewModel::play,
                            onPause = playerViewModel::pause,
                            onForward = playerViewModel::forward,
                            onSeek = playerViewModel::seekTo,
                            bottomBar = bottomBar,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.consumeOpenImportScreenExtra()) {
            openImportScreenRequest = true
            importViewModel.loadPendingCapture(openEditor = true)
        }
    }

    private fun Intent.consumeOpenImportScreenExtra(): Boolean {
        val requested = getBooleanExtra(EXTRA_OPEN_IMPORT_SCREEN, false)
        if (requested) removeExtra(EXTRA_OPEN_IMPORT_SCREEN)
        return requested
    }

    @StringRes
    private fun ImportEvent.messageRes(): Int = when (this) {
        ImportEvent.EditSaved -> R.string.import_event_edit_saved
        ImportEvent.EditFailed -> R.string.import_event_edit_failed
        ImportEvent.NameRequired -> R.string.import_event_name_required
        ImportEvent.CaptureUnavailable -> R.string.import_event_capture_unavailable
        ImportEvent.ProcessingDone -> R.string.import_event_processing_done
        ImportEvent.PreviewUnavailable -> R.string.import_event_preview_unavailable
        is ImportEvent.ProcessingFailed -> when (error) {
            ProcessingError.DECODE_FAILED ->
                if (fromFile) R.string.import_event_file_decode_failed else R.string.import_event_decode_failed
            ProcessingError.INFERENCE_FAILED -> R.string.import_event_inference_failed
            ProcessingError.SAVE_FAILED -> R.string.import_event_save_failed
        }
    }

    companion object {
        /** Impostato da `BubbleOverlayService` quando la registrazione termina, per
         * riportare in primo piano la scheda Cattura indipendentemente da dove si
         * trovasse l'utente prima di avviare la cattura (o dallo stato di default
         * dell'app se l'istanza dell'Activity era nel frattempo terminata). */
        const val EXTRA_OPEN_IMPORT_SCREEN = "open_import_screen"
    }
}

