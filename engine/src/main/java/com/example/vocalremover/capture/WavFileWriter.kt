package com.example.vocalremover.capture

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface WavFileAccess {
    fun setLength(length: Long)
    fun seek(position: Long)
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

private class RandomAccessWavFileAccess(file: File) : WavFileAccess {
    private val file = RandomAccessFile(file, "rw")

    override fun setLength(length: Long) = file.setLength(length)

    override fun seek(position: Long) = file.seek(position)

    override fun write(buffer: ByteArray, offset: Int, length: Int) =
        file.write(buffer, offset, length)

    override fun close() = file.close()
}

/**
 * Writes a WAV file (16-bit PCM, interleaved) incrementally without
 * keeping the whole recording in memory: data is appended as it
 * comes, the header with the final sizes is written/fixed only
 * on close (`close()`).
 */
internal class WavFileWriter(
    private val fileAccess: WavFileAccess,
    private val sampleRate: Int,
    private val channelCount: Int
) {
    companion object {
        private const val HEADER_SIZE = 44
        private const val BITS_PER_SAMPLE = 16

        /**
         * Largest PCM data chunk that fits both the RIFF and data size fields.
         * Four-byte alignment preserves whole stereo PCM16 frames.
         */
        const val MAX_DATA_BYTES: Long = 2_147_483_608L

        fun canAppendData(currentDataBytes: Long, bytesToAppend: Int): Boolean =
            currentDataBytes >= 0 &&
                currentDataBytes <= MAX_DATA_BYTES &&
                bytesToAppend >= 0 &&
                bytesToAppend.toLong() <= MAX_DATA_BYTES - currentDataBytes
    }

    private var dataBytesWritten = 0L
    private var closed = false

    constructor(file: File, sampleRate: Int, channelCount: Int) :
        this(RandomAccessWavFileAccess(file), sampleRate, channelCount)

    init {
        fileAccess.setLength(0)
        fileAccess.seek(HEADER_SIZE.toLong()) // placeholder: header written/fixed in close()
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "WavFileWriter already closed" }
        require(canAppendData(dataBytesWritten, length)) {
            "PCM data exceeds the maximum size supported by the WAV format"
        }
        fileAccess.write(buffer, offset, length)
        dataBytesWritten += length
    }

    fun close() {
        if (closed) return
        var headerFailure: Throwable? = null
        try {
            writeHeader()
        } catch (error: Throwable) {
            headerFailure = error
            throw error
        } finally {
            closed = true
            try {
                fileAccess.close()
            } catch (closeError: Throwable) {
                headerFailure?.addSuppressed(closeError) ?: throw closeError
            }
        }
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
        header.putInt(16) // fmt subchunk size
        header.putShort(1) // PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytesWritten.toInt())

        fileAccess.seek(0)
        fileAccess.write(header.array(), 0, header.array().size)
    }
}
