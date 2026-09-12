# System Audio Capture — Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the validated spike (see
`docs/superpowers/plans/2026-09-12-system-audio-capture-spike.md` and
`docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md`)
into the real, production feature: record audio playing in another app,
run it through the existing `VocalRemover.removeVocals` pipeline after
recording stops, and let the user save and play back the resulting
instrumental track — as a second entry point alongside the existing
"load a file" flow, which stays unchanged.

**Architecture:** `MainActivity` starts a foreground
`SystemAudioCaptureService` (MediaProjection +
`AudioPlaybackCaptureConfiguration` filtered by source app UID) that
writes raw PCM to a WAV file in the app's cache dir until the user taps
"Ferma e elabora" (or the source blocks capture, detected via RMS
silence check). `MainActivity` then reads that WAV back into memory,
feeds it to the existing `VocalRemover.removeVocals` (unchanged, reused
as-is), lets the user name the result, saves it as a `.wav` in the
device's public Music collection via `MediaStore` (scoped storage,
API 29+), and plays it back immediately through the existing
`AudioPlayer`.

**Tech Stack:** Kotlin, AndroidX, `MediaProjection`/`AudioPlaybackCaptureConfiguration`
(API 29+), `MediaStore` (API 29+ scoped storage), existing ONNX
`VocalRemover` pipeline (unchanged), JUnit 4 for unit tests.

## Global Constraints

- Capture must record at **44100 Hz, stereo, PCM 16-bit** — this matches
  `VocalRemover`/`StftProcessor`'s expected sample rate exactly, so no
  resampling step is needed before `removeVocals` (unlike the spike,
  which used 48000 Hz for a throwaway check and never called
  `removeVocals`).
- **Never bypass `ALLOW_CAPTURE_BY_NONE`.** When the RMS silence check
  detects a blocked source, the service must report a clean
  `onCaptureBlocked()` event, delete any partial file, and never save or
  process a silent capture. This is a hard constraint from the design
  spec's "Fuori scope" section.
- The **existing file-picker flow must keep working unchanged**
  (`btnPickFile` → `requestStoragePermissionAndPick()` → `processAudio(uri)`
  → `audioPlayer.loadAndProcess(uri, vocalRemover)`). The capture flow is
  a second, parallel entry point, never a replacement.
- The capture feature requires **Android 10 (API 29, `Build.VERSION_CODES.Q`)
  minimum** (`AudioPlaybackCaptureConfiguration` doesn't exist earlier).
  On API < 29 the capture UI must be visibly disabled with an explanatory
  message, exactly like the spike did.
- On **API 34+ (`UPSIDE_DOWN_CAKE`)**, the MediaProjection consent dialog
  must be forced into "Schermo intero" mode via
  `MediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`.
  On API 29-33, fall back to the plain `createScreenCaptureIntent()` (no
  way to force it there). This is a validated, load-bearing finding from
  the spike: "Un'unica app" mode never produces a working capture on the
  tested devices.
- The service must be a **foreground service of type `mediaProjection`**
  (manifest `android:foregroundServiceType="mediaProjection"` +
  `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission, already declared) with
  a persistent notification for the entire recording duration, per the
  spec's "Gestione errori"/notification requirements.
- **Saved recordings go into the public Music collection via `MediaStore`**
  (`MediaStore.Audio.Media`, `RELATIVE_PATH = Music/VocalRemover`), not a
  raw external-storage path — required for scoped-storage compliance on
  API 29+ without extra permissions.
- File names are **always manually confirmed by the user** in a save
  dialog, prefilled with a generic date/time name
  (`Registrazione_yyyy-MM-dd_HHmm`, see spec's "Naming del file salvato"),
  sanitized to strip path-invalid characters, with a fallback to a
  generic name if the sanitized result is empty.
- **No new external DSP/audio library.** Reuse `VocalRemover.removeVocals`
  and `StftProcessor` exactly as they are today — no streaming/live
  processing, no new native dependencies.
- Reuse the RMS-silence-detection approach already validated in the spike
  (`RmsSilenceDetector`, threshold `0.001f`, ~500ms check window) rather
  than reinventing capture-blocked detection.

---

### Task 1: `RecordingFileNaming` — filename sanitization + default name

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt`
- Test: `app/src/test/java/com/example/vocalremover/capture/RecordingFileNamingTest.kt`

**Interfaces:**
- Produces: `RecordingFileNaming.sanitize(input: String): String` and
  `RecordingFileNaming.defaultName(timestampMs: Long = System.currentTimeMillis()): String`,
  used by `MainActivity` in Task 6 for the save dialog.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFileNamingTest {

    @Test
    fun `sanitize leaves a normal name untouched`() {
        assertEquals("My Song", RecordingFileNaming.sanitize("My Song"))
    }

    @Test
    fun `sanitize replaces path-invalid characters with underscore`() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i",
            RecordingFileNaming.sanitize("a/b:c*d?e\"f<g>h|i")
        )
    }

    @Test
    fun `sanitize trims surrounding whitespace`() {
        assertEquals("Trimmed", RecordingFileNaming.sanitize("   Trimmed   "))
    }

    @Test
    fun `sanitize falls back to generic name when input is empty`() {
        assertEquals("Registrazione", RecordingFileNaming.sanitize(""))
    }

    @Test
    fun `sanitize falls back to generic name when input is only whitespace or invalid chars`() {
        assertEquals("Registrazione", RecordingFileNaming.sanitize("   "))
        assertEquals("Registrazione", RecordingFileNaming.sanitize("///"))
    }

    @Test
    fun `defaultName formats a fixed timestamp as expected`() {
        // 2026-09-12 11:30:00 UTC
        val timestampMs = 1789215000000L
        val name = RecordingFileNaming.defaultName(timestampMs)
        assertEquals(true, name.startsWith("Registrazione_2026-09-1"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:testDebugUnitTest --tests "com.example.vocalremover.capture.RecordingFileNamingTest"`
Expected: FAIL — `RecordingFileNaming` unresolved reference (class doesn't exist yet).

- [ ] **Step 3: Write the implementation**

```kotlin
package com.example.vocalremover.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sanifica e genera il nome (senza estensione) del file da salvare al
 * termine dell'elaborazione di una registrazione catturata da un'altra
 * app. Vedi
 * docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md,
 * sezione "Naming del file salvato".
 */
object RecordingFileNaming {

    private const val FALLBACK_NAME = "Registrazione"
    private val INVALID_CHARS_REGEX = Regex("[/\\\\:*?\"<>|\\x00-\\x1F]")
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)

    /**
     * Sostituisce i caratteri non validi per un nome file (separatori di
     * percorso e caratteri riservati su Android/Windows) con "_", elimina
     * gli spazi ai bordi, e ricade su un nome generico se il risultato è
     * vuoto.
     */
    fun sanitize(input: String): String {
        val cleaned = input.replace(INVALID_CHARS_REGEX, "_").trim()
        return cleaned.ifEmpty { FALLBACK_NAME }
    }

    /** Nome generico precompilato basato su data/ora, es. "Registrazione_2026-09-12_1130". */
    fun defaultName(timestampMs: Long = System.currentTimeMillis()): String {
        return "${FALLBACK_NAME}_${DATE_FORMAT.format(Date(timestampMs))}"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:testDebugUnitTest --tests "com.example.vocalremover.capture.RecordingFileNamingTest"`
Expected: PASS, 6/6 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt \
        app/src/test/java/com/example/vocalremover/capture/RecordingFileNamingTest.kt
git commit -m "Add RecordingFileNaming for capture save-dialog filenames"
```

---

### Task 2: Promote spike helpers to production, remove the throwaway spike

**Files:**
- Move: `app/src/main/java/com/example/vocalremover/spike/WavFileWriter.kt` →
  `app/src/main/java/com/example/vocalremover/capture/WavFileWriter.kt`
  (package changed from `com.example.vocalremover.spike` to
  `com.example.vocalremover.capture`, no other change)
- Move: `app/src/main/java/com/example/vocalremover/spike/RmsSilenceDetector.kt` →
  `app/src/main/java/com/example/vocalremover/capture/RmsSilenceDetector.kt`
  (package changed only)
- Move: `app/src/test/java/com/example/vocalremover/spike/WavFileWriterTest.kt` →
  `app/src/test/java/com/example/vocalremover/capture/WavFileWriterTest.kt`
  (package changed only)
- Move: `app/src/test/java/com/example/vocalremover/spike/RmsSilenceDetectorTest.kt` →
  `app/src/test/java/com/example/vocalremover/capture/RmsSilenceDetectorTest.kt`
  (package changed only)
- Delete: `app/src/main/java/com/example/vocalremover/spike/CaptureSpikeService.kt`
  (superseded by `SystemAudioCaptureService` in Task 4)
- Modify: `app/src/main/AndroidManifest.xml` (remove the `.spike.CaptureSpikeService`
  `<service>` declaration; keep the `<queries>` block and the
  `RECORD_AUDIO`/`FOREGROUND_SERVICE*` permissions — still needed for
  production)
- Modify: `app/src/main/res/layout/activity_main.xml` (remove the spike-only
  `etSpikePackageName`/`btnSpikeCapture`/`tvSpikeOutcome` views)
- Modify: `app/src/main/java/com/example/vocalremover/MainActivity.kt` (remove
  all spike wiring: imports, launchers, `startSpikeCapture()`,
  `launchSpikeMediaProjectionConsent()`, the API<Q disable-button guard
  in `onCreate` for the spike button)

**Interfaces:**
- Consumes: nothing new.
- Produces: `com.example.vocalremover.capture.WavFileWriter` and
  `com.example.vocalremover.capture.RmsSilenceDetector`, with the exact
  same public API as today (`WavFileWriter(file, sampleRate, channelCount)`,
  `.write(buffer, offset, length)`, `.close()`, `.totalDataBytes`;
  `RmsSilenceDetector.rms(samples)`, `RmsSilenceDetector.isSilent(samples, threshold)`),
  consumed by Task 3 and Task 4.

- [ ] **Step 1: Move the two production-worthy spike classes into the `capture` package**

```bash
mkdir -p app/src/main/java/com/example/vocalremover/capture
git mv app/src/main/java/com/example/vocalremover/spike/WavFileWriter.kt \
       app/src/main/java/com/example/vocalremover/capture/WavFileWriter.kt
git mv app/src/main/java/com/example/vocalremover/spike/RmsSilenceDetector.kt \
       app/src/main/java/com/example/vocalremover/capture/RmsSilenceDetector.kt
```

Edit the `package` line at the top of both moved files:

```kotlin
package com.example.vocalremover.capture
```

(replacing `package com.example.vocalremover.spike` — no other line changes
in either file).

- [ ] **Step 2: Move their tests into the `capture` test package**

```bash
mkdir -p app/src/test/java/com/example/vocalremover/capture
git mv app/src/test/java/com/example/vocalremover/spike/WavFileWriterTest.kt \
       app/src/test/java/com/example/vocalremover/capture/WavFileWriterTest.kt
git mv app/src/test/java/com/example/vocalremover/spike/RmsSilenceDetectorTest.kt \
       app/src/test/java/com/example/vocalremover/capture/RmsSilenceDetectorTest.kt
rmdir app/src/test/java/com/example/vocalremover/spike 2>/dev/null || true
```

Edit the `package` line at the top of both moved test files the same way
(`com.example.vocalremover.spike` → `com.example.vocalremover.capture`),
no other line changes.

- [ ] **Step 3: Delete the throwaway spike service**

```bash
git rm app/src/main/java/com/example/vocalremover/spike/CaptureSpikeService.kt
rmdir app/src/main/java/com/example/vocalremover/spike 2>/dev/null || true
```

- [ ] **Step 4: Remove the spike `<service>` declaration from the manifest**

In `app/src/main/AndroidManifest.xml`, delete this block (leave everything
else — permissions, `<queries>`, the `<activity>` — untouched):

```xml
        <service
            android:name=".spike.CaptureSpikeService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 5: Remove the spike-only views from the layout**

In `app/src/main/res/layout/activity_main.xml`, delete this entire block
(everything from the `<!-- Spike throwaway... -->` comment to the closing
`</TextView>` of `tvSpikeOutcome`, i.e. the `etSpikePackageName` EditText,
the `btnSpikeCapture` MaterialButton, and the `tvSpikeOutcome` TextView).
The `tvInfo` TextView right above it (with its
`app:layout_constraintTop_toBottomOf="@id/layoutControls"` constraint)
stays as the last element in the layout.

- [ ] **Step 6: Strip spike wiring from `MainActivity.kt`**

Replace the whole file content with:

```kotlin
package com.example.vocalremover

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vocalremover.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vocalRemover: VocalRemover
    private lateinit var audioPlayer: AudioPlayer

    private var isUserSeeking = false

    // ── Launcher file picker ─────────────────────────────────────────────────
    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processAudio(it) }
    }

    // ── Launcher permessi ────────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickAudioLauncher.launch("audio/*")
        else Toast.makeText(this, "Permesso storage necessario", Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vocalRemover = VocalRemover(this)
        audioPlayer = AudioPlayer(this)

        setupUi()
        setupPlayerCallbacks()
        setPlayerEnabled(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        vocalRemover.close()
    }

    // ── UI setup ─────────────────────────────────────────────────────────────
    private fun setupUi() {
        binding.btnPickFile.setOnClickListener { requestStoragePermissionAndPick() }

        binding.btnPlayPause.setOnClickListener {
            if (audioPlayer.isPlaying) {
                audioPlayer.pause()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                audioPlayer.play()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        binding.btnStop.setOnClickListener {
            audioPlayer.stop()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = formatMs(0)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isUserSeeking = true }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrentTime.text = formatMs(progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isUserSeeking = false
                audioPlayer.seekTo(sb.progress)
            }
        })
    }

    private fun setupPlayerCallbacks() {
        audioPlayer.onProgress = { pct ->
            runOnUiThread {
                binding.progressBar.progress = pct
                binding.tvStatus.text = when {
                    pct < 5  -> "Caricamento..."
                    pct < 20 -> "Analisi spettrale..."
                    pct < 85 -> "Rimozione voci: $pct%"
                    pct < 100 -> "Ricostruzione audio..."
                    else -> "Pronto!"
                }
            }
        }

        audioPlayer.onPlaybackPositionChanged = { currentMs, totalMs ->
            if (!isUserSeeking) {
                binding.seekBar.max = totalMs
                binding.seekBar.progress = currentMs
                binding.tvCurrentTime.text = formatMs(currentMs)
                binding.tvTotalTime.text = formatMs(totalMs)
            }
        }

        audioPlayer.onReady = {
            binding.progressBar.visibility = android.view.View.GONE
            binding.seekBar.max = audioPlayer.durationMs
            binding.tvTotalTime.text = formatMs(audioPlayer.durationMs)
            setPlayerEnabled(true)
            Toast.makeText(this, "Elaborazione completata!", Toast.LENGTH_SHORT).show()
        }

        audioPlayer.onError = { msg ->
            binding.tvStatus.text = "Errore: $msg"
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Errore: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ── Elaborazione ─────────────────────────────────────────────────────────
    private fun processAudio(uri: Uri) {
        setPlayerEnabled(false)
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Avvio elaborazione..."

        // Mostra nome file
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                binding.tvFileName.text = name
            }
        }

        lifecycleScope.launch {
            audioPlayer.loadAndProcess(uri, vocalRemover)
        }
    }

    // ── Permessi ─────────────────────────────────────────────────────────────
    private fun requestStoragePermissionAndPick() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                pickAudioLauncher.launch("audio/*")
            else ->
                requestPermissionLauncher.launch(permission)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setPlayerEnabled(enabled: Boolean) {
        binding.btnPlayPause.isEnabled = enabled
        binding.btnStop.isEnabled = enabled
        binding.seekBar.isEnabled = enabled
    }

    private fun formatMs(ms: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
```

- [ ] **Step 7: Run unit tests to verify the move didn't break anything**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:testDebugUnitTest`
Expected: PASS, all tests green (RmsSilenceDetectorTest, WavFileWriterTest,
RecordingFileNamingTest from Task 1).

- [ ] **Step 8: Verify the app still compiles and resources build**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Promote spike audio-capture helpers to production, remove throwaway spike"
```

---

### Task 3: `WavFileReader` — decode a captured WAV back into `StereoPcm`

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/capture/WavFileReader.kt`
- Test: `app/src/test/java/com/example/vocalremover/capture/WavFileReaderTest.kt`

**Interfaces:**
- Consumes: `com.example.vocalremover.capture.WavFileWriter` (Task 2, same
  package) in its own test to produce a WAV to round-trip; `com.example.vocalremover.StereoPcm`
  (existing, `left: FloatArray`, `right: FloatArray`, both same length).
- Produces: `WavFileReader.readInfo(file: File): WavFileReader.WavInfo`
  (`WavInfo(sampleRate: Int, channelCount: Int)`) and
  `WavFileReader.readStereoPcm(file: File): StereoPcm`, throwing
  `WavFileReader.UnsupportedWavFormatException` for anything that isn't
  16-bit PCM stereo — consumed by Task 6 (`MainActivity.processCapturedRecording`).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileReaderTest {

    @Test
    fun `reads back sample rate and channel count from the header`() {
        val file = File.createTempFile("wav_reader_info_test", ".wav")
        file.deleteOnExit()
        val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 2)
        writer.write(ByteArray(8) { 0 }, 0, 8)
        writer.close()

        val info = WavFileReader.readInfo(file)
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channelCount)
        file.delete()
    }

    @Test
    fun `reads back interleaved stereo samples as normalized floats`() {
        val file = File.createTempFile("wav_reader_pcm_test", ".wav")
        file.deleteOnExit()

        val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 2)
        // 4 frame stereo, interleaved L/R 16-bit PCM.
        val leftSamples = shortArrayOf(16384, 8192, 0, 32767)
        val rightSamples = shortArrayOf(-16384, -8192, 0, -32768)
        val bytes = ByteArray(4 * 4) // 4 frame * 2 canali * 2 byte
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            buffer.putShort(leftSamples[i])
            buffer.putShort(rightSamples[i])
        }
        writer.write(bytes, 0, bytes.size)
        writer.close()

        val pcm = WavFileReader.readStereoPcm(file)
        assertEquals(4, pcm.size)
        assertEquals(16384 / 32768f, pcm.left[0], 0.0001f)
        assertEquals(-16384 / 32768f, pcm.right[0], 0.0001f)
        assertEquals(0f, pcm.left[2], 0.0001f)
        assertEquals(32767 / 32768f, pcm.left[3], 0.0001f)
        assertEquals(-32768 / 32768f, pcm.right[3], 0.0001f)
        file.delete()
    }

    @Test(expected = WavFileReader.UnsupportedWavFormatException::class)
    fun `rejects mono files`() {
        val file = File.createTempFile("wav_reader_mono_test", ".wav")
        file.deleteOnExit()
        val writer = WavFileWriter(file, sampleRate = 44100, channelCount = 1)
        writer.write(ByteArray(4) { 0 }, 0, 4)
        writer.close()

        try {
            WavFileReader.readStereoPcm(file)
        } finally {
            file.delete()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:testDebugUnitTest --tests "com.example.vocalremover.capture.WavFileReaderTest"`
Expected: FAIL — `WavFileReader` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.example.vocalremover.capture

import com.example.vocalremover.StereoPcm
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Legge un file WAV PCM 16-bit stereo, prodotto da [WavFileWriter], in
 * memoria come [StereoPcm] a virgola mobile normalizzata (-1f..1f).
 * Supporta solo l'esatto formato scritto da [WavFileWriter] (PCM 16-bit,
 * header di 44 byte): non è un parser WAV generico.
 */
object WavFileReader {

    class UnsupportedWavFormatException(message: String) : Exception(message)

    data class WavInfo(val sampleRate: Int, val channelCount: Int)

    private const val HEADER_SIZE = 44

    /** Legge solo l'header e restituisce sample rate/numero canali dichiarati. */
    fun readInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            raf.readFully(header)
            return parseInfo(header)
        }
    }

    /** Legge l'intero file e lo decodifica in campioni float stereo normalizzati. */
    fun readStereoPcm(file: File): StereoPcm {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            raf.readFully(header)
            val info = parseInfo(header)
            if (info.channelCount != 2) {
                throw UnsupportedWavFormatException(
                    "Atteso WAV stereo, trovati ${info.channelCount} canali"
                )
            }

            val dataBytes = ByteArray((raf.length() - HEADER_SIZE).toInt())
            raf.readFully(dataBytes)

            val frameCount = dataBytes.size / 4 // 2 canali * 2 byte/campione
            val left = FloatArray(frameCount)
            val right = FloatArray(frameCount)
            val buffer = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frameCount) {
                left[i] = buffer.short / 32768f
                right[i] = buffer.short / 32768f
            }
            return StereoPcm(left, right)
        }
    }

    private fun parseInfo(header: ByteArray): WavInfo {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buffer.get(it) }
        if (String(riff, Charsets.US_ASCII) != "RIFF") {
            throw UnsupportedWavFormatException("Header RIFF non valido")
        }
        buffer.int // dimensione RIFF, non usata
        val wave = ByteArray(4).also { buffer.get(it) }
        if (String(wave, Charsets.US_ASCII) != "WAVE") {
            throw UnsupportedWavFormatException("Header WAVE non valido")
        }
        buffer.position(buffer.position() + 4) // "fmt "
        buffer.int // dimensione subchunk fmt
        val audioFormat = buffer.short.toInt()
        if (audioFormat != 1) {
            throw UnsupportedWavFormatException("Atteso PCM (1), trovato formato $audioFormat")
        }
        val channelCount = buffer.short.toInt()
        val sampleRate = buffer.int
        buffer.int // byte rate, non usato
        buffer.short // block align, non usato
        val bitsPerSample = buffer.short.toInt()
        if (bitsPerSample != 16) {
            throw UnsupportedWavFormatException("Atteso 16 bit per campione, trovati $bitsPerSample")
        }
        return WavInfo(sampleRate, channelCount)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:testDebugUnitTest --tests "com.example.vocalremover.capture.WavFileReaderTest"`
Expected: PASS, 3/3 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/capture/WavFileReader.kt \
        app/src/test/java/com/example/vocalremover/capture/WavFileReaderTest.kt
git commit -m "Add WavFileReader to decode captured WAV files back into StereoPcm"
```

---

### Task 4: `SystemAudioCaptureService` — production capture service

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/capture/SystemAudioCaptureService.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add the new service declaration)

**Interfaces:**
- Consumes: `com.example.vocalremover.capture.WavFileWriter` (Task 2),
  `com.example.vocalremover.capture.RmsSilenceDetector` (Task 2).
- Produces: `SystemAudioCaptureService.Listener` interface
  (`onCaptureBlocked()`, `onCaptureStopped(wavFile: File)`,
  `onCaptureError(message: String)`), `SystemAudioCaptureService.listener`
  (nullable static var), `EXTRA_RESULT_CODE`/`EXTRA_RESULT_DATA`/`EXTRA_TARGET_UID`
  intent extras, `ACTION_STOP` intent action — all consumed by
  `MainActivity` in Task 6.

- [ ] **Step 1: Write the service**

```kotlin
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
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground Service che cattura l'audio riprodotto da una singola app
 * sorgente (filtrata per UID) tramite MediaProjection +
 * AudioPlaybackCaptureConfiguration, e lo scrive incrementalmente su un
 * file WAV grezzo (44100Hz stereo PCM16, per essere compatibile
 * direttamente con `VocalRemover.removeVocals` senza resampling). Non
 * elabora l'audio (rimozione voce): questa è responsabilità di
 * `MainActivity`/`VocalRemover`, invocata sul file WAV risultante dopo
 * lo stop. Vedi
 * docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCaptureService : Service() {

    interface Listener {
        /** La sorgente scelta blocca esplicitamente la cattura (es. protezione contenuti). */
        fun onCaptureBlocked()
        /** Cattura fermata correttamente dall'utente: [wavFile] contiene il PCM grezzo registrato. */
        fun onCaptureStopped(wavFile: File)
        /** Errore imprevisto durante l'avvio o l'esecuzione della cattura. */
        fun onCaptureError(message: String)
    }

    companion object {
        private const val TAG = "SystemAudioCapture"
        private const val CHANNEL_ID = "system_audio_capture_channel"
        private const val NOTIFICATION_ID = 5150
        const val SAMPLE_RATE = 44100
        const val CHANNEL_COUNT = 2
        private const val SILENCE_CHECK_MS = 500L
        private const val BYTES_PER_SAMPLE = 2 // PCM 16-bit

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_UID = "target_uid"
        const val ACTION_STOP = "com.example.vocalremover.capture.ACTION_STOP"

        /** Impostato da MainActivity prima di avviare il servizio, azzerato quando non serve più. */
        @Volatile
        var listener: Listener? = null
    }

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var stopRequested = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection fermato dal sistema")
            stopRequested = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Richiesta di stop ricevuta")
            stopRequested = true
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val targetUid = intent?.getIntExtra(EXTRA_TARGET_UID, -1) ?: -1

        if (resultData == null || targetUid < 0) {
            Log.e(TAG, "Intent di avvio incompleto, arresto")
            listener?.onCaptureError("Dati di avvio incompleti")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection ha restituito null")
            listener?.onCaptureError("Consenso alla cattura non valido")
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        scope.launch {
            runCapture(projection, targetUid)
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

    private suspend fun runCapture(projection: MediaProjection, targetUid: Int) {
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
            withContext(Dispatchers.Main) {
                listener?.onCaptureError("Configurazione audio non supportata su questo device")
            }
            return
        }

        val record = try {
            AudioRecord.Builder()
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
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile creare AudioRecord", e)
            withContext(Dispatchers.Main) {
                listener?.onCaptureError("Impossibile inizializzare la cattura: ${e.message}")
            }
            return
        }
        audioRecord = record

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non inizializzato")
            withContext(Dispatchers.Main) { listener?.onCaptureError("Impossibile inizializzare la cattura") }
            record.release()
            return
        }

        val outputFile = File(cacheDir, "system_audio_capture_${System.currentTimeMillis()}.wav")
        val writer = WavFileWriter(outputFile, SAMPLE_RATE, CHANNEL_COUNT)

        record.startRecording()
        try {
            val blocked = captureLoop(record, writer)
            writer.close()
            if (blocked) {
                outputFile.delete()
                withContext(Dispatchers.Main) { listener?.onCaptureBlocked() }
            } else {
                withContext(Dispatchers.Main) { listener?.onCaptureStopped(outputFile) }
            }
        } finally {
            record.stop()
        }
    }

    /** Restituisce true se la sorgente scelta blocca la cattura (silenzio rilevato). */
    private fun captureLoop(record: AudioRecord, writer: WavFileWriter): Boolean {
        val bytesForSilenceCheck =
            (SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE * SILENCE_CHECK_MS / 1000).toInt()
        val silenceCheckBuffer = ArrayList<Byte>(bytesForSilenceCheck)
        var bytesReadForCheck = 0
        var silenceCheckDone = false
        val readBuffer = ByteArray(minOf(record.bufferSizeInFrames * BYTES_PER_SAMPLE * CHANNEL_COUNT, 8192))

        while (!stopRequested) {
            val read = record.read(readBuffer, 0, readBuffer.size)
            if (read <= 0) continue

            if (!silenceCheckDone) {
                for (i in 0 until read) silenceCheckBuffer.add(readBuffer[i])
                bytesReadForCheck += read
                if (bytesReadForCheck >= bytesForSilenceCheck) {
                    silenceCheckDone = true
                    val samples = bytesToFloatSamples(silenceCheckBuffer.toByteArray())
                    if (RmsSilenceDetector.isSilent(samples)) {
                        Log.w(TAG, "Cattura bloccata: nessun segnale ricevuto dalla sorgente")
                        return true
                    }
                    Log.d(TAG, "Segnale rilevato, proseguo la registrazione")
                    writer.write(silenceCheckBuffer.toByteArray(), 0, silenceCheckBuffer.size)
                }
            } else {
                writer.write(readBuffer, 0, read)
            }
        }
        return false
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
                    "Registrazione audio di sistema",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stopIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Registrazione audio in corso")
            .setContentText("Tocca per fermare ed elaborare")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(stopPendingIntent)
            .build()
    }
}
```

- [ ] **Step 2: Register the service in the manifest**

In `app/src/main/AndroidManifest.xml`, add inside `<application>` (in the
same spot the old spike service declaration used to be):

```xml
        <service
            android:name=".capture.SystemAudioCaptureService"
            android:exported="false"
            android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 3: Verify it compiles**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (This class can't be unit-tested on the JVM —
it needs a real `MediaProjection` grant; end-to-end coverage happens in
Task 7's manual verification.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/capture/SystemAudioCaptureService.kt \
        app/src/main/AndroidManifest.xml
git commit -m "Add production SystemAudioCaptureService (44100Hz stereo, stoppable)"
```

---

### Task 5: `RecordingSaver` + `AudioPlayer.loadProcessedMono`

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/capture/RecordingSaver.kt`
- Modify: `app/src/main/java/com/example/vocalremover/AudioPlayer.kt` (add one
  new public method, nothing else changes)

**Interfaces:**
- Consumes: nothing new (plain `Context`/`FloatArray`/Android `MediaStore` APIs).
- Produces: `RecordingSaver.saveMonoPcmAsWav(context: Context, monoPcm: FloatArray, displayName: String): Uri`
  and `AudioPlayer.loadProcessedMono(monoPcm: FloatArray)` — both consumed
  by `MainActivity` in Task 6.

- [ ] **Step 1: Write `RecordingSaver`**

```kotlin
package com.example.vocalremover.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Salva il risultato strumentale (PCM mono float, come prodotto da
 * `StereoPcm.downmix()`) come file WAV riproducibile in seguito, nella
 * raccolta musicale pubblica del device (MediaStore, storage con ambito
 * per API 29+, coerente con il minimo richiesto da questa feature).
 */
object RecordingSaver {

    private const val SAMPLE_RATE = 44100
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNEL_COUNT = 1

    /** Salva [monoPcm] come `<displayName>.wav` e restituisce l'Uri MediaStore risultante. */
    fun saveMonoPcmAsWav(context: Context, monoPcm: FloatArray, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$displayName.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VocalRemover")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Impossibile creare la voce MediaStore per il salvataggio")

        resolver.openOutputStream(itemUri)?.use { out ->
            writeWav(out, monoPcm)
        } ?: throw IllegalStateException("Impossibile aprire lo stream di scrittura per $itemUri")

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        return itemUri
    }

    private fun writeWav(out: OutputStream, monoPcm: FloatArray) {
        val dataBytes = monoPcm.size * (BITS_PER_SAMPLE / 8)
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)
        val blockAlign = CHANNEL_COUNT * (BITS_PER_SAMPLE / 8)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(CHANNEL_COUNT.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        out.write(header.array())

        val pcmBuffer = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in monoPcm) {
            val clamped = sample.coerceIn(-1f, 1f)
            pcmBuffer.putShort((clamped * 32767f).toInt().toShort())
        }
        out.write(pcmBuffer.array())
    }
}
```

- [ ] **Step 2: Add `loadProcessedMono` to `AudioPlayer`**

In `app/src/main/java/com/example/vocalremover/AudioPlayer.kt`, add this
new public method right after `loadAndProcess` (do not change anything
else in the file):

```kotlin
    /**
     * Carica un segnale mono già elaborato (es. lo strumentale appena
     * ottenuto da una registrazione catturata da un'altra app) per la
     * riproduzione immediata, saltando decodifica e rimozione voce (già
     * eseguite dal chiamante). Deve essere chiamato dal thread principale.
     */
    fun loadProcessedMono(monoPcm: FloatArray) {
        processedPcm = monoPcm
        prepareAudioTrack()
        onReady()
    }
```

- [ ] **Step 3: Verify it compiles**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`RecordingSaver` needs a real `ContentResolver`;
covered by Task 7's manual end-to-end test, not a JVM unit test.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/capture/RecordingSaver.kt \
        app/src/main/java/com/example/vocalremover/AudioPlayer.kt
git commit -m "Add RecordingSaver (MediaStore export) and AudioPlayer.loadProcessedMono"
```

---

### Task 6: Wire the production capture flow into `MainActivity`

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (add the capture UI:
  package-name input, start/stop buttons, status text)
- Modify: `app/src/main/java/com/example/vocalremover/MainActivity.kt` (add
  the full capture → process → save → play flow)

**Interfaces:**
- Consumes: `com.example.vocalremover.capture.SystemAudioCaptureService`
  (Task 4), `com.example.vocalremover.capture.WavFileReader` (Task 3),
  `com.example.vocalremover.capture.RecordingFileNaming` (Task 1),
  `com.example.vocalremover.capture.RecordingSaver` (Task 5),
  `AudioPlayer.loadProcessedMono` (Task 5), existing
  `VocalRemover.removeVocals(signal, onProgress)`,
  `StereoPcm.downmix()`.
- Produces: nothing further (this is the last integration point).

- [ ] **Step 1: Add the capture UI to the layout**

In `app/src/main/res/layout/activity_main.xml`, add this block right after
the `tvInfo` TextView (which stays the last element before this new block;
its existing `app:layout_constraintTop_toBottomOf="@id/layoutControls"`
constraint is untouched):

```xml
    <!-- Registrazione audio da un'altra app -->
    <EditText
        android:id="@+id/etCapturePackageName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:hint="Nome pacchetto sorgente (es. com.android.chrome)"
        android:textSize="12sp"
        app:layout_constraintTop_toBottomOf="@id/tvInfo"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="24dp" />

    <FrameLayout
        android:id="@+id/layoutCaptureButtons"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toBottomOf="@id/etCapturePackageName"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp">

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnStartCapture"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="🎙️ Registra da un'altra app"
            android:textSize="14sp" />

        <com.google.android.material.button.MaterialButton
            android:id="@+id/btnStopCapture"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="⏹ Ferma e elabora"
            android:textSize="14sp"
            android:visibility="gone" />

    </FrameLayout>

    <TextView
        android:id="@+id/tvCaptureStatus"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text=""
        android:textSize="12sp"
        android:gravity="center"
        app:layout_constraintTop_toBottomOf="@id/layoutCaptureButtons"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_marginTop="8dp" />
```

(The two buttons live in a `FrameLayout` so toggling one `GONE`/`VISIBLE`
never changes the anchor `tvCaptureStatus` binds to below them — this
sidesteps the exact constraint bug found and fixed during the spike.)

- [ ] **Step 2: Replace `MainActivity.kt` with the full production flow**

Replace the whole file content with:

```kotlin
package com.example.vocalremover

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.vocalremover.capture.RecordingFileNaming
import com.example.vocalremover.capture.RecordingSaver
import com.example.vocalremover.capture.SystemAudioCaptureService
import com.example.vocalremover.capture.WavFileReader
import com.example.vocalremover.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vocalRemover: VocalRemover
    private lateinit var audioPlayer: AudioPlayer

    private var isUserSeeking = false

    // ── Launcher file picker ─────────────────────────────────────────────────
    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processAudio(it) }
    }

    // ── Launcher permessi ────────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickAudioLauncher.launch("audio/*")
        else Toast.makeText(this, "Permesso storage necessario", Toast.LENGTH_SHORT).show()
    }

    // ── Cattura audio da un'altra app ────────────────────────────────────────
    private var pendingCaptureTargetUid: Int = -1

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCaptureMediaProjectionConsent()
        else Toast.makeText(this, "Permesso microfono necessario per la registrazione", Toast.LENGTH_SHORT).show()
    }

    private val captureMediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && pendingCaptureTargetUid >= 0) {
            SystemAudioCaptureService.listener = captureListener
            val serviceIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(SystemAudioCaptureService.EXTRA_TARGET_UID, pendingCaptureTargetUid)
            }
            startForegroundService(serviceIntent)
            showCapturingUi()
        } else {
            Toast.makeText(this, "Consenso alla registrazione negato", Toast.LENGTH_SHORT).show()
        }
    }

    private val captureListener = object : SystemAudioCaptureService.Listener {
        override fun onCaptureBlocked() {
            runOnUiThread {
                hideCapturingUi()
                binding.tvCaptureStatus.text =
                    "Questa app non consente la registrazione audio (protezione del contenuto)."
                SystemAudioCaptureService.listener = null
            }
        }

        override fun onCaptureStopped(wavFile: File) {
            runOnUiThread {
                hideCapturingUi()
                binding.tvCaptureStatus.text = ""
                SystemAudioCaptureService.listener = null
                processCapturedRecording(wavFile)
            }
        }

        override fun onCaptureError(message: String) {
            runOnUiThread {
                hideCapturingUi()
                binding.tvCaptureStatus.text = "Errore registrazione: $message"
                SystemAudioCaptureService.listener = null
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vocalRemover = VocalRemover(this)
        audioPlayer = AudioPlayer(this)

        setupUi()
        setupPlayerCallbacks()
        setPlayerEnabled(false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            binding.btnStartCapture.isEnabled = false
            binding.tvCaptureStatus.text = "Richiede Android 10 (API 29) o superiore"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        vocalRemover.close()
        SystemAudioCaptureService.listener = null
    }

    // ── UI setup ─────────────────────────────────────────────────────────────
    private fun setupUi() {
        binding.btnPickFile.setOnClickListener { requestStoragePermissionAndPick() }

        binding.btnPlayPause.setOnClickListener {
            if (audioPlayer.isPlaying) {
                audioPlayer.pause()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                audioPlayer.play()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        binding.btnStop.setOnClickListener {
            audioPlayer.stop()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            binding.seekBar.progress = 0
            binding.tvCurrentTime.text = formatMs(0)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isUserSeeking = true }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvCurrentTime.text = formatMs(progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                isUserSeeking = false
                audioPlayer.seekTo(sb.progress)
            }
        })

        binding.btnStartCapture.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startCaptureFlow()
            else Toast.makeText(this, "Richiede Android 10 (API 29) o superiore", Toast.LENGTH_SHORT).show()
        }
        binding.btnStopCapture.setOnClickListener { stopCaptureFlow() }
    }

    private fun setupPlayerCallbacks() {
        audioPlayer.onProgress = { pct ->
            runOnUiThread {
                binding.progressBar.progress = pct
                binding.tvStatus.text = when {
                    pct < 5  -> "Caricamento..."
                    pct < 20 -> "Analisi spettrale..."
                    pct < 85 -> "Rimozione voci: $pct%"
                    pct < 100 -> "Ricostruzione audio..."
                    else -> "Pronto!"
                }
            }
        }

        audioPlayer.onPlaybackPositionChanged = { currentMs, totalMs ->
            if (!isUserSeeking) {
                binding.seekBar.max = totalMs
                binding.seekBar.progress = currentMs
                binding.tvCurrentTime.text = formatMs(currentMs)
                binding.tvTotalTime.text = formatMs(totalMs)
            }
        }

        audioPlayer.onReady = {
            binding.progressBar.visibility = android.view.View.GONE
            binding.seekBar.max = audioPlayer.durationMs
            binding.tvTotalTime.text = formatMs(audioPlayer.durationMs)
            setPlayerEnabled(true)
            Toast.makeText(this, "Elaborazione completata!", Toast.LENGTH_SHORT).show()
        }

        audioPlayer.onError = { msg ->
            binding.tvStatus.text = "Errore: $msg"
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Errore: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ── Elaborazione file caricato (flusso esistente, invariato) ─────────────
    private fun processAudio(uri: Uri) {
        setPlayerEnabled(false)
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Avvio elaborazione..."

        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                binding.tvFileName.text = name
            }
        }

        lifecycleScope.launch {
            audioPlayer.loadAndProcess(uri, vocalRemover)
        }
    }

    // ── Permessi (flusso file, invariato) ─────────────────────────────────────
    private fun requestStoragePermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                pickAudioLauncher.launch("audio/*")
            else ->
                requestPermissionLauncher.launch(permission)
        }
    }

    // ── Cattura audio da un'altra app ────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCaptureFlow() {
        val packageName = binding.etCapturePackageName.text.toString().trim()
        if (packageName.isEmpty()) {
            Toast.makeText(this, "Inserisci il nome pacchetto dell'app sorgente", Toast.LENGTH_SHORT).show()
            return
        }
        val uid = try {
            packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED).uid
        } catch (e: Exception) {
            Toast.makeText(this, "App '$packageName' non trovata: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        pendingCaptureTargetUid = uid

        val recordAudioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (recordAudioGranted) launchCaptureMediaProjectionConsent()
        else requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun launchCaptureMediaProjectionConsent() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Da Android 14 (API 34) si forza la modalità "Schermo intero" nel dialogo
        // di consenso: la modalità "Un'unica app" non produce una cattura audio
        // funzionante (verificato con lo spike di validazione, vedi la spec di design).
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        captureMediaProjectionLauncher.launch(captureIntent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun stopCaptureFlow() {
        val stopIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
            action = SystemAudioCaptureService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun showCapturingUi() {
        binding.btnStartCapture.visibility = android.view.View.GONE
        binding.btnStopCapture.visibility = android.view.View.VISIBLE
        binding.etCapturePackageName.isEnabled = false
        binding.tvCaptureStatus.text = "Registrazione in corso..."
    }

    private fun hideCapturingUi() {
        binding.btnStartCapture.visibility = android.view.View.VISIBLE
        binding.btnStopCapture.visibility = android.view.View.GONE
        binding.etCapturePackageName.isEnabled = true
    }

    private fun processCapturedRecording(wavFile: File) {
        setPlayerEnabled(false)
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Elaborazione registrazione..."

        lifecycleScope.launch {
            try {
                val pcm = withContext(Dispatchers.IO) { WavFileReader.readStereoPcm(wavFile) }
                val instrumental = vocalRemover.removeVocals(pcm) { progress ->
                    runOnUiThread { binding.progressBar.progress = progress }
                }
                withContext(Dispatchers.IO) { wavFile.delete() }
                promptSaveRecording(instrumental)
            } catch (e: Exception) {
                binding.progressBar.visibility = android.view.View.GONE
                binding.tvStatus.text = "Errore: ${e.message}"
                Toast.makeText(this@MainActivity, "Errore elaborazione: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptSaveRecording(instrumental: StereoPcm) {
        val input = EditText(this).apply {
            setText(RecordingFileNaming.defaultName())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Salva registrazione")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Salva") { _, _ ->
                val name = RecordingFileNaming.sanitize(input.text.toString())
                saveAndPlayRecording(instrumental, name)
            }
            .show()
    }

    private fun saveAndPlayRecording(instrumental: StereoPcm, name: String) {
        lifecycleScope.launch {
            try {
                val monoPcm = instrumental.downmix()
                withContext(Dispatchers.IO) {
                    RecordingSaver.saveMonoPcmAsWav(this@MainActivity, monoPcm, name)
                }
                binding.tvFileName.text = "$name.wav"
                audioPlayer.loadProcessedMono(monoPcm)
            } catch (e: Exception) {
                binding.progressBar.visibility = android.view.View.GONE
                Toast.makeText(this@MainActivity, "Errore salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setPlayerEnabled(enabled: Boolean) {
        binding.btnPlayPause.isEnabled = enabled
        binding.btnStop.isEnabled = enabled
        binding.seekBar.isEnabled = enabled
    }

    private fun formatMs(ms: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
```

- [ ] **Step 3: Verify it compiles and resources build**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit test suite (no regressions from earlier tasks)**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:testDebugUnitTest`
Expected: PASS, all tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/activity_main.xml \
        app/src/main/java/com/example/vocalremover/MainActivity.kt
git commit -m "Wire production capture-record-process-save-play flow into MainActivity"
```

---

### Task 7: Manual end-to-end verification on a real device + spec update

**Files:**
- Modify: `docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md`
  (update the status blockquote once verification passes)

**Interfaces:**
- Consumes: the full app (Tasks 1-6).
- Produces: nothing (verification + documentation only).

- [ ] **Step 1: Install the debug build on a real device**

Run: `PATH=$PATH:/home/stefano/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin gradle :app:installDebug`
Expected: installs successfully on a connected device (`adb devices` shows
it attached first).

- [ ] **Step 2: Verify the existing file-picker flow is unaffected**

Manually: tap "📂 Seleziona file audio", pick a local audio file, confirm
it processes and plays exactly as before (no regression from Tasks 2/6's
`MainActivity` rewrite).

- [ ] **Step 3: Verify capture with a compatible source (e.g. YouTube/Chrome)**

Manually: enter `com.android.chrome` (or `com.google.android.youtube`) in
the package field, tap "🎙️ Registra da un'altra app", accept the
MediaProjection consent (should show only "Schermo intero" on API 34+,
per the forced config), start audio playing in the target app, wait a few
seconds, tap "⏹ Ferma e elabora". Expected: processing progress bar runs,
a save dialog appears prefilled with `Registrazione_<date>_<time>`, saving
succeeds, and the resulting instrumental plays back immediately through
the app's existing player controls. Confirm the saved file also appears
in the device's Music app / file manager under `Music/VocalRemover/`.

- [ ] **Step 4: Verify capture with a source that blocks capture (e.g. Spotify)**

Manually: repeat with `com.spotify.music` as the source. Expected: no
save dialog appears, no file is written, and
`tvCaptureStatus` shows "Questa app non consente la registrazione audio
(protezione del contenuto)." — confirming `ALLOW_CAPTURE_BY_NONE` is
still respected, not bypassed, in the production flow.

- [ ] **Step 5: Update the design spec status**

In `docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md`,
update the status blockquote at the top to record that the full
production implementation (this plan) has been built and manually
verified end-to-end, referencing this plan file.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-09-12-system-audio-capture-vocal-removal-design.md
git commit -m "Mark system audio capture feature as fully implemented and verified"
```

---

## Self-Review Notes

- **Spec coverage:** Naming/sanitization (Task 1), silence-blocked
  detection reuse (Task 2/4), 44100Hz capture matching the pipeline
  (Task 4, Global Constraints), MediaStore save (Task 5), forced
  "Schermo intero" on API 34+ (Task 6), existing file flow untouched
  (Task 2/6), `ALLOW_CAPTURE_BY_NONE` respected (Task 4/7) — all covered.
- **Placeholder scan:** no TBD/TODO, every step has full code or an exact
  manual verification script.
- **Type consistency:** `WavFileReader.readStereoPcm` returns `StereoPcm`
  (existing type, `left`/`right` `FloatArray`) consumed identically by
  `VocalRemover.removeVocals` in Task 6, matching its existing signature
  `removeVocals(signal: StereoPcm, onProgress: (Int) -> Unit): StereoPcm`.
  `AudioPlayer.loadProcessedMono(monoPcm: FloatArray)` matches the
  `FloatArray` produced by `StereoPcm.downmix()` used in Task 6.
  `SystemAudioCaptureService.Listener` method names/signatures in Task 4
  match exactly what `MainActivity`'s `captureListener` implements in
  Task 6.
