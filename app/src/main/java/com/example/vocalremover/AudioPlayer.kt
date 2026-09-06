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
            
            // Decodifica e processamento in blocchi per limitare i riferimenti contemporanei ad array giganti
            val instrumental = decodeAudio(uri)?.let { mix ->
                Log.d(TAG, "Decodificati ${mix.size} frame stereo")
                val result = remover.removeVocals(mix) { progress ->
                    onProgress(progress)
                }
                result
            }

            if (instrumental == null) {
                withContext(Dispatchers.Main) { onError("Impossibile decodificare o elaborare il file audio") }
                return@withContext
            }

            // A questo punto il mix originale non è più referenziato e può essere rimosso dal GC.
            // Facciamo il downmix strumentale e liberiamo anche la versione stereo dello strumentale.
            processedPcm = instrumental.downmix()
            
            System.gc() // Suggerimento per ripulire mix originale e instrumental stereo

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

            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            
            // Stima iniziale dei campioni per evitare troppi ridimensionamenti e array intermedi
            val initialCapacity = if (durationUs > 0) ((durationUs * srcSampleRate) / 1_000_000L).toInt() + 1000 else 1024 * 1024
            var left = FloatArray(initialCapacity)
            var right = FloatArray(initialCapacity)
            var sampleIndex = 0

            extractor.selectTrack(audioTrackIdx)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
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
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        
                        val bytesPerSample = if (isPcmFloat) 4 else 2
                        val numFrames = info.size / (bytesPerSample * srcChannels)
                        
                        // Ridimensiona se la stima iniziale era troppo piccola
                        if (sampleIndex + numFrames > left.size) {
                            val newSize = (left.size * 1.5).toInt() + numFrames
                            left = left.copyOf(newSize)
                            right = right.copyOf(newSize)
                        }

                        for (i in 0 until numFrames) {
                            if (isPcmFloat) {
                                left[sampleIndex] = buf.float
                                right[sampleIndex] = if (srcChannels >= 2) buf.float else left[sampleIndex]
                                // Salta eventuali altri canali (es. 5.1)
                                for (c in 2 until srcChannels) buf.float
                            } else {
                                left[sampleIndex] = buf.short.toFloat() / 32768f
                                right[sampleIndex] = if (srcChannels >= 2) buf.short.toFloat() / 32768f else left[sampleIndex]
                                for (c in 2 until srcChannels) buf.short
                            }
                            sampleIndex++
                        }
                    }
                    
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    isPcmFloat = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            codec.stop(); codec.release()

            // Tronca alla dimensione effettiva
            val decodedL = if (sampleIndex == left.size) left else left.copyOf(sampleIndex)
            val decodedR = if (sampleIndex == right.size) right else right.copyOf(sampleIndex)
            
            if (srcSampleRate == SAMPLE_RATE) return StereoPcm(decodedL, decodedR)

            // Resampling lineare se il file non è a 44.1kHz
            val ratio = srcSampleRate.toDouble() / SAMPLE_RATE
            val outLen = (decodedL.size / ratio).toInt()
            val resampledL = FloatArray(outLen)
            val resampledR = FloatArray(outLen)
            for (i in 0 until outLen) {
                val pos = i * ratio
                val lo = pos.toInt().coerceAtMost(decodedL.size - 1)
                val hi = (lo + 1).coerceAtMost(decodedL.size - 1)
                val frac = (pos - lo).toFloat()
                resampledL[i] = decodedL[lo] * (1f - frac) + decodedL[hi] * frac
                resampledR[i] = decodedR[lo] * (1f - frac) + decodedR[hi] * frac
            }
            return StereoPcm(resampledL, resampledR)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
            return null
        } finally {
            extractor.release()
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
        stop()
        audioTrack?.release()
        audioTrack = null
        processedPcm = null
    }
}
