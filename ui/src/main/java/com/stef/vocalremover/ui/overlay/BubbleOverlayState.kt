package com.stef.vocalremover.ui.overlay

/**
 * Pure state (no Android dependencies) of the floating recording widget.
 * Transitions are pure functions: they do no IO and touch neither WindowManager
 * nor the capture services. `BubbleOverlayService` uses them to decide what to do
 * and `BubbleOverlayView` to decide what to show.
 */
enum class BubbleOverlayPhase {
    IDLE,
    APP_SELECTED,
    AWAITING_CONSENT,
    /** Active capture, waiting for the source app to start playing. */
    LISTENING,
    /** Cancellation requested while listening: waiting for the service to close. */
    CANCELLING,
    RECORDING,
    STOPPING,
    ERROR
}

data class BubbleOverlayState(
    val phase: BubbleOverlayPhase = BubbleOverlayPhase.IDLE,
    val packageName: String? = null,
    val targetUid: Int? = null,
    val errorMessage: String? = null
)

/**
 * Valid transitions between bubble states. Each function returns the new state,
 * or null if the transition is not valid in the current state (the caller
 * ignores the event instead of propagating an exception: out-of-order events can
 * arrive from asynchronous broadcasts of the capture service).
 */
object BubbleOverlayTransitions {

    fun selectApp(
        current: BubbleOverlayState,
        packageName: String,
        targetUid: Int
    ): BubbleOverlayState? =
        // APP_SELECTED is allowed to change app (long click on the collapsed bubble).
        if (current.phase == BubbleOverlayPhase.IDLE ||
            current.phase == BubbleOverlayPhase.APP_SELECTED ||
            current.phase == BubbleOverlayPhase.ERROR
        ) {
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, packageName, targetUid)
        } else {
            null
        }

    fun requestConsent(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.APP_SELECTED) {
            current.copy(phase = BubbleOverlayPhase.AWAITING_CONSENT)
        } else {
            null
        }

    fun startListening(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.AWAITING_CONSENT) {
            current.copy(phase = BubbleOverlayPhase.LISTENING)
        } else {
            null
        }

    fun audioDetected(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.LISTENING) {
            current.copy(phase = BubbleOverlayPhase.RECORDING)
        } else {
            null
        }

    fun cancelListening(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.LISTENING) {
            current.copy(phase = BubbleOverlayPhase.CANCELLING)
        } else {
            null
        }

    /** End of the cancellation: the app stays picked, so a tap restarts listening. */
    fun cancelled(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.CANCELLING) {
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, current.packageName, current.targetUid)
        } else {
            null
        }

    fun requestStop(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.RECORDING) {
            current.copy(phase = BubbleOverlayPhase.STOPPING)
        } else {
            null
        }

    /**
     * The service produced a file: besides after a manual stop this also happens
     * on the automatic stop at the end of the track (from RECORDING) or if audio arrives right around
     * a cancellation or before the recording-started broadcast.
     */
    fun stopped(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.STOPPING ||
            current.phase == BubbleOverlayPhase.RECORDING ||
            current.phase == BubbleOverlayPhase.LISTENING ||
            current.phase == BubbleOverlayPhase.CANCELLING
        ) {
            BubbleOverlayState(BubbleOverlayPhase.IDLE)
        } else {
            null
        }

    fun failed(current: BubbleOverlayState, message: String): BubbleOverlayState =
        current.copy(phase = BubbleOverlayPhase.ERROR, errorMessage = message)

    fun dismissError(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.ERROR) {
            BubbleOverlayState(BubbleOverlayPhase.IDLE)
        } else {
            null
        }
}
