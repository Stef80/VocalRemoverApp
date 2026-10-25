package com.stef.vocalremover.ui.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.PlayableTracks
import com.stef.vocalremover.ui.previewLibraryTracks
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Schermata principale: i brani elaborati pronti per la riproduzione e, sotto, il pulsante per
 * importarne uno nuovo. La lista si rilegge a ogni ritorno in primo piano (es. dopo un'elaborazione).
 */
@Composable
fun LibraryRoute(
    onTrackClick: (LibraryTrack) -> Unit,
    onTrackRenamed: (Uri, String) -> Unit,
    onTrackDeleted: (Uri) -> Unit,
    onOpenImportScreen: () -> Unit,
    onOpenLegacyScreen: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val permission = rememberLibraryPermissionState(onResult = viewModel::refresh)
    val snackbarHostState = remember { SnackbarHostState() }
    val renameFailedMessage by rememberUpdatedState(stringResource(R.string.library_rename_failed))
    val deleteFailedMessage by rememberUpdatedState(stringResource(R.string.library_delete_failed))
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK) }
    val currentOnTrackRenamed by rememberUpdatedState(onTrackRenamed)
    val currentOnTrackDeleted by rememberUpdatedState(onTrackDeleted)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.ConsentRequired ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is LibraryEvent.Renamed -> currentOnTrackRenamed(event.uri, event.title)
                is LibraryEvent.Deleted -> currentOnTrackDeleted(event.uri)
                LibraryEvent.RenameFailed -> snackbarHostState.showSnackbar(renameFailedMessage)
                LibraryEvent.DeleteFailed -> snackbarHostState.showSnackbar(deleteFailedMessage)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        // Al ritorno dalle impostazioni il permesso può essere cambiato.
        permission.recheck()
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LibraryScreen(
        state = state,
        showPermissionRequest = !permission.granted,
        onGrantPermission = permission.requestOrOpenSettings,
        onTrackClick = onTrackClick,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onOpenImportScreen = onOpenImportScreen,
        onOpenLegacyScreen = onOpenLegacyScreen,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    showPermissionRequest: Boolean = false,
    onGrantPermission: () -> Unit = {},
    onTrackClick: (LibraryTrack) -> Unit = {},
    onRename: (LibraryTrack, String) -> Unit = { _, _ -> },
    onDelete: (LibraryTrack) -> Unit = {},
    onOpenImportScreen: () -> Unit = {},
    onOpenLegacyScreen: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val tracks = (state as? LibraryUiState.Tracks)?.tracks.orEmpty()
    // Per Uri, così le finestre sopravvivono alla rotazione e si chiudono se il brano sparisce.
    var renameUri by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteUri by rememberSaveable { mutableStateOf<String?>(null) }
    val renameTarget = tracks.firstOrNull { it.uri.toString() == renameUri }
    val deleteTarget = tracks.firstOrNull { it.uri.toString() == deleteUri }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_corallo_blu),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(16.dp))
            if (showPermissionRequest) {
                PermissionRequestCard(onGrantPermission)
                Spacer(Modifier.height(12.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    LibraryUiState.Loading -> {
                        val loadingDescription = stringResource(R.string.library_loading_description)
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = loadingDescription }
                        )
                    }
                    LibraryUiState.Empty -> LibraryMessage(stringResource(R.string.library_empty))
                    LibraryUiState.Error -> LibraryMessage(stringResource(R.string.library_error))
                    is LibraryUiState.Tracks -> PlayableTracks(
                        tracks = state.tracks,
                        modifier = Modifier.fillMaxSize(),
                        onTrackClick = onTrackClick,
                        onRenameRequest = { renameUri = it.uri.toString() },
                        onDeleteRequest = { deleteUri = it.uri.toString() },
                        contentPadding = PaddingValues(vertical = 4.dp),
                    )
                }
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

    renameTarget?.let { track ->
        RenameDialog(
            track = track,
            onConfirm = { title ->
                renameUri = null
                onRename(track, title)
            },
            onDismiss = { renameUri = null },
        )
    }
    deleteTarget?.let { track ->
        DeleteDialog(
            track = track,
            onConfirm = {
                deleteUri = null
                onDelete(track)
            },
            onDismiss = { deleteUri = null },
        )
    }
}

@Composable
private fun RenameDialog(track: LibraryTrack, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable(track.uri.toString()) { mutableStateOf(track.title) }
    val canSave = title.isNotBlank() && title.trim() != track.title
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.library_rename_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onConfirm(title) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }, enabled = canSave) {
                Text(stringResource(R.string.library_rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_dialog_cancel))
            }
        },
    )
}

@Composable
private fun DeleteDialog(track: LibraryTrack, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_delete_title)) },
        text = { Text(stringResource(R.string.library_delete_message, track.title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.library_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.library_dialog_cancel))
            }
        },
    )
}

/** Senza permesso la lista mostra solo i brani creati da questa installazione dell'app. */
@Composable
private fun PermissionRequestCard(onGrantPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.library_permission_message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            TextButton(onClick = onGrantPermission) {
                Text(stringResource(R.string.library_permission_grant))
            }
        }
    }
}

@Composable
private fun LibraryMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenTracksPreview() {
    VocalRemoverTheme {
        LibraryScreen(state = LibraryUiState.Tracks(previewLibraryTracks), showPermissionRequest = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenEmptyPreview() {
    VocalRemoverTheme {
        LibraryScreen(state = LibraryUiState.Empty)
    }
}
