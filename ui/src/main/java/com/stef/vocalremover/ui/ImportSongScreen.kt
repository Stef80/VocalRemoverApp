package com.stef.vocalremover.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vocalremover.ui.R
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
fun ImportSongScreen(onBack: () -> Unit = {}) {
    var options by rememberSaveable(stateSaver = ImportOptions.Saver) { mutableStateOf(ImportOptions()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val previewMessage = stringResource(R.string.import_preview_not_connected)
    val showPreviewMessage: () -> Unit = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(previewMessage)
        }
    }

    BackHandler(onBack = onBack)

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
            ImportTopBar(onBack = onBack)
            Spacer(Modifier.height(16.dp))
            FilePickerCard(onBrowse = showPreviewMessage)
            Spacer(Modifier.height(24.dp))
            SectionTitle(R.string.import_record_section)
            Spacer(Modifier.height(8.dp))
            CaptureCard(onStart = showPreviewMessage)
           /* Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(16.dp))
            SectionTitle(R.string.import_options_section)
            Spacer(Modifier.height(16.dp))
            ProcessingOptions(options = options, onOptionsChange = { options = it })*/
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = showPreviewMessage,
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
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImportTopBar(onBack: () -> Unit) {
    val backDescription = stringResource(R.string.control_back_description)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = backDescription,
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
                text = stringResource(R.string.import_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
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
private fun FilePickerCard(onBrowse: () -> Unit) {
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
            OutlinedButton(onClick = onBrowse) {
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
private fun CaptureCard(onStart: () -> Unit) {
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
                        text = stringResource(R.string.import_capture_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onStart,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.import_capture_start))
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
