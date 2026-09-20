package com.example.vocalremover.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RecordingSaver {
    private const val TAG = "RecordingSaver"
    private const val BASE_RELATIVE_PATH = "Music/VocalRemover"
    private const val RAW_RELATIVE_PATH = "$BASE_RELATIVE_PATH/raw"

    fun saveProcessedStereoAsWav(
        context: Context,
        stereoPcm: com.example.vocalremover.StereoPcm,
        displayName: String
    ): Uri {
        val resolver = context.contentResolver
        Log.i(
            TAG,
            "Avvio salvataggio WAV elaborato: name=$displayName frames=${stereoPcm.size}"
        )
        val uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.wav")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, BASE_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: throw IOException("Impossibile creare il file WAV in MediaStore")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                WavPcm16ProcessedSerializer.writeTo(output, stereoPcm.left, stereoPcm.right)
            } ?: throw IOException("Impossibile aprire il file WAV in MediaStore")

            val updated = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            )
            if (updated != 1) {
                throw IOException("Impossibile pubblicare il file WAV in MediaStore")
            }

            Log.i(TAG, "Salvataggio WAV elaborato completato: uri=$uri")
            return uri
        } catch (failure: Throwable) {
            Log.e(TAG, "Salvataggio WAV elaborato fallito: uri=$uri", failure)
            deleteIncompleteEntry(resolver, uri, failure)
            throw failure
        }
    }

    fun saveStereoCaptureAsWav(
        context: Context,
        stereoPcm: com.example.vocalremover.StereoPcm,
        displayName: String
    ): Uri {
        val resolver = context.contentResolver
        Log.i(
            TAG,
            "Avvio salvataggio WAV raw: name=$displayName frames=${stereoPcm.size}"
        )
        val uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.wav")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RAW_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: throw IOException("Impossibile creare il file WAV raw in MediaStore")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                WavPcm16StereoSerializer.writeTo(output, stereoPcm.left, stereoPcm.right)
            } ?: throw IOException("Impossibile aprire il file WAV raw in MediaStore")

            val updated = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                null,
                null
            )
            if (updated != 1) {
                throw IOException("Impossibile pubblicare il file WAV raw in MediaStore")
            }

            Log.i(TAG, "Salvataggio WAV raw completato: uri=$uri")
            return uri
        } catch (failure: Throwable) {
            Log.e(TAG, "Salvataggio WAV raw fallito: uri=$uri", failure)
            deleteIncompleteEntry(resolver, uri, failure)
            throw failure
        }
    }

    private fun deleteIncompleteEntry(
        resolver: android.content.ContentResolver,
        uri: Uri,
        failure: Throwable
    ) {
        try {
            if (resolver.delete(uri, null, null) != 1) {
                failure.addSuppressed(
                    IOException("Impossibile eliminare il file WAV incompleto da MediaStore")
                )
            }
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }
}

internal object WavPcm16Serializer {
    private const val HEADER_SIZE = 44
    private const val SAMPLE_RATE = 44_100
    private const val CHANNEL_COUNT = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    fun toWavBytes(monoPcm: FloatArray): ByteArray {
        val dataSize = monoPcm.size.toLong() * BYTES_PER_SAMPLE
        require(dataSize <= Int.MAX_VALUE - HEADER_SIZE) {
            "Audio PCM troppo grande per essere serializzato come WAV"
        }

        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataSize.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize.toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(CHANNEL_COUNT.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * blockAlign)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize.toInt())

        monoPcm.forEach { sample ->
            buffer.putShort(sample.toPcm16())
        }

        return buffer.array()
    }

    fun writeTo(output: OutputStream, monoPcm: FloatArray) {
        val dataSize = monoPcm.size.toLong() * BYTES_PER_SAMPLE
        require(dataSize <= Int.MAX_VALUE - HEADER_SIZE) {
            "Audio PCM troppo grande per essere serializzato come WAV"
        }

        output.write(
            wavHeader(
                channelCount = CHANNEL_COUNT,
                sampleRate = SAMPLE_RATE,
                bitsPerSample = BITS_PER_SAMPLE,
                dataSize = dataSize.toInt()
            )
        )
        val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        monoPcm.forEach { sample ->
            if (chunk.remaining() < Short.SIZE_BYTES) {
                output.write(chunk.array(), 0, chunk.position())
                chunk.clear()
            }
            chunk.putShort(sample.toPcm16())
        }
        if (chunk.position() > 0) {
            output.write(chunk.array(), 0, chunk.position())
        }
    }

    private fun Float.toPcm16(): Short = when {
        this <= -1f -> Short.MIN_VALUE
        this >= 1f -> Short.MAX_VALUE
        else -> (this * Short.MAX_VALUE).toInt().toShort()
    }
}

internal object WavPcm16StereoSerializer {
    private const val HEADER_SIZE = 44
    private const val SAMPLE_RATE = 44_100
    private const val CHANNEL_COUNT = 2
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    fun toWavBytes(left: FloatArray, right: FloatArray): ByteArray {
        require(left.size == right.size) { "I canali stereo devono avere la stessa lunghezza" }

        val dataSize = left.size.toLong() * CHANNEL_COUNT * BYTES_PER_SAMPLE
        require(dataSize <= Int.MAX_VALUE - HEADER_SIZE) {
            "Audio PCM troppo grande per essere serializzato come WAV stereo"
        }

        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataSize.toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize.toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(CHANNEL_COUNT.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * blockAlign)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(BITS_PER_SAMPLE.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize.toInt())

        for (index in left.indices) {
            buffer.putShort(left[index].toPcm16())
            buffer.putShort(right[index].toPcm16())
        }

        return buffer.array()
    }

    fun writeTo(output: OutputStream, left: FloatArray, right: FloatArray) {
        require(left.size == right.size) { "I canali stereo devono avere la stessa lunghezza" }

        val dataSize = left.size.toLong() * CHANNEL_COUNT * BYTES_PER_SAMPLE
        require(dataSize <= UInt.MAX_VALUE.toLong()) {
            "Audio PCM troppo grande per essere serializzato come WAV stereo"
        }

        output.write(
            wavHeader(
                channelCount = CHANNEL_COUNT,
                sampleRate = SAMPLE_RATE,
                bitsPerSample = BITS_PER_SAMPLE,
                dataSize = dataSize.toInt()
            )
        )
        val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (index in left.indices) {
            if (chunk.remaining() < CHANNEL_COUNT * BYTES_PER_SAMPLE) {
                output.write(chunk.array(), 0, chunk.position())
                chunk.clear()
            }
            chunk.putShort(left[index].toPcm16())
            chunk.putShort(right[index].toPcm16())
        }
        if (chunk.position() > 0) {
            output.write(chunk.array(), 0, chunk.position())
        }
    }

    private fun Float.toPcm16(): Short = when {
        this <= -1f -> Short.MIN_VALUE
        this >= 1f -> Short.MAX_VALUE
        else -> (this * Short.MAX_VALUE).toInt().toShort()
    }
}

internal object WavPcm16ProcessedSerializer {
    fun toWavBytes(left: FloatArray, right: FloatArray): ByteArray =
        WavPcm16StereoSerializer.toWavBytes(left, right)

    fun writeTo(output: OutputStream, left: FloatArray, right: FloatArray) =
        WavPcm16StereoSerializer.writeTo(output, left, right)
}

private fun wavHeader(
    channelCount: Int,
    sampleRate: Int,
    bitsPerSample: Int,
    dataSize: Int
): ByteArray {
    val blockAlign = channelCount * (bitsPerSample / 8)
    val byteRate = sampleRate * blockAlign
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + dataSize)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(channelCount.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
    }.array()
}
