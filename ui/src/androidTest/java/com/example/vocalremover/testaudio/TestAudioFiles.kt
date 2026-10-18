package com.example.vocalremover.testaudio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** File audio generati per i test strumentati: seno a 440 Hz, ampiezza 0.5. */
object TestAudioFiles {

    private const val TONE_HZ = 440.0
    private const val AMPLITUDE = 0.5

    /** PCM 16 bit con header WAV di 44 byte. */
    fun wav(dir: File, name: String, seconds: Double, sampleRate: Int = 44100, channels: Int = 2): File {
        val frames = (seconds * sampleRate).roundToInt()
        return writeWav(File(dir, name), pcm16(frames, sampleRate, channels), sampleRate, channels)
    }

    /** WAV con header valido e nessun campione. */
    fun emptyWav(dir: File, name: String): File =
        writeWav(File(dir, name), ShortArray(0), 44100, 2)

    /** AAC-LC in contenitore MPEG-4, codificato con l'encoder del dispositivo. */
    fun aac(dir: File, name: String, seconds: Double, sampleRate: Int, channels: Int): File {
        val file = File(dir, name).apply { delete() }
        val pcm = pcm16((seconds * sampleRate).roundToInt(), sampleRate, channels)
        val pcmBytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { pcmBytes.putShort(it) }
        pcmBytes.flip()

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            val bytesPerFrame = 2 * channels
            var framesQueued = 0L
            var inputDone = false
            var track = -1
            while (true) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        val chunk = minOf(buf.remaining() / bytesPerFrame * bytesPerFrame, pcmBytes.remaining())
                        val ptsUs = framesQueued * 1_000_000L / sampleRate
                        if (chunk == 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val slice = pcmBytes.duplicate().apply { limit(position() + chunk) }
                            buf.put(slice)
                            pcmBytes.position(pcmBytes.position() + chunk)
                            encoder.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
                            framesQueued += chunk / bytesPerFrame
                        }
                    }
                }
                val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                } else if (outIdx >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        muxer.writeSampleData(track, encoder.getOutputBuffer(outIdx)!!, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            muxer.stop()
        } finally {
            encoder.release()
            muxer.release()
        }
        return file
    }

    /** Byte casuali con estensione audio: nessun estrattore li riconosce. */
    fun garbage(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(Random(42).nextBytes(4096)) }

    private fun pcm16(frames: Int, sampleRate: Int, channels: Int): ShortArray {
        val out = ShortArray(frames * channels)
        for (i in 0 until frames) {
            val v = (AMPLITUDE * sin(2 * PI * TONE_HZ * i / sampleRate) * 32767).roundToInt().toShort()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int, channels: Int): File {
        val dataBytes = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16)
        buf.put("data".toByteArray()).putInt(dataBytes)
        pcm.forEach { buf.putShort(it) }
        file.writeBytes(buf.array())
        return file
    }
}
