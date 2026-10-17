package com.stef.vocalremover.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubbleOverlayStateTest {

    @Test
    fun `selecting an app from idle moves to app selected`() {
        val next = BubbleOverlayTransitions.selectApp(
            BubbleOverlayState(BubbleOverlayPhase.IDLE),
            packageName = "com.example.target",
            targetUid = 1234
        )
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.example.target", 1234),
            next
        )
    }

    @Test
    fun `selecting another app from app selected replaces the target`() {
        val next = BubbleOverlayTransitions.selectApp(
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.example.target", 1234),
            packageName = "com.other",
            targetUid = 5678
        )
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.other", 5678),
            next
        )
    }

    @Test
    fun `selecting an app while recording is rejected`() {
        val current = BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.example.target", 1234)
        assertNull(BubbleOverlayTransitions.selectApp(current, "com.other", 5678))
    }

    @Test
    fun `requesting consent only works from app selected`() {
        val selected = BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.AWAITING_CONSENT, "com.a", 1),
            BubbleOverlayTransitions.requestConsent(selected)
        )
        assertNull(BubbleOverlayTransitions.requestConsent(BubbleOverlayState(BubbleOverlayPhase.IDLE)))
    }

    @Test
    fun `consent moves to listening only while awaiting consent`() {
        val awaiting = BubbleOverlayState(BubbleOverlayPhase.AWAITING_CONSENT, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.LISTENING, "com.a", 1),
            BubbleOverlayTransitions.startListening(awaiting)
        )
        assertNull(
            BubbleOverlayTransitions.startListening(
                BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.a", 1)
            )
        )
    }

    @Test
    fun `detected audio moves from listening to recording`() {
        val listening = BubbleOverlayState(BubbleOverlayPhase.LISTENING, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1),
            BubbleOverlayTransitions.audioDetected(listening)
        )
        assertNull(
            BubbleOverlayTransitions.audioDetected(
                BubbleOverlayState(BubbleOverlayPhase.CANCELLING, "com.a", 1)
            )
        )
    }

    @Test
    fun `cancelling only works while listening`() {
        val listening = BubbleOverlayState(BubbleOverlayPhase.LISTENING, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.CANCELLING, "com.a", 1),
            BubbleOverlayTransitions.cancelListening(listening)
        )
        assertNull(
            BubbleOverlayTransitions.cancelListening(
                BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1)
            )
        )
    }

    @Test
    fun `cancelled returns to app selected keeping the chosen app`() {
        val cancelling = BubbleOverlayState(BubbleOverlayPhase.CANCELLING, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.a", 1),
            BubbleOverlayTransitions.cancelled(cancelling)
        )
        assertNull(
            BubbleOverlayTransitions.cancelled(
                BubbleOverlayState(BubbleOverlayPhase.LISTENING, "com.a", 1)
            )
        )
    }

    @Test
    fun `requesting stop only works while recording`() {
        val recording = BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1)
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.STOPPING, "com.a", 1),
            BubbleOverlayTransitions.requestStop(recording)
        )
        assertNull(BubbleOverlayTransitions.requestStop(BubbleOverlayState(BubbleOverlayPhase.IDLE)))
    }

    @Test
    fun `stopped resets to idle from any active capture phase`() {
        for (phase in listOf(
            BubbleOverlayPhase.STOPPING,
            BubbleOverlayPhase.RECORDING,
            BubbleOverlayPhase.LISTENING,
            BubbleOverlayPhase.CANCELLING
        )) {
            assertEquals(
                BubbleOverlayState(BubbleOverlayPhase.IDLE),
                BubbleOverlayTransitions.stopped(BubbleOverlayState(phase, "com.a", 1))
            )
        }
        assertNull(
            BubbleOverlayTransitions.stopped(BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, "com.a", 1))
        )
    }

    @Test
    fun `failed can happen from any phase and carries a message`() {
        val fromRecording = BubbleOverlayTransitions.failed(
            BubbleOverlayState(BubbleOverlayPhase.RECORDING, "com.a", 1),
            "Errore di cattura"
        )
        assertEquals(
            BubbleOverlayState(BubbleOverlayPhase.ERROR, "com.a", 1, "Errore di cattura"),
            fromRecording
        )
    }

    @Test
    fun `dismissing the error resets to idle only from error`() {
        val error = BubbleOverlayState(BubbleOverlayPhase.ERROR, errorMessage = "boom")
        assertEquals(BubbleOverlayState(BubbleOverlayPhase.IDLE), BubbleOverlayTransitions.dismissError(error))
        assertNull(BubbleOverlayTransitions.dismissError(BubbleOverlayState(BubbleOverlayPhase.IDLE)))
    }
}
