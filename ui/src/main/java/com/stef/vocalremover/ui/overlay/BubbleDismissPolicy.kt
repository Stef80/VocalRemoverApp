package com.stef.vocalremover.ui.overlay

/**
 * Pure rules (no Android dependencies) for closing the bubble by dragging it
 * onto the X at the bottom. `BubbleOverlayService` uses them to decide whether to show the X and
 * what to do on release.
 */
object BubbleDismissPolicy {

    /**
     * During recording (and while it stops) the X does not appear: a wrong
     * drag must not lose the track. To close, stop the recording first.
     */
    fun canDismiss(phase: BubbleOverlayPhase): Boolean =
        phase != BubbleOverlayPhase.RECORDING && phase != BubbleOverlayPhase.STOPPING

    /** While listening there is an active capture to cancel before closing the service. */
    fun mustCancelCapture(phase: BubbleOverlayPhase): Boolean =
        phase == BubbleOverlayPhase.LISTENING || phase == BubbleOverlayPhase.CANCELLING

    /** True if the bubble center is within `radius` of the X center (screen coordinates, px). */
    fun isOverTarget(
        bubbleCenterX: Float,
        bubbleCenterY: Float,
        targetCenterX: Float,
        targetCenterY: Float,
        radius: Float
    ): Boolean {
        val dx = bubbleCenterX - targetCenterX
        val dy = bubbleCenterY - targetCenterY
        return dx * dx + dy * dy <= radius * radius
    }
}
