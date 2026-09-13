package com.example.vocalremover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StftProcessorTest {

    @Test
    fun `frame count includes final partial frame`() {
        val processor = StftProcessor(nFft = 8, hopLength = 4)

        assertEquals(1, processor.frameCount(1))
        assertEquals(1, processor.frameCount(8))
        assertEquals(2, processor.frameCount(9))
        assertEquals(3, processor.frameCount(16))
        assertEquals(4, processor.frameCount(17))
    }

    @Test
    fun `streaming istft reconstructs full signal including tail`() = runBlocking {
        val processor = StftProcessor(nFft = 16, hopLength = 4)
        val signal = FloatArray(23) { index ->
            sin((2.0 * PI * index / 11.0)).toFloat() * 0.5f
        }

        val frameCount = processor.frameCount(signal.size)
        val (stftRe, stftIm) = processor.stftRange(signal, 0, frameCount)
        val streaming = processor.StreamingIstft()
        val reconstructed = FloatArray(signal.size)
        var writeIndex = 0

        repeat(frameCount) { frame ->
            val chunk = streaming.pushFrame(stftRe, stftIm, frame * processor.freqBins)
            for (sample in chunk) {
                if (writeIndex < reconstructed.size) {
                    reconstructed[writeIndex] = sample
                }
                writeIndex++
            }
        }
        val tail = streaming.flush()
        for (sample in tail) {
            if (writeIndex < reconstructed.size) {
                reconstructed[writeIndex] = sample
            }
            writeIndex++
        }

        val wholeSignal = processor.istft(stftRe, stftIm, signal.size)

        assertArrayEquals(signal, reconstructed, 1e-4f)
        assertArrayEquals(wholeSignal, reconstructed, 1e-4f)
    }
}
