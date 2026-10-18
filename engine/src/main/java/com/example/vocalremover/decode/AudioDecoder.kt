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

/** Il file non si può decodificare; [message] dice perché (solo log, non per l'utente). */
class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodifica qualsiasi file audio supportato da MediaCodec in PCM stereo float a 44,1 kHz.
 * Il mono finisce uguale su L e R; oltre due canali si tengono i primi due.
 */
@OptIn(UnstableApi::class)
class AudioDecoder(context: Context) {

    companion object {
        private const val TAG = "AudioDecoder"
        const val SAMPLE_RATE = 44100
        private const val TIMEOUT_US = 10_000L
    }

    private val appContext = context.applicationContext

    /** Bloccante: va chiamata fuori dal main thread. */
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

            // Valori del contenitore: il decoder può cambiarli (es. HE-AAC con SBR/PS dichiarato
            // 22050 Hz mono ed emesso a 44100 Hz stereo), quindi si aggiornano su INFO_OUTPUT_FORMAT_CHANGED.
            var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs    = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val mime          = format.getString(MediaFormat.KEY_MIME)!!

            Log.i(TAG, "Decodifica audio iniziata: sampleRate=$srcSampleRate channels=$srcChannels durationUs=$durationUs")

            // Stima in frame (indipendente da 16/32 bit). Margine extra per evitare resize.
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
                // Dopo l'EOS in ingresso non si accoda più nulla: alcuni codec lanciano
                // IllegalStateException se ricevono buffer dopo BUFFER_FLAG_END_OF_STREAM.
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
                        // Pattern documentato: limitare alla finestra valida
                        byteBuf.position(info.offset)
                        byteBuf.limit(info.offset + info.size)

                        // Crescita geometrica per ridurre i copyOf
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
                    // getInteger(key, default) esiste solo da API 29: minSdk è 26.
                    isPcmFloat = PcmDeinterleaver.isFloatEncoding(
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else null
                    )
                    Log.i(TAG, "Formato di uscita del decoder: sampleRate=$srcSampleRate channels=$srcChannels float=$isPcmFloat")
                }
            }

            if (sampleIndex == 0) throw DecodeException("Nessun campione decodificato: $uri")
            Log.i(TAG, "Decodifica audio completata: decodedFrames=$sampleIndex")

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
            // Rilascio anche sul percorso d'errore: le istanze di decoder hardware sono limitate.
            codec?.let {
                try { it.stop() } catch (e: IllegalStateException) { Log.w(TAG, "Codec già fermo", e) }
                it.release()
            }
            extractor.release()
            Log.i(TAG, "Decodifica terminata in ${System.currentTimeMillis() - start}ms")
        }
    }
}
