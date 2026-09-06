package com.example.vocalremover

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gestisce la decodifica del file audio, il processo di rimozione vocale
 * e la riproduzione del risultato tramite AudioTrack in modalità streaming.
 */
class AudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
    }

    private var audioTrack: AudioTrack? = null
    private var processedPcm: FloatArray? = null
    private var playbackJob: Job? = null
    private var playheadPosition = 0 // frames

    var onProgress: (Int) -> Unit = {}
    var onPlaybackPositionChanged: (Int, Int) -> Unit = { _, _ -> }
    var onReady: () -> Unit = {}
    var onError: (String) -> Unit = {}

    val isPlaying: Boolean get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    val isReady: Boolean get() = processedPcm != null

    suspend fun loadAndProcess(uri: Uri, remover: VocalRemover) = withContext(Dispatchers.IO) {
        try {
            onProgress(2)
            val rawPcm = decodeAudio(uri)
            if (rawPcm == null || rawPcm.size == 0) {
                withContext(Dispatchers.Main) { onError("Impossibile decodificare il file audio") }
                return@withContext
            }
            Log.d(TAG, "Decodificati ${rawPcm.size} frame stereo")

            val instrumental = remover.removeVocals(rawPcm) { progress ->
                onProgress(progress)
            }

            processedPcm = instrumental.downmix()
            withContext(Dispatchers.Main) {
                prepareAudioTrack()
                onReady()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Errore elaborazione", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "Errore sconosciuto") }
        }
    }

    private fun decodeAudio(uri: Uri): StereoPcm? {
        val extractor = MediaExtractor()
        val rawSamples = ByteArrayOutputStream()
        var srcSampleRate = SAMPLE_RATE
        var srcChannels = 1
        var isPcmFloat = false

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrackIdx = i; format = f; break }
            }
            if (audioTrackIdx < 0 || format == null) return null

            srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            srcChannels  = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(audioTrackIdx)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
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
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    rawSamples.write(bytes)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    isPcmFloat = codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            codec.stop(); codec.release()
        } catch (e: Exception) { return null } finally { extractor.release() }

        val raw = rawSamples.toByteArray()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val floats = if (isPcmFloat) FloatArray(raw.size / 4) { bb.float } else FloatArray(raw.size / 2) { bb.short.toFloat() / 32768f }

        val frameCount = floats.size / srcChannels
        val left = FloatArray(frameCount) { i -> floats[i * srcChannels] }
        val right = FloatArray(frameCount) { i ->
            if (srcChannels >= 2) floats[i * srcChannels + 1] else left[i]
        }

        if (srcSampleRate == SAMPLE_RATE) return StereoPcm(left, right)
        val ratio = srcSampleRate.toDouble() / SAMPLE_RATE
        val outLen = (left.size / ratio).toInt()
        fun resample(input: FloatArray): FloatArray = FloatArray(outLen) { i ->
            val pos = i * ratio
            val lo = pos.toInt().coerceAtMost(input.size - 1)
            val hi = (lo + 1).coerceAtMost(input.size - 1)
            val frac = (pos - lo).toFloat()
            input[lo] * (1f - frac) + input[hi] * frac
        }
        return StereoPcm(resample(left), resample(right))
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
            
            // Chunk size per lo streaming (es. 20ms di audio)
            val chunkSize = 4096 
            
            while (isActive && playheadPosition < totalFrames && track.playState != AudioTrack.PLAYSTATE_STOPPED) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val remaining = totalFrames - playheadPosition
                    val toWrite = minOf(remaining, chunkSize)
                    
                    val written = track.write(pcm, playheadPosition, toWrite, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        playheadPosition += written
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
        val newPosition = (ms.toLong() * SAMPLE_RATE / 1000).toInt().coerceIn(0, processedPcm?.size ?: 0)
        
        val wasPlaying = isPlaying
        if (wasPlaying) pause()
        
        audioTrack?.flush()
        playheadPosition = newPosition
        
        val totalMs = durationMs
        onPlaybackPositionChanged(ms, totalMs)
        
        if (wasPlaying) play()
    }

    val durationMs: Int
        get() = processedPcm?.let { (it.size * 1000L / SAMPLE_RATE).toInt() } ?: 0

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        processedPcm = null
    }
}
