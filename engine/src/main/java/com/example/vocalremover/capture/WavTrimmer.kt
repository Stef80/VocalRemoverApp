package com.example.vocalremover.capture

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Taglia un WAV PCM 16 bit copiando in streaming un intervallo del chunk data, senza decodificare
 * in float: memoria costante anche su catture lunghe. Scrive su `<target>.tmp` e lo rinomina solo
 * a copia completa, così un taglio interrotto non lascia un file rotto né tocca il target precedente.
 */
object WavTrimmer {

    const val MIN_LENGTH_MS = 1000L
    private const val COPY_BUFFER_BYTES = 64 * 1024

    /** Bloccante. Copia [startMs, endMs) di [source] in [target]. */
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
        // La fine esattamente alla durata (troncata) deve includere anche gli ultimi frame.
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
                if (!temp.renameTo(target)) throw IOException("Impossibile rinominare ${temp.name}")
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
