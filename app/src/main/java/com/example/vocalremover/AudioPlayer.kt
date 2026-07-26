package com.example.vocalremover

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import be.tarsos.dsp.io.android.AndroidFFMPEGLocator
import be.tarsos.dsp.io.android.AudioDispatcherFactory
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

    // ── Decodifica audio con TarsosDSP ───────────────────────────────────────

    private fun decodeAudio(uri: Uri): FloatArray {
        val baos = ByteArrayOutputStream()
        try {
            // TarsosDSP usa Android MediaCodec/FFmpeg per decodificare
            val dispatcher = AudioDispatcherFactory.fromPipe(
                context.contentResolver.openInputStream(uri)!!,
                SAMPLE_RATE,
                4096,       // buffer size
                0           // overlap
            )
            dispatcher.addAudioProcessor { audioEvent ->
                // audioEvent.floatBuffer è mono float32 normalizzato
                val buf = audioEvent.floatBuffer
                val bytes = ByteArray(buf.size * 4)
                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.forEach { bb.putFloat(it) }
                baos.write(bytes)
                true
            }
            dispatcher.run()
        } catch (e: Exception) {
            Log.e(TAG, "Errore decodifica TarsosDSP", e)
            // Fallback: leggi direttamente dallo stream come PCM raw se già PCM
        }

        val bytes = baos.toByteArray()
        val floatArray = FloatArray(bytes.size / 4)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in floatArray.indices) floatArray[i] = bb.getFloat()
        return floatArray
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
