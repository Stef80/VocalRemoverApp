package com.example.vocalremover

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Looper
import android.util.Log
import com.example.vocalremover.decode.AudioDecoder
import com.example.vocalremover.decode.DecodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val decoder = AudioDecoder(context)
    private var audioTrack: AudioTrack? = null
    private var processedPcm: StereoPcm? = null
    private var playbackJob: Job? = null
    private var processingJob: Job? = null
    // Posizione e generazione si scrivono solo sul thread principale. Il job di riproduzione
    // lavora su una copia locale e la pubblica su Main solo se nessun seek/stop/play lo ha
    // superato nel frattempo: un write() bloccante che rientra dopo il flush non sporca il seek.
    private var playheadPosition = 0 // frames
    @Volatile private var playbackGeneration = 0

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
        playbackGeneration++
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
                    val instrumental = decodeOrNull(uri)?.let { mix ->
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
        val stereoPcm = decodeOrNull(uri)
            ?: throw IllegalStateException("Impossibile aprire la registrazione salvata")
        withContext(Dispatchers.Main) {
            loadProcessedStereo(stereoPcm)
        }
    }

    /** Errori di decodifica → null: la MainActivity legacy mostra il messaggio generico di sempre. */
    private fun decodeOrNull(uri: Uri): StereoPcm? = try {
        decoder.decode(uri)
    } catch (e: DecodeException) {
        Log.e(TAG, "Error decoding audio", e)
        null
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
        val generation = ++playbackGeneration
        val startPosition = playheadPosition
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            val totalFrames = pcm.size
            val totalMs = (totalFrames * 1000L / SAMPLE_RATE).toInt()
            val chunkSize = 4096
            val interleaved = FloatArray(chunkSize * 2)
            var position = startPosition

            while (isActive && generation == playbackGeneration && position < totalFrames &&
                track.playState != AudioTrack.PLAYSTATE_STOPPED
            ) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val remaining = totalFrames - position
                    val toWrite = minOf(remaining, chunkSize)

                    for (index in 0 until toWrite) {
                        val frameIndex = position + index
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
                        position += written / 2
                        val reached = position
                        val currentMs = (reached * 1000L / SAMPLE_RATE).toInt()
                        withContext(Dispatchers.Main) {
                            if (generation == playbackGeneration) {
                                playheadPosition = reached
                                onPlaybackPositionChanged(currentMs, totalMs)
                            }
                        }
                    }
                } else {
                    delay(50) // Pausato
                }
            }
            if (position >= totalFrames) {
                withContext(Dispatchers.Main) {
                    if (generation == playbackGeneration) finishPlayback()
                }
            }
        }
    }

    /**
     * Fine brano: in MODE_STREAM AudioTrack.stop() suona comunque i dati già scritti,
     * quindi niente flush() (taglierebbe la coda dell'instrumentale).
     */
    private fun finishPlayback() {
        audioTrack?.stop()
        playheadPosition = 0
        onPlaybackPositionChanged(0, durationMs)
    }

    fun pause() {
        audioTrack?.pause()
    }

    fun stop() {
        playbackJob?.cancel()
        playbackGeneration++
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
        
        playbackGeneration++
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
