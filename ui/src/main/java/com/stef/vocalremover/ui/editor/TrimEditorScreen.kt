package com.stef.vocalremover.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.EditorUiState
import com.stef.vocalremover.ui.formatMinutesSeconds
import com.stef.vocalremover.ui.theme.VocalRemoverTheme
import kotlin.math.abs
import kotlin.math.sin

/** Editor di taglio della registrazione: nome, maniglie di inizio/fine, ascolto, Salva/Chiudi. */
@Composable
fun TrimEditorScreen(
    state: EditorUiState,
    onNameChange: (String) -> Unit,
    onHandlesChange: (Long, Long) -> Unit,
    onHandleReleased: (TrimHandle) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlayback: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    BackHandler(onBack = onClose)

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            EditorTopBar(onClose = onClose)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.editor_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            TrimWaveform(
                peaks = state.peaks,
                durationMs = state.durationMs,
                startMs = state.startMs,
                endMs = state.endMs,
                positionMs = state.positionMs,
                onHandlesChange = onHandlesChange,
                onHandleReleased = onHandleReleased,
                onSeek = onSeek,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TimeLabel(stringResource(R.string.editor_start, formatMinutesSeconds(state.startMs)))
                TimeLabel(stringResource(R.string.editor_end, formatMinutesSeconds(state.endMs)))
                TimeLabel(stringResource(R.string.editor_duration, formatMinutesSeconds(state.endMs - state.startMs)))
            }
            Spacer(Modifier.height(16.dp))
            FilledIconButton(
                onClick = onTogglePlayback,
                enabled = state.previewAvailable,
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                Icon(
                    painter = painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.control_pause_description else R.string.control_play_description
                    ),
                )
            }
            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.editor_close))
                }
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.editor_save), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EditorTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.control_back_description),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_corallo_blu),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF19263B)
@Composable
private fun TrimEditorScreenPreview() {
    val peaks = remember { FloatArray(200) { abs(sin(it / 7f)) * 0.8f } }
    VocalRemoverTheme {
        TrimEditorScreen(
            state = EditorUiState(
                name = "Registrazione_2026-10-18_1530",
                peaks = peaks,
                durationMs = 225_000,
                startMs = 12_000,
                endMs = 221_000,
                positionMs = 40_000,
                isPlaying = false,
                isSaving = false,
                previewAvailable = true,
            ),
            onNameChange = {},
            onHandlesChange = { _, _ -> },
            onHandleReleased = {},
            onSeek = {},
            onTogglePlayback = {},
            onSave = {},
            onClose = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}
