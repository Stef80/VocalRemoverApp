# Elenco delle canzoni elaborate (Libreria) — piano di implementazione

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una schermata Libreria, non collegata alla navigazione, che elenca i WAV elaborati presenti in `Music/VocalRemover`.

**Architecture:** In `:engine`, `LibraryRepository` interroga MediaStore e converte le righe con `LibraryCursorReader`, `RecordingFileNaming.titleFromLibraryName` e `LibraryDuration.resolve`. In `:ui`, `LibraryViewModel` espone `LibraryUiState`; `LibraryScreen` è senza stato e `LibraryRoute` la collega al ViewModel, rileggendo la lista a ogni `ON_RESUME`.

**Tech Stack:** Kotlin, MediaStore, Jetpack Compose Material3, lifecycle 2.11.0 (viewmodel-compose, runtime-compose), coroutines 1.8.1, JUnit 4, Robolectric 4.16, kotlinx-coroutines-test 1.8.1.

**Spec:** `docs/superpowers/specs/2026-10-18-library-list-design.md`

## Global Constraints

- Mai `git commit`: ogni passo "Stage" fa solo `git add`; il commit lo fa l'utente (CLAUDE.md).
- `:engine` non dipende mai da `:ui`.
- Nessun testo visibile scritto nel codice di `:ui`: tutto in `ui/src/main/res/values/strings.xml`.
- Ogni schermata di `:ui` mostra il logo `R.drawable.logo_corallo_blu` accanto al titolo.
- Commenti, documentazione e testi dell'interfaccia in italiano.
- Non modificare `MainActivity`, la navigazione, `PlayerScreen` né i pulsanti esistenti.
- Gradle: `G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.0*/*/gradle-9.8.0/bin/gradle | head -1)`; i comandi usano `$G`.
- Formato dei WAV scritti da noi: PCM 16 bit stereo 44,1 kHz, header 44 byte → 176 400 byte al secondo.
- Percorso MediaStore della libreria: `"Music/VocalRemover/"` (con la barra finale); `raw/` è escluso.

## Review Focus

1. Nomi di file insoliti (`.WAV` maiuscolo, `x_strumentale_strumentale.wav`, nome senza suffisso, nome che è solo `_strumentale.wav`): il titolo perde solo l'estensione e un solo suffisso finale, e non è mai vuoto. → test nel Task 1.
2. Righe MediaStore con `DURATION` o `SIZE` nulli (file appena pubblicato, non ancora analizzato): la riga compare comunque, con la durata ricavata dalla dimensione oppure 0. → test nel Task 2.
3. Due `refresh()` ravvicinati in cui il primo finisce dopo il secondo: lo stato finale è quello del secondo, e l'annullamento del primo non produce `Error`. → test nel Task 3.
4. Un errore di lettura dopo che la lista era già mostrata: lo stato diventa `Error`, non resta la lista vecchia. → test nel Task 3.
5. Titoli lunghissimi nella card: vanno su al massimo due righe con i puntini, senza spingere fuori l'icona. → si verifica nella `@Preview` del Task 4 (un brano dal titolo lungo).

---

### Task 1: Titolo e durata dei brani della libreria

**Files:**
- Modify: `engine/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt`
- Create: `engine/src/main/java/com/example/vocalremover/library/LibraryDuration.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/RecordingFileNamingTest.kt`
- Test: `engine/src/test/java/com/example/vocalremover/library/LibraryDurationTest.kt`

**Interfaces:**
- Produces: `RecordingFileNaming.titleFromLibraryName(displayName: String): String`; `object LibraryDuration { fun resolve(durationMs: Long?, sizeBytes: Long?): Long }`.

- [ ] **Step 1: Write the failing tests**

In `RecordingFileNamingTest`:

```kotlin
@Test
fun `titleFromLibraryName strips extension and suffix`() =
    assertEquals("Mr. Brightside", RecordingFileNaming.titleFromLibraryName("Mr. Brightside_strumentale.wav"))

@Test
fun `titleFromLibraryName ignores extension case`() =
    assertEquals("demo", RecordingFileNaming.titleFromLibraryName("demo.WAV"))

@Test
fun `titleFromLibraryName removes only one trailing suffix`() =
    assertEquals("x_strumentale", RecordingFileNaming.titleFromLibraryName("x_strumentale_strumentale.wav"))

@Test
fun `titleFromLibraryName keeps the suffix when nothing else is left`() =
    assertEquals("_strumentale", RecordingFileNaming.titleFromLibraryName("_strumentale.wav"))

@Test
fun `titleFromLibraryName leaves names without extension untouched`() =
    assertEquals("Canzone", RecordingFileNaming.titleFromLibraryName("Canzone"))
```

New `LibraryDurationTest` (package `com.example.vocalremover.library`):

```kotlin
@Test fun `uses the MediaStore duration when positive`() = assertEquals(225_000L, LibraryDuration.resolve(225_000L, 10L))
@Test fun `falls back to the size when duration is missing`() = assertEquals(2_000L, LibraryDuration.resolve(null, 44L + 352_800L))
@Test fun `falls back to the size when duration is zero`() = assertEquals(1_000L, LibraryDuration.resolve(0L, 44L + 176_400L))
@Test fun `unknown when size is header only`() = assertEquals(0L, LibraryDuration.resolve(null, 44L))
@Test fun `unknown when both are missing`() = assertEquals(0L, LibraryDuration.resolve(null, null))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `$G :engine:testCpuDebugUnitTest --tests "*RecordingFileNamingTest" --tests "*LibraryDurationTest"`
Expected: FAIL, compilazione: `titleFromLibraryName` e `LibraryDuration` non esistono.

- [ ] **Step 3: Implement**

`titleFromLibraryName`: `removeSuffix` di `.wav` senza distinzione di maiuscole (confronto con `endsWith(".wav", ignoreCase = true)`), poi `removeSuffix("_strumentale")`; se il risultato è vuoto restituisce il nome senza estensione. `LibraryDuration.resolve`: durata > 0 → quella; altrimenti size > 44 → `(size - 44) * 1000 / 176_400`; altrimenti 0. Le costanti 44 e 176 400 hanno un nome e un commento sul formato.

- [ ] **Step 4: Run tests to verify they pass**

Run: lo stesso comando dello Step 2.
Expected: PASS.

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt engine/src/main/java/com/example/vocalremover/library/LibraryDuration.kt engine/src/test/java/com/example/vocalremover/capture/RecordingFileNamingTest.kt engine/src/test/java/com/example/vocalremover/library/LibraryDurationTest.kt
```

### Task 2: Lettura della libreria da MediaStore

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/library/LibraryRepository.kt` (contiene `LibraryTrack`, `LibraryRepository`, `MediaStoreLibraryRepository`, `LibraryCursorReader`)
- Test: `engine/src/test/java/com/example/vocalremover/library/LibraryCursorReaderTest.kt`

**Interfaces:**
- Consumes: `titleFromLibraryName`, `LibraryDuration.resolve` (Task 1).
- Produces: `data class LibraryTrack(val uri: Uri, val title: String, val durationMs: Long)`; `interface LibraryRepository { fun load(): List<LibraryTrack>; companion object { fun create(context: Context): LibraryRepository } }`; `internal object LibraryCursorReader { fun read(cursor: Cursor, collection: Uri): List<LibraryTrack> }`.

- [ ] **Step 1: Write the failing test**

Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [35])`). Il cursore di prova è un `MatrixCursor` con colonne `_ID`, `DISPLAY_NAME`, `DURATION`, `SIZE` (nomi da `MediaStore.Audio.Media`); `collection = Uri.parse("content://media/external/audio/media")`.

```kotlin
@Test
fun `converts rows keeping cursor order`() {
    val cursor = cursorOf(
        arrayOf(7L, "B_strumentale.wav", 180_000L, 1L),
        arrayOf(3L, "A_strumentale.wav", 120_000L, 1L),
    )
    val tracks = LibraryCursorReader.read(cursor, collection)
    assertEquals(listOf("B", "A"), tracks.map { it.title })
    assertEquals(Uri.parse("content://media/external/audio/media/7"), tracks[0].uri)
    assertEquals(180_000L, tracks[0].durationMs)
}

@Test
fun `null duration falls back to size`() {
    val tracks = LibraryCursorReader.read(cursorOf(arrayOf(1L, "C.wav", null, 44L + 176_400L)), collection)
    assertEquals(1_000L, tracks.single().durationMs)
}

@Test
fun `null duration and size give unknown duration`() {
    val tracks = LibraryCursorReader.read(cursorOf(arrayOf(1L, "D.wav", null, null)), collection)
    assertEquals(0L, tracks.single().durationMs)
}

@Test
fun `empty cursor gives empty list`() =
    assertEquals(emptyList<LibraryTrack>(), LibraryCursorReader.read(cursorOf(), collection))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$G :engine:testCpuDebugUnitTest --tests "*LibraryCursorReaderTest"`
Expected: FAIL, compilazione: `LibraryCursorReader`/`LibraryTrack` non esistono.

- [ ] **Step 3: Implement**

`LibraryCursorReader.read`: indici con `getColumnIndexOrThrow`, valori nulli letti con `isNull`; non chiude il cursore (lo chiude chi lo apre). `MediaStoreLibraryRepository.load()`: sotto `Build.VERSION_CODES.Q` restituisce `emptyList()`; altrimenti `contentResolver.query(EXTERNAL_CONTENT_URI, projection, "${RELATIVE_PATH} = ? AND ${IS_PENDING} = 0 AND ${DISPLAY_NAME} LIKE ?", arrayOf("Music/VocalRemover/", "%.wav"), "${DATE_ADDED} DESC")?.use { read(it, EXTERNAL_CONTENT_URI) } ?: emptyList()`. Le eccezioni del resolver si propagano. `LibraryRepository.create` restituisce `MediaStoreLibraryRepository(context.applicationContext)`.

- [ ] **Step 4: Run test to verify it passes**

Run: lo stesso comando dello Step 2.
Expected: PASS (4 test).

- [ ] **Step 5: Stage**

```bash
git add engine/src/main/java/com/example/vocalremover/library/LibraryRepository.kt engine/src/test/java/com/example/vocalremover/library/LibraryCursorReaderTest.kt
```

### Task 3: `LibraryViewModel`

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/library/LibraryUiState.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/library/LibraryViewModel.kt`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/library/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: `LibraryRepository`, `LibraryTrack` (Task 2).
- Produces: `sealed interface LibraryUiState { data object Loading; data object Empty; data class Tracks(val tracks: List<LibraryTrack>); data object Error }`; `class LibraryViewModel(repository: LibraryRepository, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel { val state: StateFlow<LibraryUiState>; fun refresh(); companion object { val Factory: ViewModelProvider.Factory } }`.

- [ ] **Step 1: Write the failing tests**

Robolectric (gli Uri dei brani di prova) + `StandardTestDispatcher` impostato come Main e passato come `ioDispatcher`. Repository finto: `FakeLibraryRepository` con una coda di risposte (`List<LibraryTrack>` oppure eccezione) e, per il test della corsa, un `CountDownLatch` per bloccare la prima `load()`.

```kotlin
@Test fun `initial state is Loading and nothing is read`()         // state == Loading, fake.calls == 0
@Test fun `refresh with tracks gives Tracks`()                     // [a, b] → Tracks([a, b])
@Test fun `refresh with empty list gives Empty`()
@Test fun `refresh failure gives Error`()                           // load() lancia IOException
@Test fun `later refresh keeps current state while loading`()       // dopo Tracks([a]), durante il secondo load lo stato è ancora Tracks([a]), mai Loading
@Test fun `failure after tracks gives Error`()                      // Tracks([a]) poi eccezione → Error
@Test fun `latest refresh wins`()                                   // primo load bloccato restituirebbe [a]; secondo restituisce [b]; sblocco del primo → stato finale Tracks([b]), nessun Error registrato nella sequenza degli stati
```

Per `later refresh keeps current state while loading` e `latest refresh wins` si raccolgono gli stati in una lista (`backgroundScope.launch { vm.state.toList(states) }`) e si verifica che `Loading`/`Error` non compaiano dopo il primo `Tracks`. Per `latest refresh wins` il primo `load()` blocca su un dispatcher reale separato (`Dispatchers.IO` passato solo in quel test) e si aspetta lo stato finale con `withTimeout`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `$G :ui:testCpuDebugUnitTest --tests "*LibraryViewModelTest"`
Expected: FAIL, compilazione: `LibraryViewModel` non esiste.

- [ ] **Step 3: Implement**

`refresh()`: `job?.cancel(); job = viewModelScope.launch { val result = runCatching { withContext(ioDispatcher) { repository.load() } } ... }`. Una `CancellationException` va rilanciata, non mappata in `Error` (`result.exceptionOrNull() is CancellationException → throw`). Successo: vuota → `Empty`, altrimenti `Tracks`. Errore: `Log.w` con l'eccezione, poi `Error`. Lo stato non torna mai a `Loading` dopo il primo risultato. `Factory` con `viewModelFactory { initializer { LibraryViewModel(LibraryRepository.create(this[APPLICATION_KEY]!!)) } }`, come `ImportViewModel.Factory`.

- [ ] **Step 4: Run tests to verify they pass**

Run: lo stesso comando dello Step 2.
Expected: PASS (7 test).

- [ ] **Step 5: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/library/ ui/src/test/java/com/stef/vocalremover/ui/library/
```

### Task 4: `PlayableTracks` con dati veri, `LibraryScreen` e `LibraryRoute`

**Files:**
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/PlayableTracks.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/library/LibraryScreen.kt` (contiene `LibraryScreen`, `LibraryRoute`, le `@Preview`)
- Modify: `ui/src/main/res/values/strings.xml`
- Modify: `gradle/libs.versions.toml`, `ui/build.gradle.kts`

**Interfaces:**
- Consumes: `LibraryTrack` (Task 2), `LibraryUiState`, `LibraryViewModel` (Task 3), `formatMinutesSeconds` (`TimeFormat.kt`).
- Produces: `fun PlayableTracks(tracks: List<LibraryTrack>, onTrackClick: (LibraryTrack) -> Unit, modifier: Modifier = Modifier)`; `fun LibraryScreen(state: LibraryUiState, onTrackClick: (LibraryTrack) -> Unit)`; `fun LibraryRoute(onTrackClick: (LibraryTrack) -> Unit, viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory))`.

Questo task è solo interfaccia: nessun test JVM (Compose UI test non è configurato nel progetto). Lo verificano compilazione, lint e le `@Preview`.

- [ ] **Step 1: Dipendenza**

Nel catalogo: `androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleVersion" }`; in `ui/build.gradle.kts`: `implementation(libs.androidx.lifecycle.runtime.compose)`.

- [ ] **Step 2: Testi**

In `strings.xml`:

```xml
<string name="library_title">Libreria</string>
<string name="library_empty">Nessuna canzone elaborata. Importane una per iniziare.</string>
<string name="library_error">Impossibile leggere la libreria.</string>
<string name="library_duration_unknown">--:--</string>
<string name="library_loading_description">Caricamento della libreria</string>
```

- [ ] **Step 3: `PlayableTracks`**

Riceve `List<LibraryTrack>` e `onTrackClick`; `items(tracks, key = { it.uri.toString() })`; `Card(onClick = { onTrackClick(track) })`. Titolo `maxLines = 2`, `overflow = TextOverflow.Ellipsis`. Durata: `formatMinutesSeconds(durationMs)` se > 0, altrimenti `library_duration_unknown`. Il "▶" diventa `Icon(painterResource(R.drawable.ic_play), contentDescription = null)` nel cerchio esistente. Si elimina `PlayableTrack`; l'anteprima usa `LibraryTrack` con Uri `content://media/external/audio/media/<n>`, incluso un titolo molto lungo (Review Focus 5).

- [ ] **Step 4: `LibraryScreen` e `LibraryRoute`**

`LibraryScreen`: `Scaffold(modifier = Modifier.safeDrawingPadding())`; in alto riga logo (56 dp) + `library_title` come in `PlayerScreen`; sotto, in base allo stato: `CircularProgressIndicator` centrato con `library_loading_description` in `semantics`, testo `library_empty` o `library_error` centrato, oppure `PlayableTracks(modifier = Modifier.weight(1f))`. `LibraryRoute`: `val state by viewModel.state.collectAsState()`; `LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose { } }`; `LibraryScreen(state, onTrackClick)`. KDoc di `LibraryRoute`: punto d'aggancio per il footer, non ancora usato. `@Preview` per `Tracks` ed `Empty` con `VocalRemoverTheme`.

- [ ] **Step 5: Verifica completa**

Run: `$G :engine:testCpuDebugUnitTest :ui:testCpuDebugUnitTest :ui:assembleCpuDebug :ui:assembleWebgpuDebug :ui:assembleQnnDebug :ui:lintCpuDebug`
Expected: BUILD SUCCESSFUL; 0 test falliti; nessun problema di lint nei file toccati (`HardcodedText` in particolare).

- [ ] **Step 6: Stage**

```bash
git add gradle/libs.versions.toml ui/build.gradle.kts ui/src/main/res/values/strings.xml ui/src/main/java/com/stef/vocalremover/ui/PlayableTracks.kt ui/src/main/java/com/stef/vocalremover/ui/library/LibraryScreen.kt
```
