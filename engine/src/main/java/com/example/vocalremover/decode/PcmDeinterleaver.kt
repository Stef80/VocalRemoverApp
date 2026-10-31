package com.example.vocalremover.decode

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts an interleaved PCM buffer output by MediaCodec into the two float L/R arrays. */
internal object PcmDeinterleaver {

    private const val INV_SHORT = 1f / 32768f

    /**
     * Decoder `KEY_PCM_ENCODING` → true if float. Without the key it is 16 bit (MediaCodec default).
     * Other encodings (8, 24, 32-bit integer) read as 16 bit would give noise without an error.
     */
    fun isFloatEncoding(encoding: Int?): Boolean = when (encoding) {
        null, AudioFormat.ENCODING_PCM_16BIT -> false
        AudioFormat.ENCODING_PCM_FLOAT -> true
        else -> throw DecodeException("Codifica PCM non supportata: $encoding")
    }

    /**
     * Reads from `buffer.position()` to `buffer.limit()` and writes into [left]/[right] starting at
     * [offset]. Mono ends up identical on L and R; beyond two channels the first two are kept.
     * The caller guarantees the arrays are large enough. Returns the frames written.
     *
     * No shared temporary array: reads by absolute index, hence thread-safe.
     */
    fun deinterleave(
        buffer: ByteBuffer,
        isFloat: Boolean,
        channels: Int,
        left: FloatArray,
        right: FloatArray,
        offset: Int
    ): Int {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = buffer.remaining() / (bytesPerSample * channels)
        val rightChannel = if (channels == 1) 0 else 1

        if (isFloat) {
            val samples = buffer.asFloatBuffer()
            var si = 0
            for (i in 0 until frames) {
                left[offset + i] = samples.get(si)
                right[offset + i] = samples.get(si + rightChannel)
                si += channels
            }
        } else {
            val samples = buffer.asShortBuffer()
            var si = 0
            for (i in 0 until frames) {
                left[offset + i] = samples.get(si) * INV_SHORT
                right[offset + i] = samples.get(si + rightChannel) * INV_SHORT
                si += channels
            }
        }
        return frames
    }
}
