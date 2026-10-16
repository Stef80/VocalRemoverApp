package com.stef.vocalremover.ui.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vocalremover.capture.CapturePackageSuggestions
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

@Composable
fun BubbleOverlayContent(
    state: BubbleOverlayState,
    suggestions: List<String>,
    onDragDelta: (Float, Float) -> Unit,
    onPackagePicked: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val isRecording = state.phase == BubbleOverlayPhase.RECORDING

    if (!expanded) {
        // Durante la registrazione pulsa un anello rosso attorno al bubble e un
        // pallino rosso in alto a destra, per segnalare all'utente che la cattura
        // è in corso anche quando il widget è collassato sopra un'altra app.
        val pulseTransition = rememberInfiniteTransition(label = "bubble-recording-pulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bubble-recording-alpha"
        )

        Box(
            modifier = modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                }
        ) {
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .then(
                        if (isRecording) {
                            Modifier.border(
                                width = 3.dp,
                                color = Color.Red.copy(alpha = pulseAlpha),
                                shape = CircleShape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { expanded = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.logo_corallo_blu),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .background(Color.Red.copy(alpha = pulseAlpha), CircleShape)
                )
            }
        }
        return
    }

    BubbleOverlayExpandedCard(
        state = state,
        suggestions = suggestions,
        onPackagePicked = {
            expanded = false
            onPackagePicked(it)
        },
        onStartRecording = {
            expanded = false
            onStartRecording()
        },
        onStopRecording = {
            expanded = false
            onStopRecording()
        },
        modifier = modifier,
    )
}

@Composable
private fun BubbleOverlayExpandedCard(
    state: BubbleOverlayState,
    suggestions: List<String>,
    onPackagePicked: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.phase == BubbleOverlayPhase.RECORDING) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                }
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                items(suggestions) { displayText ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                // Deve coincidere esattamente con le fasi accettate da
                                // BubbleOverlayTransitions.selectApp (IDLE/ERROR): da
                                // APP_SELECTED la transizione è rifiutata (ritorna null),
                                // quindi abilitare il tap in quella fase farebbe comunque
                                // partire launchTargetApp con un pacchetto diverso da
                                // quello effettivamente registrato nello stato.
                                enabled = state.phase == BubbleOverlayPhase.IDLE ||
                                    state.phase == BubbleOverlayPhase.ERROR
                            ) {
                                onPackagePicked(
                                    CapturePackageSuggestions.packageNameFromDisplayText(displayText)
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    when (state.phase) {
                    BubbleOverlayPhase.APP_SELECTED -> onStartRecording()
                        BubbleOverlayPhase.RECORDING -> onStopRecording()
                        else -> Unit
                    }
                },
                enabled = state.phase == BubbleOverlayPhase.APP_SELECTED ||
                    state.phase == BubbleOverlayPhase.RECORDING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionButtonText(state), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun statusText(state: BubbleOverlayState): String = when (state.phase) {
    BubbleOverlayPhase.IDLE -> stringResource(R.string.bubble_status_idle)
    BubbleOverlayPhase.APP_SELECTED ->
        stringResource(R.string.bubble_status_app_selected, state.packageName.orEmpty())
    BubbleOverlayPhase.AWAITING_CONSENT -> stringResource(R.string.bubble_status_awaiting_consent)
    BubbleOverlayPhase.RECORDING -> stringResource(R.string.bubble_status_recording)
    BubbleOverlayPhase.STOPPING -> stringResource(R.string.bubble_status_stopping)
    BubbleOverlayPhase.ERROR ->
        state.errorMessage ?: stringResource(R.string.bubble_status_unknown_error)
}

@Composable
private fun actionButtonText(state: BubbleOverlayState): String = when (state.phase) {
    BubbleOverlayPhase.RECORDING -> stringResource(R.string.bubble_action_stop_and_process)
    else -> stringResource(R.string.bubble_action_start_recording)
}

@Preview(showBackground = true)
@Composable
private fun BubbleOverlayExpandedCardPreview() {
    VocalRemoverTheme {
        BubbleOverlayExpandedCard(
            state = BubbleOverlayState(
                phase = BubbleOverlayPhase.APP_SELECTED,
                packageName = "com.google.android.youtube",
            ),
            suggestions = listOf(
                "YouTube (com.google.android.youtube)",
                "Spotify (com.spotify.music)",
            ),
            onPackagePicked = {},
            onStartRecording = {},
            onStopRecording = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
