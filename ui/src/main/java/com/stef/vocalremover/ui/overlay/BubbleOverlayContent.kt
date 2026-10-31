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
    // The widget opens expanded: the first step is always picking the app to record.
    var expanded by rememberSaveable { mutableStateOf(true) }
    val isRecording = state.phase == BubbleOverlayPhase.RECORDING
    val isListening = state.phase == BubbleOverlayPhase.LISTENING

    if (!expanded) {
        // During recording a red ring pulses around the bubble along with a
        // red dot at the top right, to tell the user that the capture
        // is in progress even when the widget is collapsed over another app.
        // While listening only the ring pulses, in the theme's tertiary color.
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
            // Informational bubble only (not clickable), above the bubble and detached
            // from the recording ring/dot. Its space is always
            // reserved (invisible when not needed), so the bubble does not move.
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
                        // Tap: with an app picked it starts listening, while listening it cancels,
                        // during recording it stops, otherwise it reopens the card.
                        // Long click: always reopens the card (e.g. to change app).
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

/** Speech bubble with the tip at the bottom left, pointing at the bubble. */
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
                                // Must match exactly the stages accepted by
                                // BubbleOverlayTransitions.selectApp (IDLE/APP_SELECTED/ERROR):
                                // elsewhere the transition is rejected (returns null), so
                                // enabling the tap would still trigger launchTargetApp
                                // with a package different from the one recorded in the state.
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

            // Recording starts from the collapsed bubble: only the stop (while recording)
            // and the cancel (while listening) remain here.
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
