# Design: Cattura e rimozione vocale in tempo (quasi) reale da audio riprodotto da un'altra app

Data: 2026-09-12
Sotto-progetto: 2 di 3 (cattura audio di sistema), come già anticipato e
lasciato fuori scope in `2026-07-26-realtime-vocal-removal-design.md`
(sotto-progetto 1, streaming di un file caricato nell'app stessa).

## Contesto e obiettivo

L'utente vuole che, mentre una canzone sta suonando in un'altra app sul
device (es. Spotify, YouTube, un browser, un altro player), VocalRemoverApp
catturi quell'audio e ne rimuova la voce "dal vivo", riproducendo solo la
parte strumentale.

Questo è concettualmente diverso dal sotto-progetto 1 (streaming di un
file caricato nell'app): qui la sorgente audio è **esterna e non
controllata da noi**, quindi entrano in gioco permessi di sistema
(`MediaProjection`) e vincoli di piattaforma non aggirabili.

## Vincoli tecnici critici (verificati prima di progettare l'architettura)

Questi vincoli limitano sostanzialmente cosa è realisticamente costruibile
e determinano l'approccio raccomandato più sotto:

1. **Molte app bloccano esplicitamente la cattura.** Da Android 10 (Q) in
   poi, un'app sorgente può impostare
   `AudioAttributes.ALLOW_CAPTURE_BY_NONE`. Spotify, YouTube Music, Apple
   Music, Netflix e la maggior parte delle app di streaming con licenza
   lo fanno per policy DRM/copyright. **Con queste app la cattura non
   funzionerà mai**, indipendentemente da come implementiamo il resto:
   non è un bug nostro da risolvere, è un blocco di sistema che rispetta
   la scelta dell'app sorgente (aggirarlo violerebbe i Termini di
   Servizio, fuori discussione).
2. **Nessuna API per silenziare la sola sorgente catturata.**
   `AudioPlaybackCaptureConfiguration` è un'operazione di sola copia: il
   PCM originale continua a essere inviato normalmente
   all'altoparlante/cuffie. Non esiste un parametro di sistema per
   "cattura ma non riprodurre" lato sorgente. Se non gestito, l'utente
   sentirebbe **contemporaneamente** il mix originale (dalla sorgente) e
   il nostro strumentale (dal nostro output): risultato inascoltabile.
   - Mitigazione parziale nota: la cattura tramite
     `AudioPlaybackCaptureConfiguration` avviene a livello di mix PCM
     interno, **prima** dell'attenuazione del volume multimediale
     (`STREAM_MUSIC`). Quindi se l'utente abbassa il volume multimediale
     del device a zero, la sorgente diventa silenziosa ma il nostro
     processo continua a ricevere audio da elaborare. Il nostro output,
     però, per essere udibile deve usare un canale/attributo audio
     **diverso** da `STREAM_MUSIC` (altrimenti si silenzierebbe anche il
     nostro). Questo instradamento va validato empiricamente (vedi
     spike), non è garantito dalla documentazione ufficiale.
3. **Restrizioni in evoluzione su Android 14+.** Ci sono segnalazioni
   pubbliche (issue tracker Google) secondo cui, a partire da Android 14,
   la cattura audio di app di terze parti non di sistema può essere
   bloccata anche con permessi e configurazione corretti, come parte di
   un irrigidimento delle policy sulla cattura audio in background. Va
   verificato empiricamente sulle versioni Android effettivamente
   supportate dall'app prima di investire nell'implementazione completa.
4. **Foreground Service obbligatorio.** La cattura tramite
   `MediaProjection` richiede un Foreground Service di tipo
   `mediaProjection` (manifest + permesso
   `FOREGROUND_SERVICE_MEDIA_PROJECTION`) con notifica persistente
   visibile per tutta la durata della cattura, e consenso utente esplicito
   ad ogni sessione tramite il dialog di sistema di `MediaProjectionManager`.

## Conseguenza sull'ambito: raccomandazione

Dato che i punti 1-3 sono incerti/limitanti e non verificabili solo "sulla
carta", **raccomando di non procedere direttamente a un'implementazione
completa**, ma di validare prima con uno **spike tecnico mirato** (poche
ore/1-2 giorni, throwaway) i tre comportamenti critici:

- (a) la cattura produce PCM non silenzioso con una sorgente nota per NON
  bloccarla (es. un video HTML5 riprodotto in Chrome);
- (b) con una sorgente che blocca la cattura (es. Spotify) l'errore viene
  segnalato in modo pulito e rilevabile via API, senza crash;
- (c) abbassando `STREAM_MUSIC` a zero, la sorgente tace ma un nostro
  `AudioTrack` con attributi audio differenti resta udibile.

Se lo spike conferma (a)-(c), si procede con l'architettura descritta
sotto. Se (c) non è risolvibile in modo pulito, l'ambito realistico si
riduce a una modalità "cattura e riproduci con un piccolo ritardo" in cui
è comunque richiesto che l'utente silenzi manualmente la sorgente (stessa
limitazione, comunicata chiaramente in UI invece che nascosta).

## Architettura (post-spike)

```
Utente avvia "Cattura live" in VocalRemoverApp
        │
        ▼
MainActivity: MediaProjectionManager.createScreenCaptureIntent()
        │  (consenso utente via dialog di sistema)
        ▼
SystemAudioCaptureService (Foreground Service, type=mediaProjection)
        │
        ├─ AudioPlaybackCaptureConfiguration
        │     .addMatchingUsage(USAGE_MEDIA | USAGE_GAME | USAGE_UNKNOWN)
        │
        ├─ AudioRecord(config) ──▶ loop di lettura su thread dedicato
        │                              │ blocchi PCM stereo float
        │                              ▼
        │                      LiveVocalRemover (nuovo)
        │                        ├─ STFT a finestra scorrevole
        │                        │   (StftProcessor, invariato: nFft=4096,
        │                        │    hopLength=1024)
        │                        ├─ inferenza ONNX in sotto-chunk da 100
        │                        │   frame (stesso vincolo di CHUNK_FRAMES
        │                        │   già usato da VocalRemover)
        │                        ├─ StreamingIstft (già esistente)
        │                        └─ emette blocchi instrumentali pronti
        │                              │
        │                              ▼
        └─ AudioTrack (MODE_STREAM, attributi audio dedicati,
             non legati a STREAM_MUSIC — da confermare nello spike)
                   │
                   ▼
          Uscita udibile solo strumentale (utente ha abbassato
          manualmente il volume multimediale del device)
```

## Componenti

### `SystemAudioCaptureService` (nuovo)

- Foreground Service `mediaProjection`; riceve il risultato del consenso
  MediaProjection da `MainActivity` tramite
  `registerForActivityResult(StartActivityForResult())`.
- Notifica persistente ("Cattura audio in corso — tocca per fermare").
- Crea la configurazione di cattura e l'`AudioRecord` associato; loop di
  lettura in un thread/coroutine dedicato, inoltra i blocchi a
  `LiveVocalRemover`.
- Gestisce lo stop pulito (`AudioRecord.release()`,
  `MediaProjection.stop()`, rimozione notifica).
- Se `AudioRecord` non riceve mai dati validi (sorgente che blocca la
  cattura, es. Spotify) dopo un timeout breve, emette `onCaptureBlocked()`
  con un messaggio chiaro invece di restare bloccato in silenzio.

### `LiveVocalRemover` (nuovo, adatta la pipeline STFT/ONNX esistente per input continuo)

- Riusa lo stesso modello ONNX e `StftProcessor` già usati da
  `VocalRemover`, ma organizzato per un flusso continuo senza durata nota
  a priori (a differenza del `VocalRemover` batch attuale):
  - Ring buffer di ingresso alimentato dai blocchi PCM catturati.
  - STFT a finestra scorrevole non appena sono disponibili abbastanza
    campioni per un blocco di inferenza (stessi vincoli `CHUNK_FRAMES`
    già in `VocalRemover.kt`).
  - `StreamingIstft` esistente per l'overlap-add, così da emettere
    campioni strumentali finalizzati appena pronti.
- Callback: `onChunkReady(FloatArray)`, `onError`, `onUnderrun` — se
  l'inferenza è più lenta del tempo reale del dispositivo, notifica
  l'app per mostrare un avviso (stesso spirito di
  `onPerformanceWarning` già previsto nella spec del sotto-progetto 1),
  invece di accumulare un ritardo illimitato.

### UI (`MainActivity`, minimale)

- Un pulsante "Cattura live" che avvia il flusso di consenso
  `MediaProjection` e poi il servizio.
- Messaggio esplicito e permanente in UI quando la cattura è attiva:
  "Abbassa il volume multimediale del device per sentire solo lo
  strumentale" — comunica il limite noto invece di nasconderlo.
- Gestione esplicita dell'errore `onCaptureBlocked()`: messaggio tipo
  "Questa app non consente la cattura audio (protezione del contenuto)".

## Gestione errori

- **Sorgente blocca la cattura**: `onCaptureBlocked()` → messaggio UI
  dedicato, nessun crash, stop pulito del servizio.
- **Permesso MediaProjection negato dall'utente**: nessuna cattura
  avviata, messaggio UI, nessuna eccezione non gestita.
- **Inferenza troppo lenta rispetto al tempo reale**: `onUnderrun()` →
  UI mostra avviso "qualità ridotta / dispositivo lento"; l'audio non
  processato in tempo viene scartato in modo controllato (drop del
  blocco più vecchio) per evitare accumulo di ritardo illimitato.
- **App in background/servizio ucciso dal sistema**: la notifica
  persistente del Foreground Service è il meccanismo standard Android
  per prevenire il kill; se comunque il servizio termina, la UI rileva
  la disconnessione e torna allo stato "cattura non attiva".

## Testing

- **Spike preliminare** (vedi sopra): verifica manuale dei tre
  comportamenti critici (a)-(c) prima di procedere con il resto.
- **Unit test Kotlin**: `LiveVocalRemoverTest` con un flusso sintetico di
  campioni in arrivo a blocchi (dimensioni variabili, non allineate a
  `CHUNK_FRAMES`), verifica che l'output rimanga continuo (nessun
  click/discontinuità) e che il backpressure (`onUnderrun`) scatti nei
  casi simulati di inferenza lenta.
- **Test manuale end-to-end**: su dispositivo reale, con una sorgente
  nota per non bloccare la cattura (es. video in un browser), verifica
  soggettiva di qualità e latenza percepita; con Spotify, verifica che
  l'errore venga mostrato correttamente invece di un fallimento silente.

## Fuori scope

- Bypassare `ALLOW_CAPTURE_BY_NONE` o qualunque altra protezione DRM o di
  copyright: esplicitamente esclusa, non negoziabile.
- Testi sincronizzati (Musixmatch o simile) — resta il sotto-progetto 3,
  non affrontato qui.
- Silenziamento automatico/programmatico della sorgente catturata: non
  esiste un'API di sistema per farlo; l'unica leva disponibile è
  l'intervento manuale dell'utente sul volume multimediale, comunicato
  chiaramente in UI.
- Cattura da app di sistema privilegiate o cross-profilo utente.

## Nota sulle decisioni prese in autonomia

Questa spec è stata scritta senza un ciclo interattivo completo di
approvazione utente (l'utente non era disponibile durante la sessione).
Assunzioni esplicite fatte:
- Approccio "entrambe le modalità" implicito: si tenta la cattura di
  sistema, e se la sorgente la blocca l'errore viene comunicato
  chiaramente (non è stata implementata una modalità di fallback su
  microfono in questa spec — quella resterebbe un'estensione futura
  separata se richiesta esplicitamente).
- Prima di qualunque implementazione, è raccomandato validare lo spike
  tecnico descritto sopra, perché i vincoli di piattaforma (in
  particolare il punto 3, Android 14+) potrebbero rendere l'intera
  funzionalità non realizzabile in modo affidabile su alcuni dispositivi
  target.

**Da confermare con l'utente prima di procedere alla pianificazione
dell'implementazione (`writing-plans`)**: è d'accordo con il piano
"spike tecnico prima, poi implementazione completa solo se validato"? O
preferisce comunque procedere direttamente all'implementazione completa
accettando il rischio che parte della funzionalità risulti inutilizzabile
su certe combinazioni sorgente/versione Android?
