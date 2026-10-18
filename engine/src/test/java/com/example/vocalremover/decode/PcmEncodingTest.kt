package com.example.vocalremover.decode

import android.media.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmEncodingTest {

    @Test
    fun `missing encoding means 16 bit`() {
        assertFalse(PcmDeinterleaver.isFloatEncoding(null))
    }

    @Test
    fun `16 bit and float are supported`() {
        assertFalse(PcmDeinterleaver.isFloatEncoding(AudioFormat.ENCODING_PCM_16BIT))
        assertTrue(PcmDeinterleaver.isFloatEncoding(AudioFormat.ENCODING_PCM_FLOAT))
    }

    @Test
    fun `other encodings are rejected instead of read as 16 bit`() {
        for (encoding in listOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
        )) {
            assertThrows("encoding=$encoding", DecodeException::class.java) {
                PcmDeinterleaver.isFloatEncoding(encoding)
            }
        }
    }
}
