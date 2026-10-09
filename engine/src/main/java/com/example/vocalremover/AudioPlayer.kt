package com.example.vocalremover

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Gestisce la decodifica del file audio, il processo di rimozione vocale
 * e la riproduzione del risultato tramite AudioTrack in modalità streaming.
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
    }

    private var audioTrack: AudioTrack? = null
    private var processedPcm: StereoPcm? = null
    private var playbackJob: Job? = null
    private var processingJob: Job? = null
    private var playheadPosition = 0 // frames

    var onProgress: (Int) -> Unit = {}
    var onPlaybackPositionChanged: (Int, Int) -> Unit = { _, _ -> }
    var onReady: () -> Unit = {}
    var onError: (String) -> Unit = {}

    val isPlaying: Boolean get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    val isReady: Boolean get() = processedPcm != null
    val processedStereo: StereoPcm? get() = processedPcm

    fun loadProcessedStereo(stereoPcm: StereoPcm) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "loadProcessedStereo deve essere chiamato dal thread principale"
        }

        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.flush()
        processedPcm = stereoPcm
        prepareAudioTrack()
        onReady()
    }

    fun cancelProcessing() {
        processingJob?.cancel(CancellationException("Processing cancelled by lifecycle change"))
        processingJob = null
    }

    suspend fun loadAndProcess(uri: Uri, remover: VocalRemover) {
        val currentJob = coroutineContext[Job]
        processingJob = currentJob
        try {
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Avvio caricamento audio: uri=$uri")
                    onProgress(2)

                    // Decodifica e processamento in blocchi per limitare i riferimenti contemporanei ad array giganti
                    val instrumental = decodeAudio(uri)?.let { mix ->
                        Log.i(TAG, "Caricamento completato: frames=${mix.size}")
                        Log.i(TAG, "Avvio rimozione voce: frames=${mix.size}")
                        val result = remover.removeVocals(mix) { progress ->
                            onProgress(progress)
                        }
                        Log.i(TAG, "Rimozione voce completata: frames=${result.size}")
                        result
                    }

                    if (instrumental == null) {
                        withContext(Dispatchers.Main) { onError("Impossibile decodificare o elaborare il file audio") }
                        return@withContext
                    }

                    processedPcm = instrumental

                    System.gc() // Suggerimento per ripulire mix originale e instrumental stereo

                    withContext(Dispatchers.Main) {
                        prepareAudioTrack()
                        onReady()
                    }

                } catch (cancelled: CancellationException) {
                    Log.i(TAG, "Elaborazione audio annullata dal ciclo di vita", cancelled)
                    return@withContext
                } catch (e: Exception) {
                    Log.e(TAG, "Errore elaborazione", e)
                    withContext(Dispatchers.Main) { onError(e.message ?: "Errore sconosciuto") }
                }
            }
        } finally {
            if (processingJob === currentJob) {
                processingJob = null
            }
        }
    }

    suspend fun loadSavedRecording(uri: Uri) = withContext(Dispatchers.IO) {
        val stereoPcm = decodeAudio(uri)
            ?: throw IllegalStateException("Impossibile aprire la registrazione salvata")
        withContext(Dispatchers.Main) {
            loadProcessedStereo(stereoPcm)
        }
    }

    private fun decodeAudio(uri: Uri): StereoPcm? {
        val start = System.currentTimeMillis()
        val extractor = MediaExtractorCompat(context)
        try {
            extractor.setDataSource(uri, 0L)
            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIdx = i; format = f; break }
            }
            if (audioTrackIdx < 0 || format == null) return null

            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
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

            // Buffer temporanei riutilizzabili (evitano allocazioni per-buffer)
            var tmpF = FloatArray(0)
            var tmpS = ShortArray(0)

            extractor.selectTrack(audioTrackIdx)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isPcmFloat = false
            var eosOut = false

            while (!eosOut) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val byteBuf = codec.getOutputBuffer(outIdx)!!
                        byteBuf.order(ByteOrder.LITTLE_ENDIAN)
                        // Pattern documentato: limitare alla finestra valida
                        byteBuf.position(info.offset)
                        byteBuf.limit(info.offset + info.size)

                        val bytesPerSample = if (isPcmFloat) 4 else 2
                        val frameBytes = bytesPerSample * srcChannels
                        val numFrames = info.size / frameBytes

                        // Crescita geometrica per ridurre i copyOf
                        val needed = sampleIndex + numFrames
                        if (needed > left.size) {
                            var newSize = left.size
                            while (newSize < needed) newSize = (newSize * 3) / 2 + 1024
                            left  = left.copyOf(newSize)
                            right = right.copyOf(newSize)
                        }

                        if (isPcmFloat) {
                            val fb = byteBuf.asFloatBuffer()
                            when (srcChannels) {
                                1 -> {
                                    fb.get(left, sampleIndex, numFrames)
                                    System.arraycopy(left, sampleIndex, right, sampleIndex, numFrames)
                                }
                                2 -> {
                                    val total = numFrames shl 1
                                    if (tmpF.size < total) tmpF = FloatArray(total)
                                    fb.get(tmpF, 0, total)
                                    var oi = sampleIndex
                                    var ti = 0
                                    for (i in 0 until numFrames) {
                                        left[oi]  = tmpF[ti]
                                        right[oi] = tmpF[ti + 1]
                                        oi++; ti += 2
                                    }
                                }
                                else -> {
                                    val total = numFrames * srcChannels
                                    if (tmpF.size < total) tmpF = FloatArray(total)
                                    fb.get(tmpF, 0, total)
                                    var oi = sampleIndex
                                    var ti = 0
                                    for (i in 0 until numFrames) {
                                        left[oi]  = tmpF[ti]
                                        right[oi] = tmpF[ti + 1]
                                        oi++; ti += srcChannels
                                    }
                                }
                            }
                        } else {
                            val sb = byteBuf.asShortBuffer()
                            val inv = 1f / 32768f
                            when (srcChannels) {
                                1 -> {
                                    val total = numFrames
                                    if (tmpS.size < total) tmpS = ShortArray(total)
                                    sb.get(tmpS, 0, total)
                                    var oi = sampleIndex
                                    for (i in 0 until total) {
                                        val v = tmpS[i] * inv
                                        left[oi]  = v
                                        right[oi] = v
                                        oi++
                                    }
                                }
                                2 -> {
                                    val total = numFrames shl 1
                                    if (tmpS.size < total) tmpS = ShortArray(total)
                                    sb.get(tmpS, 0, total)
                                    var oi = sampleIndex
                                    var ti = 0
                                    for (i in 0 until numFrames) {
                                        left[oi]  = tmpS[ti]     * inv
                                        right[oi] = tmpS[ti + 1] * inv
                                        oi++; ti += 2
                                    }
                                }
                                else -> {
                                    val total = numFrames * srcChannels
                                    if (tmpS.size < total) tmpS = ShortArray(total)
                                    sb.get(tmpS, 0, total)
                                    var oi = sampleIndex
                                    var ti = 0
                                    for (i in 0 until numFrames) {
                                        left[oi]  = tmpS[ti]     * inv
                                        right[oi] = tmpS[ti + 1] * inv
                                        oi++; ti += srcChannels
                                    }
                                }
                            }
                        }
                        sampleIndex += numFrames
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    isPcmFloat = outFormat.getInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT
                    ) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            codec.stop(); codec.release()

            val decodedL = if (sampleIndex == left.size)  left  else left.copyOf(sampleIndex)
            val decodedR = if (sampleIndex == right.size) right else right.copyOf(sampleIndex)
            Log.i(TAG, "Decodifica audio completata: decodedFrames=$sampleIndex")

            if (srcSampleRate == SAMPLE_RATE) return StereoPcm(decodedL, decodedR)

            val ratio  = srcSampleRate.toDouble() / SAMPLE_RATE
            val outLen = (decodedL.size / ratio).toInt()
            Log.i(TAG, "Resampling audio: from=$srcSampleRate to=$SAMPLE_RATE outFrames=$outLen")

            val resampledL = FloatArray(outLen)
            val resampledR = FloatArray(outLen)
            val lastIdx = decodedL.size - 1
            for (i in 0 until outLen) {
                val pos  = i * ratio
                val lo   = pos.toInt()              // sempre <= lastIdx per costruzione
                val hi   = if (lo < lastIdx) lo + 1 else lo
                val frac = (pos - lo).toFloat()
                val inv  = 1f - frac
                resampledL[i] = decodedL[lo] * inv + decodedL[hi] * frac
                resampledR[i] = decodedR[lo] * inv + decodedR[hi] * frac
            }
            return StereoPcm(resampledL, resampledR)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            extractor.release()
            Log.i(TAG, "Decodifica completata in ${System.currentTimeMillis() - start}ms")
        }
    }

    private fun prepareAudioTrack() {
        audioTrack?.release()
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(CHANNELS).setEncoding(ENCODING).build())
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        playheadPosition = 0
    }

    fun play() {
        val track = audioTrack ?: return
        val pcm = processedPcm ?: return
        if (isPlaying) return

        track.play()
        playbackJob?.cancel()
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            val totalFrames = pcm.size
            val totalMs = (totalFrames * 1000L / SAMPLE_RATE).toInt()
            val chunkSize = 4096
            val interleaved = FloatArray(chunkSize * 2)
            
            while (isActive && playheadPosition < totalFrames && track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val remaining = totalFrames - playheadPosition
                    val toWrite = minOf(remaining, chunkSize)

                    for (index in 0 until toWrite) {
                        val frameIndex = playheadPosition + index
                        val outIndex = index * 2
                        interleaved[outIndex] = pcm.left[frameIndex]
                        interleaved[outIndex + 1] = pcm.right[frameIndex]
                    }

                    val written = track.write(
                        interleaved,
                        0,
                        toWrite * 2,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written > 0) {
                        playheadPosition += written / 2
                        val currentMs = (playheadPosition * 1000L / SAMPLE_RATE).toInt()
                        withContext(Dispatchers.Main) {
                            onPlaybackPositionChanged(currentMs, totalMs)
                        }
                    }
                } else {
                    delay(50) // Pausato
                }
            }
            if (playheadPosition >= totalFrames) {
                withContext(Dispatchers.Main) { stop() }
            }
        }
    }

    fun pause() {
        audioTrack?.pause()
    }

    fun stop() {
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.flush()
        playheadPosition = 0
        val totalMs = durationMs
        onPlaybackPositionChanged(0, totalMs)
    }

    fun seekTo(ms: Int) {
        val pcm = processedPcm ?: return
        val newPosition = (ms.toLong() * SAMPLE_RATE / 1000).toInt().coerceIn(0, pcm.size)
        
        val wasPlaying = isPlaying
        if (wasPlaying) pause()
        
        audioTrack?.flush()
        playheadPosition = newPosition
        
        onPlaybackPositionChanged(ms, durationMs)
        
        if (wasPlaying) play()
    }

    val durationMs: Int
        get() = processedPcm?.let { (it.size * 1000L / SAMPLE_RATE).toInt() } ?: 0

    fun release() {
        cancelProcessing()
        stop()
        audioTrack?.release()
        audioTrack = null
        processedPcm = null
    }
}
