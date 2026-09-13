package com.example.vocalremover.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RecordingSaver {
    private const val BASE_RELATIVE_PATH = "Music/VocalRemover"
    private const val RAW_RELATIVE_PATH = "$BASE_RELATIVE_PATH/raw"

    fun saveProcessedStereoAsWav(
        context: Context,
        stereoPcm: com.example.vocalremover.StereoPcm,
        displayName: String
    ): Uri {
        val resolver = context.contentResolver
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
            val wavBytes = WavPcm16ProcessedSerializer.toWavBytes(stereoPcm.left, stereoPcm.right)
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(wavBytes)
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

            return uri
        } catch (failure: Throwable) {
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
            val wavBytes = WavPcm16StereoSerializer.toWavBytes(stereoPcm.left, stereoPcm.right)
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(wavBytes)
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

            return uri
        } catch (failure: Throwable) {
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

    private fun Float.toPcm16(): Short = when {
        this <= -1f -> Short.MIN_VALUE
        this >= 1f -> Short.MAX_VALUE
        else -> (this * Short.MAX_VALUE).toInt().toShort()
    }
}

internal object WavPcm16ProcessedSerializer {
    fun toWavBytes(left: FloatArray, right: FloatArray): ByteArray =
        WavPcm16StereoSerializer.toWavBytes(left, right)
}
