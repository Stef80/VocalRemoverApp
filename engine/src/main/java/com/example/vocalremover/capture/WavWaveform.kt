package com.example.vocalremover.capture

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

/** Picchi per disegnare la forma d'onda di un WAV PCM 16 bit stereo, letti in streaming. */
object WavWaveform {

    private const val READ_BUFFER_BYTES = 64 * 1024

    /**
     * Bloccante. Restituisce `min(buckets, frameCount)` valori 0–1: per ogni blocco di frame il
     * massimo di |L| e |R|.
     */
    fun peaks(file: File, buckets: Int): FloatArray {
        require(buckets > 0) { "buckets deve essere positivo: $buckets" }
        val info = WavFileReader.readInfo(file)
        val frameCount = info.frameCount
        val count = minOf(buckets.toLong(), frameCount).toInt()
        val peaks = FloatArray(count)
        if (count == 0) return peaks

        BufferedInputStream(file.inputStream(), READ_BUFFER_BYTES).use { input ->
            if (input.skip(info.dataOffset) != info.dataOffset) throw IOException("Header WAV incompleto")
            val buffer = ByteArray(READ_BUFFER_BYTES - READ_BUFFER_BYTES % info.blockAlign)
            var frame = 0L
            while (frame < frameCount) {
                val read = readFully(input, buffer)
                if (read <= 0) throw IOException("Dati PCM16 WAV incompleti")
                var offset = 0
                while (offset + info.blockAlign <= read && frame < frameCount) {
                    val left = abs(pcm16At(buffer, offset).toInt())
                    val right = abs(pcm16At(buffer, offset + 2).toInt())
                    val bucket = (frame * count / frameCount).toInt()
                    peaks[bucket] = max(peaks[bucket], max(left, right) / 32768f)
                    offset += info.blockAlign
                    frame++
                }
            }
        }
        return peaks
    }

    /** Come InputStream.readNBytes, che su Android esiste solo da API 33. */
    private fun readFully(input: BufferedInputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun pcm16At(buffer: ByteArray, offset: Int): Short =
        ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xFF)).toShort()
}
