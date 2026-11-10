package com.example.vocalremover.capture

import com.example.vocalremover.StereoPcm
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFileReader {

    /** [dataOffset]: byte where the samples start (the header is fixed at 44 bytes). */
    data class WavInfo(
        val sampleRate: Int,
        val channelCount: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val frameCount: Long
    )

    class UnsupportedWavFormatException(message: String) : Exception(message)

    private const val HEADER_SIZE = 44
    private const val PCM_FORMAT = 1
    private const val EXPECTED_CHANNEL_COUNT = 2
    private const val EXPECTED_SAMPLE_RATE = 44100
    private const val EXPECTED_BITS_PER_SAMPLE = 16

    fun readInfo(file: File): WavInfo =
        RandomAccessFile(file, "r").use { raf ->
            val header = readHeader(raf, file.length())
            WavInfo(
                sampleRate = header.sampleRate,
                channelCount = header.channelCount,
                blockAlign = header.blockAlign,
                dataOffset = HEADER_SIZE.toLong(),
                frameCount = (header.dataSize / header.blockAlign).toLong()
            )
        }

    /** Duration in ms, truncated. */
    fun durationMs(info: WavInfo): Long = info.frameCount * 1000 / info.sampleRate

    fun readStereoPcm(file: File): StereoPcm =
        RandomAccessFile(file, "r").use { raf ->
            val header = readHeader(raf, file.length())
            val frameCount = header.dataSize / header.blockAlign
            val left = FloatArray(frameCount)
            val right = FloatArray(frameCount)

            repeat(frameCount) { index ->
                left[index] = readPcm16LittleEndian(raf) / 32768f
                right[index] = readPcm16LittleEndian(raf) / 32768f
            }

            StereoPcm(left, right)
        }

    private data class ParsedHeader(
        val sampleRate: Int,
        val channelCount: Int,
        val blockAlign: Int,
        val dataSize: Int
    )

    private fun readHeader(raf: RandomAccessFile, fileLength: Long): ParsedHeader {
        if (fileLength < HEADER_SIZE) {
            throw UnsupportedWavFormatException("WAV file too short: incomplete header")
        }

        raf.seek(0)
        val headerBytes = ByteArray(HEADER_SIZE)
        raf.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

        requireFourCc(header, expected = "RIFF", label = "RIFF")
        val riffChunkSize = header.int
        requireFourCc(header, expected = "WAVE", label = "WAVE")
        requireFourCc(header, expected = "fmt ", label = "fmt ")

        val fmtChunkSize = header.int
        if (fmtChunkSize != 16) {
            throw UnsupportedWavFormatException("Unsupported fmt chunk: expected 16, found $fmtChunkSize")
        }

        val audioFormat = header.short.toInt() and 0xFFFF
        if (audioFormat != PCM_FORMAT) {
            throw UnsupportedWavFormatException("Unsupported WAV format: expected PCM")
        }

        val channelCount = header.short.toInt() and 0xFFFF
        if (channelCount != EXPECTED_CHANNEL_COUNT) {
            throw UnsupportedWavFormatException("WAV file must be stereo (2 channels)")
        }

        val sampleRate = header.int
        if (sampleRate != EXPECTED_SAMPLE_RATE) {
            throw UnsupportedWavFormatException("Temporary WAV file must have a 44100 Hz sample rate")
        }

        val byteRate = header.int
        val blockAlign = header.short.toInt() and 0xFFFF
        val bitsPerSample = header.short.toInt() and 0xFFFF

        if (bitsPerSample != EXPECTED_BITS_PER_SAMPLE) {
            throw UnsupportedWavFormatException("Unsupported sample depth: expected 16 bits")
        }

        val expectedBlockAlign = EXPECTED_CHANNEL_COUNT * (EXPECTED_BITS_PER_SAMPLE / 8)
        if (blockAlign != expectedBlockAlign) {
            throw UnsupportedWavFormatException("Invalid WAV block align: expected $expectedBlockAlign")
        }

        val expectedByteRate = EXPECTED_SAMPLE_RATE * expectedBlockAlign
        if (byteRate != expectedByteRate) {
            throw UnsupportedWavFormatException("Invalid WAV byte rate: expected $expectedByteRate")
        }

        requireFourCc(header, expected = "data", label = "data")
        val dataSize = header.int
        if (dataSize < 0) {
            throw UnsupportedWavFormatException("Invalid data chunk size")
        }
        if (dataSize % blockAlign != 0) {
            throw UnsupportedWavFormatException("Data chunk size not aligned to stereo frames")
        }
        if (riffChunkSize != 36 + dataSize) {
            throw UnsupportedWavFormatException("RIFF size inconsistent with the data chunk")
        }
        if (fileLength != HEADER_SIZE.toLong() + dataSize.toLong()) {
            throw UnsupportedWavFormatException("WAV file length inconsistent with its header")
        }

        return ParsedHeader(
            sampleRate = sampleRate,
            channelCount = channelCount,
            blockAlign = blockAlign,
            dataSize = dataSize
        )
    }

    private fun requireFourCc(buffer: ByteBuffer, expected: String, label: String) {
        val value = ByteArray(4).also(buffer::get).toString(Charsets.US_ASCII)
        if (value != expected) {
            throw UnsupportedWavFormatException("Invalid WAV header: expected $label, found $value")
        }
    }

    private fun readPcm16LittleEndian(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        if (low == -1 || high == -1) {
            throw UnsupportedWavFormatException("Incomplete PCM16 WAV data")
        }
        return ((high shl 8) or low).toShort().toInt()
    }
}
