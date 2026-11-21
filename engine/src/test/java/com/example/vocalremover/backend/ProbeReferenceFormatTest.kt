package com.example.vocalremover.backend

import com.example.vocalremover.StereoPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ProbeReferenceFormatTest {

    private fun stereo(n: Int): StereoPcm {
        val rnd = Random(1)
        return StereoPcm(
            FloatArray(n) { rnd.nextFloat() * 2 - 1 },
            FloatArray(n) { rnd.nextFloat() * 2 - 1 },
        )
    }

    @Test
    fun `encode then decode round-trips within fp16 precision`() {
        val pcm = stereo(1000)
        val back = ProbeReference.decode(ProbeReference.encode(pcm))
        assertEquals(1000, back.size)
        for (i in 0 until 1000) {
            assertTrue(abs(pcm.left[i] - back.left[i]) <= 1e-3f)
            assertTrue(abs(pcm.right[i] - back.right[i]) <= 1e-3f)
        }
    }

    @Test
    fun `encoded size is 4 bytes per frame`() {
        assertEquals(4000, ProbeReference.encode(stereo(1000)).size)
    }

    @Test
    fun `left channel comes first in little-endian fp16`() {
        val bytes = ProbeReference.encode(StereoPcm(floatArrayOf(1f), floatArrayOf(-2f)))
        // 1.0 = 0x3C00, -2.0 = 0xC000
        assertEquals(listOf<Byte>(0x00, 0x3C, 0x00, 0xC0.toByte()), bytes.toList())
    }

    @Test
    fun `bundled asset holds exactly one probe chunk`() {
        // Unit tests run from the module directory.
        val asset = java.io.File("src/main/assets/${ProbeReference.ASSET}")
        assertTrue("Missing ${asset.absolutePath}", asset.isFile)
        assertEquals(com.example.vocalremover.VocalRemover.CHUNK_FRAMES, ProbeSignal.FRAMES)
        assertEquals(ProbeSignal.FRAMES * 4L, asset.length())
        assertEquals(ProbeSignal.FRAMES, ProbeReference.decode(asset.readBytes()).size)
    }
}
