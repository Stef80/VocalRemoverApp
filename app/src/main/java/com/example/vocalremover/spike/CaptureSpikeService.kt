package com.example.vocalremover.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Servizio throwaway per lo spike di validazione della cattura audio di
 * sistema (vedi
 * docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md).
 * NON fa parte della pipeline di produzione: registra alcuni secondi di
 * PCM da una sola app sorgente (filtrata per UID) in un file WAV grezzo
 * su `getExternalFilesDir`, e rileva se la sorgente blocca la cattura
 * (silenzio) invece di fallire in modo silente.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CaptureSpikeService : Service() {

    companion object {
        private const val TAG = "CaptureSpike"
        private const val CHANNEL_ID = "capture_spike_channel"
        private const val NOTIFICATION_ID = 4242
        private const val SAMPLE_RATE = 48000
        private const val CAPTURE_CHANNEL_COUNT = 2
        private const val SPIKE_DURATION_MS = 10_000L
        private const val SILENCE_CHECK_MS = 500L

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_UID = "target_uid"

        /** Ultimo esito testuale dello spike, letto dalla UI dopo che il servizio si ferma. */
        @Volatile
        var lastOutcome: String = "Nessuno spike eseguito ancora"
            private set
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection fermato dal sistema")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val targetUid = intent?.getIntExtra(EXTRA_TARGET_UID, -1) ?: -1
        Log.d(TAG, "onStartCommand: resultCode=$resultCode, resultData=$resultData, targetUid=$targetUid")

        if (resultData == null || targetUid < 0) {
            Log.e(TAG, "Intent di avvio incompleto, arresto")
            lastOutcome = "Errore: dati di avvio incompleti"
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection ha restituito null")
            lastOutcome = "Errore: consenso MediaProjection non valido"
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        scope.launch {
            runCaptureSpike(projection, targetUid)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecord?.release()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        job.cancel()
    }

    private fun runCaptureSpike(projection: MediaProjection, targetUid: Int) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUid(targetUid)
            .build()

        val channelMask = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, encoding)
        if (minBufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize non valido: $minBufferSize")
            lastOutcome = "Errore: configurazione audio non supportata su questo device"
            return
        }

        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non inizializzato")
            lastOutcome = "Errore: impossibile inizializzare la cattura"
            record.release()
            return
        }

        val outputFile = File(getExternalFilesDir(null), "capture_spike.wav")
        val writer = WavFileWriter(outputFile, SAMPLE_RATE, CAPTURE_CHANNEL_COUNT)

        record.startRecording()
        try {
            captureLoop(record, writer, outputFile, targetUid)
        } finally {
            record.stop()
            writer.close()
        }
    }

    private fun captureLoop(
        record: AudioRecord,
        writer: WavFileWriter,
        outputFile: File,
        targetUid: Int
    ) {
        val bytesPerSample = 2 // PCM16
        val readBuffer = ByteArray(minOf(record.bufferSizeInFrames * bytesPerSample * CAPTURE_CHANNEL_COUNT, 8192))
        val bytesForSilenceCheck =
            (SAMPLE_RATE * CAPTURE_CHANNEL_COUNT * bytesPerSample * SILENCE_CHECK_MS / 1000).toInt()
        val silenceCheckBytes = ArrayList<Byte>(bytesForSilenceCheck)
        var bytesReadForCheck = 0
        var blocked = false
        var silenceCheckDone = false
        var totalBytesRead = 0L
        val targetTotalBytes = SAMPLE_RATE.toLong() * CAPTURE_CHANNEL_COUNT * bytesPerSample * SPIKE_DURATION_MS / 1000

        while (totalBytesRead < targetTotalBytes) {
            val read = record.read(readBuffer, 0, readBuffer.size)
            if (read <= 0) continue

            if (!silenceCheckDone) {
                for (i in 0 until read) silenceCheckBytes.add(readBuffer[i])
                bytesReadForCheck += read
                if (bytesReadForCheck >= bytesForSilenceCheck) {
                    silenceCheckDone = true
                    val samples = bytesToFloatSamples(silenceCheckBytes.toByteArray())
                    blocked = RmsSilenceDetector.isSilent(samples)
                    if (blocked) {
                        Log.w(TAG, "SPIKE_CAPTURE_BLOCKED: uid=$targetUid non catturabile (silenzio)")
                        lastOutcome = "BLOCCATA: la sorgente scelta (uid=$targetUid) non consente la cattura (nessun audio ricevuto)"
                        return
                    }
                    Log.d(TAG, "SPIKE_CAPTURE_OK: segnale rilevato, proseguo la registrazione")
                    // Scrive anche i byte già accumulati per il check, per non perderli.
                    writer.write(silenceCheckBytes.toByteArray(), 0, silenceCheckBytes.size)
                    totalBytesRead += silenceCheckBytes.size
                }
            } else {
                writer.write(readBuffer, 0, read)
                totalBytesRead += read
            }
        }

        if (!blocked) {
            lastOutcome = "OK: registrati ${writer.totalDataBytes} byte in ${outputFile.absolutePath}"
            Log.i(TAG, "SPIKE_CAPTURE_OK: $lastOutcome")
        }
    }

    private fun bytesToFloatSamples(bytes: ByteArray): FloatArray {
        val shortCount = bytes.size / 2
        val samples = FloatArray(shortCount)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            samples[i] = buffer.short / 32768f
        }
        return samples
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Spike cattura audio",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spike: registrazione audio in corso")
            .setContentText("Validazione tecnica, si ferma automaticamente")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
