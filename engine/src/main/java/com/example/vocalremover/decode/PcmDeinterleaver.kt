package com.example.vocalremover.decode

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converte un buffer PCM interleaved in uscita da MediaCodec nei due array L/R float. */
internal object PcmDeinterleaver {

    private const val INV_SHORT = 1f / 32768f

    /**
     * `KEY_PCM_ENCODING` del decoder → true se float. Senza chiave è 16 bit (default MediaCodec).
     * Le altre codifiche (8, 24, 32 bit intero) lette come 16 bit darebbero rumore senza errore.
     */
    fun isFloatEncoding(encoding: Int?): Boolean = when (encoding) {
        null, AudioFormat.ENCODING_PCM_16BIT -> false
        AudioFormat.ENCODING_PCM_FLOAT -> true
        else -> throw DecodeException("Codifica PCM non supportata: $encoding")
    }

    /**
     * Legge da `buffer.position()` a `buffer.limit()` e scrive in [left]/[right] a partire da
     * [offset]. Il mono finisce uguale su L e R; oltre due canali si tengono i primi due.
     * Il chiamante garantisce la capienza degli array. Restituisce i frame scritti.
     *
     * Nessun array temporaneo condiviso: lettura per indice assoluto, quindi thread-safe.
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
