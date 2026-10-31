package com.example.vocalremover.capture

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Trims a 16-bit PCM WAV by streaming a range of the data chunk, without decoding
 * to float: constant memory even on long captures. Writes to `<target>.tmp` and renames it only
 * once the copy is complete, so an interrupted trim leaves no broken file and does not touch the previous target.
 */
object WavTrimmer {

    const val MIN_LENGTH_MS = 1000L
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** Blocking. Copies [startMs, endMs) of [source] into [target]. */
    fun trim(source: File, target: File, startMs: Long, endMs: Long) =
        trim(source, target, startMs, endMs) { file ->
            val info = WavFileReader.readInfo(source)
            WavFileWriter(file, sampleRate = info.sampleRate, channelCount = info.channelCount)
        }

    internal fun trim(
        source: File,
        target: File,
        startMs: Long,
        endMs: Long,
        openWriter: (File) -> WavFileWriter
    ) {
        val info = WavFileReader.readInfo(source)
        val durationMs = WavFileReader.durationMs(info)
        require(startMs >= 0) { "Inizio negativo: $startMs" }
        require(endMs <= durationMs) { "Fine oltre la durata: $endMs > $durationMs" }
        require(endMs - startMs >= MIN_LENGTH_MS) { "Intervallo troppo corto: ${endMs - startMs} ms" }

        val startFrame = startMs * info.sampleRate / 1000
        // An end exactly at the (truncated) duration must include the last frames too.
        val endFrame = if (endMs == durationMs) info.frameCount else endMs * info.sampleRate / 1000

        val temp = File(target.path + ".tmp")
        try {
            RandomAccessFile(source, "r").use { input ->
                input.seek(info.dataOffset + startFrame * info.blockAlign)
                val writer = openWriter(temp)
                try {
                    copy(input, writer, (endFrame - startFrame) * info.blockAlign)
                } finally {
                    writer.close()
                }
            }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Cannot rename ${temp.name}")
            }
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    private fun copy(input: RandomAccessFile, writer: WavFileWriter, byteCount: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw IOException("WAV sorgente più corto dell'header")
            writer.write(buffer, 0, read)
            remaining -= read
        }
    }
}
