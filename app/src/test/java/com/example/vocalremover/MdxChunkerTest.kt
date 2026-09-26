package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test

class MdxChunkerTest {

    @Test
    fun `passthrough process function reconstructs original signal`() {
        val chunker = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        val signal = FloatArray(37) { (it % 7).toFloat() - 3f }

        val result = chunker.process(signal) { chunk -> chunk }

        assertEquals(signal.size, result.size)
        for (i in signal.indices) {
            assertEquals("sample $i", signal[i], result[i], 1e-3f)
        }
    }

    @Test
    fun `scaling process function scales reconstructed signal uniformly`() {
        val chunker = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        val signal = FloatArray(50) { (it % 5).toFloat() * 0.1f }

        val result = chunker.process(signal) { chunk -> FloatArray(chunk.size) { chunk[it] * 2f } }

        for (i in signal.indices) {
            assertEquals("sample $i", signal[i] * 2f, result[i], 1e-3f)
        }
    }

    @Test
    fun `output length always matches input length regardless of chunk boundary alignment`() {
        val chunker = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        for (len in listOf(1, 7, 8, 9, 16, 33, 100)) {
            val signal = FloatArray(len) { it.toFloat() }
            val result = chunker.process(signal) { chunk -> chunk }
            assertEquals("len=$len", len, result.size)
        }
    }

    @Test
    fun `chunkCount equals number of processed chunks`() {
        val small = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        for (len in listOf(1, 5, 37, 100, 257)) {
            var calls = 0
            small.process(FloatArray(len)) { chunk -> calls++; chunk }
            assertEquals("len=$len", calls, small.chunkCount(len))
        }

        // Caso reale dei log: 3.684.352 frame -> 20 chiamate ONNX.
        val model = MdxChunker(nFft = 5120, hopLength = 1024, dimT = 256, overlap = 0.25)
        assertEquals(20, model.chunkCount(3_684_352))
    }
}
