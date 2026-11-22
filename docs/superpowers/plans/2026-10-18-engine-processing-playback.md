# Decodifica, elaborazione e riproduzione separate in `:engine` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dare a `:engine` tre componenti con stato `StateFlow` per la futura UI Compose: `AudioDecoder` (file → `StereoPcm`), `SongProcessor` (file → strumentale salvato in `Music/VocalRemover`) e `LibraryPlayer` (ExoPlayer sui WAV salvati).

**Architecture:** La decodifica esce da `AudioPlayer.decodeAudio` e si divide in due funzioni pure (deinterleave, ricampionamento) più il ciclo `MediaCodec`. `SongProcessor` orchestra decodifica → `VocalRemover.removeVocals` → `RecordingSaver` tramite tre `fun interface` sostituibili nei test. `LibraryPlayer` avvolge ExoPlayer, solo main thread. Il vecchio `AudioPlayer` resta per la `MainActivity` legacy ma usa `AudioDecoder`.

**Tech Stack:** Kotlin, coroutines 1.8.1 (`StateFlow`), Media3 ExoPlayer 1.4.1, MediaCodec/MediaExtractorCompat, JUnit 4, Robolectric + kotlinx-coroutines-test (solo test), AndroidJUnit4 (androidTest di `:ui`).

**Spec:** `docs/superpowers/specs/2026-10-18-engine-processing-playback-design.md`

## Global Constraints

- **Mai `git commit`** (CLAUDE.md): ogni task finisce con `git add` dei file toccati; il commit lo fa l'utente.
- `:engine` non dipende da `:ui`. Le API pubbliche nuove non espongono tipi Media3 (`:ui` non ha Media3 nel compile classpath).
- Commenti e KDoc in italiano; nessun testo per l'utente nell'engine (errori come enum).
- minSdk 26: nessuna API > 26 senza controllo di versione. Media3 resta 1.4.1, coroutines 1.8.1.
- Gradle: usare la 9.8.0 in cache, non il `gradle` di sistema:
  `G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.0*/*/gradle-9.8.0/bin/gradle | head -1)`; i comandi sotto scrivono `$G`.
- Pipeline audio invariata: 44 100 Hz, stereo float `StereoPcm(left, right)`; mono copiato su L e R; oltre 2 canali si tengono i primi due.
- Package nuovi: `com.example.vocalremover.decode`, `.processing`, `.playback`.

## Review Focus

1. File senza traccia audio o corrotto (es. byte casuali con estensione `.mp3`) → `DecodeException`, mai `null` né crash. Test: Task 2, `garbage file throws DecodeException`.
2. WAV valido ma con 0 frame → `DecodeException` (evita l'indice negativo in `MdxChunker.reflectIndex`). Test: Task 2, `empty wav throws DecodeException`.
3. Cancellazione mentre si sta già salvando → il file è scritto, quindi lo stato deve finire `Done(uri)` e non `Idle` (altrimenti resta un WAV "orfano" che la UI non conosce). Test: Task 3, `cancellation during saving still reports Done`.
4. `process()` di nuovo dopo `Failed`/`Done` → riparte da `Decoding`. Test: Task 3, `process can run again after a failure`.
5. `play(altroUri)` mentre un brano suona → cambia brano, `currentUri` aggiornato, posizione da 0; `seekTo` prima che la durata sia nota non deve finire a 0. Test: Task 4, `switching uri restarts from zero` e `seek before duration is known is not clamped to zero`.

---

### Task 1: Funzioni pure di conversione PCM

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/decode/PcmDeinterleaver.kt`
- Create: `engine/src/main/java/com/example/vocalremover/decode/LinearResampler.kt`
- Test: `engine/src/test/java/com/example/vocalremover/decode/PcmDeinterleaverTest.kt`
- Test: `engine/src/test/java/com/example/vocalremover/decode/LinearResamplerTest.kt`

**Interfaces:**
- Produces:
  - `internal object PcmDeinterleaver { fun deinterleave(buffer: ByteBuffer, isFloat: Boolean, channels: Int, left: FloatArray, right: FloatArray, offset: Int): Int }` — legge da `buffer.position()` a `buffer.limit()` (little endian, lo imposta lui), scrive in `left/right` da `offset`, restituisce i frame scritti (`remaining / (bytesPerSample * channels)`). Il chiamante garantisce la capienza. 16 bit normalizzati con `1/32768f`.
  - `internal object LinearResampler { fun resample(pcm: StereoPcm, fromRate: Int, toRate: Int): StereoPcm }` — stessa istanza se `fromRate == toRate`; lunghezza uscita `(size * toRate / fromRate)`; input vuoto → output vuoto.

- [ ] **Step 1: Scrivere i test che falliscono**

`PcmDeinterleaverTest` (costruire i `ByteBuffer` little endian nel test):
```kotlin
@Test fun `16 bit stereo is split and normalised`()      // shorts [16384, -16384, 8192, 0] → L=[0.5,0.25], R=[-0.5,0.0], ritorna 2
@Test fun `16 bit mono is copied to both channels`()    // shorts [8192, -8192] → L=R=[0.25,-0.25], ritorna 2
@Test fun `float stereo is split as is`()               // floats [0.1,0.2,0.3,0.4] → L=[0.1,0.3], R=[0.2,0.4]
@Test fun `six channels keep only the first two`()      // un frame float [0.1..0.6] → L=[0.1], R=[0.2], ritorna 1
@Test fun `writes from the given offset`()              // offset=3 su array di 5: indici 0..2 restano 0f
```
Tolleranza `1e-6f`.

`LinearResamplerTest`:
```kotlin
@Test fun `same rate returns the same instance`()       // assertSame(pcm, resample(pcm, 44100, 44100))
@Test fun `doubling the rate doubles the length`()      // 1000 frame 22050→44100 → size 2000
@Test fun `empty input gives empty output`()            // size 0 → size 0, nessuna eccezione
@Test fun `1 kHz sine keeps frequency and amplitude`()
// sorgente: 0.5*sin(2π·1000·i/22050), 2205 frame, uguale su L e R
// uscita i in 10 until size-10: |out[i] - 0.5*sin(2π·1000·i/44100)| < 0.01 su entrambi i canali
```

- [ ] **Step 2: Verificare che falliscano**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.decode.*"`
Expected: FAIL, compilazione: `Unresolved reference: PcmDeinterleaver` / `LinearResampler`.

- [ ] **Step 3: Implementare i due oggetti**

La logica è quella già presente in `AudioPlayer.decodeAudio` (rami `isPcmFloat`/16 bit per 1, 2, N canali, e il ciclo di ricampionamento con `lo`/`hi`/`frac`). Unica differenza: gli `object` devono restare thread-safe, quindi niente array temporanei condivisi (`tmpF`/`tmpS`); `deinterleave` usa `asFloatBuffer()`/`asShortBuffer()` e legge per indice assoluto.

- [ ] **Step 4: Verificare che passino**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.decode.*"`
Expected: PASS (9 test).

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/decode engine/src/test/java/com/example/vocalremover/decode
```

---

### Task 2: `AudioDecoder` e migrazione del vecchio `AudioPlayer`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/decode/AudioDecoder.kt`
- Modify: `engine/src/main/java/com/example/vocalremover/AudioPlayer.kt` (eliminare `decodeAudio` e gli import rimasti inutili; `loadAndProcess`, `loadSavedRecording`)
- Create: `ui/src/androidTest/java/com/example/vocalremover/testaudio/TestAudioFiles.kt`
- Test: `ui/src/androidTest/java/com/example/vocalremover/decode/AudioDecoderInstrumentedTest.kt`

**Interfaces:**
- Consumes: `PcmDeinterleaver.deinterleave`, `LinearResampler.resample` (Task 1).
- Produces:
  - `class AudioDecoder(context: Context) { fun decode(uri: Uri): StereoPcm }` — bloccante, sempre 44 100 Hz.
  - `class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)`
  - (test) `object TestAudioFiles { fun wav(dir: File, name: String, seconds: Double, sampleRate: Int = 44100, channels: Int = 2): File; fun emptyWav(dir: File, name: String): File; fun aac(dir: File, name: String, seconds: Double, sampleRate: Int, channels: Int): File; fun garbage(dir: File, name: String): File }` — `wav` scrive un seno a 440 Hz ampiezza 0.5 in PCM 16 bit con header di 44 byte; `aac` codifica con `MediaCodec` encoder `audio/mp4a-latm` e `MediaMuxer` (MPEG_4); `garbage` scrive 4096 byte casuali con estensione `.mp3`.

- [ ] **Step 1: Scrivere il test strumentato che fallisce**

`AudioDecoderInstrumentedTest` (`@RunWith(AndroidJUnit4::class)`, file in `context.cacheDir`, `Uri.fromFile`):
```kotlin
@Test fun `stereo wav at 44100 keeps length and channels`()  // 2.0 s → size 88200 ±1, L != R non richiesto, picco ≈ 0.5 (±0.02)
@Test fun `mono wav is duplicated on both channels`()        // 1.0 s mono → left contentEquals right
@Test fun `22050 Hz wav is resampled to 44100`()             // 1.0 s a 22050 → size 44100 ±2
@Test fun `aac mono 22050 decodes to 44100 stereo`()         // 2.0 s → size entro ±5% di 88200 (fix 1: formato d'uscita)
@Test fun `empty wav throws DecodeException`()               // assertThrows(DecodeException::class.java)
@Test fun `garbage file throws DecodeException`()
@Test fun `missing file throws DecodeException`()            // Uri.fromFile(File(cacheDir, "nope.wav"))
```

- [ ] **Step 2: Verificare che fallisca**

Run: `$G :ui:compileCpuDebugAndroidTestKotlin`
Expected: FAIL, `Unresolved reference: AudioDecoder`.

- [ ] **Step 3: Implementare `AudioDecoder.decode`**

Spostare il ciclo `MediaExtractorCompat` + `MediaCodec` da `AudioPlayer.decodeAudio` **con i fix già presenti** (rilettura `KEY_SAMPLE_RATE`/`KEY_CHANNEL_COUNT` su `INFO_OUTPUT_FORMAT_CHANGED`, `eosIn`, rilascio del codec nel `finally`), usando le funzioni del Task 1. In più:
- encoding PCM: `if (outFormat.containsKey(KEY_PCM_ENCODING)) outFormat.getInteger(KEY_PCM_ENCODING) else ENCODING_PCM_16BIT` (la variante con default è API 29);
- `@OptIn(UnstableApi::class)` sulla classe;
- `DecodeException` per: nessuna traccia audio, `setDataSource` fallito, eccezione del codec (come `cause`), 0 frame decodificati. Le altre eccezioni si avvolgono in `DecodeException`; `CancellationException` non c'è (funzione non sospesa).
- Log con tag `"AudioDecoder"` equivalenti a quelli di oggi.

- [ ] **Step 4: Migrare `AudioPlayer`**

Campo `private val decoder = AudioDecoder(context)`. In `loadAndProcess` e `loadSavedRecording` sostituire `decodeAudio(uri)` con una funzione privata `decodeOrNull(uri): StereoPcm?` che cattura `DecodeException`, la logga (`Log.e`) e restituisce `null`: i messaggi e i flussi verso `MainActivity` restano identici.

- [ ] **Step 5: Verificare**

Run: `$G :engine:testCpuDebugUnitTest :ui:assembleCpuDebug :ui:compileCpuDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

Con telefono collegato: `$G :ui:connectedCpuDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.vocalremover.decode.AudioDecoderInstrumentedTest`
Expected: 7 test PASS. Senza telefono: segnalarlo all'utente, non dichiararlo verificato.

Run: `$G :engine:lintCpuDebug` e controllare nel report (`engine/build/intermediates/lint_intermediate_text_report/cpuDebug/lintReportCpuDebug/lint-results-cpuDebug.txt`) che non ci siano errori in `decode/` né in `AudioPlayer.kt` (gli errori in `MainActivity`, `CaptureSessionController` e nel manifest sono preesistenti).

- [ ] **Step 6: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/decode engine/src/main/java/com/example/vocalremover/AudioPlayer.kt ui/src/androidTest/java/com/example/vocalremover/testaudio ui/src/androidTest/java/com/example/vocalremover/decode
```

---

### Task 3: `SongProcessor` e `ProcessingState`

**Files:**
- Modify: `engine/build.gradle.kts` (dipendenze di test)
- Create: `engine/src/main/java/com/example/vocalremover/processing/ProcessingState.kt` (`ProcessingState`, `ProcessingError`, `ProcessingException`)
- Create: `engine/src/main/java/com/example/vocalremover/processing/SongProcessor.kt` (classe + `PcmDecoder`, `InstrumentalExtractor`, `InstrumentalSaver`)
- Test: `engine/src/test/java/com/example/vocalremover/processing/SongProcessorTest.kt`

**Interfaces:**
- Consumes: `AudioDecoder.decode`, `DecodeException` (Task 2); `VocalRemover.removeVocals(signal, onProgress)`; `RecordingSaver.saveProcessedStereoAsWav(context, pcm, displayName): Uri`.
- Produces (firme esatte della spec, sezione "Componenti" e "Stati"), con in più due parametri per i test:
  ```kotlin
  class SongProcessor(
      private val decoder: PcmDecoder,
      private val extractor: InstrumentalExtractor,
      private val saver: InstrumentalSaver,
      private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
      private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
  )
  ```
  `val state: StateFlow<ProcessingState>`, `suspend fun process(source: Uri, displayName: String): Uri`, `companion fun create(context: Context, remover: VocalRemover): SongProcessor`.

- [ ] **Step 1: Aggiungere le dipendenze di test**

In `engine/build.gradle.kts`, dopo `testImplementation("junit:junit:4.13.2")`:
```kotlin
testImplementation("org.robolectric:robolectric:4.16")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```
(se esiste una 4.x più recente compatibile, usarla e dirlo nel report).

- [ ] **Step 2: Scrivere i test che falliscono**

`@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])`. Fake in-test: decoder che restituisce un `StereoPcm` di 10 frame; extractor che chiama `onProgress(50)` e restituisce l'input; saver che restituisce `Uri.parse("content://media/external/audio/media/1")`. Dispatcher: `UnconfinedTestDispatcher(testScheduler)` per entrambi. Stati raccolti in lista con `backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { processor.state.toList(states) }`.
```kotlin
@Test fun `successful run goes through every state in order`()
// states == [Idle, Decoding, Processing(0), Processing(50), Saving, Done(uri)]; process() restituisce uri
@Test fun `decode failure reports DECODE_FAILED`()        // decoder lancia DecodeException → ProcessingException.error == DECODE_FAILED, state Failed(DECODE_FAILED)
@Test fun `inference failure reports INFERENCE_FAILED`()  // extractor lancia RuntimeException
@Test fun `save failure reports SAVE_FAILED`()            // saver lancia IOException
@Test fun `cancellation during extraction returns to Idle`()   // extractor: awaitCancellation(); job.cancel() → state Idle, saver mai chiamato
@Test fun `cancellation during saving still reports Done`()    // saver chiama job.cancel() prima di restituire → state Done(uri)
@Test fun `second concurrent call is rejected`()          // prima chiamata sospesa su CompletableDeferred → seconda lancia IllegalStateException, state resta Processing(0)
@Test fun `process can run again after a failure`()       // prima chiamata fallisce, seconda (decoder ok) → Done
```

- [ ] **Step 3: Verificare che falliscano**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.processing.*"`
Expected: FAIL, `Unresolved reference: SongProcessor`.

- [ ] **Step 4: Implementare**

Regole che i test non bastano a fissare:
- occupato/libero con `AtomicBoolean` (`compareAndSet(false, true)` all'ingresso, reset nel `finally`); il rifiuto avviene **prima** di toccare lo stato;
- `Processing(0)` emesso all'inizio dell'estrazione, poi ogni `onProgress` → `Processing(p)`;
- decodifica e salvataggio su `ioDispatcher`, estrazione su `computeDispatcher`;
- il salvataggio gira in `withContext(NonCancellable + ioDispatcher)` e dopo si imposta `Done` senza ulteriori punti di sospensione (Review Focus 3);
- `CancellationException` → stato `Idle` e rilancio; ogni altra eccezione → `Failed(errore della fase)` + `throw ProcessingException(errore, causa)`, con `Log.e` del dettaglio (tag `"SongProcessor"`);
- il riferimento al mix decodificato si azzera appena l'estrazione restituisce (variabile locale `var mix: StereoPcm?` messa a `null`);
- `create()`: `PcmDecoder(AudioDecoder(context)::decode)`, `InstrumentalExtractor(remover::removeVocals)`, `InstrumentalSaver { pcm, name -> RecordingSaver.saveProcessedStereoAsWav(appContext, pcm, name) }` con `appContext = context.applicationContext`.

- [ ] **Step 5: Verificare che passino**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.processing.*"`
Expected: PASS (8 test). Se Robolectric rifiuta l'SDK, abbassare `@Config(sdk = [...])` al massimo supportato e annotarlo.

- [ ] **Step 6: Stage**

```bash
git add engine/build.gradle.kts engine/src/main/java/com/example/vocalremover/processing engine/src/test/java/com/example/vocalremover/processing
```

---

### Task 4: `LibraryPlayer` e `PlayerState`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/playback/PlayerState.kt` (`PlayerState`, `PlayerError`, `internal fun playerErrorFor(errorCode: Int): PlayerError`)
- Create: `engine/src/main/java/com/example/vocalremover/playback/LibraryPlayer.kt`
- Test: `engine/src/test/java/com/example/vocalremover/playback/PlayerErrorMappingTest.kt`
- Test: `ui/src/androidTest/java/com/example/vocalremover/playback/LibraryPlayerInstrumentedTest.kt`

**Interfaces:**
- Consumes: `TestAudioFiles.wav` (Task 2, solo test).
- Produces: `class LibraryPlayer(context: Context)` con `val state: StateFlow<PlayerState>`, `fun play(uri: Uri)`, `fun pause()`, `fun seekTo(positionMs: Long)`, `fun release()` — tipi `PlayerState`/`PlayerError` come nella spec.

- [ ] **Step 1: Scrivere i test che falliscono**

`PlayerErrorMappingTest` (JVM puro, costanti di `androidx.media3.common.PlaybackException`):
```kotlin
@Test fun `file not found maps to SOURCE_UNAVAILABLE`()   // ERROR_CODE_IO_FILE_NOT_FOUND
@Test fun `no permission maps to SOURCE_UNAVAILABLE`()    // ERROR_CODE_IO_NO_PERMISSION
@Test fun `other codes map to PLAYBACK_FAILED`()          // ERROR_CODE_DECODING_FAILED, ERROR_CODE_UNSPECIFIED
```

`LibraryPlayerInstrumentedTest` (`@RunWith(AndroidJUnit4::class)`): player creato e comandato con `instrumentation.runOnMainSync { … }`; attese sul thread del test con `runBlocking { withTimeout(5_000) { player.state.first { … } } }`; WAV da 3 s in `cacheDir`; `release()` in `@After` sul main.
```kotlin
@Test fun `plays a saved wav and advances position`()     // isPlaying true; durationMs in 2900..3100; poi positionMs > 300
@Test fun `pause stops position`()                        // dopo pause: isPlaying false, positionMs stabile per 500 ms (±50)
@Test fun `seekTo moves position`()                       // seekTo(2000) → positionMs >= 1900
@Test fun `end of track leaves position at duration and play restarts from zero`()
// seekTo(2800) e attesa: isPlaying false && positionMs == durationMs; poi play(stesso uri) → positionMs < 500 && isPlaying
@Test fun `switching uri restarts from zero`()            // secondo WAV mentre suona il primo → currentUri == secondo, positionMs < 500
@Test fun `seek before duration is known is not clamped to zero`()  // play + seekTo(1500) subito nello stesso runOnMainSync → poi positionMs >= 1400
@Test fun `missing file reports SOURCE_UNAVAILABLE`()     // Uri.fromFile(File(cacheDir, "nope.wav")) → error == SOURCE_UNAVAILABLE; poi play(wav valido) → error == null
@Test fun `calls off the main thread are rejected`()      // play() dal thread del test → IllegalStateException
@Test fun `calls after release are rejected`()            // release() poi play() sul main → IllegalStateException
```

- [ ] **Step 2: Verificare che falliscano**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.playback.*" :ui:compileCpuDebugAndroidTestKotlin`
Expected: FAIL, `Unresolved reference: playerErrorFor` / `LibraryPlayer`.

- [ ] **Step 3: Implementare**

Regole che i test non bastano a fissare:
- ExoPlayer: `ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ true).setHandleAudioBecomingNoisy(true).build()`;
- controllo thread in costruttore e in ogni metodo pubblico: `check(Looper.myLooper() == Looper.getMainLooper())`; dopo `release()`: `check(!released)`;
- `play(uri)`: uri diverso → `setMediaItem(MediaItem.fromUri(uri))`, `prepare()`, `play()`, stato con `currentUri = uri`, `positionMs = 0`, `error = null`; stesso uri in `STATE_ENDED` → `seekTo(0)` poi `play()`; stesso uri in pausa → `play()`;
- `seekTo`: `coerceAtLeast(0)`, e `coerceAtMost(durationMs)` solo se la durata è nota (`player.duration != C.TIME_UNSET`) — Review Focus 5;
- `Player.Listener`: `onIsPlayingChanged`, `onPlaybackStateChanged` (`STATE_READY` → `durationMs`; `STATE_ENDED` → `isPlaying = false`, `positionMs = durationMs`), `onPlayerError` → `error = playerErrorFor(e.errorCode)`;
- polling posizione: `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, un job che finché `isPlaying` aggiorna `positionMs` ogni 200 ms; posizione aggiornata subito anche dopo `pause` e `seekTo`; `release()` cancella lo scope, rilascia ExoPlayer, lascia lo stato all'ultimo valore.

- [ ] **Step 4: Verificare**

Run: `$G :engine:testCpuDebugUnitTest --tests "com.example.vocalremover.playback.*"`
Expected: PASS (3 test).

Con telefono: `$G :ui:connectedCpuDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.vocalremover.playback.LibraryPlayerInstrumentedTest`
Expected: 9 test PASS. Senza telefono: dirlo, non dichiararlo verificato.

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/playback engine/src/test/java/com/example/vocalremover/playback ui/src/androidTest/java/com/example/vocalremover/playback
```

---

### Task 5: Verifica finale

- [ ] **Step 1: Build e test di tutti i flavor**

Run: `$G :engine:testCpuDebugUnitTest :engine:testWebgpuDebugUnitTest :engine:testQnnDebugUnitTest :ui:assembleCpuDebug :ui:assembleWebgpuDebug :ui:assembleQnnDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Lint**

Run: `$G :engine:lintCpuDebug` (fallisce per errori preesistenti). Nel report: nessun errore in `decode/`, `processing/`, `playback/`, `AudioPlayer.kt`.

- [ ] **Step 3: Legacy invariata (telefono)**

Installare `:ui:installCpuDebug`, aprire la schermata legacy da `PlayerScreen` ("apri schermata legacy"), importare un brano: elaborazione, salvataggio in `Music/VocalRemover` e riproduzione funzionano come prima. Se non c'è un telefono, elencarlo tra le verifiche non fatte.

- [ ] **Step 4: Riepilogo per l'utente**

File creati/modificati, test eseguiti con esito, verifiche sul telefono fatte o mancanti, cosa è in stage. Nessun commit.
