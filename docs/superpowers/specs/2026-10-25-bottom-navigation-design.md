# Design: footer di navigazione tra Libreria, Cattura e Player

Data: 2026-10-25
Dipende da: `2026-10-18-library-list-design.md`, `2026-10-24-library-player-actions-design.md`,
`2026-10-18-capture-trim-editor-design.md`.

## Obiettivo

Un footer (`NavigationBar` Material 3) con tre voci — **Libreria**, **Cattura**, **Player** — per
passare con un tocco tra `LibraryRoute`, `ImportSongScreen` e `PlayerScreen`. Dopo lo splash si
apre la Libreria. Oggi la navigazione è una catena di `if/else` su flag (`showImportScreen`,
`showPlayer`) in `MainActivity` e ogni schermata ha la sua freccia indietro / i suoi pulsanti per
raggiungere le altre: il footer li sostituisce.

## Decisioni prese

- **Player senza brano**: la voce Player è **disabilitata** finché non si apre un brano dalla
  Libreria (`playerViewModel.state == null`). Niente stato vuoto, niente "ultimo brano" persistito.
- **Riproduzione al cambio scheda**: **pausa** uscendo dalla scheda. Uscendo da Player si chiama
  `playerViewModel.leave()` (come oggi); uscendo da Cattura si mette in pausa l'anteprima. I due
  audio non si sovrappongono mai.
- **Navigazione a stato, senza librerie**: un `enum` salvato con `rememberSaveable`. Scartati
  Navigation Compose / Navigation 3 (dipendenza e cerimonia per tre schede piatte; l'apertura dal
  bubble va gestita a mano comunque) e `HorizontalPager` (lo swipe confligge con lo slider del
  Player e con le maniglie dell'editor).
- **Rimossi**: frecce indietro di Cattura e Player, pulsante "Importa canzone" e link
  "Apri schermata legacy" in Libreria e Player.

## Fuori scope

- Rimozione della `MainActivity` legacy in `:engine`: resta nel codice ma non è più raggiungibile
  dall'app (va tolta in un intervento separato, insieme a layout e risorse che usa solo lei).
- Mini-player nella Libreria, riproduzione in background, badge sulle voci del footer.
- Cambio automatico di scheda a fine elaborazione: si resta su Cattura con lo snackbar di oggi.

## `:ui`

### `AppNavigation.kt` (nuovo, package `com.stef.vocalremover.ui`)

```kotlin
enum class AppTab { Library, Capture, Player }

/** Regole pure, testabili in JVM. */
object AppNavigation {
    /** La scheda effettiva: Player senza brano ricade sulla Libreria (es. dopo process death). */
    fun resolve(selected: AppTab, hasTrack: Boolean): AppTab
    /** Tasto Indietro: da Cattura/Player alla Libreria; dalla Libreria null = lascia uscire l'app. */
    fun back(current: AppTab): AppTab?
}

@Composable
fun AppBottomBar(selected: AppTab, playerEnabled: Boolean, onSelect: (AppTab) -> Unit)
```

- `AppBottomBar`: `NavigationBar` con tre `NavigationBarItem` (icona + etichetta sempre visibile).
  La voce Player ha `enabled = playerEnabled`. Ritoccare la scheda già selezionata non fa nulla.
- Icone: tre vector drawable nuovi in `ui/src/main/res/drawable` — `ic_tab_library`
  (lista/libreria musicale), `ic_tab_capture` (microfono/registra), `ic_tab_player` (nota/play) —
  come gli attuali `ic_play`/`ic_pause` (nessuna libreria di icone nel progetto).
- Stringhe in `strings.xml`: `tab_library` ("Libreria"), `tab_capture` ("Cattura"),
  `tab_player` ("Player").
- Insets: le schermate applicano già `safeDrawingPadding()` al loro `Scaffold`, quindi la
  `NavigationBar` usa `windowInsets = WindowInsets(0)` per non contare due volte la barra di sistema.

### `MainActivity`

- `showImportScreen` / `showPlayer` sostituiti da `var tab by rememberSaveable { mutableStateOf(AppTab.Library) }`.
- Ordine di rendering invariato nei casi a tutto schermo: splash → editor (`editorState != null`)
  → altrimenti la scheda `AppNavigation.resolve(tab, player != null)`. Splash ed editor **non**
  mostrano il footer; chiudere l'editor torna su Cattura.
- Una sola funzione `selectTab(new)` usata da footer, Indietro, Libreria e bubble:
  - se si lascia Player → `playerViewModel.leave()`;
  - se si lascia Cattura → `importViewModel.pausePreview()`;
  - poi `tab = new`.
- Tocco su un brano in Libreria → `playerViewModel.open(track)` + `selectTab(Player)`.
- `openImportScreenRequest` (fine registrazione dal bubble, `onCreate`/`onNewIntent`) →
  `selectTab(Capture)`; l'editor si apre sopra come oggi via `loadPendingCapture(openEditor = true)`.
- Brano aperto eliminato dalla Libreria → `player` diventa `null`: la voce Player si disabilita
  (si è già sulla Libreria, l'unico punto da cui si elimina).
- Indietro: `BackHandler(enabled = tab != Library)` a livello di `MainActivity` →
  `selectTab(AppTab.Library)`. Dalla Libreria il sistema chiude l'app.

### `ImportViewModel`

- Nuovo `fun pausePreview() = player.pause()` (nessun effetto se non sta suonando).

### Schermate

Ogni schermata riceve uno slot `bottomBar: @Composable () -> Unit = {}` passato al proprio
`Scaffold`, così snackbar e padding restano gestiti da lei.

- **`LibraryRoute` / `LibraryScreen`**: rimossi `onOpenImportScreen`, `onOpenLegacyScreen`, il
  pulsante "Importa canzone" e il link legacy; la lista occupa lo spazio liberato.
- **`ImportSongScreen`**: rimossi `onBack`, il `BackHandler` e la freccia in `ImportTopBar` (restano
  logo + titolo).
- **`PlayerScreen`**: rimossi `onNavigateBack`, `BackHandler`, freccia nell'header,
  `onOpenImportScreen`, `onOpenLegacyScreen` e i due pulsanti in fondo. La `bottomBar` del
  `Scaffold` diventa una `Column` con la riga dei controlli (`<<`, `>`, `||`, `>>`) sopra il footer.
- Stringhe non più usate (`player_import_song`, `player_open_legacy_screen`,
  `control_back_description` se nessun altro la usa, es. `TrimEditorScreen`) vanno rimosse da
  `strings.xml` dopo aver verificato i riferimenti; il lint `UnusedResources` lo conferma.

## Test

- **JVM (`ui/src/test`)**: `AppNavigationTest` — `resolve` (Player senza brano → Libreria, altre
  schede invariate), `back` (Cattura/Player → Libreria, Libreria → null);
  `ImportViewModelTest` — `pausePreview` mette in pausa il player finto.
- Aggiornare i test/preview che usano i parametri rimossi.
- Build e lint dei tre flavor: `gradle :ui:assembleCpuDebug :ui:assembleWebgpuDebug :ui:assembleQnnDebug`,
  `gradle :ui:lintCpuDebug :ui:lintWebgpuDebug :ui:lintQnnDebug`, `gradle :ui:testCpuDebugUnitTest`.
- Verifica manuale su dispositivo: avvio → Libreria con Player disabilitato; tocco brano → Player
  attivo e in riproduzione; passaggio a Libreria → pausa; registrazione dal bubble → si riapre
  Cattura con l'editor senza footer; rotazione mantiene la scheda; Indietro da Cattura/Player →
  Libreria, poi esce.
