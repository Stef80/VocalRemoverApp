package com.stef.vocalremover.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Brani elaborati pronti per la riproduzione: tocco → [onTrackClick], tocco lungo → menu con
 * Rinomina ed Elimina.
 */
@Composable
fun PlayableTracks(
    tracks: List<LibraryTrack>,
    modifier: Modifier = Modifier,
    onTrackClick: (LibraryTrack) -> Unit = {},
    onRenameRequest: (LibraryTrack) -> Unit = {},
    onDeleteRequest: (LibraryTrack) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(20.dp),
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tracks, key = { it.uri.toString() }) { track ->
            PlayableTrackCard(
                track = track,
                onClick = { onTrackClick(track) },
                onRename = { onRenameRequest(track) },
                onDelete = { onDeleteRequest(track) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayableTrackCard(
    track: LibraryTrack,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.control_play_description),
                    onLongClickLabel = stringResource(R.string.library_more_actions),
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                    onClick = onClick,
                ),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            PlayableTrackContent(track)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_action_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_action_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun PlayableTrackContent(track: LibraryTrack) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (track.durationMs > 0) {
                    formatMinutesSeconds(track.durationMs)
                } else {
                    stringResource(R.string.library_duration_unknown)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Decorativa: l'azione è sull'intera card.
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

internal val previewLibraryTracks = listOf(
    LibraryTrack(Uri.parse("content://media/external/audio/media/1"), "Mr. Brightside", 222_000L),
    LibraryTrack(
        Uri.parse("content://media/external/audio/media/2"),
        "Un titolo lunghissimo che non entra in una riga sola e nemmeno in due righe della card",
        248_000L
    ),
    LibraryTrack(Uri.parse("content://media/external/audio/media/3"), "Registrazione_2026-10-18_2130", 0L)
)

@Preview(showBackground = true)
@Composable
private fun PlayableTracksPreview() {
    VocalRemoverTheme {
        PlayableTracks(tracks = previewLibraryTracks)
    }
}
