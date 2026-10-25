package com.stef.vocalremover.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.vocalremover.playback.PlayerState
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.overlay.BubbleOverlayService
import com.stef.vocalremover.ui.overlay.CaptureOverlayStatus
import com.stef.vocalremover.ui.theme.VocalRemoverTheme
import kotlinx.coroutines.launch

enum class ProcessingQuality {
    FAST,
    BALANCED,
    STUDIO;

    val labelRes: Int
        get() = when (this) {
            FAST -> R.string.import_quality_fast
            BALANCED -> R.string.import_quality_balanced
            STUDIO -> R.string.import_quality_studio
        }
}

data class ImportOptions(
    val quality: ProcessingQuality = ProcessingQuality.BALANCED,
    val autoSave: Boolean = true,
    val normalizeVolume: Boolean = true,
    val lightReverb: Boolean = false,
) {
    companion object {
        val Saver = listSaver<ImportOptions, Any>(
            save = { listOf(it.quality.name, it.autoSave, it.normalizeVolume, it.lightReverb) },
            restore = {
                ImportOptions(
                    quality = ProcessingQuality.valueOf(it[0] as String),
                    autoSave = it[1] as Boolean,
                    normalizeVolume = it[2] as Boolean,
                    lightReverb = it[3] as Boolean,
                )
            },
        )
    }
}

@Composable
fun ImportSongScreen(
    state: ImportUiState = ImportUiState.Empty,
    playerState: PlayerState = PlayerState(),
    onEdit: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onProcess: () -> Unit = {},
    onPickFile: (Uri) -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    bottomBar: @Composable () -> Unit = {},
) {
    var options by rememberSaveable(stateSaver = ImportOptions.Saver) { mutableStateOf(ImportOptions()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val overlayPermissionMessage = stringResource(R.string.import_overlay_permission_required)
    val captureRequiresAndroid10Message = stringResource(R.string.import_capture_requires_android_10)
    // Selettore di sistema come nel caricamento da file di `:engine`: nessun permesso storage richiesto.
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onPickFile)
    }
    val isProcessing = state is ImportUiState.Processing
    val startCaptureWidget: () -> Unit = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(captureRequiresAndroid10Message)
            }
        } else if (BubbleOverlayService.canShowOverlay(context)) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleOverlayService::class.java))
            // Nasconde l'app (senza chiuderla) così resta visibile solo il bubble sopra
            // la home o l'app da registrare. moveTaskToBack mantiene viva l'Activity, che
            // il bubble riporta in primo piano a fine registrazione (FLAG_ACTIVITY_SINGLE_TOP).
            context.findActivity()?.moveTaskToBack(true)
        } else {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(overlayPermissionMessage)
            }
            context.startActivity(BubbleOverlayService.overlayPermissionSettingsIntent(context))
        }
    }
    // Riflette lo stato del widget flottante (unico scrittore: BubbleOverlayService),
    // per poter avviare/fermare la stessa sessione di cattura anche da qui invece di
    // dover per forza interagire con il bubble.
    val isRecording by CaptureOverlayStatus.isRecording.collectAsState()
    val stopCaptureWidget: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.startForegroundService(context, BubbleOverlayService.requestStopIntent(context))
        }
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            ImportTopBar()
            Spacer(Modifier.height(16.dp))
            FilePickerCard(
                processingName = (state as? ImportUiState.Processing)?.takeIf { it.fromFile }?.name,
                enabled = !isProcessing,
                onBrowse = { pickAudio.launch(AUDIO_MIME_TYPE) },
            )
            Spacer(Modifier.height(24.dp))
            SectionTitle(R.string.import_record_section)
            Spacer(Modifier.height(8.dp))
            when (state) {
                ImportUiState.Empty ->
                    CaptureCard(isRecording, enabled = true, onStart = startCaptureWidget, onStop = stopCaptureWidget)
                is ImportUiState.CaptureReady ->
                    PendingCaptureCard(state.name, state.isTrimmed, enabled = true, onEdit = onEdit, onDiscard = onDiscard)
                is ImportUiState.Processing -> if (state.fromFile) {
                    // Durante l'elaborazione di un file la cattura non si avvia: il processore è occupato.
                    CaptureCard(isRecording, enabled = false, onStart = startCaptureWidget, onStop = stopCaptureWidget)
                } else {
                    PendingCaptureCard(state.name, isTrimmed = false, enabled = false, onEdit = onEdit, onDiscard = onDiscard)
                }
            }
           /* Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(16.dp))
            SectionTitle(R.string.import_options_section)
            Spacer(Modifier.height(16.dp))
            ProcessingOptions(options = options, onOptionsChange = { options = it })*/
            Spacer(Modifier.height(24.dp))
            Button(
                // Elabora la cattura in attesa; un file scelto dalla memoria parte da solo.
                onClick = onProcess,
                enabled = state is ImportUiState.CaptureReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.import_process_now),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            when (state) {
                ImportUiState.Empty -> Unit
                is ImportUiState.CaptureReady -> {
                    Spacer(Modifier.height(16.dp))
                    PreviewPlayerCard(
                        playerState = playerState,
                        playableUri = state.playableUri,
                        fallbackDurationMs = state.durationMs,
                        onTogglePlayback = onTogglePlayback,
                        onSeek = onSeek,
                    )
                }
                is ImportUiState.Processing -> {
                    Spacer(Modifier.height(16.dp))
                    ProcessingProgressCard(state.phase)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImportTopBar() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.logo_corallo_blu),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.import_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SectionTitle(@StringRes textRes: Int) {
    Text(
        text = stringResource(textRes).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FilePickerCard(processingName: String?, enabled: Boolean, onBrowse: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "📁", style = MaterialTheme.typography.displaySmall)
            Text(
                text = stringResource(R.string.import_select_file),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!processingName.isNullOrBlank()) {
                Text(
                    text = processingName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(onClick = onBrowse, enabled = enabled) {
                Text(stringResource(R.string.import_browse_storage))
            }
            Text(
                text = stringResource(R.string.import_supported_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CaptureCard(isRecording: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "🎙️", style = MaterialTheme.typography.titleLarge)
                Column {
                    Text(
                        text = stringResource(R.string.import_capture_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            if (isRecording) R.string.import_capture_recording else R.string.import_capture_description
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isRecording) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Button(
                onClick = if (isRecording) onStop else onStart,
                enabled = enabled,
                colors = if (isRecording) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(if (isRecording) R.string.import_capture_stop else R.string.import_capture_start))
            }
        }
    }
}

@Composable
private fun ProcessingOptions(
    options: ImportOptions,
    onOptionsChange: (ImportOptions) -> Unit,
) {
    Text(
        text = stringResource(R.string.import_quality_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ProcessingQuality.entries.forEach { quality ->
            Row(
                modifier = Modifier.selectable(
                    selected = options.quality == quality,
                    onClick = { onOptionsChange(options.copy(quality = quality)) },
                    role = Role.RadioButton,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = options.quality == quality, onClick = null)
                Text(
                    text = stringResource(quality.labelRes),
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    OptionCheckbox(
        textRes = R.string.import_option_auto_save,
        checked = options.autoSave,
        onCheckedChange = { onOptionsChange(options.copy(autoSave = it)) },
    )
    OptionCheckbox(
        textRes = R.string.import_option_normalize,
        checked = options.normalizeVolume,
        onCheckedChange = { onOptionsChange(options.copy(normalizeVolume = it)) },
    )
    OptionCheckbox(
        textRes = R.string.import_option_reverb,
        checked = options.lightReverb,
        onCheckedChange = { onOptionsChange(options.copy(lightReverb = it)) },
    )
}

@Composable
private fun OptionCheckbox(
    @StringRes textRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = stringResource(textRes),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF19263B)
@Composable
private fun ImportSongScreenPreview() {
    VocalRemoverTheme {
        ImportSongScreen()
    }
}

private const val AUDIO_MIME_TYPE = "audio/*"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
