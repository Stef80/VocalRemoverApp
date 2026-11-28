# Proiezione dei testi sulla TV (fase 1, Presentation) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Con un display di presentazione collegato (Miracast del produttore o HDMI/USB-C), un tocco su "Proietta" nel Player mostra sulla TV il testo del brano a tutto schermo in stile karaoke, mentre il telefono resta il telecomando e la riproduzione continua in background.

**Architecture:** La logica di `PlayerViewModel` passa in un `PlayerSession` di processo posseduto da `VocalRemoverApplication`. Un `ProjectionController` puro (testato in JVM) tiene lo stato `NoDisplay/Ready/Projecting` partendo da un `PresentationDisplaySource` e comanda un `ProjectionHost`. Il concreto è `TvProjectionService`: un servizio in foreground `mediaPlayback` che mostra una `Presentation(service, display)` (tipo di finestra predefinito, senza permesso overlay) con la vista Compose `TvLyricsScreen` e pubblica `MediaSession` e notifica di piattaforma. Un test strumentato su un `VirtualDisplay` di presentazione verifica il percorso reale su più marche via Firebase Test Lab. Nessuna dipendenza nuova in produzione; `:engine` non si tocca.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (BOM 2026.09.00), `android.app.Presentation`, `DisplayManager`, `android.media.session.MediaSession`, `Notification.MediaStyle`, JUnit4 + Robolectric + kotlinx-coroutines-test (`ui/src/test`); AndroidJUnitRunner + UiAutomator (`ui/src/androidTest`); Firebase Test Lab (`scripts/run-testlab.sh`).

**Spec:** `docs/superpowers/specs/2026-11-24-tv-projection-design.md`

## Global Constraints

- **Mai `git commit`**: ogni task termina con `git add` dei file toccati e una descrizione del commit proposto; il commit lo fa l'utente. Fermarsi dopo ogni task per la revisione dell'utente.
- Nessun testo visibile hard-coded in `:ui`: tutto in `ui/src/main/res/values/strings.xml` (italiano), con `stringResource` nei composable e `getString(R.string...)` in Service/Activity; segnaposto `%1$s`.
- La vista TV e il Player mostrano `R.drawable.logo_corallo_blu` accanto al titolo.
- Nessuna dipendenza nuova in produzione (niente `media3-session`, `androidx.media`, `material-icons`, Play Services): icone come vector drawable in `ui/src/main/res/drawable/`. Unica aggiunta, solo test: `androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")` (Task 4).
- La proiezione **non richiede il permesso "Mostra sopra altre app"**: `Presentation(service, display)` con il tipo di finestra predefinito; mai `window.setType(...)` (con `TYPE_APPLICATION_OVERLAY` fallisce con "Window type mismatch", Task 0b).
- Mai `FLAG_SECURE` sulla vista TV o sulla sua finestra: sul Realme il display della TV non è sicuro e resterebbe nero.
- Il `displayId` della TV cambia a ogni connessione: si cerca sempre con `DISPLAY_CATEGORY_PRESENTATION` e `DisplayListener`; non si salva mai (né in preferenze né tra un avvio del servizio e l'altro).
- `BackendBenchmarkTest` e le corse benchmark di `scripts/run-testlab.sh` restano invariate.
- `:engine` e `:connections` non cambiano. Tutto il codice nuovo sta in `:ui`.
- Backup invariato: la fase 1 non persiste nulla (niente SharedPreferences nuove).
- Senza proiezione il comportamento del Player resta quello di oggi: `leave()` mette in pausa.
- Commenti e log in inglese; documenti e testi UI in italiano.
- Gradle: usare la 9.8.0 del wrapper (`~/.gradle/wrapper/dists/`); il `gradle` di sistema può essere troppo vecchio.
- Il Task 0a non scrive codice; lo spike del Task 0b è usa-e-getta, non committato: non resta nel repo.

## Review Focus

1. **Display tolto mentre si proietta e l'app è in background** (Miracast interrotto dal menu rapido, cavo staccato): pausa alla posizione raggiunta, servizio fermato, notifica rimossa, nessuna ripresa automatica. Test `display rimosso in proiezione mette in pausa e termina` (Task 2) e verifica manuale (Task 6).
2. **Activity chiusa con Indietro o Home durante la proiezione**: la musica continua, la TV resta sul karaoke, la notifica riapre l'app sul Player con lo stesso brano. Test strumentato `la_proiezione_resta_dopo_Home` (Task 4) e verifica manuale (Task 6); prerequisito il `PlayerSession` di processo (Task 1). Lo swipe dalle recenti su ColorOS/Realme UI uccide il processo: limite accettato e solo documentato, nessuna contromisura.
3. **Anteprima nella scheda Cattura avviata durante la proiezione**: l'audio focus mette in pausa `LibraryPlayer`; la TV deve mostrare lo stato in pausa e non restare incoerente. Verifica manuale (Task 6).
4. **Libreria durante la proiezione**: "Elimina" è disabilitata (visibile, non selezionabile) e torna attiva a proiezione finita; una conferma di eliminazione rimasta aperta non elimina; la rinomina funziona e il titolo sulla TV si aggiorna. File cancellato da un'altra app: errore del player → fine proiezione e messaggio. Test `deleteEnabled` e `errore del player in proiezione termina con PLAYBACK_ERROR` (Task 2), verifica manuale (Task 5 e 6).
5. **Display che sparisce tra il tocco su "Proietta" e l'avvio del servizio** (o display privato/virtuale, dove la Presentation sceglie da sola `TYPE_PRIVATE_PRESENTATION`): il servizio non trova il display o `show()` lancia `BadTokenException`/`InvalidDisplayException` → proiezione terminata senza crash. Gestito nel Task 4; il caso virtuale è esercitato dal test strumentato.

---

### Task 0a: Verifica manuale senza codice — completato (2026-11-27)

- [x] Mirroring verso la LG (Screen Share) su Oppo Find X9 Pro e Realme GT2 Pro: display `type WIFI` con `FLAG_PRESENTATION` su entrambi, audio dalla TV, nessun ritardo percepito, mirroring attivo a schermo spento. Gate superato. Dettagli in "Esito Task 0 → 0a" della spec.

### Task 0b: Spike usa-e-getta della Presentation — completato (2026-11-28)

- [x] Spike `com.stef.vrspike` (fuori dal repo) già cancellato e disinstallato dai telefoni. Esito in "Esito Task 0 → 0b" della spec. Conseguenze recepite in questo piano:
  - `Presentation(service, display)` da servizio `mediaPlayback` resta visibile dopo Home e a schermo spento; serve il tipo predefinito (`TYPE_PRESENTATION`), mai `setType(TYPE_APPLICATION_OVERLAY)`; niente permesso overlay.
  - Lo swipe dalle recenti su ColorOS uccide il processo (`o-kill`): limite documentato; il servizio usa `START_NOT_STICKY`.
  - Il `displayId` cambia a ogni connessione: mai salvato.
  - La notifica MediaStyle su ColorOS compare solo con audio in riproduzione: da verificare nel Task 4 con la riproduzione reale.
  - `FLAG_KEEP_SCREEN_ON` non serve (con il mirroring il telefono resta `Awake`).

---

### Task 1: `PlayerSession` di processo

Sposta la logica di `PlayerViewModel` (brano, testi, attesa dei testi, seek, rinomina/eliminazione) in una classe di processo, senza cambiare il comportamento.

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/player/PlayerSession.kt`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/player/PlayerViewModel.kt` (diventa una facciata)
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/VocalRemoverApplication.kt` (aggiunge `playerSession`)
- Test: `ui/src/test/java/com/stef/vocalremover/ui/player/PlayerSessionTest.kt` (i test di `PlayerViewModelTest` spostati qui)
- Modify: `ui/src/test/java/com/stef/vocalremover/ui/player/PlayerViewModelTest.kt` (resta solo la delega di `leave()`)

**Interfaces:**
- Produces:
  - `class PlayerSession(player: PreviewPlayer, loadLyrics: (Uri) -> TrackLyrics? = { null }, ioDispatcher: CoroutineDispatcher = Dispatchers.IO, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))`
    - `val state: StateFlow<PlayerUiState?>`
    - `fun open(track: LibraryTrack)`, `fun togglePlayback()`, `fun play()`, `fun pause()`, `fun seekTo(positionMs: Long)`, `fun rewind()`, `fun forward()`, `fun onTrackDeleted(uri: Uri)`, `fun onTrackRenamed(uri: Uri, title: String)`
    - `companion object { const val SKIP_MS = 10_000L; internal const val LYRICS_POLL_MS = 1_000L }`
  - `class PlayerViewModel(session: PlayerSession, isProjecting: () -> Boolean = { false }) : ViewModel()`: stessi metodi pubblici di oggi delegati a `session`. `leave()`, solo se `!isProjecting()`, chiama `session.pause()` e `session.cancelPendingStart()` (annulla l'attesa dei testi e chiude il brano aperto se i testi sono ancora `Loading`; si torna con `open`). `onCleared()` **non** rilascia più il player.
  - `VocalRemoverApplication.playerSession: PlayerSession` (`by lazy`, creato al primo accesso dal main thread con `LibraryPreviewPlayer(LibraryPlayer(this))` e `TrackLyricsRepository.create(this)::get`). Mai toccato in `Application.onCreate`.
  - `PlayerUiState` e `PlayerLyrics` restano dove sono, invariati.

- [x] **Step 1: Spostare i test.** Copiare i test di `PlayerViewModelTest` in `PlayerSessionTest`, sostituendo `viewModel` con `session = PlayerSession(player, loadLyrics = { storedLyrics }, ioDispatcher = dispatcher, scope = CoroutineScope(dispatcher))`. Lasciare in `PlayerViewModelTest` solo:

```kotlin
@Test fun `leave without projection pauses`() {
    val vm = PlayerViewModel(session, isProjecting = { false })
    vm.open(track); /* testi FOUND → in riproduzione */ vm.leave()
    assertEquals(1, player.pauseCalls)
}
@Test fun `leave while projecting does not pause`() {
    val vm = PlayerViewModel(session, isProjecting = { true })
    vm.open(track); vm.leave()
    assertEquals(0, player.pauseCalls)
}
```
  Se `FakePlayer` non conta le pause, aggiungere `var pauseCalls = 0`.
- [x] **Step 2: Verificare che falliscano.** `gradle :ui:testDebugUnitTest --tests "com.stef.vocalremover.ui.player.*"`. Atteso: errore di compilazione (`PlayerSession` non esiste).
- [x] **Step 3: Implementare `PlayerSession`.** Spostare il corpo di `PlayerViewModel` sostituendo `viewModelScope` con `scope` e `stateIn(viewModelScope, Eagerly, null)` con `stateIn(scope, Eagerly, null)`. Poi rendere `PlayerViewModel` la facciata descritta sopra. Nella `Factory` si legge `(this[APPLICATION_KEY] as VocalRemoverApplication).playerSession`; `isProjecting` resta `{ false }` fino al Task 5.
- [x] **Step 4: Verificare.** `gradle :ui:testDebugUnitTest`. Atteso: tutto verde, incluso `PreviewPlaybackTest`.
- [x] **Step 5: Verifica manuale rapida.** `gradle :ui:assembleDebug`, installare, aprire un brano: riproduzione, ±10 s, cambio scheda (pausa) e rotazione si comportano come prima.
- [x] **Step 6: Staging.** `git add` dei file del task; proporre il commit "refactor(ui): move player state into process-scoped PlayerSession". Fermarsi per la revisione.

---

### Task 2: Stato della proiezione (`ProjectionController`) e sorgente dei display

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/ProjectionDisplay.kt` (modello e interfacce)
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/ProjectionController.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/DisplayManagerPresentationDisplaySource.kt`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/projection/ProjectionControllerTest.kt`

**Interfaces:**
- Consumes: `PlayerSession.pause()`, `PlayerSession.state` (Task 1).
- Produces:
  - `data class ProjectionDisplay(val id: Int, val name: String)`
  - `interface PresentationDisplaySource { val display: StateFlow<ProjectionDisplay?> }`
  - `interface ProjectionHost { fun show(display: ProjectionDisplay); fun hide() }`
  - `sealed interface ProjectionState { data object NoDisplay; data class Ready(val display: ProjectionDisplay); data class Projecting(val display: ProjectionDisplay) }`
  - `enum class ProjectionStartResult { STARTED, NO_DISPLAY, NO_TRACK }`
  - `enum class ProjectionEvent { DISCONNECTED, PLAYBACK_ERROR }`
  - `class ProjectionController(source: PresentationDisplaySource, host: ProjectionHost, session: PlayerSession, scope: CoroutineScope)` con:
    - `val state: StateFlow<ProjectionState>`
    - `val events: SharedFlow<ProjectionEvent>` (`extraBufferCapacity = 1`)
    - `fun start(): ProjectionStartResult`
    - `fun stop()`: idempotente; chiama `host.hide()` solo se si stava proiettando
    - `val isProjecting: Boolean`
  - `fun deleteEnabled(state: ProjectionState): Boolean = state !is ProjectionState.Projecting` (top-level in `ProjectionDisplay.kt`), usata dalla Libreria nel Task 5.
  - `class DisplayManagerPresentationDisplaySource(context: Context) : PresentationDisplaySource`: `getDisplays(DISPLAY_CATEGORY_PRESENTATION).firstOrNull()` (ordine di `DisplayManager`, D9), riletto a ogni `onDisplayAdded/Removed/Changed` di `registerDisplayListener` su `Handler(Looper.getMainLooper())`; `name = display.name`. Nessuna cache dell'id oltre il valore corrente del flow.

Regole (dalla spec):
- `start()`:
  - senza brano in `session.state` → `NO_TRACK`;
  - senza display → `NO_DISPLAY`;
  - altrimenti `host.show(display)`, stato `Projecting`, `STARTED`.
  - In proiezione `start()` è un no-op che restituisce `STARTED`.
- Se il display proiettato diventa `null` o cambia `id` mentre si proietta (D6, D9):
  - `session.pause()`, `host.hide()`, emette `DISCONNECTED`;
  - nuovo stato `Ready(nuovo)` oppure `NoDisplay`.
- Un nuovo `open()` di `PlayerSession` non cambia lo stato della proiezione (D7).
- Se mentre si proietta `session.state.value?.error` diventa non `null` (D11: file cancellato da un'altra app o
  errore di riproduzione): `stop()` ed emette `PLAYBACK_ERROR`. Un errore fuori proiezione non fa nulla.

- [ ] **Step 1: Scrivere i test che falliscono** (Robolectric come `PlayerSessionTest`, con `FakeDisplaySource` basato su `MutableStateFlow`, `FakeHost` che registra `show`/`hide` e il `FakePlayer` del Task 1):

```kotlin
@Test fun `senza display lo stato è NoDisplay e start restituisce NO_DISPLAY`()
    // assertEquals(ProjectionState.NoDisplay, controller.state.value); assertEquals(NO_DISPLAY, controller.start()); host.shown vuoto
@Test fun `display collegato porta a Ready`()
    // source.display.value = tv → assertEquals(ProjectionState.Ready(tv), controller.state.value)
@Test fun `start senza brano aperto restituisce NO_TRACK`()
@Test fun `start con display e brano mostra sul display e passa a Projecting`()
    // assertEquals(STARTED, ...); assertEquals(listOf(tv), host.shown); state == Projecting(tv)
@Test fun `display rimosso in proiezione mette in pausa e termina`()
    // in riproduzione → source.display.value = null → player.pauseCalls == 1, host.hideCalls == 1,
    // state == NoDisplay, evento DISCONNECTED; nessun play() successivo
@Test fun `display sostituito da un altro in proiezione conta come disconnessione`()
    // tv(id=2) → altra(id=3): DISCONNECTED, state == Ready(altra)
@Test fun `display rimosso fuori proiezione non mette in pausa`()
@Test fun `stop è idempotente e nasconde una volta sola`()
@Test fun `nuovo brano in proiezione resta in proiezione`()
    // session.open(altroBrano) → state ancora Projecting(tv), host.hideCalls == 0
@Test fun `errore del player in proiezione termina con PLAYBACK_ERROR`()
    // in proiezione → player.state con error = SOURCE_UNAVAILABLE → host.hideCalls == 1, state == Ready(tv), evento PLAYBACK_ERROR
@Test fun `errore del player fuori proiezione non emette eventi`()
@Test fun `Elimina disabilitata solo in proiezione`() {
    assertTrue(deleteEnabled(ProjectionState.NoDisplay))
    assertTrue(deleteEnabled(ProjectionState.Ready(tv)))
    assertFalse(deleteEnabled(ProjectionState.Projecting(tv)))
}
@Test fun `Elimina torna attiva dopo stop e dopo disconnessione`()
    // start → deleteEnabled(state) falso; stop → vero; start → source.display.value = null → vero
```
- [ ] **Step 2: Verificare che falliscano.** `gradle :ui:testDebugUnitTest --tests "com.stef.vocalremover.ui.projection.*"`. Atteso: errore di compilazione.
- [ ] **Step 3: Implementare modello, interfacce, `ProjectionController` e `DisplayManagerPresentationDisplaySource`** con le regole sopra. Il controller osserva `source.display` in `scope`.
- [ ] **Step 4: Verificare.** Stesso comando dello Step 2. Atteso: PASS. Poi `gradle :ui:testDebugUnitTest`, tutto verde.
- [ ] **Step 5: Staging.** `git add`; proporre il commit "feat(ui): projection state machine and presentation display source". Fermarsi per la revisione.

---

### Task 3: Vista TV (`TvLyricsScreen`) e modello karaoke

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/KaraokeLines.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/TvLyricsScreen.kt`
- Modify: `ui/src/main/res/values/strings.xml`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/projection/KaraokeLinesTest.kt` (JUnit4 semplice)

**Interfaces:**
- Consumes: `PlayerUiState`, `PlayerLyrics` e `PlayerLyrics.Synced.activeLine()` (esistenti).
- Produces:
  - `data class KaraokeWindow(val previous: List<String>, val active: String?, val next: List<String>)`
  - `object KaraokeLines { const val BEFORE = 2; const val AFTER = 2; fun window(lyrics: PlayerLyrics.Synced, positionMs: Long): KaraokeWindow; fun plainScrollFraction(positionMs: Long, durationMs: Long): Float }`
  - `@Composable fun TvLyricsScreen(state: PlayerUiState?)`

- [ ] **Step 1: Scrivere i test che falliscono:**

```kotlin
private val synced = PlayerLyrics.Synced(
    lines = listOf("a", "b", "c", "d", "e", "f"),
    startsMs = listOf(10_000, 20_000, 30_000, 40_000, 50_000, 60_000), songOffsetMs = 0)

@Test fun `prima della prima riga nessuna attiva e le prime come successive`() =
    assertEquals(KaraokeWindow(emptyList(), null, listOf("a", "b")), KaraokeLines.window(synced, 5_000))
@Test fun `riga centrale con due prima e due dopo`() =
    assertEquals(KaraokeWindow(listOf("b", "c"), "d", listOf("e", "f")), KaraokeLines.window(synced, 41_000))
@Test fun `prima riga senza precedenti`() =
    assertEquals(KaraokeWindow(emptyList(), "a", listOf("b", "c")), KaraokeLines.window(synced, 10_000))
@Test fun `ultima riga senza successive`() =
    assertEquals(KaraokeWindow(listOf("d", "e"), "f", emptyList()), KaraokeLines.window(synced, 99_000))
@Test fun `songOffset sposta la riga attiva`() =
    assertEquals("a", KaraokeLines.window(synced.copy(songOffsetMs = 5_000), 5_000).active)
@Test fun `scroll del testo semplice proporzionale e limitato`() {
    assertEquals(0f, KaraokeLines.plainScrollFraction(0, 200_000))
    assertEquals(0.5f, KaraokeLines.plainScrollFraction(100_000, 200_000))
    assertEquals(1f, KaraokeLines.plainScrollFraction(300_000, 200_000))
    assertEquals(0f, KaraokeLines.plainScrollFraction(10_000, 0))
}
```
- [ ] **Step 2: Verificare che falliscano.** `gradle :ui:testDebugUnitTest --tests "com.stef.vocalremover.ui.projection.KaraokeLinesTest"`. Atteso: errore di compilazione.
- [ ] **Step 3: Implementare `KaraokeLines`.** Usa `activeLine()`; `plainScrollFraction` = `(positionMs / durationMs).coerceIn(0f, 1f)`, e `0f` se `durationMs <= 0`.
- [ ] **Step 4: Verificare.** Stesso comando dello Step 2. Atteso: PASS.
- [ ] **Step 5: Implementare `TvLyricsScreen`** (D1 della spec), con `VocalRemoverTheme` e sfondo scuro del tema:
  - testata fissa in alto a sinistra: `logo_corallo_blu` (72.dp) e titolo del brano;
  - `Synced`: colonna centrata; le righe `previous`/`next` in `headlineSmall`, colore `onSurfaceVariant`; la riga `active` in `displayMedium`, grassetto, colore `primary`; cambio di riga animato con `AnimatedContent` (la chiave è l'indice attivo);
  - `Plain`: `Text` in `headlineMedium` dentro `verticalScroll`; un `LaunchedEffect(fraction)` porta la scroll a `fraction * scrollState.maxValue` con `animateScrollTo`, in modo che lo scorrimento sia lento e segua pausa e seek;
  - `Instrumental`/`Unavailable`: logo grande (240.dp) e titolo in `displaySmall`, centrati;
  - `Loading`: come sopra, più `R.string.player_lyrics_wait`;
  - `state == null`: solo il logo.

  Stringhe nuove: `tv_lyrics_logo_description` ("Logo VocalRemover"). Riusare `player_lyrics_wait`. Aggiungere due `@Preview` 1920×1080 (`Synced` e `Instrumental`).
- [ ] **Step 6: Verificare.** `gradle :ui:assembleDebug :ui:lintDebug`. Atteso: BUILD SUCCESSFUL, nessun nuovo `HardcodedText`. Controllare le preview in Android Studio (`android studio render-compose-preview`, se disponibile).
- [ ] **Step 7: Staging.** `git add`; proporre il commit "feat(ui): full-screen karaoke view for the TV". Fermarsi per la revisione.

---

### Task 4: `TvProjectionService`, `TvPresentation`, MediaSession, notifica e test strumentato

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/TvPresentation.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/TvProjectionService.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/ServiceProjectionHost.kt`
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/ProjectionPlaybackState.kt` (mappatura pura per la MediaSession)
- Create: `ui/src/main/res/drawable/ic_notification_rewind.xml`, `ic_notification_play.xml`, `ic_notification_pause.xml`, `ic_notification_forward.xml`, `ic_notification_stop_projection.xml`
- Modify: `ui/src/main/AndroidManifest.xml`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/VocalRemoverApplication.kt` (aggiunge `projectionController`)
- Modify: `ui/src/main/res/values/strings.xml`
- Modify: `ui/build.gradle.kts` (solo `androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")`)
- Test: `ui/src/test/java/com/stef/vocalremover/ui/projection/ProjectionPlaybackStateTest.kt`
- Test: `ui/src/androidTest/java/com/stef/vocalremover/ui/projection/TvProjectionInstrumentedTest.kt`

**Interfaces:**
- Consumes: `ProjectionController`, `ProjectionHost`, `ProjectionDisplay` (Task 2); `TvLyricsScreen` (Task 3); `PlayerSession` (Task 1); `OverlayLifecycleOwner` (`ui/overlay`, esistente); `TestAudioFiles.wav(dir, name, seconds)` (`ui/src/androidTest/.../testaudio`, esistente).
- Produces:
  - `class ServiceProjectionHost(context: Context) : ProjectionHost`:
    - `show(display)` → `ContextCompat.startForegroundService(context, TvProjectionService.showIntent(context, display.id))`;
    - `hide()` → `context.startService(TvProjectionService.stopIntent(context))`.
  - `TvProjectionService` con `companion`:
    - `fun showIntent(context: Context, displayId: Int): Intent`, `fun stopIntent(context: Context): Intent`;
    - azioni `ACTION_SHOW`, `ACTION_STOP`, `ACTION_TOGGLE`, `ACTION_REWIND`, `ACTION_FORWARD`;
    - extra `EXTRA_DISPLAY_ID`: vale solo per quell'avvio (l'id della connessione corrente), non viene salvato.
  - `class TvPresentation(context: Context, display: Display, content: @Composable () -> Unit) : Presentation`: costruita con il contesto del servizio; **non** chiama `window.setType` e non aggiunge `FLAG_SECURE`.
  - `data class SessionPlayback(val state: Int, val positionMs: Long, val actions: Long)` e `fun sessionPlaybackFor(ui: PlayerUiState?): SessionPlayback`:
    - stati `PlaybackState.STATE_PLAYING` / `STATE_PAUSED` / `STATE_BUFFERING` (con `Loading`) / `STATE_NONE` (`null`);
    - azioni `ACTION_PLAY_PAUSE or ACTION_PLAY or ACTION_PAUSE or ACTION_REWIND or ACTION_FAST_FORWARD or ACTION_SEEK_TO`, ma solo `0L` quando i testi sono `Loading`, coerente con il blocco dei comandi di `PlayerSession`.
  - `VocalRemoverApplication.projectionController: ProjectionController` (`by lazy`, main thread) con `DisplayManagerPresentationDisplaySource(this)`, `ServiceProjectionHost(this)`, `playerSession` e uno scope Main dell'applicazione.

Comportamento del servizio:
- `onStartCommand` restituisce sempre `START_NOT_STICKY`: dopo un `o-kill` il sistema non deve riavviarlo a metà. Se riceve un intent `null` o senza azione, `stopSelf()`.
- `ACTION_SHOW`:
  - `startForeground` **subito** (entro 5 s), con tipo `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` su API 29+ (`ServiceCompat.startForeground`);
  - poi cerca il display tra `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` con l'id ricevuto; se non c'è più → `projectionController.stop()` e `stopSelf()`;
  - poi crea `TvPresentation(this, display)` e chiama `show()`;
  - la `ComposeView` è ospitata da un `OverlayLifecycleOwner` (`attach()` prima di `show()`, `detach()` alla chiusura) e il contenuto è `TvLyricsScreen(playerSession.state.collectAsState().value)`.
  - Un secondo `ACTION_SHOW` sostituisce la Presentation esistente.
- `ACTION_STOP`: chiude la Presentation, rilascia la `MediaSession`, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`.
- `ACTION_TOGGLE` / `ACTION_REWIND` / `ACTION_FORWARD` (dalla notifica) e i callback della `MediaSession` (`onPlay`, `onPause`, `onRewind`, `onFastForward`, `onSeekTo`, `onStop`): delegano a `PlayerSession`. `onStop` ed "Termina proiezione" chiamano `projectionController.stop()`.
- La `MediaSession` (tag `"VocalRemoverTv"`) è attiva finché il servizio vive.
  - Raccogliendo `playerSession.state` aggiorna `setPlaybackState` con `sessionPlaybackFor` e `setMetadata` (`METADATA_KEY_TITLE`, `METADATA_KEY_DURATION`), e ricostruisce la notifica.
- La notifica usa il canale `tv_projection`, `IMPORTANCE_LOW`, con il nome da `R.string.tv_projection_channel_name`:
  - `Notification.Builder` (API 26+) con `Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2)`;
  - azioni indietro, play/pausa, avanti, "Termina proiezione"; `contentIntent` che apre `MainActivity`; testo `R.string.tv_projection_notification_text` con il nome del display.
- `onTaskRemoved`: nessuna azione e nessuna contromisura. Su ColorOS/Realme UI lo swipe dalle recenti uccide comunque il processo: è un limite accettato.
- `onDestroy`: chiude la Presentation e la sessione se sono ancora aperte.
- `WindowManager.BadTokenException` / `WindowManager.InvalidDisplayException` in `show()` → log con tag `TvProjection`, `projectionController.stop()`, `stopSelf()`.

Test strumentato `TvProjectionInstrumentedTest` (AndroidJUnit4, package `com.stef.vocalremover.ui.projection`):
- Usa le istanze reali `app.playerSession` e `app.projectionController` (`ApplicationProvider.getApplicationContext<VocalRemoverApplication>()`), senza lanciare `MainActivity`, quindi non carica ORT.
- `@Before`:
  - su API 33+ `pm grant <package> android.permission.POST_NOTIFICATIONS` via `UiAutomation.executeShellCommand`;
  - crea `ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)` e `DisplayManager.createVirtualDisplay("vr-test-tv", 1280, 720, 320, reader.surface, VIRTUAL_DISPLAY_FLAG_PRESENTATION)`, senza `VIRTUAL_DISPLAY_FLAG_PUBLIC` (richiederebbe un permesso di sistema). Il display privato è visibile al processo che lo crea, che è quello dell'app.
  - Poi apre un WAV di prova: `TestAudioFiles.wav(cacheDir, "tv.wav", 20.0)` come `LibraryTrack(Uri.fromFile(...), "Brano di prova", 20_000)` con `playerSession.open(...)` sul main thread; i testi non esistono, quindi lo stato è `Unavailable` e la riproduzione parte subito.
- `@After`: `projectionController.stop()`, `virtualDisplay.release()` se ancora presente, `reader.close()`, `playerSession.pause()`.
- Helper `awaitFrameWithContent(reader)`: entro 5 s acquisisce l'ultima immagine e controlla che non sia uniforme, cioè che contenga almeno 2 colori distinti su una griglia di campioni (sfondo scuro + logo/titolo). Così si verifica che la vista sia disegnata davvero su quel display, su tutte le API dalla 26.
- Helper `windowOnDisplay(displayId)` (solo API 30+): `UiAutomation.getWindowsOnAllDisplays()` contiene `displayId`. Sotto l'API 30 si usa solo il controllo sui pixel.
- Helper `serviceRunning()`: `ActivityManager.getRunningServices(Int.MAX_VALUE)` (deprecato, ma restituisce ancora i servizi dell'app) contiene `TvProjectionService`.

```kotlin
@Test fun la_proiezione_compare_sul_display_di_presentazione() {
    // state == Ready(display con id == virtualDisplay.display.displayId); start() == STARTED
    // awaitFrameWithContent(reader); su API 30+ windowOnDisplay(id); serviceRunning()
}
@Test fun la_proiezione_resta_dopo_Home() {
    // start(); UiDevice.getInstance(instrumentation).pressHome(); attesa 2 s
    // state è ancora Projecting; awaitFrameWithContent(reader) con un'immagine nuova; serviceRunning()
}
@Test fun rimuovere_il_display_mette_in_pausa_e_ferma_il_servizio() {
    // start(); attesa isPlaying == true; raccolta di events in un buffer
    // virtualDisplay.release() → entro 5 s: evento DISCONNECTED, playerSession.state.value.isPlaying == false,
    // state == NoDisplay, !serviceRunning()
}
@Test fun la_notifica_media_compare_con_la_riproduzione() {
    // start(); isPlaying == true → NotificationManager.activeNotifications contiene una notifica del canale
    // "tv_projection" con extras[Notification.EXTRA_MEDIA_SESSION] != null
    // Argomento opzionale holdMs (InstrumentationRegistry.getArguments): se presente, resta in riproduzione
    // per holdMs ms prima delle asserzioni finali, per il controllo a vista della tendina (Step 6)
}
```

- [ ] **Step 1: Scrivere i test JVM che falliscono** (`ProjectionPlaybackStateTest`, Robolectric per `PlaybackState`):

```kotlin
@Test fun `in riproduzione stato PLAYING con posizione`() // isPlaying=true, positionMs=12_000 → STATE_PLAYING, 12_000
@Test fun `in pausa stato PAUSED`()
@Test fun `testi in caricamento BUFFERING senza azioni`() // lyrics=Loading → STATE_BUFFERING, actions == 0L
@Test fun `senza brano STATE_NONE`()
```
- [ ] **Step 2: Verificare che falliscano.** `gradle :ui:testDebugUnitTest --tests "com.stef.vocalremover.ui.projection.ProjectionPlaybackStateTest"`. Atteso: errore di compilazione.
- [ ] **Step 3: Implementare `sessionPlaybackFor`, `TvPresentation`, `TvProjectionService`, `ServiceProjectionHost` e `projectionController`** come sopra.
  - Manifest di `:ui`:
    - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />`
    - `<service android:name="com.stef.vocalremover.ui.projection.TvProjectionService" android:exported="false" android:foregroundServiceType="mediaPlayback" />`
  - Stringhe nuove:
    - `tv_projection_channel_name` ("Proiezione sulla TV")
    - `tv_projection_notification_text` ("Testo su %1$s")
    - `tv_projection_action_stop` ("Termina proiezione")
  - Riusare le esistenti `control_rewind_description`, `control_play_description`, `control_pause_description`, `control_forward_description` come titoli delle azioni.
- [ ] **Step 4: Verificare i JVM.** `gradle :ui:testDebugUnitTest :ui:assembleDebug :ui:lintDebug`. Atteso: verde, nessun avviso nuovo su servizi in foreground o testo hard-coded.
- [ ] **Step 5: Scrivere ed eseguire il test strumentato** su un telefono collegato via adb, senza Miracast (il display è virtuale):
  `gradle :ui:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.stef.vocalremover.ui.projection.TvProjectionInstrumentedTest`
  Atteso: 4 test verdi. Poi verificare che il benchmark non sia toccato: `gradle :ui:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.vocalremover.benchmark.BackendBenchmarkTest -Pandroid.testInstrumentationRunnerArguments.backend=cpu` resta verde.
- [ ] **Step 6: Notifica con la riproduzione reale su ColorOS** (Oppo Find X9 Pro):
  `gradle :ui:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.stef.vocalremover.ui.projection.TvProjectionInstrumentedTest#la_notifica_media_compare_con_la_riproduzione -Pandroid.testInstrumentationRunnerArguments.holdMs=30000`
  Durante i 30 s aprire la tendina. Atteso: notifica multimediale con titolo "Brano di prova" e i comandi; play/pausa dalla notifica agisce sull'audio. Annotare l'esito nella spec ("Esito Task 4").
- [ ] **Step 7: Staging.** `git add`; proporre il commit "feat(ui): foreground service showing the karaoke presentation with media controls". Fermarsi per la revisione.

---

### Task 5: Pulsante "Proietta" nel Player, "Elimina" disabilitata e cablaggio in `MainActivity`

**Files:**
- Create: `ui/src/main/java/com/stef/vocalremover/ui/projection/ProjectionButton.kt`
- Create: `ui/src/main/res/drawable/ic_projection_tv.xml`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/PlayerScreen.kt` (testata, `SnackbarHost`)
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/PlayableTracks.kt` (parametro `deleteEnabled`)
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/library/LibraryScreen.kt` (`LibraryRoute` e `LibraryScreen` passano `deleteEnabled`)
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/MainActivity.kt`
- Modify: `ui/src/main/java/com/stef/vocalremover/ui/player/PlayerViewModel.kt` (`Factory`: `isProjecting`)
- Modify: `ui/src/main/res/values/strings.xml`
- Test: la regola `deleteEnabled` è già coperta in `ProjectionControllerTest` (Task 2); qui verifica manuale.

**Interfaces:**
- Consumes: `ProjectionController.state/events/start/stop`, `ProjectionStartResult`, `ProjectionEvent`, `deleteEnabled(state)` (Task 2); `VocalRemoverApplication.projectionController` (Task 4).
- Produces:
  - `@Composable fun ProjectionButton(state: ProjectionState, onClick: () -> Unit, modifier: Modifier = Modifier)`: `IconButton` con `ic_projection_tv`; in `Projecting` usa `FilledIconButton` (evidenziato). Content description:
    - `NoDisplay` → `projection_connect_description`;
    - `Ready` → `projection_start_description`;
    - `Projecting` → `projection_stop_description`.
  - `PlayerScreen(..., projection: ProjectionState = ProjectionState.NoDisplay, onProjectionClick: () -> Unit = {}, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }, ...)`:
    - `ProjectionButton` nella `Row` della testata, a fianco del blocco del titolo, che prende `Modifier.weight(1f)` (D12);
    - in `Projecting`, sotto il titolo, `stringResource(R.string.projection_active, display.name)`.
  - `PlayableTracks(..., deleteEnabled: Boolean = true)`: il `DropdownMenuItem` di `library_action_delete` riceve `enabled = deleteEnabled` (voce visibile ma disabilitata, D10). "Rinomina" resta sempre attiva.
  - `LibraryScreen(..., deleteEnabled: Boolean = true)` e `LibraryRoute(..., deleteEnabled: Boolean = true)`: inoltrano il valore a `PlayableTracks`. Se la conferma di eliminazione è aperta e `deleteEnabled` è falso, il pulsante di conferma chiude il dialog senza chiamare `onDelete`.
  - `PlayerViewModel(session, isProjecting)`: nessun nuovo parametro; `onTrackDeleted` resta com'è (serve fuori proiezione).

Click in `MainActivity` (`onProjectionClick`):
- in `Projecting` → `controller.stop()`;
- altrimenti `controller.start()`, con questi esiti:
  - `STARTED`: nessun messaggio;
  - `NO_DISPLAY`: `Intent(Settings.ACTION_CAST_SETTINGS)` se `resolveActivity` non è `null`, altrimenti snackbar `projection_no_display`;
  - `NO_TRACK`: nessuna azione (il pulsante sta nel Player, che ha sempre un brano).

`LibraryRoute` riceve `deleteEnabled = deleteEnabled(projection)`, con `projection` letto da `app.projectionController.state.collectAsState()`.

Eventi raccolti in un `LaunchedEffect(Unit)`:
- `DISCONNECTED` → snackbar `projection_disconnected`;
- `PLAYBACK_ERROR` → snackbar `projection_stopped_error`. Il messaggio d'errore già esistente sotto la barra (`player_error_unavailable` / `player_error_failed`) resta.

Stringhe nuove:
- `projection_connect_description`: "Collega una TV"
- `projection_start_description`: "Proietta il testo sulla TV"
- `projection_stop_description`: "Termina la proiezione"
- `projection_active`: "Su %1$s"
- `projection_no_display`: "Avvia la proiezione dello schermo dal menu rapido del telefono, poi tocca di nuovo Proietta."
- `projection_disconnected`: "TV scollegata: riproduzione in pausa."
- `projection_stopped_error`: "Impossibile riprodurre il brano: proiezione terminata."

- [ ] **Step 1: Implementare.** `PlayerViewModel.Factory` (`isProjecting = { app.projectionController.isProjecting }`), poi `ProjectionButton`, `PlayerScreen`, `PlayableTracks`, `LibraryScreen`/`LibraryRoute` e `MainActivity`. In `MainActivity` lo stato si legge con `app.projectionController.state.collectAsState()` e si passa a `PlayerScreen` (`projection`, `onProjectionClick`) e a `LibraryRoute` (`deleteEnabled`). Aggiornare le `@Preview`: `PlayerScreen` con `Projecting(ProjectionDisplay(2, "LG TV"))`, `PlayableTracks` con `deleteEnabled = false`.
- [ ] **Step 2: Verificare.** `gradle :ui:testDebugUnitTest :ui:lintDebug :ui:assembleDebug`. Atteso: verde.
- [ ] **Step 3: Verifica manuale (debug) su un telefono del Task 0.**
  - Senza Miracast: tocco su "Proietta" → impostazioni di proiezione o messaggio.
  - Con Miracast: tocco → karaoke sulla TV, "Su <nome>" sotto il titolo.
  - Cambio scheda: la musica continua.
  - Altro brano dalla Libreria: va sulla TV.
  - In proiezione, pressione lunga su un brano della Libreria: "Elimina" visibile ma disabilitata, "Rinomina" attiva; dopo la rinomina del brano in riproduzione il titolo sulla TV si aggiorna.
  - Tocco sul pulsante: la TV torna al mirroring e "Elimina" torna attiva.
  - Miracast interrotto: pausa, messaggio e "Elimina" di nuovo attiva.
  - File cancellato da un'altra app (per esempio con `adb shell rm` del WAV o con un file manager) durante la proiezione: errore del player, proiezione terminata, messaggio.
- [ ] **Step 4: Staging.** `git add`; proporre il commit "feat(ui): project button in the player, delete disabled while projecting". Fermarsi per la revisione.

---

### Task 6: Verifica finale, matrice Firebase Test Lab e APK release sui telefoni

**Files:**
- Modify: `scripts/run-testlab.sh` (modalità `projection`)
- Modify: `CLAUDE.md` (una riga nella sezione Commands: `scripts/run-testlab.sh projection [--dry-run]`)
- Modify: `docs/superpowers/specs/2026-11-24-tv-projection-design.md` (esiti e limiti trovati)
- Modify: `README.md` (breve paragrafo in italiano: requisiti della proiezione, Miracast o cavo HDMI; limite dello swipe dalle recenti su ColorOS/Realme UI)

Estensione di `scripts/run-testlab.sh` (oggi lancia tutta la suite strumentata senza `--test-targets`, quindi il nuovo test finirebbe anche nelle corse benchmark):
- Nuova modalità `projection`:
  - `--test-targets "class com.stef.vocalremover.ui.projection.TvProjectionInstrumentedTest"`;
  - niente `--environment-variables` del benchmark e niente `--directories-to-pull`;
  - `RESULTS_DIR=vr-projection-$STAMP`;
  - non scarica i `report-*.json`: l'esito è lo stato di `gcloud` (`exit "$STATUS"`) e il riepilogo per dispositivo;
  - `DEFAULT_DEVICES` per marca e API: Samsung fisico, Pixel fisico, più dispositivi virtuali per coprire le API vecchie (26, 30), così da restare nel limite Spark di 5 fisici al giorno. Xiaomi/Oppo solo se presenti nel catalogo. La lista concreta la sceglie android-tester da `gcloud firebase test android models list`; `DEVICES=...` resta sovrascrivibile.
- Modalità benchmark (`cpu|webgpu|qnn|auto`): aggiungere `--test-targets "notClass com.stef.vocalremover.ui.projection.TvProjectionInstrumentedTest"`, così si comportano esattamente come oggi.
- `--dry-run` stampa il comando anche per `projection`.

- [ ] **Step 1: Suite JVM e lint.** `gradle :ui:testDebugUnitTest :engine:testDebugUnitTest :ui:lintDebug :engine:lintDebug`. Atteso: verde.
- [ ] **Step 2: Script.** Estendere `scripts/run-testlab.sh` come sopra. Verifica: `scripts/run-testlab.sh projection --dry-run` mostra `--test-targets "class …TvProjectionInstrumentedTest"`; `scripts/run-testlab.sh auto --dry-run` mostra il `notClass` e per il resto è identico a prima (confronto con l'output dry-run della versione attuale).
- [ ] **Step 3: Matrice su Test Lab (android-tester).** `scripts/run-testlab.sh projection`. Atteso: i 4 test verdi su ogni dispositivo della matrice. Un fallimento su una marca o API va analizzato (logcat dalla console) prima di proseguire. Annotare la matrice e l'esito nella spec ("Esito Task 6").
- [ ] **Step 4: APK release** (R8 attivo, firmato con la chiave dell'utente). `gradle :ui:assembleRelease`, installare su Oppo Find X9 Pro e Realme GT2 Pro.
- [ ] **Step 5: Checklist manuale essenziale su entrambi i telefoni, con Miracast reale verso la LG**, annotando l'esito nella spec:
  1. Proiezione: tocco su "Proietta" → karaoke sulla TV (riga attiva, logo, titolo); seek e ±10 s aggiornano la TV.
  2. Home: la musica e la TV continuano.
  3. Schermo del telefono spento per più di 2 minuti: la musica e la TV continuano.
  4. Disconnessione TV (Miracast interrotto dal menu rapido o TV spenta): pausa, messaggio, notifica rimossa.
  5. "Elimina" disabilitata durante la proiezione e di nuovo attiva dopo.
  6. Notifica: compare con i comandi durante la riproduzione, i comandi funzionano, il tocco riapre il Player.
- [ ] **Step 6: Documentare il limite.** Nella spec e nel README: su ColorOS/Realme UI lo swipe dell'app dalle recenti uccide il processo e termina proiezione e riproduzione; nessuna contromisura prevista.
- [ ] **Step 7: Controlli di CLAUDE.md.**
  - `git diff --stat` non tocca `:engine` né `:connections`.
  - `data_extraction_rules.xml` e `backup_rules.xml` invariati.
  - Nessuna dipendenza nuova in produzione in `ui/build.gradle.kts` o `gradle/libs.versions.toml` (solo `uiautomator` in `androidTestImplementation`).
  - `grep -rn "FLAG_SECURE\|setType(" ui/src/main/java/com/stef/vocalremover/ui/projection` non trova nulla.
  - `grep -rn '"' ui/src/main/java/com/stef/vocalremover/ui/projection` non mostra testi utente.
- [ ] **Step 8: Staging.** `git add` di script, CLAUDE.md, spec e README; proporre il commit "test: TV projection on Test Lab; docs: requirements and limits". Fermarsi per la revisione.

---

## Codice da riusare (non riscrivere)

- `PreviewPlayer` / `LibraryPreviewPlayer` / `LibraryPlayer`: il player resta quello.
- `PlayerUiState`, `PlayerLyrics` (incluso `activeLine()`), `formatMinutesSeconds`.
- `OverlayLifecycleOwner` (`ui/overlay`) per la `ComposeView` nella Presentation.
- `TestAudioFiles` (`ui/src/androidTest/.../testaudio`) per il WAV del test strumentato.
- Il pattern del canale di notifica di `BubbleOverlayService.buildNotification`.
- `VocalRemoverTheme` e `R.drawable.logo_corallo_blu`.

## Alternative scartate (una riga ciascuna)

- Presentation ospitata dall'Activity: si chiude con l'Activity (verificato nel Task 0b).
- `media3-session` / `MediaSessionService`: è una dipendenza nuova e scavalcherebbe il blocco dei comandi durante il caricamento dei testi di `PlayerSession`.
- Riusare `LyricsPanel` del telefono sulla TV: è un layout a lista pensato per il telefono; la TV ha bisogno di una finestra centrata a tre zone.
- Avvio automatico della proiezione quando compare un display: scartato dalla decisione D8.
- Gestire l'eliminazione del brano proiettato (stop e messaggio): l'utente preferisce evitare il caso disabilitando "Elimina" (D10).

## Rischi

- Altri produttori possono non esporre il display Miracast come display di presentazione: sui due telefoni dell'utente funziona (Task 0a/0b); il test su `VirtualDisplay` copre il percorso dell'app ma non lo stack Miracast di ciascuna marca.
- Ritardo audio/testo con Miracast (audio codificato dal sistema): il testo è sincronizzato con la posizione di ExoPlayer, non con l'audio sulla TV; nel Task 0a non è stato percepito alcun ritardo, quindi non serve un offset.
- Swipe dalle recenti su ColorOS/Realme UI: il processo viene ucciso (`o-kill`) anche con il servizio in foreground. Limite accettato e documentato.
- La notifica MediaStyle su ColorOS compare solo con audio reale in riproduzione (Task 4, Step 6).
- **API 26–29 non verificate dallo spike** (fatto solo su API 37): prima dell'API 30 la Presentation non ha un window context proprio e, creata da un Service senza token, potrebbe essere rifiutata (`BadTokenException`, gestita senza crash). La matrice Test Lab del Task 6 lo mostra. Se succede, ci si ferma e si porta la scelta all'utente; non si decide da soli: limitare la proiezione all'API 30+, oppure sotto l'API 30 usare `TYPE_APPLICATION_OVERLAY` con il permesso overlay.
- Su Test Lab un display virtuale privato usa `TYPE_PRIVATE_PRESENTATION`, scelto dalla Presentation stessa: per questo il codice non forza mai il tipo di finestra.
- `PlayerSession` di processo: il player non viene più rilasciato a fine Activity. È voluto, ma un ExoPlayer resta vivo per tutto il processo (costo trascurabile da fermo).

## Domande aperte

Decise il 2026-11-27 (vedi la spec, D8, D10–D12). Resta solo l'aspetto della vista TV, con il default della spec (sfondo scuro, riga attiva in `primary`, logo in alto a sinistra), che si può ritoccare in revisione del Task 3.
