# Footer di navigazione — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un footer `NavigationBar` con Libreria / Cattura / Player che sostituisce i flag di navigazione di `MainActivity`, con la Libreria come home.

**Architecture:** Un `enum AppTab` salvato con `rememberSaveable` in `MainActivity`, più regole pure (`AppNavigation.resolve`/`back`) testate in JVM. Ogni schermata riceve uno slot `bottomBar` per il proprio `Scaffold`; frecce indietro e pulsanti di navigazione incrociata spariscono. Nessuna nuova dipendenza.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (BOM 2026.09.00), JUnit4 + Robolectric (`ui/src/test`).

**Spec:** `docs/superpowers/specs/2026-10-25-bottom-navigation-design.md`

## Global Constraints

- **Mai `git commit`**: ogni task termina con `git add` dei file toccati; il commit lo fa l'utente.
- Nessun testo visibile hard-coded in `:ui`: etichette in `ui/src/main/res/values/strings.xml`, lette con `stringResource`.
- Ogni schermata `:ui` mantiene il logo `R.drawable.logo_corallo_blu` accanto al titolo.
- Commenti e copy in italiano.
- Nessuna nuova dipendenza (niente Navigation Compose, niente material-icons).
- `:engine` non si tocca (la `MainActivity` legacy resta, solo non più raggiungibile).
- Gradle: usare la 9.8.0 del wrapper (`~/.gradle/wrapper/dists/`), il `gradle` di sistema può essere troppo vecchio.

## Review Focus

1. **Process death / ripristino sulla scheda Player senza brano** → deve comparire la Libreria, non uno schermo vuoto né un crash (`resolve` coperto in Task 1; cablaggio verificato in Task 3).
2. **Fine registrazione dal bubble mentre si è sul Player in riproduzione** → il brano va in pausa, si apre Cattura con l'editor sopra, senza footer (verifica manuale Task 3).
3. **Doppio inset di sistema** sotto il footer (barra di navigazione gestuale) → nessuna fascia vuota doppia; `windowInsets = WindowInsets(0)` (Task 1, verifica visiva Task 3).
4. **Indietro con l'editor aperto** → resta il comportamento attuale dell'editor; il `BackHandler` di `MainActivity` è disabilitato finché l'editor è aperto (Task 3).
5. **Ritocco della scheda già selezionata** → nessuna pausa/ricarica (`selectTab` con `new == current` è no-op; Task 3).

---

### Task 1: `AppTab`, regole di navigazione e `AppBottomBar`

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/AppNavigation.kt`
- Create: `ui/src/main/res/drawable/ic_tab_library.xml`, `ic_tab_capture.xml`, `ic_tab_player.xml`
- Modify: `ui/src/main/res/values/strings.xml`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/AppNavigationTest.kt`

**Interfaces:**
- Produces:
  - `enum class AppTab { Library, Capture, Player }`
  - `object AppNavigation { fun resolve(selected: AppTab, hasTrack: Boolean): AppTab; fun back(current: AppTab): AppTab? }`
  - `@Composable fun AppBottomBar(selected: AppTab, playerEnabled: Boolean, onSelect: (AppTab) -> Unit)`

- [ ] **Step 1: Scrivere il test che fallisce** (`AppNavigationTest`, JUnit4 semplice, niente Robolectric)

```kotlin
@Test fun `player senza brano ricade sulla libreria`() =
    assertEquals(AppTab.Library, AppNavigation.resolve(AppTab.Player, hasTrack = false))
@Test fun `player con brano resta player`() =
    assertEquals(AppTab.Player, AppNavigation.resolve(AppTab.Player, hasTrack = true))
@Test fun `cattura e libreria non dipendono dal brano`() {
    assertEquals(AppTab.Capture, AppNavigation.resolve(AppTab.Capture, hasTrack = false))
    assertEquals(AppTab.Library, AppNavigation.resolve(AppTab.Library, hasTrack = true))
}
@Test fun `indietro da cattura e player torna alla libreria`() {
    assertEquals(AppTab.Library, AppNavigation.back(AppTab.Capture))
    assertEquals(AppTab.Library, AppNavigation.back(AppTab.Player))
}
@Test fun `indietro dalla libreria lascia uscire l'app`() = assertNull(AppNavigation.back(AppTab.Library))
```

- [ ] **Step 2: Verificare che fallisca**

Run: `gradle :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.AppNavigationTest"`
Expected: FAIL di compilazione (`AppTab` / `AppNavigation` non definiti).

- [ ] **Step 3: Implementare `AppTab` e `AppNavigation` in `AppNavigation.kt`**

- [ ] **Step 4: Aggiungere stringhe e icone**
  - `strings.xml`: `tab_library` = "Libreria", `tab_capture` = "Cattura", `tab_player` = "Player".
  - Tre vector drawable 24dp a tinta unica (il colore lo applica `NavigationBarItem`): libreria musicale (Material "library_music"), microfono (Material "mic"), nota/play (Material "music_note" o "play_circle"); stesso formato degli esistenti `ic_play.xml`/`ic_pause.xml`.

- [ ] **Step 5: Implementare `AppBottomBar` in `AppNavigation.kt`**
  - `NavigationBar(windowInsets = WindowInsets(0))` con un `NavigationBarItem` per ogni `AppTab.entries`: `selected = tab == selected`, `onClick = { onSelect(tab) }`, `enabled = tab != AppTab.Player || playerEnabled`, `icon = Icon(painterResource(...), contentDescription = null)`, `label = Text(stringResource(...))`, `alwaysShowLabel = true`.
  - Aggiungere una `@Preview` con `VocalRemoverTheme`, `selected = Library`, `playerEnabled = false`, come le preview esistenti.

- [ ] **Step 6: Verificare che i test passino**

Run: `gradle :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.AppNavigationTest"`
Expected: PASS (5 test).

- [ ] **Step 7: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/AppNavigation.kt ui/src/main/res/drawable/ic_tab_*.xml ui/src/main/res/values/strings.xml ui/src/test/java/com/stef/vocalremover/ui/AppNavigationTest.kt
```

---

### Task 2: `ImportViewModel.pausePreview()`

**Files:**
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/ImportViewModel.kt` (vicino a `closeEditor`, ~riga 87)
- Test: `ui/src/test/java/com/stef/vocalremover/ui/ImportViewModelTest.kt`

**Interfaces:**
- Produces: `fun pausePreview()` su `ImportViewModel`.

- [ ] **Step 1: Test che fallisce** (usa il `FakePlayer` esistente, che registra le chiamate in `player.calls`)

```kotlin
@Test
fun `pausePreview mette in pausa l'anteprima`() = runTest(dispatcher) {
    captures.capture = pending
    val vm = viewModel()
    vm.togglePlayback()
    player.calls.clear()

    vm.pausePreview()

    assertEquals(listOf("pause"), player.calls)
}
```

- [ ] **Step 2: Verificare che fallisca**

Run: `gradle :ui:testCpuDebugUnitTest --tests "com.stef.vocalremover.ui.ImportViewModelTest"`
Expected: FAIL di compilazione (`pausePreview` non definito).

- [ ] **Step 3: Implementare `fun pausePreview() = player.pause()`** con KDoc breve ("Uscendo dalla scheda Cattura dal footer.").

- [ ] **Step 4: Verificare che passi**

Run: stesso comando. Expected: PASS, nessuna regressione negli altri test della classe.

- [ ] **Step 5: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/ImportViewModel.kt ui/src/test/java/com/stef/vocalremover/ui/ImportViewModelTest.kt
```

---

### Task 3: Schermate con slot `bottomBar` e `MainActivity` a schede

Un unico task: togliere i parametri dalle schermate rompe la compilazione di `MainActivity`, quindi vanno cambiati insieme.

**Files:**
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/library/LibraryScreen.kt`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/ImportSongScreen.kt`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/PlayerScreen.kt`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/MainActivity.kt`
- Modify: `ui/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `AppTab`, `AppNavigation.resolve/back`, `AppBottomBar` (Task 1); `ImportViewModel.pausePreview()` (Task 2).
- Produces (nuove firme):
  - `LibraryRoute(onTrackClick, onTrackRenamed, onTrackDeleted, bottomBar: @Composable () -> Unit = {}, viewModel = ...)`
  - `LibraryScreen(state, showPermissionRequest, onGrantPermission, onTrackClick, onRename, onDelete, snackbarHostState, bottomBar: @Composable () -> Unit = {})`
  - `ImportSongScreen(state, playerState, onEdit, onDiscard, onProcess, onTogglePlayback, onSeek, snackbarHostState, bottomBar: @Composable () -> Unit = {})`
  - `PlayerScreen(state, onRewind, onPlay, onPause, onForward, onSeek, bottomBar: @Composable () -> Unit = {})`

- [ ] **Step 1: `LibraryScreen`/`LibraryRoute`**: rimuovere `onOpenImportScreen`, `onOpenLegacyScreen`, lo `Spacer(12.dp)` + `Button` "Importa canzone" + `TextButton` legacy in fondo; passare `bottomBar` allo `Scaffold`. Aggiornare le `@Preview` del file.

- [ ] **Step 2: `ImportSongScreen`**: rimuovere `onBack`, `BackHandler(onBack = onBack)`, e in `ImportTopBar` l'`IconButton` con la freccia (la funzione diventa senza parametri: logo + titolo); passare `bottomBar` allo `Scaffold`. Aggiornare le preview.

- [ ] **Step 3: `PlayerScreen`**: rimuovere `onNavigateBack`, `BackHandler`, l'`IconButton` freccia nell'header, `onOpenImportScreen`, `onOpenLegacyScreen`, `Spacer(12.dp)` + `Button` + `TextButton` in fondo. La `bottomBar` dello `Scaffold` diventa `Column { <Row dei quattro FAB invariata>; bottomBar() }`. Aggiornare le preview.

- [ ] **Step 4: `MainActivity`**
  - Sostituire `showImportScreen`/`showPlayer` con `var tab by rememberSaveable { mutableStateOf(AppTab.Library) }`.
  - `val current = AppNavigation.resolve(tab, player != null)`.
  - `fun selectTab(new: AppTab)` locale al `setContent`: no-op se `new == current`; se `current == Player` → `playerViewModel.leave()`; se `current == Capture` → `importViewModel.pausePreview()`; poi `tab = new`.
  - `LaunchedEffect(openImportScreenRequest)`: al posto di `showImportScreen = true` → `selectTab(AppTab.Capture)`.
  - Rendering: splash → editor (invariato, senza footer) → altrimenti `when (current)` con le tre schermate, ognuna con `bottomBar = { AppBottomBar(current, playerEnabled = player != null, onSelect = ::selectTab) }`.
  - `LibraryRoute(onTrackClick = { playerViewModel.open(it); selectTab(AppTab.Player) }, onTrackRenamed = ..., onTrackDeleted = ...)`.
  - `BackHandler(enabled = editor == null && !showIntroduction && AppNavigation.back(current) != null) { AppNavigation.back(current)?.let(::selectTab) }`: disabilitato con l'editor aperto, così l'Indietro nell'editor si comporta come oggi.
  - Rimuovere import non più usati (`Intent` resta per `onNewIntent`; `LegacyMainActivity` sparisce).

- [ ] **Step 5: Pulizia stringhe**
  Run: `grep -rn "player_import_song\|player_open_legacy_screen\|control_back_description" ui/src`
  Rimuovere da `strings.xml` solo le chiavi senza più riferimenti (`control_back_description` probabilmente resta per `TrimEditorScreen`).

- [ ] **Step 6: Build, lint e test di tutti i flavor**

Run:
```bash
gradle :ui:assembleCpuDebug :ui:assembleWebgpuDebug :ui:assembleQnnDebug
gradle :ui:lintCpuDebug :ui:lintWebgpuDebug :ui:lintQnnDebug
gradle :ui:testCpuDebugUnitTest
```
Expected: BUILD SUCCESSFUL; nessun nuovo warning `UnusedResources`/`HardcodedText`; tutti i test verdi.

- [ ] **Step 7: Verifica manuale su dispositivo** (`gradle :ui:installCpuDebug`)
  - Avvio → splash → Libreria, voce Player disabilitata.
  - Tocco su un brano → scheda Player attiva, brano in riproduzione, controlli sopra il footer, nessuna doppia fascia in basso.
  - Player → Libreria: pausa. Ritocco su Libreria: nulla cambia.
  - Cattura con anteprima in riproduzione → Libreria: anteprima in pausa.
  - Avvio registrazione dal bubble con il Player in riproduzione, stop → si apre l'editor senza footer; chiudendolo si è su Cattura con il footer.
  - Indietro: con editor aperto come oggi; da Cattura/Player → Libreria; dalla Libreria esce.
  - Rotazione su Cattura e su Player: scheda mantenuta.
  - "Non mantenere attività" (opzioni sviluppatore) sul Player, uscire e rientrare → Libreria, nessun crash.

- [ ] **Step 8: Stage**

```bash
git add ui/src/main/java/com/stef/vocalremover/ui/MainActivity.kt ui/src/main/java/com/stef/vocalremover/ui/PlayerScreen.kt ui/src/main/java/com/stef/vocalremover/ui/ImportSongScreen.kt ui/src/main/java/com/stef/vocalremover/ui/library/LibraryScreen.kt ui/src/main/res/values/strings.xml
```
