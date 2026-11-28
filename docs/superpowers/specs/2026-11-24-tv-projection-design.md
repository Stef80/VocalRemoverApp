# Proiezione dei testi sulla TV durante la riproduzione — Design

Data: 2026-11-24 (aggiornata il 2026-11-27 con le risposte alle domande aperte e il 2026-11-28 con l'esito del Task 0b)
Stato: decisioni dell'utente raccolte (grilling); fase 1 pronta per il piano, fase 2 solo abbozzata.

## Intento

Mentre un brano della Libreria è in riproduzione nel Player, l'utente vuole vedere **il testo a tutto schermo
sulla TV**, in stile karaoke: riga attiva grande ed evidenziata, qualche riga precedente e successiva, il logo
dell'app. Il telefono resta il telecomando (play/pausa, ±10 s, barra di avanzamento). Non si vogliono
sottotitoli né il mirroring dello schermo del telefono.

Contesto hardware dell'utente:
- TV LG (webOS): **niente Google Cast** integrato. L'utente ha però anche un Chromecast, quindi la fase 2 è utile.
- Telefoni di prova: Oppo Find X9 Pro (ColorOS) e Realme GT2 Pro (Realme UI). Entrambi hanno la proiezione
  Miracast di sistema; non è ancora verificato che espongano alle app un display con `FLAG_PRESENTATION`.

## Obiettivi, non-obiettivi, anti-obiettivi

Obiettivi:
- Fase 1: secondo schermo con `android.app.Presentation` su un display di presentazione (Miracast/"Screen
  Share" del produttore, HDMI/USB-C), con una schermata Compose "TV" a tutto schermo.
- Con la proiezione attiva, la riproduzione continua anche cambiando scheda o mandando l'app in background,
  con notifica di riproduzione e comandi.
- Fase 2 (piano separato, successivo): Google Cast con ricevitore web personalizzato.

Non-obiettivi:
- Sottotitoli WebVTT sul Default Media Receiver (scartati: l'utente vuole il karaoke a tutto schermo).
- AirPlay, DLNA, app webOS native.
- Ripristinare la proiezione dopo la morte del processo.
- Cambiare il comportamento del Player quando **non** si proietta (lasciare la scheda Player continua a mettere
  in pausa come oggi).

Anti-obiettivi (sarebbe un fallimento anche se "funziona"):
- La TV che mostra il mirroring del telefono invece della vista karaoke quando la proiezione è attiva.
- La TV che resta su una schermata congelata dopo che il brano si è fermato o è andato in errore.
- Audio che riparte da solo dopo una disconnessione della TV.

## Vincoli (da CLAUDE.md e dal codice attuale)

- `:engine` non dipende da `:ui`; i moduli parlano per interfacce, i concreti si cablano in `:ui`.
- Ogni testo visibile in `ui/src/main/res/values/strings.xml` (italiano), anche quello della notifica.
- Ogni schermata `:ui` mostra `R.drawable.logo_corallo_blu` accanto al titolo (vale anche per la vista TV).
- Backup: allowlist invariata. La fase 1 non persiste nulla.
- Nessun impatto sulla pipeline audio né sui backend ORT.
- minSdk 26: `Presentation`, `DisplayManager.DISPLAY_CATEGORY_PRESENTATION`, `TYPE_APPLICATION_OVERLAY`,
  `android.media.session.MediaSession` e `Notification.MediaStyle` esistono tutti dall'API 26 o prima.
- Fase 1: **nessuna dipendenza nuova** (niente `media3-session`, niente `androidx.media`, niente Play Services).
- Release con R8: la fase 1 non usa reflection; il test dell'APK release sul dispositivo resta obbligatorio
  (nuovo servizio e nuova finestra di sistema).
- La proiezione non richiede il permesso "Mostra sopra altre app" e deve funzionare anche se l'utente l'ha negato.
- Mai `FLAG_SECURE` sulla vista TV: sul Realme il display della TV non è sicuro e la vista resterebbe nera.
- Il `displayId` della TV cambia a ogni connessione: si cerca sempre con `DISPLAY_CATEGORY_PRESENTATION` e
  `DisplayListener`, mai salvato.
- Non ci si basa su un solo telefono: un test strumentato su un `VirtualDisplay` di presentazione gira su una
  matrice di marche e API (Firebase Test Lab), senza toccare `BackendBenchmarkTest`.

## Stato attuale (cosa tocca)

- `LibraryPlayer` (`:engine` `playback/`): ExoPlayer locale, solo main thread, `StateFlow<PlayerState>`. Non
  cambia.
- `PreviewPlayer` / `LibraryPreviewPlayer` (`:ui`): interfaccia già esistente sopra `LibraryPlayer`. Si riusa.
- `PlayerViewModel` (`:ui` `player/`): possiede il player, il brano aperto e i testi (`PlayerLyrics`), ed è
  legato a `MainActivity`; `onCleared()` rilascia il player. Per far vivere la riproduzione oltre l'Activity
  (notifica, Activity chiusa con Indietro) la sua logica passa in un oggetto di processo.
- `PlayerScreen`: `LyricsPanel`/`SyncedLyrics` sono privati e pensati per il telefono; la vista TV è un layout
  diverso che riusa il modello `PlayerLyrics` (`activeLine()`), non i composable del telefono.
- `ui/overlay/OverlayLifecycleOwner`: owner già usato per ospitare una `ComposeView` in una finestra creata da
  un Service. Si riusa per la Presentation.
- `POST_NOTIFICATIONS` è già dichiarato in `:engine`. `SYSTEM_ALERT_WINDOW` (bolla di cattura) non serve alla
  proiezione.
- `ui/src/androidTest` contiene già `TestAudioFiles` (WAV di prova) e `BackendBenchmarkTest`; `scripts/run-testlab.sh`
  oggi lancia tutta la suite strumentata, senza `--test-targets`.

## Approcci considerati

1. **Presentation su display di presentazione (scelto per la fase 1).** Zero dipendenze, zero server, riusa il
   modello dei testi; l'audio resta su ExoPlayer e lo instrada il sistema (con Miracast va alla TV, con HDMI
   all'uscita HDMI). Limite: funziona solo se il produttore espone il display Miracast come display di
   presentazione; va verificato sui due telefoni (Task 0 del piano).
2. **Google Cast con ricevitore web personalizzato (fase 2).** Il risultato più fedele su Chromecast/Google TV
   e la base per una futura app di ricevitore, ma richiede Play Services, Cast Developer Console, hosting HTTPS
   del ricevitore e un server HTTP locale; la TV LG non ha Cast, ma l'utente ha un Chromecast.
3. **Sottotitoli WebVTT sul Default Media Receiver.** Scartato dall'utente: niente karaoke a tutto schermo.

## Decisioni (valide per entrambe le fasi)

- **D1 – Contenuto sulla TV.**
  - `Synced`: karaoke. Riga attiva grande ed evidenziata al centro, 2 righe precedenti attenuate sopra,
    2 successive sotto; prima della prima riga nessuna riga attiva e le prime righe come "successive".
  - `Plain`: testo semplice grande che scorre lentamente; lo scorrimento è proporzionale alla posizione nel
    brano (`posizione / durata`), così segue pausa e seek.
  - `Instrumental` / `Unavailable`: titolo del brano e logo a tutto schermo.
  - `Loading` (testi ancora in download): logo, titolo e "Attendere".
  - Sempre presente il logo `logo_corallo_blu` accanto al titolo del brano.
- **D2 – Audio.** Fase 1: resta su `LibraryPlayer` (ExoPlayer) del telefono; dove esce lo decide il sistema.
  Fase 2: esce dalla TV, il telefono è muto e fa da telecomando.
- **D3 – Background e cambio scheda.** Con la proiezione attiva la riproduzione **continua** quando si cambia
  scheda o l'app va in background: servizio in foreground di tipo `mediaPlayback` con notifica di riproduzione
  (`MediaSession` di piattaforma + `Notification.MediaStyle`): indietro 10 s, play/pausa, avanti 10 s, "Termina
  proiezione". Senza proiezione il comportamento resta quello di oggi. Il servizio usa `START_NOT_STICKY`.
  **Limite accettato:** su ColorOS/Realme UI lo swipe dell'app dalle recenti uccide il processo (`o-kill`) anche con
  il servizio in foreground, e termina proiezione e riproduzione. Si documenta soltanto: niente contromisure né UI
  dedicate.
- **D4 – Proprietà del player.** Brano aperto, testi e player passano da `PlayerViewModel` a un `PlayerSession`
  di processo (posseduto da `VocalRemoverApplication`); `PlayerViewModel` diventa una facciata. Motivo: con la
  proiezione la riproduzione deve sopravvivere alla distruzione dell'Activity (Indietro) e la
  notifica deve comandarla senza Activity.
- **D5 – Chi ospita la Presentation** (confermata dal Task 0b). Il servizio in foreground, non l'Activity: una
  Presentation con contesto Activity si chiude con l'Activity. Il servizio usa `Presentation(service, display)` con il
  suo tipo di finestra predefinito (`TYPE_PRESENTATION`, o `TYPE_PRIVATE_PRESENTATION` su un display privato come quello
  virtuale dei test): **mai** `window.setType(...)`. Con `TYPE_APPLICATION_OVERLAY` fallisce con "Window type mismatch".
  Quindi **il permesso overlay non è un requisito**: la proiezione funziona anche se l'utente l'ha negato.
- **D6 – Disconnessione** (display rimosso, Wi-Fi caduto, cavo staccato): pausa sul telefono alla posizione
  raggiunta, proiezione terminata, servizio fermato, messaggio sul telefono. Nessuna ripresa automatica.
- **D7 – Nuovo brano con proiezione attiva.** Un brano scelto dalla Libreria va direttamente sulla TV: la
  proiezione resta attiva e la vista TV segue il brano aperto.
- **D8 – Avvio.** La proiezione parte solo su tocco dell'utente; non parte da sola quando compare un display.
  Il brano proiettato è sempre quello aperto nel Player.
- **D9 – Più display di presentazione.** Si usa il primo restituito da `DisplayManager` per la categoria
  presentazione. Se il display proiettato sparisce, vale D6 anche se ne compare un altro. L'id del display vale solo
  per la connessione corrente (cambia a ogni connessione) e non viene mai salvato.
  Limite noto: con più display di presentazione si usa il primo, e un display virtuale pubblico di un'altra app
  potrebbe essere scelto al posto della TV.
- **D10 – Eliminazione durante la proiezione: evitata, non gestita.** Mentre si proietta, la voce "Elimina"
  (`library_action_delete`) del menu dei brani della Libreria (`PlayableTracks`) è **disabilitata** (visibile ma
  non selezionabile) per tutti i brani, e torna attiva appena la proiezione termina. Se una conferma di
  eliminazione era già aperta, la conferma non elimina finché si proietta. La rinomina resta permessa e il titolo
  sulla TV si aggiorna.
- **D11 – File sparito per cause esterne** (cancellato da un'altra app, scheda SD tolta). Non c'è uno stato
  dedicato: è l'errore generico del player (`PlayerState.error` ≠ `null`). Con la proiezione attiva, la proiezione
  termina; sul telefono resta il messaggio d'errore già esistente sotto la barra (`player_error_unavailable` /
  `player_error_failed`) e compare un messaggio generico "proiezione terminata".
- **D12 – Pulsante "Proietta".** Nella testata di `PlayerScreen`, a fianco del titolo (confermato).

## Fase 1 — Presentation

Componenti (tutti in `:ui`, package `com.stef.vocalremover.ui.projection` salvo `PlayerSession`):

- `PlayerSession` (`ui/player/`): la logica attuale di `PlayerViewModel`, invariata, con un proprio scope sul
  main thread.
- `PresentationDisplaySource` (interfaccia) e `DisplayManagerPresentationDisplaySource` (concreto):
  `StateFlow<ProjectionDisplay?>` del primo display di presentazione, aggiornato con `DisplayListener`.
- `ProjectionController`: macchina a stati `NoDisplay` → `Ready(display)` → `Projecting(display)`, `start()`,
  `stop()`, eventi `DISCONNECTED` / `PLAYBACK_ERROR`; alla disconnessione mette in pausa `PlayerSession`, su un
  errore del player termina la proiezione (D11). Parla con il servizio solo attraverso l'interfaccia
  `ProjectionHost`.
- Libreria: la voce "Elimina" segue lo stato della proiezione (D10).
- `TvProjectionService` (foreground `mediaPlayback`): mostra `TvPresentation` sul display, ospita la `ComposeView`
  con `OverlayLifecycleOwner`, pubblica la `MediaSession` e la notifica.
- `TvLyricsScreen`: la vista TV (D1), più il modello puro `KaraokeLines` che sceglie le righe da mostrare.
- Telefono: pulsante "Proietta" nella testata di `PlayerScreen`, accanto al titolo, con tre stati:
  - nessun display: apre le impostazioni di proiezione di sistema (`Settings.ACTION_CAST_SETTINGS`) se esistono,
    altrimenti un messaggio che spiega di avviare la proiezione dal menu rapido del telefono;
  - display pronto: avvia la proiezione;
  - in proiezione: pulsante evidenziato con "Su <nome TV>" sotto il titolo; un tocco termina la proiezione.

Errori e cancellazioni:
- Permesso overlay negato: nessun effetto, la proiezione non lo usa.
- Notifiche negate (API 33+): il servizio parte in foreground lo stesso (Task 0b).
- Notifica su ColorOS: compare solo con audio reale in riproduzione; va verificata nel Task 4 con la riproduzione
  reale.
- Display sparito tra il tocco su "Proietta" e l'avvio del servizio, o `show()` rifiutato: proiezione terminata,
  nessun crash.
- Brano eliminato dall'app durante la proiezione: non può succedere, "Elimina" è disabilitata (D10).
- File sparito per cause esterne o altro errore del player: proiezione terminata e messaggio (D11).
- Morte del processo (compreso lo swipe dalle recenti su ColorOS/Realme UI): la Presentation sparisce con il
  processo e la TV torna al mirroring di sistema. Non si ripristina nulla; il servizio è `START_NOT_STICKY`.

### Test su più marche

Un test strumentato (`ui/src/androidTest`, `TvProjectionInstrumentedTest`) crea un `VirtualDisplay` con
`VIRTUAL_DISPLAY_FLAG_PRESENTATION` (surface di un `ImageReader`) e verifica il percorso reale
`ProjectionController` → `TvProjectionService` → `TvPresentation`:
- la vista compare su quel display (pixel disegnati nell'`ImageReader`; su API 30+ anche la finestra con quel
  `displayId`);
- resta dopo Home (UiAutomator `pressHome`);
- alla rimozione del display parte `DISCONNECTED`, la riproduzione va in pausa e il servizio si ferma;
- la notifica media è pubblicata durante la riproduzione.

Il test gira su Firebase Test Lab con una matrice di marche (Samsung, Pixel, Xiaomi/Oppo se disponibili) e API dalla 26
alla più recente, tramite una modalità `projection` di `scripts/run-testlab.sh`. Le corse benchmark escludono questa
classe e restano invariate. La verifica Miracast reale resta sui due telefoni dell'utente.

### Ramo di ripiego (superato: il Task 0 ha dato esito positivo)

- Nessuno dei due telefoni espone un display di presentazione via Miracast, ma l'HDMI/USB-C sì: la fase 1 si
  completa uguale, con il supporto limitato al cavo e documentato.
- Nemmeno l'HDMI è disponibile/utilizzabile: la fase 1 si ferma dopo il Task 0 e si torna dall'utente per
  decidere se anticipare la fase 2 (con il Chromecast dell'utente collegato alla TV LG).

## Fase 2 — Google Cast con ricevitore personalizzato (abbozzo)

Da pianificare in un documento separato dopo la fase 1:
- Nuovo modulo libreria `:cast` (dipende al più da `:engine` per `PlayerState`, mai da `:ui`): server HTTP locale
  che serve il WAV da `content://` con supporto `Range`, token casuale nel path, legato solo all'IP Wi-Fi, chiuso a
  fine sessione; i testi passano al ricevitore come JSON servito dallo stesso server o tramite un canale di
  messaggi custom.
- `CastPlayer` di `media3-cast` allineato alla 1.4.1 delle altre librerie Media3, dietro un adapter
  `PreviewPlayer` cablato in `:ui`, così `PlayerSession` non cambia.
- Ricevitore web personalizzato (HTML/JS) che mostra lo stesso karaoke di D1 e legge la posizione dal media
  element; `receiverAppId` registrato su Cast Developer Console (lo paga l'utente).
- **Hosting del ricevitore (deciso):** cartella `connections/cast-receiver/`, **fuori da `src/`**: Gradle non la
  compila e non finisce nell'APK. È pubblicata su GitHub Pages da un workflow GitHub Actions (Pages "da branch"
  supporta solo `/` o `/docs`, quindi serve il deploy da Actions con `actions/upload-pages-artifact` e
  `actions/deploy-pages` della sola cartella).
- **Da decidere in fase 2: dove va il codice Android di Cast** (server HTTP locale, `CastPlayer`, `OptionsProvider`).
  Vincoli:
  - `:engine` dipende da `:connections`: mettere Play Services / `media3-cast` in `:connections` li porterebbe
    dentro `:engine` (e la cartella del ricevitore lì non obbliga a mettere lì anche il codice Android);
  - il sender ha bisogno di `PlayerState` (in `:engine`): se stesse in `:connections` non potrebbe dipendere da
    `:engine` (ciclo); le strade sono un modulo `:cast` nuovo che dipende da `:engine`, oppure tipi propri del
    sender mappati in `:ui`;
  - la proposta di partenza è un modulo `:cast` (dipende da `:engine`, mai da `:ui`), cablato in `:ui`.
- Pulsante Cast nascosto senza Google Play Services; `OptionsProvider` istanziato per reflection, quindi regola
  keep R8 e test dell'APK release sul dispositivo.
- Stesse decisioni D1, D3 (il servizio in foreground resta necessario per tenere vivo il server), D6, D7, D8.
- L'utente ha accettato Play Services e il server sulla LAN.

## Domande aperte

Decise il 2026-11-27: pulsante "Proietta" nella testata (D12); eliminazione disabilitata durante la proiezione
(D10, D11); nessun avvio automatico (D8); ricevitore in `connections/cast-receiver/` con GitHub Pages (fase 2);
l'utente ha un Chromecast (fase 2 utile).

Restano (non bloccanti, tra parentesi il default del piano):
1. Orientamento e aspetto della vista TV (default: si adatta al display, che per una TV è orizzontale; sfondo scuro
   del tema, riga attiva nel colore `primary`, logo in alto a sinistra con il titolo del brano).
2. Fase 2: modulo del codice Android di Cast (vedi i vincoli nella sezione Fase 2; default proposto: `:cast`).
3. Solo se la matrice Test Lab lo mostra rotto: su API 26–29 una Presentation creata dal servizio potrebbe essere
   rifiutata (lo spike ha provato solo l'API 37). Le opzioni sono limitare la proiezione all'API 30+, oppure sotto
   l'API 30 usare `TYPE_APPLICATION_OVERLAY` con il permesso overlay. Non c'è un default: decide l'utente.

## Esito Task 0

### 0a – Verifica manuale (2026-11-27): superata, si prosegue con Miracast

Mirroring di sistema verso TV LG (webOS, Screen Share, nome Miracast "cameraG"), letto con `adb shell dumpsys display`
a mirroring attivo.

| | Oppo Find X9 Pro (ColorOS) | Realme GT2 Pro (Realme UI) |
|---|---|---|
| Display della TV esposto | sì, `displayId 3`, `type WIFI` | sì, `displayId 3`, `type WIFI` |
| Flag | `FLAG_SECURE`, `FLAG_PRESENTATION`, `FLAG_TRUSTED` | `FLAG_PRESENTATION`, `FLAG_TRUSTED` (niente `FLAG_SECURE`) |
| Modalità | 1920×1080 @ 60 Hz, 320 dpi | 1920×1080 @ 60 Hz, 320 dpi |
| Audio del brano in riproduzione | esce dalla TV | esce dalla TV |
| Ritardo audio/immagine percepito | nessuno | nessuno |
| Schermo del telefono spento | il mirroring resta attivo | il mirroring resta attivo |

Note:
- Sul Realme il display della TV non ha `FLAG_SECURE`: la vista TV non deve mai usare `WindowManager.LayoutParams.FLAG_SECURE`,
  altrimenti su quel display resterebbe nera.
- In una seconda lettura sull'Oppo la TV non compariva (mirroring non attivo o caduto in quel momento): conferma che il
  caso "display rimosso" (`DISCONNECTED` → pausa) va gestito e verificato sul dispositivo.
- HDMI/USB-C non provato (non necessario per proseguire).
- Il Realme vede anche un secondo ricevitore Miracast ("Samsung Q60AA 55 TV"), utilizzabile come prova aggiuntiva.

### 0b – Spike

Prova 2026-11-27/28 su Oppo Find X9 Pro (ColorOS, API 37) con app usa-e-getta `com.stef.vrspike` (scratchpad, fuori
dal repo), mirroring verso la LG attivo. Esito letto con `dumpsys window` (displayId della finestra), logcat e
conferma a vista sulla TV.

| Scenario | A: `Presentation(activity, display)` | B: `Presentation(service, display)` da servizio `mediaPlayback` |
|---|---|---|
| In primo piano | ✅ | ✅ |
| Dopo Home | ✅ | ✅ |
| Schermo spento (B: > 2 min) | ✅ | ✅ |
| Activity chiusa con Indietro | ❌ la Presentation si chiude con l'Activity | non provato (atteso ✅: non dipende dall'Activity) |
| Swipe dalle recenti | non provato (atteso ❌, vedi B) | ❌ ColorOS uccide l'intero processo |
| Notifiche negate | n/a | ✅ il servizio parte in foreground lo stesso |

Note:
- **Tecnica che funziona dal servizio:** `Presentation(service, display)` con il suo tipo di finestra predefinito
  (`TYPE_PRESENTATION`, 2037). Impostare `TYPE_APPLICATION_OVERLAY` (2038) con `window.setType` fallisce con
  `IllegalArgumentException: Window type mismatch` (il window context creato dalla Presentation è di tipo 2037). Quindi
  **il permesso "Mostra sopra altre app" non serve**: D5 va aggiornata di conseguenza.
- **Swipe dalle recenti:** ColorOS rimuove il task e uccide il processo anche con servizio in foreground
  `mediaPlayback`, notifiche concesse e (tentato) lucchetto nelle recenti: `ApplicationExitInfo reason=13
  (OTHER KILLS BY SYSTEM), description=o-kill(40)`. Non dipende dal codice; il servizio viene poi riavviato dal sistema
  senza foreground né finestra. Conseguenze: lo swipe dalle recenti termina proiezione e riproduzione (limite
  documentato); il servizio deve usare `START_NOT_STICKY` per non tornare in uno stato a metà.
- **Notifica:** con le notifiche concesse è pubblicata (`importance=3`), ma ColorOS non la mostra nella tendina finché
  la `MediaSession` non riproduce davvero audio (lo spike non riproduce). Da riverificare nel Task 4 con la riproduzione
  reale.
- Con il mirroring attivo il telefono resta `mWakefulness=Awake` anche a schermo spento.
- Il `displayId` della TV cambia a ogni connessione (visti 3 e 5): va sempre cercato con
  `DisplayManager.getDisplays(DISPLAY_CATEGORY_PRESENTATION)` / `DisplayListener`, mai salvato.
- Realme GT2 Pro non provato nella 0b (Realme UI deriva da ColorOS: comportamento atteso uguale; si verifica nel Task 6).

**Decisione:** si prosegue con D5 (Presentation dal servizio in foreground), senza permesso overlay: sopravvive a Home,
cambio scheda, schermo spento e chiusura dell'Activity. Lo swipe dalle recenti su ColorOS/Realme UI ferma tutto ed è
accettato come limite.
