package com.example.vocalremover.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeSignalTest {

    @Test
    fun `probe signal is deterministic and stereo`() {
        val a = ProbeSignal.generate()
        val b = ProbeSignal.generate()
        assertEquals(261_120, a.left.size)
        assertEquals(261_120, a.right.size)
        assertTrue(a.left.contentEquals(b.left))
        assertTrue(a.right.contentEquals(b.right))
        assertFalse(a.left.contentEquals(a.right))
    }

    @Test
    fun `synthetic signal is deterministic stereo with real channel difference`() {
        val a = ProbeSignal.generate(seconds = 1.0, sampleRate = 44100, seed = 7)
        val b = ProbeSignal.generate(seconds = 1.0, sampleRate = 44100, seed = 7)
        assertEquals(44100, a.left.size)
        assertEquals(44100, a.right.size)
        assertTrue(a.left.contentEquals(b.left))
        assertFalse(a.left.contentEquals(a.right))
        val peak = (a.left + a.right).maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak", peak in 0.1f..0.95f)
    }

    @Test
    fun `synthetic signal changes with seed`() {
        val a = ProbeSignal.generate(1.0, 44100, seed = 1)
        val b = ProbeSignal.generate(1.0, 44100, seed = 2)
        assertNotEquals(a.left.toList(), b.left.toList())
    }
}
