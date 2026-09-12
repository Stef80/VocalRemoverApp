package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureProcessingStateMachineTest {

    @Test
    fun `acquiring pending work starts processing and assigns the requester`() {
        val acquired = CaptureProcessingStateMachine.acquire(
            CaptureWorkState(CaptureTerminalState.PENDING),
            requesterId = "activity-one",
            requesterProcessId = 100
        )

        assertEquals(
            CaptureWorkState(
                state = CaptureTerminalState.PROCESSING,
                ownerId = "activity-one",
                ownerProcessId = 100
            ),
            acquired
        )
    }

    @Test
    fun `active work cannot be acquired again by another activity in the same process`() {
        val acquired = CaptureProcessingStateMachine.acquire(
            CaptureWorkState(
                state = CaptureTerminalState.PROCESSING,
                ownerId = "activity-one",
                ownerProcessId = 100
            ),
            requesterId = "activity-two",
            requesterProcessId = 100
        )

        assertNull(acquired)
    }

    @Test
    fun `released processing work can be resumed by a recreated activity`() {
        val released = CaptureProcessingStateMachine.release(
            CaptureWorkState(
                state = CaptureTerminalState.PROCESSING,
                ownerId = "activity-one",
                ownerProcessId = 100
            ),
            ownerId = "activity-one"
        )

        val acquired = CaptureProcessingStateMachine.acquire(
            requireNotNull(released),
            requesterId = "activity-two",
            requesterProcessId = 100
        )

        assertEquals(
            CaptureWorkState(
                state = CaptureTerminalState.PROCESSING,
                ownerId = "activity-two",
                ownerProcessId = 100
            ),
            acquired
        )
    }

    @Test
    fun `saved output can be reacquired after process recreation`() {
        val acquired = CaptureProcessingStateMachine.acquire(
            CaptureWorkState(
                state = CaptureTerminalState.SAVED,
                ownerId = "former-activity",
                ownerProcessId = 100,
                savedOutputUri = "content://media/external/audio/media/42"
            ),
            requesterId = "new-activity",
            requesterProcessId = 200
        )

        assertEquals(
            CaptureWorkState(
                state = CaptureTerminalState.SAVED,
                ownerId = "new-activity",
                ownerProcessId = 200,
                savedOutputUri = "content://media/external/audio/media/42"
            ),
            acquired
        )
    }

    @Test
    fun `only the owner can mark processing work as saved`() {
        val processing = CaptureWorkState(
            state = CaptureTerminalState.PROCESSING,
            ownerId = "activity-one",
            ownerProcessId = 100
        )

        assertNull(
            CaptureProcessingStateMachine.markSaved(
                processing,
                ownerId = "activity-two",
                savedOutputUri = "content://media/external/audio/media/42"
            )
        )
        assertEquals(
            CaptureWorkState(
                state = CaptureTerminalState.SAVED,
                ownerId = "activity-one",
                ownerProcessId = 100,
                savedOutputUri = "content://media/external/audio/media/42"
            ),
            CaptureProcessingStateMachine.markSaved(
                processing,
                ownerId = "activity-one",
                savedOutputUri = "content://media/external/audio/media/42"
            )
        )
    }
}
