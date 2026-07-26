package com.example.vocalremover

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Gestisce la decodifica del file audio, il processo di rimozione vocale
 * e la riproduzione del risultato tramite AudioTrack.
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

    var onProgress: (Int) -> Unit = {}
    var onPlaybackPositionChanged: (Int, Int) -> Unit = { _, _ -> }  // currentMs, totalMs
    var onReady: () -> Unit = {}
    var onError: (String) -> Unit = {}

    val isPlaying: Boolean get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    val isReady: Boolean get() = processedPcm != null

    // ── Decodifica + rimozione vocale ────────────────────────────────────────

    /**
     * Decodifica il file audio e applica la rimozione vocale.
     * Operazione asincrona; chiama [onProgress] e alla fine [onReady].
     */
    suspend fun loadAndProcess(uri: Uri, remover: VocalRemover) = withContext(Dispatchers.IO) {
        try {
            // 1. Decodifica come PCM float32 mono a 44100 Hz
            onProgress(2)
            val rawPcm = decodeAudio(uri)
            if (rawPcm.isEmpty()) { onError("Impossibile decodificare il file audio"); return@withContext }
            Log.d(TAG, "Decodificati ${rawPcm.size} campioni (${rawPcm.size / SAMPLE_RATE}s)")

            // 2. Rimozione vocale
            val instrumental = remover.removeVocals(rawPcm) { progress ->
                onProgress(progress)
            }

            // 3. Prepara AudioTrack
            processedPcm = instrumental
            prepareAudioTrack(instrumental)
            withContext(Dispatchers.Main) { onReady() }

        } catch (e: Exception) {
            Log.e(TAG, "Errore elaborazione", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "Errore sconosciuto") }
        }
    }

    // ── Decodifica audio con MediaExtractor + MediaCodec ─────────────────────

    private fun decodeAudio(uri: Uri): FloatArray {
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
            if (audioTrackIdx < 0 || format == null) {
                Log.e(TAG, "Nessuna traccia audio trovata")
                return FloatArray(0)
            }

            srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            srcChannels  = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(audioTrackIdx)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var eosIn = false
            var eosOut = false
            while (!eosOut) {
                if (!eosIn) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosIn = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        rawSamples.write(bytes)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        isPcmFloat = outFmt.getInteger(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT
                        ) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "Errore decodifica audio", e)
            return FloatArray(0)
        } finally {
            extractor.release()
        }

        // Raw bytes → float32
        val raw = rawSamples.toByteArray()
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val floats = if (isPcmFloat) {
            FloatArray(raw.size / 4) { bb.getFloat() }
        } else {
            FloatArray(raw.size / 2) { bb.getShort().toFloat() / 32768f }
        }

        // Stereo → mono
        val mono = if (srcChannels >= 2) {
            FloatArray(floats.size / srcChannels) { i ->
                var sum = 0f
                for (ch in 0 until srcChannels) sum += floats[i * srcChannels + ch]
                sum / srcChannels
            }
        } else floats

        // Resample → SAMPLE_RATE (linear interpolation)
        if (srcSampleRate == SAMPLE_RATE) return mono
        val ratio = srcSampleRate.toDouble() / SAMPLE_RATE
        val outLen = (mono.size / ratio).toInt()
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val lo = pos.toInt().coerceAtMost(mono.size - 1)
            val hi = (lo + 1).coerceAtMost(mono.size - 1)
            val frac = (pos - lo).toFloat()
            mono[lo] * (1f - frac) + mono[hi] * frac
        }
    }

    // ── Riproduzione ─────────────────────────────────────────────────────────

    private fun prepareAudioTrack(pcm: FloatArray) {
        audioTrack?.release()
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096 * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack!!.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    fun play() {
        val track = audioTrack ?: return
        val pcm = processedPcm ?: return
        val totalMs = (pcm.size * 1000L / SAMPLE_RATE).toInt()

        track.play()
        playbackJob?.cancel()
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val positionFrames = track.playbackHeadPosition
                val currentMs = (positionFrames * 1000L / SAMPLE_RATE).toInt()
                withContext(Dispatchers.Main) {
                    onPlaybackPositionChanged(currentMs, totalMs)
                }
                delay(250)
            }
        }
    }

    fun pause() {
        audioTrack?.pause()
        playbackJob?.cancel()
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.reloadStaticData()
        playbackJob?.cancel()
    }

    /** Seek in millisecondi */
    fun seekTo(ms: Int) {
        val wasPlaying = isPlaying
        audioTrack?.pause()
        val framePosition = (ms.toLong() * SAMPLE_RATE / 1000).toInt()
        audioTrack?.setPlaybackHeadPosition(framePosition)
        if (wasPlaying) audioTrack?.play()
    }

    val durationMs: Int
        get() = processedPcm?.let { (it.size * 1000L / SAMPLE_RATE).toInt() } ?: 0

    fun release() {
        playbackJob?.cancel()
        audioTrack?.release()
        audioTrack = null
    }
}
