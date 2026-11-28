package com.stef.vocalremover.ui.projection

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.player.PlayerLyrics
import com.stef.vocalremover.ui.player.PlayerUiState
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/**
 * Full-screen karaoke for a landscape TV (designed for 1920x1080 at 320 dpi). Never sets `FLAG_SECURE`: some
 * presentation displays are not secure and would stay black.
 */
@Composable
fun TvLyricsScreen(state: PlayerUiState?) {
    VocalRemoverTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state == null) {
                TvLogo(Modifier.align(Alignment.Center).size(LARGE_LOGO))
                return@Box
            }
            when (val lyrics = state.lyrics) {
                is PlayerLyrics.Synced -> LyricsLayout(state.track.title) { SyncedKaraoke(lyrics, state.positionMs) }
                is PlayerLyrics.Plain -> LyricsLayout(state.track.title) {
                    ScrollingText(lyrics.text, state.positionMs, state.durationMs)
                }
                PlayerLyrics.Loading, PlayerLyrics.Instrumental, PlayerLyrics.Unavailable ->
                    TitleOnly(state.track.title, waiting = lyrics == PlayerLyrics.Loading)
            }
        }
    }
}

/** Header on top, [content] centered in the space left below it. */
@Composable
private fun LyricsLayout(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TvHeader(title)
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun TvLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_corallo_blu),
        contentDescription = stringResource(R.string.tv_lyrics_logo_description),
        modifier = modifier,
    )
}

/** Logo and title, top left. */
@Composable
private fun TvHeader(title: String) {
    Row(
        modifier = Modifier.padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvLogo(Modifier.size(HEADER_LOGO))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SyncedKaraoke(lyrics: PlayerLyrics.Synced, positionMs: Long) {
    val activeIndex = lyrics.activeLine(positionMs)
    val shown = remember(lyrics, activeIndex) { IndexedWindow(activeIndex, KaraokeLines.window(lyrics, positionMs)) }
    // Keyed on the index: two identical consecutive lines still animate.
    AnimatedContent(targetState = shown, label = "karaoke") { (_, window) ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 96.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            window.previous.forEach { SecondaryLine(it) }
            window.active?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            window.next.forEach { SecondaryLine(it) }
        }
    }
}

private data class IndexedWindow(val index: Int, val window: KaraokeWindow)

@Composable
private fun SecondaryLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Plain lyrics scrolled slowly in proportion to the position, so it follows pause and seek. */
@Composable
private fun ScrollingText(text: String, positionMs: Long, durationMs: Long) {
    val scrollState = rememberScrollState()
    val fraction by rememberUpdatedState(KaraokeLines.plainScrollFraction(positionMs, durationMs))
    LaunchedEffect(scrollState) {
        snapshotFlow { (fraction * scrollState.maxValue).toInt() }.collect {
            scrollState.animateScrollTo(it, tween(durationMillis = SCROLL_MS, easing = LinearEasing))
        }
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 96.dp, vertical = 48.dp),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
}

/** Instrumental, no lyrics, or lyrics still downloading. */
@Composable
private fun TitleOnly(title: String, waiting: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(96.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvLogo(Modifier.size(LARGE_LOGO))
        Spacer(Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (waiting) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.player_lyrics_wait),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SCROLL_MS = 250
private val HEADER_LOGO = 72.dp
private val LARGE_LOGO = 240.dp

private const val TV_PREVIEW = "spec:width=1920px,height=1080px,dpi=320"

private fun previewState(lyrics: PlayerLyrics) = PlayerUiState(
    track = LibraryTrack(Uri.EMPTY, "Titolo del brano", 222_000L),
    isPlaying = true,
    positionMs = 74_000L,
    durationMs = 222_000L,
    error = null,
    lyrics = lyrics,
)

@Preview(device = TV_PREVIEW)
@Composable
private fun TvLyricsSyncedPreview() = TvLyricsScreen(
    previewState(
        PlayerLyrics.Synced(
            lines = listOf("Prima riga", "Seconda riga", "Terza riga", "Quarta riga", "Quinta riga"),
            startsMs = listOf(50_000L, 60_000L, 70_000L, 80_000L, 90_000L),
            songOffsetMs = 0L,
        )
    )
)

@Preview(device = TV_PREVIEW)
@Composable
private fun TvLyricsInstrumentalPreview() = TvLyricsScreen(previewState(PlayerLyrics.Instrumental))
