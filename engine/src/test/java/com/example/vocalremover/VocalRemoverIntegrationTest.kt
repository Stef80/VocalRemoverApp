package com.example.vocalremover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Checks the stereo chunking + peak normalization logic WITHOUT loading the real
 * ONNX model (too heavy/slow for a JVM unit test): we test directly
 * MdxChunker.processStereo with a fake (identity) "model", to guarantee that if the real
 * model returned its input unchanged, the output would match the original exactly
 * (no loss/introduced offset in chunking).
 */
class VocalRemoverIntegrationTest {

    @Test
    fun `stereo chunker passthrough reconstructs both channels`() = runBlocking {
        val chunker = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        val left = FloatArray(50) { (it % 5).toFloat() * 0.1f }
        val right = FloatArray(50) { (it % 7).toFloat() * 0.05f }

        val (outL, outR) = chunker.processStereo(left, right) { chunkL, chunkR -> Pair(chunkL, chunkR) }

        assertEquals(left.size, outL.size)
        assertEquals(right.size, outR.size)
        for (i in left.indices) {
            assertEquals("left[$i]", left[i], outL[i], 1e-3f)
            assertEquals("right[$i]", right[i], outR[i], 1e-3f)
        }
    }

    @Test
    fun `stereo chunker keeps both channels synchronized on same chunk boundaries`() = runBlocking {
        val chunker = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        val left = FloatArray(37) { 1f }
        val right = FloatArray(37) { 2f }

        var callCount = 0
        val (outL, outR) = chunker.processStereo(left, right) { chunkL, chunkR ->
            assertEquals("both channels must have same chunk size", chunkL.size, chunkR.size)
            callCount++
            Pair(chunkL, chunkR)
        }

        assert(callCount > 0)
        assertEquals(37, outL.size)
        assertEquals(37, outR.size)
    }

    @Test
    fun `copies model asset to file without readBytes helper`() {
        val modelBytes = ByteArray(32 * 1024) { (it % 251).toByte() }
        val outputFile = File("build/test-model-copy/${System.nanoTime()}_model.onnx")
        outputFile.parentFile?.mkdirs()

        VocalRemover.copyAssetStreamToFile(
            input = modelBytes.inputStream(),
            outputFile = outputFile
        )

        assertFalse(outputFile.length() == 0L)
        assertEquals(modelBytes.size.toLong(), outputFile.length())
        assertEquals(modelBytes.toList(), outputFile.readBytes().toList())

        outputFile.delete()
    }

    @Test
    fun `chunker can write directly into output arrays without changing reconstruction`() = runBlocking {
        val chunker = MdxChunker(nFft = 6, hopLength = 2, dimT = 8, overlap = 0.25)
        val left = FloatArray(50) { (it % 5).toFloat() * 0.1f }
        val right = FloatArray(50) { (it % 7).toFloat() * 0.05f }
        val outL = FloatArray(left.size)
        val outR = FloatArray(right.size)

        chunker.processStereoInto(left, right, outL, outR) { chunkL, chunkR -> Pair(chunkL, chunkR) }

        assertEquals(left.size, outL.size)
        assertEquals(right.size, outR.size)
        for (i in left.indices) {
            assertEquals("left[$i]", left[i], outL[i], 1e-3f)
            assertEquals("right[$i]", right[i], outR[i], 1e-3f)
        }
    }
}
