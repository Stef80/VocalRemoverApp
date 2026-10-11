package com.stef.vocalremover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

@Composable
fun PlayerScreen(
    onBack: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onForward: () -> Unit = {},
    onOpenImportScreen: () -> Unit = {},
    onOpenLegacyScreen: () -> Unit = {},
) {
    val backDescription = stringResource(R.string.control_back_description)
    val playDescription = stringResource(R.string.control_play_description)
    val pauseDescription = stringResource(R.string.control_pause_description)
    val forwardDescription = stringResource(R.string.control_forward_description)

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = onBack,
                    modifier = Modifier
                        .semantics { contentDescription = backDescription }
                ) {
                    Text("<<")
                }
                FloatingActionButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .semantics { contentDescription = playDescription }
                ) {
                    Text(">")
                }
                FloatingActionButton(
                    onClick = onPause,
                    modifier = Modifier
                        .semantics { contentDescription = pauseDescription }
                ) {
                    Text("||")
                }
                FloatingActionButton(
                    onClick = onForward,
                    modifier = Modifier
                        .semantics { contentDescription = forwardDescription }
                ) {
                    Text(">>")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row (
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ){
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.logo_corallo_blu),
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                )
                Column {
                    Text(
                        text = stringResource(R.string.player_now_playing),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.player_track_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    text = sampleLyrics,
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenImportScreen,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.player_import_song))
            }
            TextButton(
                onClick = onOpenLegacyScreen,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.player_open_legacy_screen))
            }
        }
    }
}

private const val sampleLyrics = """
Questo e lo spazio dedicato ai testi della canzone.

Qui puoi mostrare strofa, ritornello, bridge e outro.
La card e volutamente ampia e scorrevole per testi lunghi.

Strofa 1
Parole della canzone riga 1
Parole della canzone riga 2
Parole della canzone riga 3

Ritornello
Parole del ritornello riga 1
Parole del ritornello riga 2
Parole del ritornello riga 3

Strofa 2
Parole della canzone riga 1
Parole della canzone riga 2
Parole della canzone riga 3
"""

@Preview(showBackground = true)
@Composable
private fun PlayerScreenPreview() {
    VocalRemoverTheme {
        PlayerScreen()
    }
}

