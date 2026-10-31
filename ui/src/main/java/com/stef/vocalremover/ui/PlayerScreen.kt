package com.stef.vocalremover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.vocalremover.playback.PlayerError
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.player.PlayerLyrics
import com.stef.vocalremover.ui.player.PlayerUiState
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Player of a Library track. `<<` and `>>` skip by 10 seconds; the arrow and the system
 * back key go back to the Library.
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
                LyricsPanel(
                    lyrics = state?.lyrics ?: PlayerLyrics.Unavailable,
                    positionMs = state?.positionMs ?: 0L,
                )
            }
            Spacer(Modifier.height(12.dp))
            PlaybackProgress(state = state, onSeek = onSeek)
        }
    }
}

/** Track lyrics: spinner during the download, then synced lines or plain text. */
@Composable
private fun LyricsPanel(lyrics: PlayerLyrics, positionMs: Long) {
    when (lyrics) {
        PlayerLyrics.Loading -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.player_lyrics_wait),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        is PlayerLyrics.Synced -> SyncedLyrics(lyrics, positionMs)
        is PlayerLyrics.Plain -> Text(
            text = lyrics.text,
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PlayerLyrics.Instrumental, PlayerLyrics.Unavailable -> Box(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (lyrics == PlayerLyrics.Instrumental) R.string.player_lyrics_instrumental
                    else R.string.player_lyrics_unavailable
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Synced lines: the current one is highlighted and the list follows it. */
@Composable
private fun SyncedLyrics(lyrics: PlayerLyrics.Synced, positionMs: Long) {
    val active = lyrics.activeLine(positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(active) {
        if (active >= 0) listState.animateScrollToItem((active - 2).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            Text(
                text = line,
                style = if (index == active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal,
                color = if (index == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Draggable bar: while dragging it shows the chosen position, applies it on release. */
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
            enabled = durationMs > 0 && state?.lyrics != PlayerLyrics.Loading,
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
                lyrics = PlayerLyrics.Synced(
                    lines = listOf("Prima riga", "Seconda riga", "Terza riga"),
                    startsMs = listOf(60_000L, 70_000L, 80_000L),
                    songOffsetMs = 0L,
                ),
            )
        )
    }
}

