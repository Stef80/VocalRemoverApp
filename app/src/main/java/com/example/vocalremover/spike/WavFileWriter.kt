package com.example.vocalremover.spike

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scrive incrementalmente un file WAV (PCM 16-bit, interleaved) senza
 * tenere l'intera registrazione in memoria: i dati vengono appesi mano a
 * mano, l'header con le dimensioni finali viene scritto/corretto solo
 * alla chiusura (`close()`).
 */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int
) {
    companion object {
        private const val HEADER_SIZE = 44
        private const val BITS_PER_SAMPLE = 16
    }

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.seek(HEADER_SIZE.toLong()) // placeholder: header scritto/corretto in close()
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "WavFileWriter già chiuso" }
        raf.write(buffer, offset, length)
        dataBytesWritten += length
    }

    fun close() {
        if (closed) return
        writeHeader()
        raf.close()
        closed = true
    }

    val totalDataBytes: Long get() = dataBytesWritten

    private fun writeHeader() {
        val byteRate = sampleRate * channelCount * (BITS_PER_SAMPLE / 8)
        val blockAlign = channelCount * (BITS_PER_SAMPLE / 8)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytesWritten).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // dimensione subchunk fmt
        header.putShort(1) // PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytesWritten.toInt())

        raf.seek(0)
        raf.write(header.array())
    }
}
