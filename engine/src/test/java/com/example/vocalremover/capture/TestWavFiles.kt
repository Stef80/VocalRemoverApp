package com.example.vocalremover.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Helpers shared by the captured WAV tests (16-bit stereo PCM).

internal fun writePcm16StereoWav(file: File, interleaved: ShortArray, sampleRate: Int = 44100) {
    val writer = WavFileWriter(file, sampleRate = sampleRate, channelCount = 2)
    writer.write(shortsToLittleEndian(interleaved), 0, interleaved.size * Short.SIZE_BYTES)
    writer.close()
}

/** Temporary folder under build/, deleted at the end of the block. */
internal fun withTempDir(block: (File) -> Unit) {
    val directory = File("build/test-wav/${System.nanoTime()}").apply { mkdirs() }
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}

/** Every frame is recognizable: L = index, R = -index (truncated to Short). */
internal fun rampSamples(frames: Int): ShortArray = ShortArray(frames * 2) { i ->
    val frame = i / 2
    (if (i % 2 == 0) frame else -frame).toShort()
}

internal fun shortsToLittleEndian(samples: ShortArray): ByteArray {
    val bytes = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach(bytes::putShort)
    return bytes.array()
}
