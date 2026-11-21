package com.example.vocalremover

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkOutputCheckTest {

    private val loud = FloatArray(8) { if (it % 2 == 0) 0.1f else -0.1f }
    private val silent = FloatArray(8)
    private val qnn = ExecutionBackend.QNN

    @Test
    fun `plausible output passes`() {
        assertNull(ChunkOutputCheck.problem(qnn, FloatArray(4) { 1f }, loud, loud, loud, loud))
    }

    @Test
    fun `non finite values fail`() {
        val reason = ChunkOutputCheck.problem(qnn, floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY), loud, loud, loud, loud)
        assertTrue(reason!!.startsWith("2 non-finite"))
    }

    @Test
    fun `silent output for loud input fails`() {
        assertNotNull(ChunkOutputCheck.problem(qnn, FloatArray(4), loud, loud, silent, silent))
    }

    @Test
    fun `cpu output is never rejected`() {
        assertNull(ChunkOutputCheck.problem(ExecutionBackend.CPU, FloatArray(4), loud, loud, silent, silent))
        assertNull(ChunkOutputCheck.problem(ExecutionBackend.CPU, floatArrayOf(Float.NaN), loud, loud, loud, loud))
    }

    @Test
    fun `silent output for silent input passes`() {
        assertNull(ChunkOutputCheck.problem(qnn, FloatArray(4), silent, silent, silent, silent))
    }

    @Test
    fun `stale samples past the chunk length are ignored`() {
        val shortInput = FloatArray(4) { 0.1f }
        val output = FloatArray(8).also { it[6] = 1f }
        assertNotNull(ChunkOutputCheck.problem(qnn, FloatArray(4), shortInput, shortInput, output, output))
    }
}
