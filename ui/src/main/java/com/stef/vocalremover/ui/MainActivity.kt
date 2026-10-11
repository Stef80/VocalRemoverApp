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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

