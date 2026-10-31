package com.example.vocalremover.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import com.example.vocalremover.StereoPcm

/** The file cannot be decoded; [message] says why (logs only, not for the user). */
class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes any audio file supported by MediaCodec into 44.1 kHz float stereo PCM.
 * Mono ends up identical on L and R; beyond two channels the first two are kept.
 */
@OptIn(UnstableApi::class)
class AudioDecoder(context: Context) {

    companion object {
        private const val TAG = "AudioDecoder"
        const val SAMPLE_RATE = 44100
        private const val TIMEOUT_US = 10_000L
    }

    private val appContext = context.applicationContext

    /** Blocking: must be called off the main thread. */
    fun decode(uri: Uri): StereoPcm {
        val start = System.currentTimeMillis()
        val extractor = MediaExtractorCompat(appContext)
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(uri, 0L)
            } catch (e: Exception) {
                throw DecodeException("File non leggibile: $uri", e)
            }
            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIdx = i; format = f; break }
            }
            if (format == null) throw DecodeException("Nessuna traccia audio: $uri")

            // Container values: the decoder may change them (e.g. HE-AAC with SBR/PS declared
            // 22050 Hz mono and output at 44100 Hz stereo), so they are updated on INFO_OUTPUT_FORMAT_CHANGED.
            var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs    = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime          = format.getString(MediaFormat.KEY_MIME)!!

            Log.i(TAG, "Audio decoding started: sampleRate=$srcSampleRate channels=$srcChannels durationUs=$durationUs")

            // Estimate in frames (independent of 16/32 bit). Extra margin to avoid resizing.
            val initialCapacity = if (durationUs > 0)
                ((durationUs * srcSampleRate) / 1_000_000L).toInt() + 4096
            else 1 shl 20   // 1M frame fallback

            var left  = FloatArray(initialCapacity)
            var right = FloatArray(initialCapacity)
            var sampleIndex = 0

            extractor.selectTrack(audioTrackIdx)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var isPcmFloat = false
            var eosIn = false
            var eosOut = false

            while (!eosOut) {
                // After the input EOS nothing more is queued: some codecs throw
                // IllegalStateException if they receive buffers after BUFFER_FLAG_END_OF_STREAM.
                val inIdx = if (eosIn) -1 else decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosIn = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val byteBuf = decoder.getOutputBuffer(outIdx)!!
                        // Documented pattern: limit to the valid window
                        byteBuf.position(info.offset)
                        byteBuf.limit(info.offset + info.size)

                        // Geometric growth to reduce copyOf calls
                        val frameBytes = (if (isPcmFloat) 4 else 2) * srcChannels
                        val needed = sampleIndex + info.size / frameBytes
                        if (needed > left.size) {
                            var newSize = left.size
                            while (newSize < needed) newSize = (newSize * 3) / 2 + 1024
                            left  = left.copyOf(newSize)
                            right = right.copyOf(newSize)
                        }
                        sampleIndex += PcmDeinterleaver.deinterleave(
                            byteBuf, isPcmFloat, srcChannels, left, right, sampleIndex
                        )
                    }

                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = decoder.outputFormat
                    srcSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannels   = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    // getInteger(key, default) exists only since API 29: minSdk is 26.
                    isPcmFloat = PcmDeinterleaver.isFloatEncoding(
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else null
                    )
                    Log.i(TAG, "Decoder output format: sampleRate=$srcSampleRate channels=$srcChannels float=$isPcmFloat")
                }
            }

            if (sampleIndex == 0) throw DecodeException("Nessun campione decodificato: $uri")
            Log.i(TAG, "Audio decoding completed: decodedFrames=$sampleIndex")

            val decoded = StereoPcm(
                if (sampleIndex == left.size)  left  else left.copyOf(sampleIndex),
                if (sampleIndex == right.size) right else right.copyOf(sampleIndex)
            )
            if (srcSampleRate != SAMPLE_RATE) {
                Log.i(TAG, "Resampling audio: from=$srcSampleRate to=$SAMPLE_RATE")
            }
            return LinearResampler.resample(decoded, srcSampleRate, SAMPLE_RATE)
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("Errore di decodifica: $uri", e)
        } finally {
            // Released on the error path too: hardware decoder instances are limited.
            codec?.let {
                try { it.stop() } catch (e: IllegalStateException) { Log.w(TAG, "Codec already stopped", e) }
                it.release()
            }
            extractor.release()
            Log.i(TAG, "Decoding finished in ${System.currentTimeMillis() - start}ms")
        }
    }
}
