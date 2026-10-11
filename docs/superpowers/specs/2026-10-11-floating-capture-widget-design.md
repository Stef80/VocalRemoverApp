# Design: Widget flottante per gestire la registrazione da un'altra app

Data: 2026-10-11
Sotto-progetto correlato a `2026-09-12-system-audio-capture-vocal-removal-design.md`
(cattura audio di sistema, già implementata) e a `2026-10-10-import-song-screen-design.md`
(`ImportSongScreen`, dove il bottone "Avvia registrazione" è oggi uno stub
`showPreviewMessage`).

## Contesto e obiettivo

Oggi la cattura dell'audio di sistema (`SystemAudioCaptureService`, già
implementata) richiede che l'utente avvii la registrazione dall'interno di
VocalRemoverApp, poi vada manualmente sull'altra app per far partire la
canzone, e per fermare la registrazione può solo aprire la tendina delle
notifiche (notifica persistente del Foreground Service) o tornare all'app.

Questa spec introduce un **widget flottante (bubble)**, in stile "chat
heads", che resta visibile sopra qualunque altra app durante tutto il
processo, e permette all'utente di:
1. scegliere da un elenco l'app su cui registrare (la selezione imposta
   anche il filtro audio per-app già esistente, `addMatchingUid`, e apre
   quell'app in primo piano per comodità);
2. avviare e fermare la registrazione senza mai dover tornare manualmente
   a VocalRemoverApp;
3. essere riportato automaticamente in primo piano su VocalRemoverApp non
   appena la registrazione viene fermata, per seguire l'elaborazione e poi
   riascoltare il risultato.

**Non cambia la pipeline di cattura/elaborazione esistente** (
`SystemAudioCaptureService`, `VocalRemover.removeVocals`, salvataggio
MediaStore): questa spec aggiunge solo un nuovo punto di controllo overlay
che la orchestration, riusando la logica già implementata senza
modificarla.

## Architettura e flusso

Nuovo Foreground Service **`BubbleOverlayService`** (`engine/src/main/java/
com/example/vocalremover/overlay/`) che disegna un bubble trascinabile con
`WindowManager` e `TYPE_APPLICATION_OVERLAY`, indipendente da
`SystemAudioCaptureService` (che resta invariato e viene solo avviato/fermato
dal nuovo service tramite gli `ACTION_*` già esistenti).

Flusso:

1. L'utente preme "Avvia registrazione" nel `CaptureCard` di
   `ImportSongScreen` (sostituendo lo stub attuale `showPreviewMessage`).
2. Se manca il permesso overlay (`Settings.canDrawOverlays`), si apre
   `ACTION_MANAGE_OVERLAY_PERMISSION` con una spiegazione (stringa in
   `strings.xml`); se negato, il flusso bubble non parte e l'utente può
   comunque usare la registrazione classica dalla legacy UI (senza
   bubble, con sola notifica FGS).
3. `BubbleOverlayService` parte e mostra subito il bubble **collassato**
   (icona piccola trascinabile), prima ancora del consenso
   MediaProjection.
4. Tap sul bubble → si espande in un pannellino con un selettore dell'app
   target (riuso della stessa lista pacchetti recenti/candidati già
   presente in `MainActivity.setupCapturePackageSuggestions`, estratta in
   una funzione condivisa non duplicata) + un pulsante "Avvia".
5. Selezionare un'app dalla lista: imposta il target uid per il filtro
   `addMatchingUid` **e** porta quell'app in primo piano
   (`startActivity` con `FLAG_ACTIVITY_NEW_TASK`). Il bubble si ricolassa
   automaticamente per non ostruire l'altra app.
6. L'utente fa partire la canzone nell'altra app, poi riapre il bubble
   (sempre visibile sopra) e preme "Avvia registrazione": questo richiede
   il consenso di sistema `MediaProjectionManager`, che deve passare da
   un'Activity. Una nuova **`MediaProjectionConsentActivity`**
   trasparente (`Theme.Translucent.NoTitleBar`), lanciata dal service,
   chiede il consenso e torna immediatamente al bubble passando
   `resultCode`/`resultData` via broadcast locale, senza UI propria.
7. Consenso ottenuto → si avvia `SystemAudioCaptureService` (esistente,
   nessuna modifica) con il target uid scelto. Il bubble passa allo stato
   "Stop" con timer.
8. Tap su "Stop" → ferma `SystemAudioCaptureService` (pipeline di
   elaborazione invariata), il bubble si chiude, e `BubbleOverlayService`
   riporta VocalRemoverApp in primo piano (`FLAG_ACTIVITY_REORDER_TO_FRONT`)
   dove l'utente segue l'elaborazione e poi riascolta il risultato con la
   UI di riproduzione già esistente.

## Nuovi componenti e permessi

- **`BubbleOverlayService`**: gestisce `WindowManager` + vista bubble
  (drag, tap-to-expand/collapse), lo stato (`IDLE → APP_SELECTED →
  RECORDING → STOPPING`), e l'orchestration verso `SystemAudioCaptureService`
  tramite i broadcast/action già esistenti (`ACTION_STOP`,
  `ACTION_CAPTURE_*`). Non modifica `SystemAudioCaptureService`.
- **`MediaProjectionConsentActivity`**: trampolino trasparente, nessuna UI
  propria, finisce subito dopo aver propagato il risultato del consenso.
- **Permesso nuovo**: `SYSTEM_ALERT_WINDOW` in
  `engine/src/main/AndroidManifest.xml`, richiesto solo per il flusso
  bubble (la registrazione classica senza bubble non lo richiede).
- **Riuso**: la logica di lettura pacchetti candidati/recenti
  (`readRecentCapturePackages`, `readCaptureCandidatePackagesFromManifest`)
  viene estratta in una funzione condivisa richiamabile sia dalla legacy
  `MainActivity` sia dal bubble, per evitare duplicazione.

## Gestione errori

- Permesso overlay negato → messaggio esplicativo in `ImportSongScreen`,
  nessun bubble; resta disponibile il flusso esistente senza bubble.
- Consenso MediaProjection negato/annullato → il bubble torna allo stato
  `IDLE`, nessun crash.
- App target disinstallata o non più risolvibile tra selezione e avvio →
  il bubble mostra un errore e richiede una nuova selezione.
- Rotazione schermo o spegnimento display: il bubble, essendo un overlay
  di sistema e non un'Activity, resta attivo; la registrazione tramite
  `SystemAudioCaptureService` non ne risente (comportamento già verificato
  nell'architettura esistente).

## Testing

- Unit test JVM (`:engine` test) per la logica di stato del bubble
  (`BubbleOverlayState`, transizioni `IDLE → APP_SELECTED → RECORDING →
  STOPPING`), estratta in una classe pura testabile separata dalla
  view/`WindowManager`, seguendo il pattern già usato per
  `CaptureProcessingStateMachineTest`.
- Unit test per la funzione condivisa di estrazione pacchetti
  candidati/recenti (dopo il refactoring), riusando l'impostazione di
  `RecordingFileNamingTest`.
- Nessun instrumentation test automatico nuovo: `WindowManager` overlay e
  permessi di sistema sono difficili da testare in automatico; la verifica
  resta manuale su device reale (concessione permesso overlay, drag del
  bubble, consenso MediaProjection, stop → ritorno automatico all'app),
  da documentare come checklist manuale nel piano di implementazione.
