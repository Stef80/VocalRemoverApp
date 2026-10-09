package com.stef.vocalremover.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Punto di accesso dell'app. La schermata XML precedente resta raggiungibile finché non viene migrata. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                HomeScreen(onOpenLegacyScreen = {
                    startActivity(Intent(this, com.example.vocalremover.MainActivity::class.java))
                })
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenLegacyScreen: () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Vocal Remover", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onOpenLegacyScreen, modifier = Modifier.padding(top = 24.dp)) {
                Text("Apri schermata attuale")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme { HomeScreen(onOpenLegacyScreen = {}) }
}
