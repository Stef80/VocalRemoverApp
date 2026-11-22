# Editor di taglio della registrazione ed elaborazione dalla schermata Import — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dopo la cattura l'utente taglia inizio e fine della registrazione in un editor con forma d'onda, la nomina, la ascolta e la elabora da `ImportSongScreen`; a elaborazione riuscita lo strumentale è nella libreria e i WAV della registrazione sono cancellati.

**Architecture:** In `:engine` tre utilità in streaming sul WAV PCM16 (`WavTrimmer`, `WavWaveform`, estensione di `WavFileReader`) e una facciata pubblica `PendingCaptureRepository` che unisce `CaptureTerminalResultStore` (fonte del WAV originale e delle fasi), una nuova `CaptureDraftStore` (nome, maniglie, file tagliato) e la pulizia dei file. In `:ui` un `ImportViewModel` legato a `MainActivity` comanda editor, anteprima (`PreviewPlayer` su `LibraryPlayer`) ed elaborazione (`SongProcessor`); le schermate leggono i suoi `StateFlow`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `lifecycle-viewmodel-compose` 2.11.0, coroutines 1.8.1, Media3 ExoPlayer 1.4.1 (tramite `LibraryPlayer`), SharedPreferences, JUnit 4, Robolectric 4.16, kotlinx-coroutines-test 1.8.1.

**Spec:** `docs/superpowers/specs/2026-10-18-capture-trim-editor-design.md`

## Global Constraints

- **Mai `git commit`** (CLAUDE.md): ogni task finisce con `git add` dei file toccati; il commit lo fa l'utente.
- `:engine` non dipende da `:ui`; nei file nuovi di `:engine` nessun testo per l'utente (errori come eccezioni/enum, log in italiano).
- In `:ui` nessun testo hard-coded: tutto in `ui/src/main/res/values/strings.xml`, `stringResource` nei composable. Ogni schermata mostra `R.drawable.logo_corallo_blu` accanto al titolo.
- Commenti e KDoc in italiano. minSdk 26. Nessuna nuova dipendenza di produzione; `collectAsState` (non `collectAsStateWithLifecycle`, la libreria non c'è).
- Gradle 9.8.0 in cache, non il `gradle` di sistema:
  `G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.0*/*/gradle-9.8.0/bin/gradle | head -1)`; i comandi sotto scrivono `$G`.
- Formato del WAV catturato: PCM 16 bit, stereo, 44 100 Hz, header di 44 byte (`WavFileReader` rifiuta altro).
- Valori fissati dalla spec: minimo 1000 ms tra le maniglie; passo maniglie 10 ms; 1000 bucket di forma d'onda; salto di 3000 ms prima della fine al rilascio della maniglia di fine; suffisso `_tagliato`; file tagliato in `cacheDir/<trimmedName>.wav`.
- Package nuovi: engine `com.example.vocalremover.capture` (file nuovi accanto agli esistenti); ui `com.stef.vocalremover.ui` (ViewModel, stati, player) e `com.stef.vocalremover.ui.editor` (editor).

## Review Focus

1. Cattura più corta di 1 s (o appena sopra): le maniglie non devono produrre `start > end` né valori negativi; si salva solo senza taglio. Test: Task 5, `capture shorter than min gap keeps full range`.
2. Rinomina + nuovo taglio che fallisce (intervallo rifiutato o scrittura interrotta): il vecchio file tagliato e la bozza restano validi, nessun file a metà col nome nuovo. Test: Task 4, `failed trim keeps previous trimmed file and draft`.
3. Due `load()` concorrenti all'avvio (init del ViewModel + intent del bubble) non devono creare due bozze diverse: la seconda legge quella della prima. Test: Task 4, `second load reuses the draft created by the first`.
4. Fine maniglia esattamente a `durationMs` con durata non multipla di 10 ms: il taglio include l'ultimo frame, nessuna eccezione. Test: Task 2, `end at duration copies up to the last frame`.
5. Elaborazione che fallisce dopo un taglio: dopo l'errore "Elabora" deve funzionare di nuovo sullo stesso file tagliato. Test: Task 6, `process can be retried after a failure`.

---

### Task 1: `WavFileReader` con offset/frame/durata e `RecordingFileNaming.trimmedName`

**Files:**
- Modify: `engine/src/main/java/com/example/vocalremover/capture/WavFileReader.kt`
- Modify: `engine/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt`
- Create: `engine/src/test/java/com/example/vocalremover/capture/TestWavFiles.kt` (helper condivisi dai test)
- Modify: `engine/src/test/java/com/example/vocalremover/capture/WavFileReaderTest.kt`, `RecordingFileNamingTest.kt`

**Interfaces:**
- Produces:
  - `data class WavFileReader.WavInfo(val sampleRate: Int, val channelCount: Int, val blockAlign: Int, val dataOffset: Long, val frameCount: Long)`
  - `fun WavFileReader.durationMs(info: WavInfo): Long` = `info.frameCount * 1000 / info.sampleRate` (troncato)
  - `fun RecordingFileNaming.trimmedName(displayName: String): String` = `"${sanitize(displayName)}_tagliato"`
  - test: `internal fun writePcm16StereoWav(file: File, interleaved: ShortArray)` (via `WavFileWriter`), `internal fun withTempDir(block: (File) -> Unit)` (cartella sotto `build/`, cancellata alla fine), `internal fun rampSamples(frames: Int): ShortArray` (L = indice, R = -indice, troncati a Short)

- [ ] **Step 1: Sposta `writeStereoWav`/`withTestFile` di `WavFileReaderTest` in `TestWavFiles.kt` con i nomi sopra e scrivi i test che falliscono**

```kotlin
@Test fun `readInfo reports data offset, frame count and duration`() = withTempDir { dir ->
    val file = File(dir, "a.wav").also { writePcm16StereoWav(it, rampSamples(44_100 * 2 + 441)) }
    val info = WavFileReader.readInfo(file)
    assertEquals(44L, info.dataOffset)
    assertEquals(4, info.blockAlign)
    assertEquals(88_641L, info.frameCount)
    assertEquals(2010L, WavFileReader.durationMs(info))
}
// RecordingFileNamingTest
@Test fun `trimmedName sanitizes and adds suffix`() =
    assertEquals("A_B_tagliato", RecordingFileNaming.trimmedName("A/B"))
```

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.WavFileReaderTest" --tests "com.example.vocalremover.capture.RecordingFileNamingTest"`
Expected: FAIL di compilazione (`dataOffset`, `trimmedName` non esistono).

- [ ] **Step 3: Implementa** — `ParsedHeader` già conosce `blockAlign` e `dataSize`; `dataOffset` è sempre `HEADER_SIZE` (l'header è fisso a 44 byte), `frameCount = dataSize / blockAlign`.

- [ ] **Step 4: Esegui tutti i test di `capture`**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.*"`
Expected: PASS (anche i test preesistenti di `WavFileReaderTest`).

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/WavFileReader.kt engine/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt engine/src/test/java/com/example/vocalremover/capture/
```

---

### Task 2: `WavTrimmer`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/capture/WavTrimmer.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/WavTrimmerTest.kt`

**Interfaces:**
- Consumes: `WavFileReader.readInfo/durationMs` (Task 1); `WavFileWriter(fileAccess: WavFileAccess, sampleRate, channelCount)` e `WavFileWriter(file, sampleRate, channelCount)` (esistenti, `internal`).
- Produces:
  - `object WavTrimmer { const val MIN_LENGTH_MS = 1000L; fun trim(source: File, target: File, startMs: Long, endMs: Long) }`
  - `internal fun WavTrimmer.trim(source, target, startMs, endMs, openWriter: (File) -> WavFileWriter)` (overload per i test)

- [ ] **Step 1: Scrivi i test che falliscono** (usa `rampSamples`, così ogni frame è riconoscibile)

```kotlin
@Test fun `copies exactly the frames of the interval`()      // 3 s, trim 1000..2000 → frameCount 44_100,
                                                              // primo frame L == 44_100.toShort(), header valido (readInfo non lancia)
@Test fun `end at duration copies up to the last frame`()     // 2010 ms (88_641 frame), trim 500..durationMs → frameCount 88_641 - 22_050
@Test fun `full range produces an identical data chunk`()     // byte di target == byte di source
@Test fun `rejects invalid ranges`()                          // assertThrows<IllegalArgumentException> per: start < 0; end > durationMs;
                                                              // end - start == 999
@Test fun `failure mid copy leaves no target and keeps previous one`() {
    // target esiste già con contenuto "old"; openWriter restituisce un WavFileWriter su una WavFileAccess
    // finta che lancia IOException alla seconda write.
    assertThrows(IOException::class.java) { WavTrimmer.trim(src, target, 0, 2000) { failingWriter } }
    assertEquals("old", target.readText())
    assertFalse(File(target.path + ".tmp").exists())
}
```

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.WavTrimmerTest"`
Expected: FAIL (`WavTrimmer` non esiste).

- [ ] **Step 3: Implementa `trim`** — frame: `startFrame = startMs * sampleRate / 1000`; `endFrame = if (endMs == durationMs) frameCount else endMs * sampleRate / 1000`. Apri `source` con `RandomAccessFile`, `seek(dataOffset + startFrame * blockAlign)`, copia a blocchi da 64 KiB nel writer aperto su `File(target.path + ".tmp")`, `close()` del writer, poi `renameTo(target)` (se `false`: cancella `target` e riprova; se fallisce ancora `IOException`). Qualsiasi eccezione: chiudi, cancella il `.tmp`, rilancia.

- [ ] **Step 4: Esegui i test**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.WavTrimmerTest"`
Expected: PASS

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/WavTrimmer.kt engine/src/test/java/com/example/vocalremover/capture/WavTrimmerTest.kt
```

---

### Task 3: `WavWaveform`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/capture/WavWaveform.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/WavWaveformTest.kt`

**Interfaces:**
- Consumes: `WavFileReader.readInfo` (Task 1).
- Produces: `object WavWaveform { fun peaks(file: File, buckets: Int): FloatArray }` — valori 0–1 (`max(|L|,|R|) / 32768f` per blocco); lunghezza `min(buckets, frameCount)`; `require(buckets > 0)`.

- [ ] **Step 1: Scrivi i test che falliscono**

```kotlin
@Test fun `returns one peak per bucket`()              // 10_000 frame, 1000 bucket → size 1000
@Test fun `peak reflects the loudest channel`()         // tutto 0 tranne frame 5_500 con R = -16384 → peaks[550] == 0.5f (±1e-4), gli altri 0f
@Test fun `short file returns one peak per frame`()     // 10 frame, 1000 bucket → size 10
```

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.WavWaveformTest"`
Expected: FAIL

- [ ] **Step 3: Implementa** — lettura a blocchi da 64 KiB con `BufferedInputStream` dopo `dataOffset`; il frame `i` va nel bucket `(i * n / frameCount).toInt()`.

- [ ] **Step 4: Esegui i test**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.WavWaveformTest"`
Expected: PASS

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/WavWaveform.kt engine/src/test/java/com/example/vocalremover/capture/WavWaveformTest.kt
```

---

### Task 4: `CaptureDraftStore` e `PendingCaptureRepository`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/capture/CaptureDraftStore.kt`
- Create: `engine/src/main/java/com/example/vocalremover/capture/PendingCaptureRepository.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/CaptureDraftStoreTest.kt`, `PendingCaptureRepositoryTest.kt`

**Interfaces:**
- Consumes: `CaptureTerminalResultStore` (`persist`, `acquireStoppedResult(ownerId)`, `markSaved`, `releaseStoppedResult`, `completeSavedHandoff`, `discardStoppedResult`), `WavTrimmer`, `WavWaveform`, `WavFileReader`, `RecordingFileNaming.defaultName/sanitize/trimmedName`.
- Produces:
  - `internal data class CaptureDraft(captureId: String, displayName: String, trimmedPath: String?, trimStartMs: Long, trimEndMs: Long)`
  - `internal class CaptureDraftStore(context: Context) { fun read(captureId: String): CaptureDraft?; fun write(draft: CaptureDraft); fun clear() }` — SharedPreferences `capture_draft`; `read` con id diverso → `clear()` e `null`.
  - `data class PendingCapture` e `interface PendingCaptureRepository` **esattamente come nella spec** (sezione "PendingCaptureRepository"), con `companion object { fun create(context: Context): PendingCaptureRepository }`.
  - `internal class DefaultPendingCaptureRepository(context: Context, private val cacheDir: File = context.cacheDir, private val clock: () -> Long = System::currentTimeMillis) : PendingCaptureRepository` — ogni metodo pubblico `@Synchronized`; `ownerId = UUID.randomUUID().toString()` per istanza.

Regole che i test fissano (dalla spec):
- `load()`: risultato `STOPPED` assente → `null`. WAV mancante o `UnsupportedWavFormatException` → acquisisce e `discardStoppedResult` (o, se non acquisibile, lascia lo store), `draftStore.clear()`, `null`. Bozza assente → `CaptureDraft(id, defaultName(clock()), null, 0, durationMs)` scritta subito. Una bozza con `trimmedPath` il cui file non esiste più → torna senza taglio (`trimmedPath = null`, maniglie a 0/durata).
- `load()` **non** acquisisce la cattura (la sola lettura usa lo store senza lease): aggiungi a `CaptureTerminalResultStore` un `internal fun peekStoppedResult(): CaptureTerminalResult?` (`readLocked()` filtrato su `STOPPED`, sotto `lock`).
- `beginProcessing()`: `acquireStoppedResult(ownerId)` con stato risultante `PROCESSING` → `true`; `null` → `false`.
- `completeProcessing(uri)`: `markSaved` → cancella raw e tagliato → `completeSavedHandoff` → `draftStore.clear()`.
- `abortProcessing()`: `releaseStoppedResult`. `discard()`: acquisisce se serve, cancella i file, `discardStoppedResult`, `draftStore.clear()`.
- `cleanOrphans()`: in `cacheDir` cancella i file che corrispondono a `system_audio_capture_*.wav`, `*_tagliato.wav`, `*_tagliato.wav.tmp`, tranne raw e tagliato della cattura corrente.

- [ ] **Step 1: Scrivi i test che falliscono** (Robolectric, `@Config(sdk = [35])`, `ApplicationProvider.getApplicationContext()`; ogni test crea il WAV in una cartella temporanea passata come `cacheDir` e chiama `CaptureTerminalResultStore(ctx).persist(STOPPED, outputPath = raw.path)`; `clock = { 0L }`)

```kotlin
// CaptureDraftStoreTest
@Test fun `draft for another capture is discarded`()       // write(id "a"), read("b") == null, poi read("a") == null

// PendingCaptureRepositoryTest
@Test fun `no stopped capture loads null`()
@Test fun `first load creates the default draft`()        // displayName == defaultName(0L), 0..durationMs, trimmedFile == null
@Test fun `second load reuses the draft created by the first`()  // due istanze del repository, clock diversi → stesso displayName
@Test fun `missing wav discards capture and draft`()      // raw cancellato prima di load → null; peekStoppedResult() == null
@Test fun `saveEdit with full range removes trim and keeps name`()  // dopo un taglio, saveEdit("X", 0, duration) → trimmedFile null, file vecchio cancellato
@Test fun `saveEdit trims into cacheDir with suffix`()    // saveEdit("Canzone", 1000, 2000) → trimmedFile == File(cacheDir, "Canzone_tagliato.wav"), durata 1000 ms
@Test fun `renaming deletes the old trimmed file`()
@Test fun `blank name is rejected`()                      // assertThrows<IllegalArgumentException> { saveEdit("  ", 0, duration) }
@Test fun `failed trim keeps previous trimmed file and draft`()  // primo saveEdit("A", 1000, 2000) ok; secondo saveEdit("B", 1000, 1999) → IAE dal trimmer;
                                                                 // load() restituisce ancora "A" con A_tagliato.wav esistente, nessun B_tagliato.wav*
@Test fun `complete processing deletes files and clears everything`()  // begin → true; complete(uri) → raw e tagliato spariti, load() == null
@Test fun `abort keeps files and allows a new begin`()
@Test fun `discard deletes files and clears store`()
@Test fun `cleanOrphans keeps only the current capture files`()  // più file orfani dei tre pattern + un file "altro.txt" che resta
```

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.CaptureDraftStoreTest" --tests "com.example.vocalremover.capture.PendingCaptureRepositoryTest"`
Expected: FAIL (classi assenti).

- [ ] **Step 3: Implementa `CaptureDraftStore`, `peekStoppedResult`, `PendingCapture`, `PendingCaptureRepository`, `DefaultPendingCaptureRepository`** con le regole sopra. In `saveEdit` la bozza si scrive solo dopo il taglio riuscito e il vecchio file tagliato si cancella solo dopo (se il percorso è diverso). Log con tag `PendingCaptureRepo`.

- [ ] **Step 4: Esegui tutti i test di `capture` e `processing`**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.capture.*" --tests "com.example.vocalremover.processing.*"`
Expected: PASS

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/ engine/src/test/java/com/example/vocalremover/capture/
```

---

### Task 5: `TrimHandles` (logica pura delle maniglie)

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/editor/TrimHandles.kt`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/editor/TrimHandlesTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class TrimHandle { START, END }

internal object TrimHandles {
    const val STEP_MS = 10L
    const val MIN_GAP_MS = 1000L
    const val END_PREVIEW_MS = 3000L
    const val ACCESSIBILITY_STEP_MS = 1000L
    /** Arrotonda a STEP_MS, poi limita a [0, endMs - MIN_GAP_MS] (mai sotto 0). */
    fun moveStart(requestedMs: Long, endMs: Long): Long
    /** Arrotonda a STEP_MS, poi limita a [startMs + MIN_GAP_MS, durationMs] (mai sopra durationMs). */
    fun moveEnd(requestedMs: Long, startMs: Long, durationMs: Long): Long
    fun pxToMs(px: Float, widthPx: Float, durationMs: Long): Long   // limitato a [0, durationMs]
    fun msToPx(ms: Long, widthPx: Float, durationMs: Long): Float
    /** START → startMs; END → max(startMs, endMs - END_PREVIEW_MS). */
    fun seekAfterRelease(handle: TrimHandle, startMs: Long, endMs: Long): Long
    /** positionMs se in [startMs, endMs), altrimenti startMs. */
    fun playbackStart(positionMs: Long, startMs: Long, endMs: Long): Long
    fun isFullRange(startMs: Long, endMs: Long, durationMs: Long): Boolean = startMs == 0L && endMs == durationMs
}
```

- [ ] **Step 1: Scrivi i test che falliscono** (JUnit puro)

```kotlin
@Test fun `start snaps to 10 ms and stays min gap before end`()   // moveStart(1234, 5000) == 1230; moveStart(4500, 5000) == 4000; moveStart(-50, 5000) == 0
@Test fun `end snaps and is clamped to duration`()                // moveEnd(3337, 1000, 10_005) == 3340; moveEnd(20_000, 1000, 10_005) == 10_005; moveEnd(1500, 1000, 10_005) == 2000
@Test fun `capture shorter than min gap keeps full range`()        // durata 800: moveStart(300, 800) == 0; moveEnd(100, 0, 800) == 800
@Test fun `px and ms convert both ways`()                          // pxToMs(500f, 1000f, 240_000) == 120_000; pxToMs(-10f, …) == 0; msToPx(60_000, 1000f, 240_000) == 250f
@Test fun `release seeks to start or three seconds before end`()  // (START,1000,9000) → 1000; (END,1000,9000) → 6000; (END,1000,2500) → 1000
@Test fun `playback starts inside the interval`()                  // (5000,1000,9000) → 5000; (9000,1000,9000) → 1000; (500,1000,9000) → 1000
```

- [ ] **Step 2: Esegui e verifica che fallisca**

Run: `$G :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.editor.TrimHandlesTest"`
Expected: FAIL

- [ ] **Step 3: Implementa `TrimHandles`**

- [ ] **Step 4: Esegui i test**

Run: `$G :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.editor.TrimHandlesTest"`
Expected: PASS

- [ ] **Step 5: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/editor/TrimHandles.kt ui/src/test/java/com/stef/vocalremover/ui/editor/TrimHandlesTest.kt
```

---

### Task 6: `PreviewPlayer`, stati e `ImportViewModel`

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/PreviewPlayer.kt` (interfaccia + `LibraryPreviewPlayer`)
- Create: `ui/src/main/java/com/stef/vocalremover/ui/ImportUiState.kt` (`ImportUiState`, `ProcessingPhase`, `EditorUiState`, `ImportEvent`)
- Create: `ui/src/main/java/com/stef/vocalremover/ui/ImportViewModel.kt`
- Modify: `ui/build.gradle.kts` (dipendenze di test + `testOptions` per Robolectric come in `engine/build.gradle.kts:49-56`)
- Test: `ui/src/test/java/com/stef/vocalremover/ui/ImportViewModelTest.kt`

**Interfaces:**
- Consumes: `PendingCaptureRepository`, `PendingCapture`, `RecordingFileNaming.instrumentalName` (engine); `SongProcessor`, `ProcessingState`, `ProcessingException`, `ProcessingError`; `LibraryPlayer`, `PlayerState`; `VocalRemover`; `TrimHandles`, `TrimHandle` (Task 5).
- Produces:
  - `interface PreviewPlayer { val state: StateFlow<PlayerState>; fun play(uri: Uri); fun pause(); fun seekTo(positionMs: Long); fun release() }`; `class LibraryPreviewPlayer(private val player: LibraryPlayer) : PreviewPlayer` (delega 1:1).
  - Tipi di stato **come nella spec** (sezione "Stati"), tranne `TrimHandle` che sta in `editor/TrimHandles.kt`.
  - `class ImportViewModel(captures: PendingCaptureRepository, processor: SongProcessor, player: PreviewPlayer, ioDispatcher: CoroutineDispatcher = Dispatchers.IO, releaseResources: () -> Unit = {}) : ViewModel()` con i membri pubblici della spec (`importState`, `editorState`, `playerState`, `events`, `loadPendingCapture(openEditor)`, `openEditor`, `closeEditor`, `onNameChange`, `onHandlesChange`, `onHandleReleased`, `onSeek`, `togglePlayback`, `saveEdit`, `process`, `discard`) e `companion object { val Factory: ViewModelProvider.Factory }`.

Comportamenti che i test fissano:
- `init`: su `ioDispatcher` `cleanOrphans()` poi `loadPendingCapture(false)`.
- `loadPendingCapture(openEditor)`: `load()` → `null`: stato `Empty`, e se `openEditor` evento `CaptureUnavailable`. Altrimenti `CaptureReady(name, Uri.fromFile(playableFile), durata del file riproducibile, isTrimmed)`; durata = `trimEndMs - trimStartMs` se tagliato, altrimenti `durationMs`. Se `openEditor` → `openEditor()`. Ignorato durante `Processing`.
- `openEditor()`: `player.pause()`; `editorState` dalla bozza (`peaks = null`, `positionMs = trimStartMs`, `previewAvailable = true`); poi `captures.peaks(1000)` su IO riempie `peaks`. L'anteprima dell'editor suona sempre `rawFile`.
- `onHandlesChange` applica `TrimHandles.moveStart/moveEnd` (si muove la maniglia il cui valore è cambiato). `onHandleReleased(h)` → `player.seekTo(seekAfterRelease(...))`.
- `togglePlayback()` nell'editor: se suona → `pause()`; altrimenti `play(rawUri)` poi `seekTo(playbackStart(...))`. Nella card Import: `play(playableUri)` / `pause()`.
- Mentre l'editor è aperto, uno stato del player con `isPlaying && positionMs >= endMs` → `pause()` + `seekTo(endMs)`. `PlayerState.error != null` → `previewAvailable = false` + evento `PreviewUnavailable` (una volta).
- `saveEdit()`: nome `isBlank()` → `NameRequired`; altrimenti `isSaving = true`, `captures.saveEdit` su IO; ok → `editorState = null`, `CaptureReady` aggiornato, `EditSaved`; eccezione → `isSaving = false`, `EditFailed`.
- `closeEditor()`: `pause()`, `editorState = null`.
- `process()`: solo da `CaptureReady`. `pause()`; `beginProcessing()` su IO; `false` → `CaptureUnavailable` + `loadPendingCapture(false)`. Poi `Processing(name, Decoding)`, raccogli `processor.state` (`Decoding`→`Decoding`, `Processing(p)`→`Running(p)`, `Saving`→`Saving`) e chiama `processor.process(playableUri, instrumentalName(name))`. Ok → `completeProcessing(uri)` su IO, `Empty`, `ProcessingDone`. `ProcessingException` → `abortProcessing()`, `loadPendingCapture(false)`, `ProcessingFailed(e.error)`.
- `discard()`: ignorato durante `Processing`; `pause()`, `captures.discard()` su IO, `Empty`, `editorState = null`.
- `events`: `Channel<ImportEvent>(Channel.BUFFERED).receiveAsFlow()`.
- `onCleared()`: `player.release()`, `releaseResources()`.
- `Factory`: `viewModelFactory { initializer { val app = this[APPLICATION_KEY]!!; val remover = VocalRemover(app); ImportViewModel(PendingCaptureRepository.create(app), SongProcessor.create(app, remover), LibraryPreviewPlayer(LibraryPlayer(app)), releaseResources = remover::close) } }`.

- [ ] **Step 1: Aggiungi a `ui/build.gradle.kts`** `testImplementation("org.robolectric:robolectric:4.16")`, `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")` e il blocco `testOptions { unitTests.all { … java.io.tmpdir … } }` copiato da `engine/build.gradle.kts:49-56`.

- [ ] **Step 2: Scrivi i test che falliscono** (Robolectric `@Config(sdk = [35])`; `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `resetMain` in `@After`; `ioDispatcher` = stesso dispatcher; `FakeCaptures : PendingCaptureRepository` in memoria che registra le chiamate e può lanciare; `FakePlayer : PreviewPlayer` con `MutableStateFlow<PlayerState>` e la lista delle chiamate; `SongProcessor` vero con dipendenze finte come in `SongProcessorTest`)

```kotlin
@Test fun `no capture is Empty`()
@Test fun `pending capture is CaptureReady with playable trimmed file`()   // isTrimmed, playableUri == Uri.fromFile(trimmed), durata 1000
@Test fun `load from capture stop opens the editor`()                     // loadPendingCapture(true) → editorState != null, peaks.size == 1000 dopo IO
@Test fun `load from capture stop without capture emits CaptureUnavailable`()
@Test fun `blank name emits NameRequired and keeps editor open`()
@Test fun `save closes editor and updates CaptureReady`()                 // events contiene EditSaved
@Test fun `save failure keeps editor open with EditFailed`()
@Test fun `playback pauses at end handle`()                               // editor aperto, end 2000; player emette isPlaying=true, position 2010 → pause() e seekTo(2000)
@Test fun `successful processing returns to Empty and completes capture`() // stati: CaptureReady → Processing(Running(50)) → Empty; FakeCaptures.completed == savedUri
@Test fun `process can be retried after a failure`()                      // primo process: extractor lancia → ProcessingFailed(INFERENCE_FAILED), abort chiamato, CaptureReady;
                                                                          // secondo process ok → Empty
@Test fun `begin refused emits CaptureUnavailable`()
@Test fun `discard returns to Empty`()
@Test fun `discard is ignored while processing`()
```

- [ ] **Step 3: Esegui e verifica che fallisca**

Run: `$G :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.ImportViewModelTest"`
Expected: FAIL (classi assenti)

- [ ] **Step 4: Implementa `PreviewPlayer.kt`, `ImportUiState.kt`, `ImportViewModel.kt`** secondo i comportamenti sopra.

- [ ] **Step 5: Esegui tutti i test di `:ui`**

Run: `$G :ui:testCpuDebugUnitTest`
Expected: PASS (compresi `BubbleOverlayStateTest` e `BubbleDismissPolicyTest`)

- [ ] **Step 6: Stage**

```bash
git add ui/build.gradle.kts ui/src/main/java/com/stef/vocalremover/ui/PreviewPlayer.kt ui/src/main/java/com/stef/vocalremover/ui/ImportUiState.kt ui/src/main/java/com/stef/vocalremover/ui/ImportViewModel.kt ui/src/test/java/com/stef/vocalremover/ui/ImportViewModelTest.kt
```

---

### Task 7: `TrimEditorScreen` e `TrimWaveform`

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/editor/TrimWaveform.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/editor/TrimEditorScreen.kt`
- Modify: `ui/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `EditorUiState` (Task 6), `TrimHandles`, `TrimHandle` (Task 5).
- Produces:
  - `@Composable fun TrimWaveform(peaks: FloatArray?, durationMs: Long, startMs: Long, endMs: Long, positionMs: Long, onHandlesChange: (Long, Long) -> Unit, onHandleReleased: (TrimHandle) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun TrimEditorScreen(state: EditorUiState, onNameChange: (String) -> Unit, onHandlesChange: (Long, Long) -> Unit, onHandleReleased: (TrimHandle) -> Unit, onSeek: (Long) -> Unit, onTogglePlayback: () -> Unit, onSave: () -> Unit, onClose: () -> Unit, snackbarHostState: SnackbarHostState)`

Stringhe nuove (`strings.xml`, testi esatti):

```xml
<string name="editor_title">Modifica registrazione</string>
<string name="editor_name_label">Nome brano</string>
<string name="editor_start">Inizio %1$s</string>
<string name="editor_end">Fine %1$s</string>
<string name="editor_duration">Durata %1$s</string>
<string name="editor_save">Salva</string>
<string name="editor_close">Chiudi</string>
<string name="editor_handle_start_description">Inizio taglio, %1$s</string>
<string name="editor_handle_end_description">Fine taglio, %1$s</string>
<string name="editor_handle_forward">Avanti di un secondo</string>
<string name="editor_handle_backward">Indietro di un secondo</string>
<string name="editor_waveform_loading">Caricamento forma d\'onda…</string>
```

- [ ] **Step 1: Implementa `TrimWaveform`** — `BoxWithConstraints` alto 160 dp: un `Canvas` disegna una barra per picco (alpha 0.35 fuori da `[startMs, endMs]`, `colorScheme.primary` dentro) e la linea della posizione; senza `peaks` un `CircularProgressIndicator` con `editor_waveform_loading` come descrizione. Tocco sul `Canvas` (`detectTapGestures`) → `onSeek(pxToMs)`. Ogni maniglia è un `Box` largo 48 dp sovrapposto, centrato su `msToPx`, con `draggable(Orientation.Horizontal, onDragStopped = { onHandleReleased(h) })` che chiama `onHandlesChange` con `TrimHandles.moveStart/moveEnd`, e `semantics { contentDescription = …; customActions = ±ACCESSIBILITY_STEP_MS }`. Maniglie non interattive se `durationMs <= TrimHandles.MIN_GAP_MS`.

- [ ] **Step 2: Implementa `TrimEditorScreen`** — `Scaffold` con `safeDrawingPadding` come `ImportSongScreen`; barra con `ic_arrow_back` (`control_back_description`) + logo 40 dp + `editor_title`; `OutlinedTextField` (`editor_name_label`, singola riga); `TrimWaveform`; riga `editor_start`/`editor_end`/`editor_duration` con tempi `m:ss` (helper privato `formatMs`); `IconButton` play/pausa (`control_play_description`/`control_pause_description`, disabilitato se `!previewAvailable`); in fondo `OutlinedButton` `editor_close` e `Button` `editor_save` (con `CircularProgressIndicator` piccolo e disabilitato quando `isSaving`). `BackHandler(onBack = onClose)`. Un `@Preview` con `EditorUiState` di esempio (picchi sintetici).

- [ ] **Step 3: Compila e lint**

Run: `$G :ui:assembleCpuDebug :ui:lintCpuDebug`
Expected: BUILD SUCCESSFUL; nessun nuovo problema di lint in `editor/` (in particolare nessun `HardcodedText`).

- [ ] **Step 4: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/editor/ ui/src/main/res/values/strings.xml
```

---

### Task 8: `ImportSongScreen`, `MainActivity` e verifica completa

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/PendingCaptureCards.kt` (`PendingCaptureCard`, `PreviewPlayerCard`, `ProcessingProgressCard`, dialogo di Scarta)
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/ImportSongScreen.kt`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/MainActivity.kt`
- Modify: `ui/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ImportViewModel` e tipi di stato (Task 6), `TrimEditorScreen` (Task 7).
- Produces: `ImportSongScreen(state: ImportUiState, playerState: PlayerState, onBack, onEdit, onDiscard, onProcess, onTogglePlayback, onSeek, snackbarHostState)` (resta l'unico chiamante `MainActivity`; i parametri nuovi hanno default per l'`@Preview`).

Stringhe nuove (testi esatti):

```xml
<string name="import_capture_edit">Modifica</string>
<string name="import_capture_discard">Scarta</string>
<string name="import_discard_title">Scartare la registrazione?</string>
<string name="import_discard_message">Il file registrato verrà eliminato definitivamente.</string>
<string name="import_discard_confirm">Scarta</string>
<string name="import_discard_cancel">Annulla</string>
<string name="import_capture_trimmed">Tagliata</string>
<string name="import_processing_decoding">Lettura del brano…</string>
<string name="import_processing_running">Rimozione voce… %1$d%%</string>
<string name="import_processing_saving">Salvataggio…</string>
<string name="import_event_edit_saved">Modifica salvata</string>
<string name="import_event_edit_failed">Impossibile salvare la modifica</string>
<string name="import_event_name_required">Inserisci un nome per il brano</string>
<string name="import_event_capture_unavailable">Registrazione non disponibile</string>
<string name="import_event_processing_done">Salvato in libreria</string>
<string name="import_event_decode_failed">Impossibile leggere la registrazione</string>
<string name="import_event_inference_failed">Errore durante la rimozione della voce</string>
<string name="import_event_save_failed">Impossibile salvare il brano</string>
<string name="import_event_preview_unavailable">Anteprima non disponibile</string>
```

- [ ] **Step 1: Implementa le card in `PendingCaptureCards.kt`** — stile delle card esistenti (`Surface`, `RoundedCornerShape(20.dp)`, `colorScheme.surface`). `PendingCaptureCard(name, isTrimmed, enabled, onEdit, onDiscard)`: nome, etichetta `import_capture_trimmed` se tagliata, `OutlinedButton` Scarta e `Button` Modifica; Scarta apre un `AlertDialog` (`import_discard_*`), conferma → `onDiscard`. `PreviewPlayerCard(playerState, onTogglePlayback, onSeek)`: play/pausa, `Slider` su `positionMs/durationMs` (seek su `onValueChangeFinished`), tempi `m:ss`. `ProcessingProgressCard(phase)`: `LinearProgressIndicator` indeterminato per `Decoding`/`Saving`, determinato `percent/100f` per `Running`, con la stringa di fase.

- [ ] **Step 2: Modifica `ImportSongScreen`** — `SnackbarHostState` passato dall'esterno. In `Empty` tutto come oggi. Altrimenti `PendingCaptureCard` al posto di `CaptureCard` (`enabled = state is CaptureReady`); il pulsante "Elabora" chiama `onProcess` (disabilitato in `Processing`); sotto, `PreviewPlayerCard` in `CaptureReady` o `ProcessingProgressCard` in `Processing`. Senza cattura "Elabora" mantiene `showPreviewMessage`.

- [ ] **Step 3: Modifica `MainActivity`** — `private val importViewModel: ImportViewModel by viewModels { ImportViewModel.Factory }`. In `onCreate`/`onNewIntent`, quando `consumeOpenImportScreenExtra()` è vero, oltre a `openImportScreenRequest = true` chiama `importViewModel.loadPendingCapture(openEditor = true)`. In `setContent`: `collectAsState` di `importState`, `editorState`, `playerState`; un solo `SnackbarHostState` e un `LaunchedEffect(Unit)` che raccoglie `events` e mostra la stringa corrispondente (mappa `ImportEvent` → `R.string.import_event_*`, `ProcessingFailed` secondo `error`). Dopo lo splash: se `editorState != null` → `TrimEditorScreen` collegata al ViewModel; altrimenti la logica attuale, con `ImportSongScreen` collegata al ViewModel.

- [ ] **Step 4: Build, lint e test di tutti i flavor**

Run: `$G :ui:assembleCpuDebug :ui:assembleWebgpuDebug :ui:assembleQnnDebug :ui:lintCpuDebug :ui:testCpuDebugUnitTest :engine:testCpuDebugUnitTest`
Expected: BUILD SUCCESSFUL, tutti i test PASS, nessun nuovo problema di lint nei file toccati.

- [ ] **Step 5: Verifica manuale sul telefono** (`$G :ui:installCpuDebug`)

Cattura di una canzone con qualche secondo di troppo → l'editor si apre da solo → sposta le maniglie (l'ascolto salta all'inizio / 3 s prima della fine al rilascio, si ferma alla fine) → rinomina → Salva → snackbar "Modifica salvata", card con nome ed etichetta "Tagliata" → ascolto nella card → Modifica (maniglie dove erano) → Chiudi → Elabora → progress bar con percentuale → "Salvato in libreria", schermata come all'inizio → `<nome>_strumentale.wav` in `Music/VocalRemover`; `adb shell run-as com.example.vocalremover.cpu ls cache` non mostra `system_audio_capture_*.wav` né `*_tagliato.wav`. Seconda cattura → Scarta → Annulla (resta) → Scarta → conferma (sparisce, file cancellati).

- [ ] **Step 6: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/ ui/src/main/res/values/strings.xml
```
