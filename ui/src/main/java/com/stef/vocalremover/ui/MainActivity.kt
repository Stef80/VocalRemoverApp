package com.stef.vocalremover.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.vocalremover.MainActivity as LegacyMainActivity
import com.stef.vocalremover.ui.theme.VocalRemoverTheme
import kotlinx.coroutines.delay

/** Punto di accesso dell'app. La schermata XML precedente resta raggiungibile finché non viene migrata. */
class MainActivity : ComponentActivity() {

    /**
     * Trigger di navigazione verso `ImportSongScreen`, aggiornabile sia da `onCreate`
     * (avvio "a freddo", es. quando il bubble riapre l'app con un nuovo task dopo lo
     * stop della registrazione) sia da `onNewIntent` (istanza già viva, riusata grazie
     * a `FLAG_ACTIVITY_SINGLE_TOP`). Separato dagli state `rememberSaveable` della UI
     * perché questi ultimi vivono solo dentro `setContent` e non sono raggiungibili da
     * `onNewIntent`.
     */
    private var openImportScreenRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.consumeOpenImportScreenExtra()) {
            openImportScreenRequest = true
        }
        val midnightBlue = android.graphics.Color.rgb(25, 38, 59)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(midnightBlue),
            navigationBarStyle = SystemBarStyle.dark(midnightBlue)
        )
        setContent {
            VocalRemoverTheme {
                var showIntroduction by rememberSaveable { mutableStateOf(true) }
                var showImportScreen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(showIntroduction) {
                    if (showIntroduction) {
                        delay(1_500L)
                        showIntroduction = false
                    }
                }
                LaunchedEffect(openImportScreenRequest) {
                    if (openImportScreenRequest) {
                        showIntroduction = false
                        showImportScreen = true
                        openImportScreenRequest = false
                    }
                }
                if (showIntroduction) {
                    SplashScreen()
                } else if (showImportScreen) {
                    ImportSongScreen(onBack = { showImportScreen = false })
                } else {
                    PlayerScreen(
                        onOpenImportScreen = { showImportScreen = true },
                        onOpenLegacyScreen = {
                            startActivity(Intent(this, LegacyMainActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.consumeOpenImportScreenExtra()) {
            openImportScreenRequest = true
        }
    }

    private fun Intent.consumeOpenImportScreenExtra(): Boolean {
        val requested = getBooleanExtra(EXTRA_OPEN_IMPORT_SCREEN, false)
        if (requested) removeExtra(EXTRA_OPEN_IMPORT_SCREEN)
        return requested
    }

    companion object {
        /** Impostato da `BubbleOverlayService` quando la registrazione termina, per
         * riportare in primo piano `ImportSongScreen` indipendentemente da dove si
         * trovasse l'utente prima di avviare la cattura (o dallo stato di default
         * dell'app se l'istanza dell'Activity era nel frattempo terminata). */
        const val EXTRA_OPEN_IMPORT_SCREEN = "open_import_screen"
    }
}

