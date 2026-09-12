package com.example.vocalremover.capture

import com.example.vocalremover.StereoPcm
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFileReader {

    data class WavInfo(val sampleRate: Int, val channelCount: Int)

    class UnsupportedWavFormatException(message: String) : Exception(message)

    private const val HEADER_SIZE = 44
    private const val PCM_FORMAT = 1
    private const val EXPECTED_CHANNEL_COUNT = 2
    private const val EXPECTED_SAMPLE_RATE = 44100
    private const val EXPECTED_BITS_PER_SAMPLE = 16

    fun readInfo(file: File): WavInfo =
        RandomAccessFile(file, "r").use { raf ->
            val header = readHeader(raf, file.length())
            WavInfo(sampleRate = header.sampleRate, channelCount = header.channelCount)
        }

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
            throw UnsupportedWavFormatException("File WAV troppo corto: header incompleto")
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
            throw UnsupportedWavFormatException("Chunk fmt non supportato: atteso 16, trovato $fmtChunkSize")
        }

        val audioFormat = header.short.toInt() and 0xFFFF
        if (audioFormat != PCM_FORMAT) {
            throw UnsupportedWavFormatException("Formato WAV non supportato: atteso PCM")
        }

        val channelCount = header.short.toInt() and 0xFFFF
        if (channelCount != EXPECTED_CHANNEL_COUNT) {
            throw UnsupportedWavFormatException("Il file WAV deve essere stereo (2 canali)")
        }

        val sampleRate = header.int
        if (sampleRate != EXPECTED_SAMPLE_RATE) {
            throw UnsupportedWavFormatException("Il file WAV temporaneo deve avere sample rate 44100 Hz")
        }

        val byteRate = header.int
        val blockAlign = header.short.toInt() and 0xFFFF
        val bitsPerSample = header.short.toInt() and 0xFFFF

        if (bitsPerSample != EXPECTED_BITS_PER_SAMPLE) {
            throw UnsupportedWavFormatException("Profondità campione non supportata: attesi 16 bit")
        }

        val expectedBlockAlign = EXPECTED_CHANNEL_COUNT * (EXPECTED_BITS_PER_SAMPLE / 8)
        if (blockAlign != expectedBlockAlign) {
            throw UnsupportedWavFormatException("Block align WAV non valido: atteso $expectedBlockAlign")
        }

        val expectedByteRate = EXPECTED_SAMPLE_RATE * expectedBlockAlign
        if (byteRate != expectedByteRate) {
            throw UnsupportedWavFormatException("Byte rate WAV non valido: atteso $expectedByteRate")
        }

        requireFourCc(header, expected = "data", label = "data")
        val dataSize = header.int
        if (dataSize < 0) {
            throw UnsupportedWavFormatException("Dimensione chunk data non valida")
        }
        if (dataSize % blockAlign != 0) {
            throw UnsupportedWavFormatException("Dimensione chunk data non allineata ai frame stereo")
        }
        if (riffChunkSize != 36 + dataSize) {
            throw UnsupportedWavFormatException("Dimensione RIFF incoerente con il chunk data")
        }
        if (fileLength != HEADER_SIZE.toLong() + dataSize.toLong()) {
            throw UnsupportedWavFormatException("Lunghezza file WAV incoerente con l'header")
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
            throw UnsupportedWavFormatException("Header WAV non valido: atteso $label, trovato $value")
        }
    }

    private fun readPcm16LittleEndian(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        if (low == -1 || high == -1) {
            throw UnsupportedWavFormatException("Dati PCM16 WAV incompleti")
        }
        return ((high shl 8) or low).toShort().toInt()
    }
}
