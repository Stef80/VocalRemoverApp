# System Audio Capture Spike — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validare tecnicamente, con un piccolo prototipo throwaway, che la cattura dell'audio riprodotto da un'altra app (via `MediaProjection` + `AudioPlaybackCaptureConfiguration`) produce dati non silenziosi con sorgenti che lo permettono, e che con sorgenti che lo bloccano (es. Spotify) l'app rileva la situazione in modo pulito invece di fallire in silenzio o crashare.

**Architecture:** Un `Service` foreground dedicato (`CaptureSpikeService`, package `com.example.vocalremover.spike`) apre un `AudioRecord` configurato con `AudioPlaybackCaptureConfiguration` filtrato per UID (risolto da un nome pacchetto scelto dall'utente tramite `PackageManager`), verifica il livello RMS dei primi ~500ms per distinguere "sorgente bloccata" da "sorgente catturabile", e nel secondo caso scrive ~10s di PCM grezzo in un file WAV tramite un writer incrementale. `MainActivity` aggiunge un punto di ingresso temporaneo (pulsante + campo nome pacchetto) che gestisce permesso `RECORD_AUDIO` e consenso `MediaProjection`, poi avvia il servizio.

**Tech Stack:** Kotlin, Android `MediaProjection`/`AudioPlaybackCaptureConfiguration`/`AudioRecord` (API 29+), JUnit 4 per i test di logica pura, nessuna nuova libreria esterna.

## Global Constraints

- Il codice di questo spike è throwaway/di validazione: vive isolato nel package `com.example.vocalremover.spike`, non tocca `VocalRemover`/`AudioPlayer`/`StftProcessor` esistenti (spec: "nessuna nuova classe" nella pipeline di elaborazione — qui non elaboriamo nulla, solo catturiamo).
- Nessun bypass di `ALLOW_CAPTURE_BY_NONE` o altre protezioni DRM: se la sorgente blocca la cattura, lo spike deve rilevarlo e fermarsi in modo pulito (requisito esplicito dello spec).
- `AudioPlaybackConfiguration.getClientUid()` è API di sistema nascosta e NON va usata: l'UID della sorgente si ottiene sempre da un nome pacchetto tramite `PackageManager.getApplicationInfo(packageName, 0).uid` (correzione applicata allo spec).
- Funziona solo da Android 10 (API 29) in su (`AudioPlaybackCaptureConfiguration` non esiste prima): ogni componente che la usa è annotato `@RequiresApi(Build.VERSION_CODES.Q)` e la UI nasconde/disabilita l'ingresso su API < 29.
- Minimizzare le dipendenze: usare solo API Android + coroutines già presenti nel progetto (`kotlinx-coroutines-android` già in `app/build.gradle.kts`), più JUnit 4 da aggiungere per i test unitari.
- Struttura file Gradle esistente: sorgenti in `app/src/main/java/com/example/vocalremover/`, test unitari JVM in `app/src/test/java/com/example/vocalremover/` (cartella attualmente inesistente, va creata).

---

### Task 1: Aggiungere JUnit e il rilevatore di silenzio RMS

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/vocalremover/spike/RmsSilenceDetector.kt`
- Test: `app/src/test/java/com/example/vocalremover/spike/RmsSilenceDetectorTest.kt`

**Interfaces:**
- Produces: `RmsSilenceDetector.rms(samples: FloatArray): Float`, `RmsSilenceDetector.isSilent(samples: FloatArray, threshold: Float = RmsSilenceDetector.DEFAULT_THRESHOLD): Boolean`, costante `RmsSilenceDetector.DEFAULT_THRESHOLD: Float`. Usato da Task 4 (`CaptureSpikeService`) per decidere se la sorgente sta bloccando la cattura.

- [ ] **Step 1: Aggiungere la dipendenza JUnit al modulo `app`**

In `app/build.gradle.kts`, dentro il blocco `dependencies { ... }`, aggiungere in fondo:

```kotlin
    // Test unitari JVM (nessun test esisteva finora nel modulo)
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Scrivere il test che fallisce**

Creare `app/src/test/java/com/example/vocalremover/spike/RmsSilenceDetectorTest.kt`:

```kotlin
package com.example.vocalremover.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class RmsSilenceDetectorTest {

    @Test
    fun `rms of all-zero buffer is zero`() {
        val samples = FloatArray(1024) { 0f }
        assertEquals(0f, RmsSilenceDetector.rms(samples), 0.0001f)
    }

    @Test
    fun `rms of constant amplitude buffer equals that amplitude`() {
        val samples = FloatArray(1024) { 0.5f }
        assertEquals(0.5f, RmsSilenceDetector.rms(samples), 0.0001f)
    }

    @Test
    fun `all-zero buffer is detected as silent`() {
        val samples = FloatArray(1024) { 0f }
        assertTrue(RmsSilenceDetector.isSilent(samples))
    }

    @Test
    fun `buffer with audible sine signal is not silent`() {
        val samples = FloatArray(1024) { i -> sin(i * 0.1) .toFloat() * 0.3f }
        assertFalse(RmsSilenceDetector.isSilent(samples))
    }

    @Test
    fun `empty buffer is treated as silent`() {
        assertTrue(RmsSilenceDetector.isSilent(FloatArray(0)))
    }
}
```

- [ ] **Step 3: Eseguire il test e verificare che fallisca**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.spike.RmsSilenceDetectorTest"`
Expected: FAIL — errore di compilazione, `RmsSilenceDetector` non esiste ancora.

- [ ] **Step 4: Implementare `RmsSilenceDetector`**

Creare `app/src/main/java/com/example/vocalremover/spike/RmsSilenceDetector.kt`:

```kotlin
package com.example.vocalremover.spike

/**
 * Rileva se un buffer di campioni PCM float è "silenzio" (RMS sotto
 * soglia). Usato dallo spike di cattura audio di sistema per distinguere
 * una sorgente che blocca la cattura (AudioPlaybackCaptureConfiguration
 * restituisce PCM a zero senza generare un errore di sistema) da una
 * sorgente che sta effettivamente inviando audio.
 */
object RmsSilenceDetector {

    /** Soglia RMS sotto la quale un buffer è considerato silenzio. */
    const val DEFAULT_THRESHOLD = 0.001f

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) sumSquares += (s * s).toDouble()
        return Math.sqrt(sumSquares / samples.size).toFloat()
    }

    fun isSilent(samples: FloatArray, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return rms(samples) < threshold
    }
}
```

- [ ] **Step 5: Eseguire il test e verificare che passi**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.spike.RmsSilenceDetectorTest"`
Expected: PASS (5 test superati).

- [ ] **Step 6: Preparare (senza committare) — l'utente esegue il commit**

Non eseguire `git commit` autonomamente (regola generale del repo, vedi .github/copilot-instructions.md). Lasciare i file modificati/creati pronti (`app/build.gradle.kts app/src/main/java/com/example/vocalremover/spike/RmsSilenceDetector.kt app/src/test/java/com/example/vocalremover/spike/RmsSilenceDetectorTest.kt`) e riportare all'utente il messaggio di commit suggerito, perché lo esegua lui stesso quando vuole:

```
Add RmsSilenceDetector for system-audio capture spike

Pure Kotlin RMS-based silence check, used to detect when a capture
source blocks AudioPlaybackCaptureConfiguration (silent PCM instead of
an error). First JUnit test added to the app module.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

### Task 2: Writer WAV incrementale

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/spike/WavFileWriter.kt`
- Test: `app/src/test/java/com/example/vocalremover/spike/WavFileWriterTest.kt`

**Interfaces:**
- Consumes: nessuna dipendenza da altri task.
- Produces: `class WavFileWriter(file: File, sampleRate: Int, channelCount: Int)` con `fun write(buffer: ByteArray, offset: Int, length: Int)`, `fun close()`, proprietà `val totalDataBytes: Long`. Usato da Task 4 (`CaptureSpikeService`) per salvare il PCM catturato senza tenerlo tutto in memoria.

- [ ] **Step 1: Scrivere il test che fallisce**

Creare `app/src/test/java/com/example/vocalremover/spike/WavFileWriterTest.kt`:

```kotlin
package com.example.vocalremover.spike

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @Test
    fun `writes correct header fields after closing`() {
        val file = File.createTempFile("spike_test", ".wav")
        file.deleteOnExit()

        val writer = WavFileWriter(file, sampleRate = 48000, channelCount = 2)
        val samples = ByteArray(2000) { 1 }
        writer.write(samples, 0, samples.size)
        writer.close()

        val raf = RandomAccessFile(file, "r")
        val header = ByteArray(44)
        raf.readFully(header)
        raf.close()

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buffer.get(it) }
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))

        val riffSize = buffer.int
        assertEquals(36 + 2000, riffSize)

        val wave = ByteArray(4).also { buffer.get(it) }
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))

        val fmt = ByteArray(4).also { buffer.get(it) }
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))

        assertEquals(16, buffer.int) // dimensione subchunk fmt
        assertEquals(1, buffer.short.toInt()) // PCM
        assertEquals(2, buffer.short.toInt()) // canali
        assertEquals(48000, buffer.int) // sample rate
        assertEquals(48000 * 2 * 2, buffer.int) // byte rate
        assertEquals(4, buffer.short.toInt()) // block align
        assertEquals(16, buffer.short.toInt()) // bit per campione

        val data = ByteArray(4).also { buffer.get(it) }
        assertEquals("data", String(data, Charsets.US_ASCII))
        assertEquals(2000, buffer.int)

        assertEquals(2000L, writer.totalDataBytes)
        file.delete()
    }

    @Test
    fun `accumulates data size across multiple incremental writes`() {
        val file = File.createTempFile("spike_test2", ".wav")
        file.deleteOnExit()

        val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 1)
        writer.write(ByteArray(500) { 1 }, 0, 500)
        writer.write(ByteArray(500) { 1 }, 0, 500)
        writer.close()

        assertEquals(1000L, writer.totalDataBytes)
        assertEquals(44L + 1000L, file.length())
        file.delete()
    }
}
```

- [ ] **Step 2: Eseguire il test e verificare che fallisca**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.spike.WavFileWriterTest"`
Expected: FAIL — `WavFileWriter` non esiste ancora.

- [ ] **Step 3: Implementare `WavFileWriter`**

Creare `app/src/main/java/com/example/vocalremover/spike/WavFileWriter.kt`:

```kotlin
package com.example.vocalremover.spike

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scrive incrementalmente un file WAV (PCM 16-bit, interleaved) senza
 * tenere l'intera registrazione in memoria: i dati vengono appesi mano a
 * mano, l'header con le dimensioni finali viene scritto/corretto solo
 * alla chiusura (`close()`).
 */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channelCount: Int
) {
    companion object {
        private const val HEADER_SIZE = 44
        private const val BITS_PER_SAMPLE = 16
    }

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.seek(HEADER_SIZE.toLong()) // placeholder: header scritto/corretto in close()
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!closed) { "WavFileWriter già chiuso" }
        raf.write(buffer, offset, length)
        dataBytesWritten += length
    }

    fun close() {
        if (closed) return
        writeHeader()
        raf.close()
        closed = true
    }

    val totalDataBytes: Long get() = dataBytesWritten

    private fun writeHeader() {
        val byteRate = sampleRate * channelCount * (BITS_PER_SAMPLE / 8)
        val blockAlign = channelCount * (BITS_PER_SAMPLE / 8)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytesWritten).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // dimensione subchunk fmt
        header.putShort(1) // PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytesWritten.toInt())

        raf.seek(0)
        raf.write(header.array())
    }
}
```

- [ ] **Step 4: Eseguire il test e verificare che passi**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.spike.WavFileWriterTest"`
Expected: PASS (2 test superati).

- [ ] **Step 5: Preparare (senza committare) — l'utente esegue il commit**

Non eseguire `git commit` autonomamente (regola generale del repo, vedi .github/copilot-instructions.md). Lasciare i file modificati/creati pronti (`app/src/main/java/com/example/vocalremover/spike/WavFileWriter.kt app/src/test/java/com/example/vocalremover/spike/WavFileWriterTest.kt`) e riportare all'utente il messaggio di commit suggerito, perché lo esegua lui stesso quando vuole:

```
Add incremental WavFileWriter for system-audio capture spike

Writes PCM16 data as it arrives and patches the RIFF/data chunk sizes
in the header only on close(), avoiding buffering the whole recording
in memory.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

### Task 3: Permessi manifest e dichiarazione del servizio

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: dichiarazione del servizio `.spike.CaptureSpikeService` con `foregroundServiceType="mediaProjection"`, permessi `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` disponibili per Task 4 e Task 5.

- [ ] **Step 1: Aggiungere i permessi**

In `app/src/main/AndroidManifest.xml`, subito dopo il blocco permessi esistente (dopo `<uses-permission android:name="android.permission.WAKE_LOCK" />`), aggiungere:

```xml
    <!-- Spike: cattura audio di sistema da un'altra app (MediaProjection) -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

- [ ] **Step 2: Dichiarare il servizio**

Nello stesso file, dentro `<application ...> ... </application>`, subito dopo il tag di chiusura `</activity>` di `MainActivity` e prima di `</application>`, aggiungere:

```xml
        <service
            android:name=".spike.CaptureSpikeService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 3: Verificare che il manifest sia valido**

Run: `gradle :app:processDebugManifest`
Expected: BUILD SUCCESSFUL (nessun errore di parsing XML/merge del manifest).

- [ ] **Step 4: Preparare (senza committare) — l'utente esegue il commit**

Non eseguire `git commit` autonomamente (regola generale del repo, vedi .github/copilot-instructions.md). Lasciare i file modificati/creati pronti (`app/src/main/AndroidManifest.xml`) e riportare all'utente il messaggio di commit suggerito, perché lo esegua lui stesso quando vuole:

```
Declare permissions and foreground service for capture spike

Adds RECORD_AUDIO, FOREGROUND_SERVICE, and
FOREGROUND_SERVICE_MEDIA_PROJECTION permissions plus the
CaptureSpikeService declaration (foregroundServiceType=mediaProjection),
required by AudioPlaybackCaptureConfiguration on API 29+.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

### Task 4: `CaptureSpikeService` — cattura, rilevamento blocco, salvataggio WAV

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/spike/CaptureSpikeService.kt`

**Interfaces:**
- Consumes: `RmsSilenceDetector.isSilent(samples: FloatArray): Boolean` (Task 1), `WavFileWriter(file, sampleRate, channelCount)` con `.write(buffer, offset, length)` e `.close()` (Task 2), permessi/servizio dichiarati in Task 3.
- Produces: `CaptureSpikeService` (Android `Service`), con costanti pubbliche `EXTRA_RESULT_CODE`, `EXTRA_RESULT_DATA`, `EXTRA_TARGET_UID` (nomi extra dell'`Intent` di avvio) e `CaptureSpikeService.lastOutcome: String` (esito testuale leggibile dopo che il servizio si ferma). Usato da Task 5 (`MainActivity`) per avviare la cattura e leggere l'esito.

Questo task non ha una unit test JVM significativa (dipende da `MediaProjection`/`AudioRecord`, non testabili senza un device reale o Robolectric, non presente nel progetto): la verifica avviene tramite il test manuale end-to-end del Task 6.

- [ ] **Step 1: Implementare `CaptureSpikeService`**

Creare `app/src/main/java/com/example/vocalremover/spike/CaptureSpikeService.kt`:

```kotlin
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
```

- [ ] **Step 2: Verificare che il modulo compili**

Run: `gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Preparare (senza committare) — l'utente esegue il commit**

Non eseguire `git commit` autonomamente (regola generale del repo, vedi .github/copilot-instructions.md). Lasciare i file modificati/creati pronti (`app/src/main/java/com/example/vocalremover/spike/CaptureSpikeService.kt`) e riportare all'utente il messaggio di commit suggerito, perché lo esegua lui stesso quando vuole:

```
Add CaptureSpikeService: system-audio capture validation

Foreground service that captures playback audio from a single app
(filtered by UID) via AudioPlaybackCaptureConfiguration, detects a
blocked source via RMS silence check on the first ~500ms, and writes
non-blocked captures to a raw WAV file for manual inspection.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

### Task 5: Punto di ingresso in `MainActivity`

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/example/vocalremover/MainActivity.kt`

**Interfaces:**
- Consumes: `CaptureSpikeService.EXTRA_RESULT_CODE`, `EXTRA_RESULT_DATA`, `EXTRA_TARGET_UID`, `CaptureSpikeService.lastOutcome` (Task 4).
- Produces: nessuna nuova interfaccia pubblica (punto di ingresso UI finale di questo spike).

- [ ] **Step 1: Aggiungere gli elementi UI temporanei al layout**

In `app/src/main/res/layout/activity_main.xml`, subito prima della chiusura `</androidx.constraintlayout.widget.ConstraintLayout>` finale, aggiungere:

```xml
    <!-- Spike throwaway: validazione cattura audio di sistema -->
    <EditText
        android:id="@+id/etSpikePackageName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="com.android.chrome"
        android:hint="Nome pacchetto sorgente (es. com.android.chrome)"
        android:textSize="12sp"
        app:layout_constraintTop_toBottomOf="@id/tvInfo"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="12dp" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnSpikeCapture"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="🧪 Spike: registra da un'altra app (10s)"
        android:textSize="13sp"
        app:layout_constraintTop_toBottomOf="@id/etSpikePackageName"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />

    <TextView
        android:id="@+id/tvSpikeOutcome"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text=""
        android:textSize="11sp"
        android:gravity="center"
        app:layout_constraintTop_toBottomOf="@id/btnSpikeCapture"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />
```

Nota: `tvInfo` ha attualmente un vincolo `app:layout_constraintBottom_toBottomOf="parent"`; per far stare i tre nuovi elementi sotto di esso in un `ConstraintLayout` con altezza `match_parent`, rimuovere quel vincolo bottom da `tvInfo` (il layout scorrerà naturalmente in altezza; non è richiesto uno `ScrollView` per lo spike throwaway).

- [ ] **Step 2: Rimuovere il vincolo bottom da `tvInfo`**

In `app/src/main/res/layout/activity_main.xml`, nel blocco `tvInfo`, rimuovere la riga:

```xml
        app:layout_constraintBottom_toBottomOf="parent"
```

- [ ] **Step 3: Aggiungere permesso RECORD_AUDIO, consenso MediaProjection e avvio servizio in `MainActivity`**

In `app/src/main/java/com/example/vocalremover/MainActivity.kt`, aggiungere gli import necessari in cima al file (dopo gli import esistenti):

```kotlin
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.vocalremover.spike.CaptureSpikeService
```

Aggiungere, come proprietà della classe (accanto agli altri launcher esistenti), il permesso microfono e il consenso MediaProjection:

```kotlin
    // ── Spike: cattura audio di sistema ──────────────────────────────────────
    private var pendingSpikeTargetUid: Int = -1

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchSpikeMediaProjectionConsent()
        else Toast.makeText(this, "Permesso microfono necessario per lo spike", Toast.LENGTH_SHORT).show()
    }

    private val spikeMediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && pendingSpikeTargetUid >= 0) {
            val serviceIntent = Intent(this, CaptureSpikeService::class.java).apply {
                putExtra(CaptureSpikeService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureSpikeService.EXTRA_RESULT_DATA, result.data)
                putExtra(CaptureSpikeService.EXTRA_TARGET_UID, pendingSpikeTargetUid)
            }
            startForegroundService(serviceIntent)
            binding.tvSpikeOutcome.text = "Cattura avviata, attendi ~10s..."
            binding.root.postDelayed({
                binding.tvSpikeOutcome.text = CaptureSpikeService.lastOutcome
            }, 11_000L)
        } else {
            Toast.makeText(this, "Consenso alla cattura negato", Toast.LENGTH_SHORT).show()
        }
    }
```

Aggiungere nel metodo `setupUi()`, come ultima riga prima della chiusura della funzione, il collegamento del pulsante:

```kotlin
        binding.btnSpikeCapture.setOnClickListener { startSpikeCapture() }
```

Aggiungere i metodi privati (vicino a `requestStoragePermissionAndPick`):

```kotlin
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSpikeCapture() {
        val packageName = binding.etSpikePackageName.text.toString().trim()
        if (packageName.isEmpty()) {
            Toast.makeText(this, "Inserisci un nome pacchetto", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = try {
            packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED).uid
        } catch (e: Exception) {
            Toast.makeText(this, "App '$packageName' non trovata", Toast.LENGTH_SHORT).show()
            return
        }
        pendingSpikeTargetUid = uid

        val recordAudioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (recordAudioGranted) launchSpikeMediaProjectionConsent()
        else requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchSpikeMediaProjectionConsent() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        spikeMediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
```

Infine, guardia sulla versione Android: nel metodo `onCreate`, subito dopo `setupUi()`, aggiungere:

```kotlin
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.btnSpikeCapture.isEnabled = false
            binding.tvSpikeOutcome.text = "Spike non disponibile: richiede Android 10 (API 29) o superiore"
        }
```

- [ ] **Step 4: Verificare che il modulo compili**

Run: `gradle :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Preparare (senza committare) — l'utente esegue il commit**

Non eseguire `git commit` autonomamente (regola generale del repo, vedi .github/copilot-instructions.md). Lasciare i file modificati/creati pronti (`app/src/main/res/layout/activity_main.xml app/src/main/java/com/example/vocalremover/MainActivity.kt`) e riportare all'utente il messaggio di commit suggerito, perché lo esegua lui stesso quando vuole:

```
Wire temporary UI entry point for system-audio capture spike

Adds a package-name field and a button that resolves the target UID
via PackageManager, requests RECORD_AUDIO, walks through the
MediaProjection consent dialog, and starts CaptureSpikeService.
Disabled on API < 29 where AudioPlaybackCaptureConfiguration doesn't
exist.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

---

### Task 6: Verifica manuale end-to-end su device reale

**Files:** nessuno (task di verifica manuale, nessun codice prodotto).

**Interfaces:** nessuna (task terminale del piano).

Questo task valida sperimentalmente i due comportamenti critici individuati nello spec (`docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md`), non automatizzabili senza un device reale con audio in riproduzione.

- [ ] **Step 1: Build e installazione su device/emulatore reale con Android 10+**

Run: `gradle :app:installDebug`
Expected: BUILD SUCCESSFUL, app installata.

- [ ] **Step 2: Scenario "sorgente permissiva" — Chrome con un video HTML5**

1. Aprire Chrome (`com.android.chrome`) sul device e avviare la riproduzione di un video con audio (es. un video di prova su una pagina web qualsiasi).
2. Aprire VocalRemoverApp, lasciare il campo nome pacchetto sul valore precompilato `com.android.chrome`.
3. Toccare "🧪 Spike: registra da un'altra app (10s)", concedere il permesso microfono se richiesto, poi concedere il consenso di sistema alla cattura schermo/audio.
4. Attendere ~11 secondi.

Expected: `tvSpikeOutcome` mostra un messaggio che inizia con `"OK: registrati <N> byte in <path>"`, con N chiaramente maggiore di zero (atteso: vicino a `48000 * 2canali * 2byte * 10s ≈ 1.920.000` byte). Verificare anche via logcat:

Run: `adb logcat -d | grep CaptureSpike`
Expected: riga `SPIKE_CAPTURE_OK: segnale rilevato, proseguo la registrazione` seguita da `SPIKE_CAPTURE_OK: OK: registrati ...`.

- [ ] **Step 3: Verificare il file WAV prodotto**

Run: `adb pull /sdcard/Android/data/com.example.vocalremover/files/capture_spike.wav /tmp/capture_spike.wav && ls -la /tmp/capture_spike.wav`
Expected: file di dimensione coerente con quanto riportato da `tvSpikeOutcome` (44 byte header + N byte dati); riprodurre il file con un player qualsiasi e confermare a orecchio che contiene l'audio del video Chrome riprodotto al passo 2 (verifica soggettiva, non automatizzabile).

- [ ] **Step 4: Scenario "sorgente bloccata" — Spotify**

1. Aprire l'app Spotify (`com.spotify.music`, se installata sul device di test; in alternativa qualunque altra app di streaming musicale con licenza nota per bloccare la cattura) e avviare la riproduzione di un brano.
2. In VocalRemoverApp, sostituire il testo nel campo nome pacchetto con `com.spotify.music`.
3. Toccare di nuovo "🧪 Spike: registra da un'altra app (10s)" e concedere il consenso di sistema.
4. Attendere ~11 secondi.

Expected: `tvSpikeOutcome` mostra un messaggio che inizia con `"BLOCCATA: la sorgente scelta ... non consente la cattura"`, **senza crash dell'app**. Verificare via logcat:

Run: `adb logcat -d | grep CaptureSpike`
Expected: riga `SPIKE_CAPTURE_BLOCKED: uid=... non catturabile (silenzio)`, nessuna eccezione non gestita nello stack trace.

- [ ] **Step 5: Documentare l'esito nello spec**

Aggiungere in coda alla sezione "Testing" di
`docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md`
un paragrafo con l'esito effettivo dello spike (versione Android testata, se lo scenario (a) e (b) sono stati confermati come attesi, eventuali sorprese).

Non eseguire `git commit` autonomamente (regola generale del repo, vedi
`.github/copilot-instructions.md`). Lasciare il file modificato pronto e
riportare all'utente il messaggio di commit suggerito, perché lo esegua
lui stesso quando vuole:

```
Document spike validation results

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

Questo esito determina se procedere con l'implementazione completa (sotto-progetto 2) descritta nello spec, o se i vincoli di piattaforma la rendono non praticabile e va rivalutato l'ambito.
