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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.vocalremover.playback.PlayerError
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.player.PlayerUiState
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Player di un brano della Libreria. `<<` e `>>` spostano di 10 secondi; la freccia e il tasto
 * indietro di sistema tornano alla Libreria.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState? = null,
    onRewind: () -> Unit = {},
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onForward: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val rewindDescription = stringResource(R.string.control_rewind_description)
    val playDescription = stringResource(R.string.control_play_description)
    val pauseDescription = stringResource(R.string.control_pause_description)
    val forwardDescription = stringResource(R.string.control_forward_description)

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        bottomBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingActionButton(
                        onClick = onRewind,
                        modifier = Modifier
                            .semantics { contentDescription = rewindDescription }
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
                bottomBar()
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
                    painter = painterResource(R.drawable.logo_corallo_blu),
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
                        text = state?.track?.title ?: stringResource(R.string.player_track_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
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
            PlaybackProgress(state = state, onSeek = onSeek)
        }
    }
}

/** Barra trascinabile: durante il trascinamento mostra la posizione scelta, la applica al rilascio. */
@Composable
private fun PlaybackProgress(state: PlayerUiState?, onSeek: (Long) -> Unit) {
    val durationMs = state?.durationMs ?: 0L
    var dragPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = (dragPositionMs ?: state?.positionMs ?: 0L).coerceIn(0L, durationMs.coerceAtLeast(0L))
    val seekDescription = stringResource(R.string.player_seek_description)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (durationMs > 0) shownPositionMs.toFloat() / durationMs else 0f,
            onValueChange = { fraction -> dragPositionMs = (fraction * durationMs).toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let(onSeek)
                dragPositionMs = null
            },
            enabled = durationMs > 0,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = seekDescription }
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            val error = state?.error
            if (error != null) {
                Text(
                    text = stringResource(
                        when (error) {
                            PlayerError.SOURCE_UNAVAILABLE -> R.string.player_error_unavailable
                            PlayerError.PLAYBACK_FAILED -> R.string.player_error_failed
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = stringResource(
                    R.string.import_player_time,
                    formatMinutesSeconds(shownPositionMs),
                    formatMinutesSeconds(durationMs)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        PlayerScreen(
            state = PlayerUiState(
                track = previewLibraryTracks.first(),
                isPlaying = true,
                positionMs = 74_000L,
                durationMs = 222_000L,
                error = null,
            )
        )
    }
}

