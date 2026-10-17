package com.stef.vocalremover.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleDismissPolicyTest {

    @Test
    fun `bubble can be dismissed when no recording is in progress`() {
        for (phase in listOf(
            BubbleOverlayPhase.IDLE,
            BubbleOverlayPhase.APP_SELECTED,
            BubbleOverlayPhase.AWAITING_CONSENT,
            BubbleOverlayPhase.LISTENING,
            BubbleOverlayPhase.CANCELLING,
            BubbleOverlayPhase.ERROR
        )) {
            assertTrue(phase.name, BubbleDismissPolicy.canDismiss(phase))
        }
    }

    @Test
    fun `bubble cannot be dismissed while recording or stopping`() {
        assertFalse(BubbleDismissPolicy.canDismiss(BubbleOverlayPhase.RECORDING))
        assertFalse(BubbleDismissPolicy.canDismiss(BubbleOverlayPhase.STOPPING))
    }

    @Test
    fun `dismissing during listening cancels the capture first`() {
        assertTrue(BubbleDismissPolicy.mustCancelCapture(BubbleOverlayPhase.LISTENING))
        assertTrue(BubbleDismissPolicy.mustCancelCapture(BubbleOverlayPhase.CANCELLING))
        assertFalse(BubbleDismissPolicy.mustCancelCapture(BubbleOverlayPhase.APP_SELECTED))
        assertFalse(BubbleDismissPolicy.mustCancelCapture(BubbleOverlayPhase.IDLE))
    }

    @Test
    fun `bubble is over the target only within the radius`() {
        assertTrue(BubbleDismissPolicy.isOverTarget(500f, 1_800f, 500f, 1_800f, 150f))
        assertTrue(BubbleDismissPolicy.isOverTarget(590f, 1_680f, 500f, 1_800f, 150f))
        assertFalse(BubbleDismissPolicy.isOverTarget(500f, 1_600f, 500f, 1_800f, 150f))
        assertFalse(BubbleDismissPolicy.isOverTarget(700f, 1_800f, 500f, 1_800f, 150f))
    }
}
