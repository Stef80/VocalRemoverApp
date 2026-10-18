package com.stef.vocalremover.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.formatMinutesSeconds
import kotlin.math.roundToInt

private val HANDLE_TOUCH_WIDTH = 48.dp

/**
 * Forma d'onda con due maniglie di taglio. Le barre fuori da [startMs, endMs] sono attenuate; la
 * linea verticale è la posizione di riproduzione. Un tocco fuori dalle maniglie sposta l'ascolto.
 */
@Composable
fun TrimWaveform(
    peaks: FloatArray?,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onHandlesChange: (Long, Long) -> Unit,
    onHandleReleased: (TrimHandle) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val loadingDescription = stringResource(R.string.editor_waveform_loading)
    val handlesEnabled = peaks != null && durationMs > TrimHandles.MIN_GAP_MS

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        if (peaks == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { contentDescription = loadingDescription },
            )
            return@BoxWithConstraints
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(durationMs, widthPx) {
                    detectTapGestures { offset -> onSeek(TrimHandles.pxToMs(offset.x, widthPx, durationMs)) }
                },
        ) {
            if (peaks.isNotEmpty()) {
                val barWidth = size.width / peaks.size
                val startX = TrimHandles.msToPx(startMs, size.width, durationMs)
                val endX = TrimHandles.msToPx(endMs, size.width, durationMs)
                peaks.forEachIndexed { index, peak ->
                    val x = index * barWidth
                    val barHeight = (peak * size.height).coerceAtLeast(1f)
                    drawRect(
                        color = if (x + barWidth / 2 in startX..endX) activeColor else inactiveColor,
                        topLeft = Offset(x, (size.height - barHeight) / 2),
                        size = Size(barWidth.coerceAtLeast(1f), barHeight),
                    )
                }
            }
            val playheadX = TrimHandles.msToPx(positionMs, size.width, durationMs)
            drawLine(playheadColor, Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2.dp.toPx())
        }
        if (handlesEnabled) {
            TrimHandleBox(
                handle = TrimHandle.START,
                valueMs = startMs,
                widthPx = widthPx,
                durationMs = durationMs,
                color = activeColor,
                onMove = { requested -> onHandlesChange(TrimHandles.moveStart(requested, endMs), endMs) },
                onReleased = { onHandleReleased(TrimHandle.START) },
            )
            TrimHandleBox(
                handle = TrimHandle.END,
                valueMs = endMs,
                widthPx = widthPx,
                durationMs = durationMs,
                color = activeColor,
                onMove = { requested ->
                    onHandlesChange(startMs, TrimHandles.moveEnd(requested, startMs, durationMs))
                },
                onReleased = { onHandleReleased(TrimHandle.END) },
            )
        }
    }
}

/** Area di tocco larga 48 dp centrata sulla maniglia, con azioni di accessibilità ±1 s. */
@Composable
private fun TrimHandleBox(
    handle: TrimHandle,
    valueMs: Long,
    widthPx: Float,
    durationMs: Long,
    color: Color,
    onMove: (Long) -> Unit,
    onReleased: () -> Unit,
) {
    val currentValue by rememberUpdatedState(valueMs)
    val currentOnMove by rememberUpdatedState(onMove)
    // Posizione non arrotondata durante il trascinamento: il passo da 10 ms non mangia i piccoli spostamenti.
    val dragMs = remember { mutableFloatStateOf(0f) }
    val touchWidthPx = with(LocalDensity.current) { HANDLE_TOUCH_WIDTH.toPx() }
    val description = stringResource(
        if (handle == TrimHandle.START) R.string.editor_handle_start_description else R.string.editor_handle_end_description,
        formatMinutesSeconds(valueMs),
    )
    val forwardLabel = stringResource(R.string.editor_handle_forward)
    val backwardLabel = stringResource(R.string.editor_handle_backward)
    val draggableState = rememberDraggableState { deltaPx ->
        if (widthPx > 0f) {
            dragMs.floatValue += deltaPx / widthPx * durationMs
            currentOnMove(dragMs.floatValue.roundToInt().toLong())
        }
    }
    val centerX = TrimHandles.msToPx(valueMs, widthPx, durationMs)

    Box(
        modifier = Modifier
            .offset { IntOffset((centerX - touchWidthPx / 2).roundToInt(), 0) }
            .width(HANDLE_TOUCH_WIDTH)
            .fillMaxHeight()
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStarted = { dragMs.floatValue = currentValue.toFloat() },
                onDragStopped = { onReleased() },
            )
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(forwardLabel) {
                        currentOnMove(currentValue + TrimHandles.ACCESSIBILITY_STEP_MS)
                        onReleased()
                        true
                    },
                    CustomAccessibilityAction(backwardLabel) {
                        currentOnMove(currentValue - TrimHandles.ACCESSIBILITY_STEP_MS)
                        onReleased()
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color),
        )
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}
