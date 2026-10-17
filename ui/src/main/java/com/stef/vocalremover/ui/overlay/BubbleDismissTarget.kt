package com.stef.vocalremover.ui.overlay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/** Dimensione massima della X: la finestra overlay la riserva sempre, così non cambia misura. */
internal val BubbleDismissTargetMaxSize = 84.dp

/**
 * Cerchio con la X mostrato in basso durante il trascinamento del bubble: rilasciando
 * il bubble sopra di esso il widget viene chiuso. Si ingrandisce quando il bubble è
 * abbastanza vicino da essere chiuso al rilascio.
 */
@Composable
fun BubbleDismissTarget(hovering: Boolean, modifier: Modifier = Modifier) {
    val size by animateDpAsState(
        targetValue = if (hovering) BubbleDismissTargetMaxSize else 64.dp,
        label = "bubble-dismiss-size"
    )
    val description = stringResource(R.string.bubble_dismiss_target_description)
    Box(
        modifier = modifier.size(BubbleDismissTargetMaxSize),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(size)
                .semantics { contentDescription = description },
            shape = CircleShape,
            color = if (hovering) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)
            },
            shadowElevation = 8.dp,
        ) {
            val stroke = if (hovering) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.inverseOnSurface
            }
            Canvas(modifier = Modifier.size(size)) {
                val arm = this.size.minDimension * 0.18f
                val center = this.center
                val width = 3.dp.toPx()
                drawLine(
                    stroke,
                    Offset(center.x - arm, center.y - arm),
                    Offset(center.x + arm, center.y + arm),
                    strokeWidth = width,
                    cap = StrokeCap.Round
                )
                drawLine(
                    stroke,
                    Offset(center.x + arm, center.y - arm),
                    Offset(center.x - arm, center.y + arm),
                    strokeWidth = width,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Preview
@Composable
private fun BubbleDismissTargetPreview() {
    VocalRemoverTheme {
        BubbleDismissTarget(hovering = false)
    }
}
