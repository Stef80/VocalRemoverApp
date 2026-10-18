package com.stef.vocalremover.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.vocalremover.playback.PlayerState
import com.example.vocalremover.ui.R

/** Card della cattura in attesa, al posto di "Registra audio interno": nome, Modifica, Scarta. */
@Composable
internal fun PendingCaptureCard(
    name: String,
    isTrimmed: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    CardSurface {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "🎙️", style = MaterialTheme.typography.titleLarge)
                Column {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isTrimmed) {
                        Text(
                            text = stringResource(R.string.import_capture_trimmed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { confirmDiscard = true }, enabled = enabled) {
                    Text(stringResource(R.string.import_capture_discard))
                }
                Button(onClick = onEdit, enabled = enabled) {
                    Text(stringResource(R.string.import_capture_edit))
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.import_discard_title)) },
            text = { Text(stringResource(R.string.import_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) {
                    Text(stringResource(R.string.import_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.import_discard_cancel))
                }
            },
        )
    }
}

/** Ascolto del risultato (tagliato o intero) prima di elaborare. */
@Composable
internal fun PreviewPlayerCard(
    playerState: PlayerState,
    playableUri: Uri,
    fallbackDurationMs: Long,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val playback = previewPlaybackFor(playerState, playableUri, fallbackDurationMs)
    val durationMs = playback.durationMs
    // Valore dello slider durante il trascinamento; il seek parte solo al rilascio.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val positionMs = dragging?.toLong() ?: playback.positionMs
    CardSurface {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(onClick = onTogglePlayback) {
                Icon(
                    painter = painterResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(
                        if (playback.isPlaying) R.string.control_pause_description else R.string.control_play_description
                    ),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = positionMs.coerceIn(0L, durationMs).toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(it.toLong()) }
                        dragging = null
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                )
                Text(
                    text = stringResource(
                        R.string.import_player_time,
                        formatMinutesSeconds(positionMs),
                        formatMinutesSeconds(durationMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Cosa mostra la card del player: lo stato condiviso vale solo se riguarda il file della card. */
internal data class PreviewPlayback(val isPlaying: Boolean, val positionMs: Long, val durationMs: Long)

internal fun previewPlaybackFor(state: PlayerState, playableUri: Uri, fallbackDurationMs: Long): PreviewPlayback {
    if (state.currentUri != playableUri) return PreviewPlayback(false, 0L, fallbackDurationMs)
    return PreviewPlayback(
        isPlaying = state.isPlaying,
        positionMs = state.positionMs,
        durationMs = state.durationMs.takeIf { it > 0 } ?: fallbackDurationMs,
    )
}

/** Avanzamento dell'elaborazione, al posto del player. */
@Composable
internal fun ProcessingProgressCard(phase: ProcessingPhase) {
    CardSurface {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when (phase) {
                    ProcessingPhase.Decoding -> stringResource(R.string.import_processing_decoding)
                    is ProcessingPhase.Running -> stringResource(R.string.import_processing_running, phase.percent)
                    ProcessingPhase.Saving -> stringResource(R.string.import_processing_saving)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (phase is ProcessingPhase.Running) {
                LinearProgressIndicator(
                    progress = { phase.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}
