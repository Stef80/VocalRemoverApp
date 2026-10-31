package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongPositionEstimatorTest {

    @Test
    fun inRiproduzioneAvanzaDalLUltimoAggiornamento() =
        assertEquals(95_500L, SongPositionEstimator.positionAt(PlaybackSnapshot(true, 93_000, 10_000, 1f), 12_500))

    @Test
    fun velocitaDoppia() =
        assertEquals(2_000L, SongPositionEstimator.positionAt(PlaybackSnapshot(true, 0, 0, 2f), 1_000))

    @Test
    fun inPausaNessunaStima() =
        assertNull(SongPositionEstimator.positionAt(PlaybackSnapshot(false, 93_000, 10_000, 0f), 12_500))

    @Test
    fun statoAssente() = assertNull(SongPositionEstimator.positionAt(null, 12_500))

    @Test
    fun negativoLimitatoAZero() =
        assertEquals(0L, SongPositionEstimator.positionAt(PlaybackSnapshot(true, 100, 10_000, 1f), 9_000))
}
