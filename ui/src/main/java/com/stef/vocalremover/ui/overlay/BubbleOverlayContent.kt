package com.stef.vocalremover.ui.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onPackagePicked: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Il widget si apre espanso: il primo passo è sempre scegliere l'app da registrare.
    var expanded by rememberSaveable { mutableStateOf(true) }
    val isRecording = state.phase == BubbleOverlayPhase.RECORDING
    val isListening = state.phase == BubbleOverlayPhase.LISTENING

    if (!expanded) {
        // Durante la registrazione pulsa un anello rosso attorno al bubble e un
        // pallino rosso in alto a destra, per segnalare all'utente che la cattura
        // è in corso anche quando il widget è collassato sopra un'altra app.
        // In ascolto pulsa solo l'anello, nel colore terziario del tema.
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
        val hintText = when (state.phase) {
            BubbleOverlayPhase.APP_SELECTED -> stringResource(R.string.bubble_hint_record)
            BubbleOverlayPhase.LISTENING -> stringResource(R.string.bubble_hint_listening)
            BubbleOverlayPhase.RECORDING -> stringResource(R.string.bubble_hint_stop)
            else -> null
        }

        Column(
            modifier = modifier
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x, dragAmount.y)
                    }
                }
        ) {
            // Fumetto solo informativo (non cliccabile), sopra il bubble e staccato
            // dall'anello/pallino della registrazione. Il suo spazio resta sempre
            // riservato (invisibile quando non serve), così il bubble non si sposta.
            RecordHintBalloon(
                text = hintText ?: stringResource(R.string.bubble_hint_record),
                modifier = Modifier
                    .padding(start = 40.dp, bottom = 3.dp)
                    .alpha(if (hintText != null) 1f else 0f)
            )
            Box {
                Surface(
                    modifier = Modifier
                        .size(64.dp)
                        .then(
                            if (isRecording || isListening) {
                                Modifier.border(
                                    width = 3.dp,
                                    color = (if (isRecording) Color.Red else MaterialTheme.colorScheme.tertiary)
                                        .copy(alpha = pulseAlpha),
                                    shape = CircleShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        // Tap: con un'app scelta avvia l'ascolto, in ascolto lo annulla,
                        // durante la registrazione la ferma, altrimenti riapre la card.
                        // Click lungo: riapre sempre la card (es. per cambiare app).
                        .combinedClickable(
                            onClick = {
                                when (state.phase) {
                                    BubbleOverlayPhase.APP_SELECTED -> onStartRecording()
                                    BubbleOverlayPhase.LISTENING,
                                    BubbleOverlayPhase.RECORDING -> onStopRecording()
                                    else -> expanded = true
                                }
                            },
                            onLongClick = { expanded = true },
                        ),
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
        onStopRecording = {
            expanded = false
            onStopRecording()
        },
        modifier = modifier,
    )
}

/** Fumetto con la punta in basso a sinistra, rivolta verso il bubble. */
@Composable
private fun RecordHintBalloon(text: String, modifier: Modifier = Modifier) {
    val balloonColor = MaterialTheme.colorScheme.error
    Column(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = balloonColor,
            contentColor = MaterialTheme.colorScheme.onError,
            shadowElevation = 4.dp,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Canvas(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(width = 8.dp, height = 5.dp)
        ) {
            drawPath(
                path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(0f, size.height)
                    close()
                },
                color = balloonColor,
            )
        }
    }
}

@Composable
private fun BubbleOverlayExpandedCard(
    state: BubbleOverlayState,
    suggestions: List<String>,
    onPackagePicked: (String) -> Unit,
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
                                // BubbleOverlayTransitions.selectApp (IDLE/APP_SELECTED/ERROR):
                                // altrove la transizione è rifiutata (ritorna null), quindi
                                // abilitare il tap farebbe comunque partire launchTargetApp
                                // con un pacchetto diverso da quello registrato nello stato.
                                enabled = state.phase == BubbleOverlayPhase.IDLE ||
                                    state.phase == BubbleOverlayPhase.APP_SELECTED ||
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

            // L'avvio della registrazione passa dal bubble collassato: qui restano solo
            // lo stop (in registrazione) e l'annullamento (in ascolto).
            if (state.phase == BubbleOverlayPhase.RECORDING ||
                state.phase == BubbleOverlayPhase.LISTENING
            ) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onStopRecording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.phase == BubbleOverlayPhase.RECORDING) {
                                R.string.bubble_action_stop_and_process
                            } else {
                                R.string.bubble_action_cancel_listening
                            }
                        ),
                        fontWeight = FontWeight.Bold
                    )
                }
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
    BubbleOverlayPhase.LISTENING -> stringResource(R.string.bubble_status_listening)
    BubbleOverlayPhase.CANCELLING -> stringResource(R.string.bubble_status_cancelling)
    BubbleOverlayPhase.RECORDING -> stringResource(R.string.bubble_status_recording)
    BubbleOverlayPhase.STOPPING -> stringResource(R.string.bubble_status_stopping)
    BubbleOverlayPhase.ERROR ->
        state.errorMessage ?: stringResource(R.string.bubble_status_unknown_error)
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
            onStopRecording = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
