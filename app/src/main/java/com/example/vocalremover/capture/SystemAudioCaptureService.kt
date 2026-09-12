package com.example.vocalremover.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "system_audio_capture"
        private const val NOTIFICATION_ID = 4242
        private const val STOP_REQUEST_CODE = 1
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val SILENCE_CHECK_MS = 500
        private const val MAX_READ_BUFFER_SIZE = 16 * 1024

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_UID = "target_uid"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        const val ACTION_STOP = "com.example.vocalremover.action.STOP_SYSTEM_AUDIO_CAPTURE"
        const val ACTION_CAPTURE_BLOCKED =
            "com.example.vocalremover.action.SYSTEM_AUDIO_CAPTURE_BLOCKED"
        const val ACTION_CAPTURE_STOPPED =
            "com.example.vocalremover.action.SYSTEM_AUDIO_CAPTURE_STOPPED"
        const val ACTION_CAPTURE_ERROR =
            "com.example.vocalremover.action.SYSTEM_AUDIO_CAPTURE_ERROR"
        const val ACTION_CAPTURE_PROCESSING_AVAILABLE =
            "com.example.vocalremover.action.SYSTEM_AUDIO_CAPTURE_PROCESSING_AVAILABLE"
    }

    private sealed interface CaptureResult {
        data object Blocked : CaptureResult
        data class Stopped(val outputFile: File) : CaptureResult
        data class Error(val message: String) : CaptureResult
    }

    private class CaptureBlockedException : Exception()

    private class CaptureException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val captureStarted = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val terminalBroadcastSent = AtomicBoolean(false)
    private val foregroundStarted = AtomicBoolean(false)
    private val projectionStopExpected = AtomicBoolean(false)
    private val projectionReleased = AtomicBoolean(false)
    private val audioRecordReleased = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val projectionFailure = AtomicReference<String?>(null)
    private val audioRecordLock = Any()
    private val projectionLock = Any()

    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!projectionStopExpected.get()) {
                projectionFailure.compareAndSet(
                    null,
                    "La cattura è stata interrotta dal sistema"
                )
                requestStop()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (captureJob?.isActive == true) {
                requestStop()
            } else {
                stopForegroundSafely()
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        if (!captureStarted.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }

        val resultData = intent.resultData()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val targetUid = intent?.getIntExtra(EXTRA_TARGET_UID, -1) ?: -1
        if (
            intent == null ||
            !intent.hasExtra(EXTRA_RESULT_CODE) ||
            resultCode == Int.MIN_VALUE ||
            resultData == null ||
            targetUid < 0
        ) {
            emitError("Dati di avvio della cattura non validi")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted.set(true)

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw CaptureException("Consenso MediaProjection non valido")
            if (!attachMediaProjection(projection)) {
                throw CaptureException("La cattura è stata annullata prima dell'avvio")
            }

            captureJob = serviceScope.launch {
                capture(projection, targetUid)
            }
        } catch (error: Exception) {
            releaseMediaProjection()
            stopForegroundSafely()
            emitError(error.userMessage("Impossibile avviare la cattura"))
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        requestStop()
        stopAndReleaseAudioRecord()
        releaseMediaProjection()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun capture(projection: MediaProjection, targetUid: Int) {
        var writer: WavFileWriter? = null
        var outputFile: File? = null
        var result: CaptureResult? = null
        var recordingStarted = false
        var pcmBytesWritten = 0L

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUid(targetUid)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                throw CaptureException("Configurazione audio non supportata da questo dispositivo")
            }

            val captureFile = File(
                cacheDir,
                "system_audio_capture_${System.currentTimeMillis()}.wav"
            )
            outputFile = captureFile
            val captureWriter = WavFileWriter(captureFile, SAMPLE_RATE, CHANNEL_COUNT)
            writer = captureWriter

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            installAudioRecord(record)

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw CaptureException("Impossibile inizializzare la cattura audio")
            }

            if (!stopRequested.get()) {
                recordingStarted = startRecording(record)
                if (!recordingStarted && !stopRequested.get()) {
                    throw CaptureException("Impossibile avviare la cattura audio")
                }
                if (recordingStarted) {
                    pcmBytesWritten = captureLoop(record, captureWriter)
                }
            }

            projectionFailure.get()?.let { throw CaptureException(it) }
            result = when {
                !recordingStarted ->
                    CaptureResult.Error("Cattura annullata prima dell'avvio della registrazione")
                pcmBytesWritten == 0L ->
                    CaptureResult.Error("Cattura annullata: nessun audio acquisito")
                else -> CaptureResult.Stopped(captureFile)
            }
        } catch (_: CaptureBlockedException) {
            result = CaptureResult.Blocked
        } catch (error: Exception) {
            result = CaptureResult.Error(error.userMessage("Errore durante la cattura audio"))
        } finally {
            stopAndReleaseAudioRecord()
            val closeError = closeWriter(writer)
            val finalResult = when {
                result == null -> CaptureResult.Error("La cattura è terminata in modo imprevisto")
                closeError != null && result is CaptureResult.Stopped ->
                    CaptureResult.Error("Impossibile completare il file audio temporaneo")
                else -> result
            }

            releaseMediaProjection()
            when (finalResult) {
                CaptureResult.Blocked -> {
                    outputFile?.delete()
                    emitBlocked()
                }

                is CaptureResult.Stopped -> emitStopped(finalResult.outputFile)

                is CaptureResult.Error -> {
                    outputFile?.delete()
                    emitError(finalResult.message)
                }
            }
            stopForegroundSafely()
            stopSelf()
        }
    }

    private fun captureLoop(record: AudioRecord, writer: WavFileWriter): Long {
        val bufferSize = (record.bufferSizeInFrames * BYTES_PER_FRAME)
            .coerceIn(BYTES_PER_FRAME, MAX_READ_BUFFER_SIZE)
        val readBuffer = ByteArray(bufferSize)
        val silenceCheckBytes =
            SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE * SILENCE_CHECK_MS / 1_000
        val firstSamples = ByteArrayOutputStream(silenceCheckBytes)
        var silenceCheckComplete = false
        var pcmBytesWritten = 0L

        while (!stopRequested.get()) {
            val read = record.read(
                readBuffer,
                0,
                readBuffer.size,
                AudioRecord.READ_BLOCKING
            )
            when {
                read > 0 -> {
                    if (silenceCheckComplete) {
                        pcmBytesWritten = appendPcm(
                            writer,
                            readBuffer,
                            0,
                            read,
                            pcmBytesWritten
                        )
                    } else {
                        firstSamples.write(readBuffer, 0, read)
                        if (firstSamples.size() >= silenceCheckBytes) {
                            val initialPcm = firstSamples.toByteArray()
                            if (
                                RmsSilenceDetector.isSilent(
                                    bytesToFloatSamples(initialPcm.copyOf(silenceCheckBytes))
                                )
                            ) {
                                throw CaptureBlockedException()
                            }
                            pcmBytesWritten = appendPcm(
                                writer,
                                initialPcm,
                                0,
                                initialPcm.size,
                                pcmBytesWritten
                            )
                            silenceCheckComplete = true
                        }
                    }
                }

                read == 0 -> Unit
                stopRequested.get() -> break
                else -> throw CaptureException("Lettura audio non riuscita (codice $read)")
            }
        }

        if (!silenceCheckComplete && firstSamples.size() > 0) {
            val capturedBeforeStop = firstSamples.toByteArray()
            pcmBytesWritten = appendPcm(
                writer,
                capturedBeforeStop,
                0,
                capturedBeforeStop.size,
                pcmBytesWritten
            )
        }

        return pcmBytesWritten
    }

    private fun appendPcm(
        writer: WavFileWriter,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        currentDataBytes: Long
    ): Long {
        if (!WavFileWriter.canAppendData(currentDataBytes, length)) {
            throw CaptureException(
                "La registrazione ha raggiunto il limite WAV di 2 GB ed è stata annullata."
            )
        }
        writer.write(buffer, offset, length)
        return currentDataBytes + length
    }

    private fun installAudioRecord(record: AudioRecord) {
        synchronized(audioRecordLock) {
            if (audioRecordReleased.get()) {
                record.release()
                throw CaptureException("La cattura è stata arrestata prima dell'inizializzazione")
            }
            audioRecord = record
        }
    }

    private fun startRecording(record: AudioRecord): Boolean =
        synchronized(audioRecordLock) {
            if (stopRequested.get() || audioRecordReleased.get()) {
                return@synchronized false
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@synchronized false
            }
            recording.set(true)
            true
        }

    private fun requestStop() {
        stopRequested.set(true)
        stopRecordingIfActive()
    }

    private fun stopRecordingIfActive() {
        synchronized(audioRecordLock) {
            if (audioRecordReleased.get()) return
            val record = audioRecord ?: return
            if (recording.compareAndSet(true, false)) {
                runCatching { record.stop() }
            }
        }
    }

    private fun stopAndReleaseAudioRecord() {
        val record = synchronized(audioRecordLock) {
            if (!audioRecordReleased.compareAndSet(false, true)) return
            val currentRecord = audioRecord
            audioRecord = null
            if (recording.compareAndSet(true, false)) {
                runCatching { currentRecord?.stop() }
            }
            currentRecord
        }
        runCatching { record?.release() }
    }

    private fun attachMediaProjection(projection: MediaProjection): Boolean =
        synchronized(projectionLock) {
            if (projectionReleased.get()) {
                projection.stop()
                false
            } else {
                mediaProjection = projection
                projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                true
            }
        }

    private fun releaseMediaProjection() {
        val projection = synchronized(projectionLock) {
            if (!projectionReleased.compareAndSet(false, true)) return
            projectionStopExpected.set(true)
            mediaProjection.also { mediaProjection = null }
        }
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
    }

    private fun closeWriter(writer: WavFileWriter?): Exception? =
        try {
            writer?.close()
            null
        } catch (error: Exception) {
            error
        }

    private fun emitBlocked() {
        emitTerminal(ACTION_CAPTURE_BLOCKED, CaptureTerminalKind.BLOCKED)
    }

    private fun emitStopped(outputFile: File) {
        emitTerminal(
            ACTION_CAPTURE_STOPPED,
            CaptureTerminalKind.STOPPED,
            outputPath = outputFile.absolutePath
        )
    }

    private fun emitError(message: String) {
        emitTerminal(ACTION_CAPTURE_ERROR, CaptureTerminalKind.ERROR, errorMessage = message)
    }

    private fun emitTerminal(
        action: String,
        kind: CaptureTerminalKind,
        outputPath: String? = null,
        errorMessage: String? = null
    ) {
        if (!terminalBroadcastSent.compareAndSet(false, true)) return
        try {
            CaptureTerminalResultStore(this).persist(kind, outputPath, errorMessage)
        } catch (_: Exception) {
            if (kind == CaptureTerminalKind.STOPPED) File(outputPath.orEmpty()).delete()
            return
        }
        sendBroadcast(
            Intent(action)
                .setPackage(packageName)
                .apply {
                    outputPath?.let { putExtra(EXTRA_OUTPUT_PATH, it) }
                    errorMessage?.let { putExtra(EXTRA_ERROR_MESSAGE, it) }
                }
        )
    }

    private fun stopForegroundSafely() {
        if (foregroundStarted.compareAndSet(true, false)) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Cattura audio",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, SystemAudioCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Registrazione audio in corso")
            .setContentText("Tocca “Ferma e elabora” al termine")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Ferma e elabora", stopIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun Intent?.resultData(): Intent? =
        when {
            this == null -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else -> getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun bytesToFloatSamples(bytes: ByteArray): FloatArray {
        val samples = FloatArray(bytes.size / BYTES_PER_SAMPLE)
        for (index in samples.indices) {
            val byteIndex = index * BYTES_PER_SAMPLE
            val value = ((bytes[byteIndex + 1].toInt() shl 8) or
                (bytes[byteIndex].toInt() and 0xFF)).toShort()
            samples[index] = value / 32_768f
        }
        return samples
    }

    private fun Exception.userMessage(defaultMessage: String): String =
        message?.takeIf { it.isNotBlank() } ?: defaultMessage
}
