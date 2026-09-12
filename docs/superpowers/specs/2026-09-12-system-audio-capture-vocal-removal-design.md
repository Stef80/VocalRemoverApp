# Design: Cattura audio da un'altra app, rimozione voce ed elaborazione differita (non live)

Data: 2026-09-12 (revisione 2 — cambio di piano rispetto alla revisione 1)
Sotto-progetto: 2 di 3 (cattura audio di sistema), come già anticipato e
lasciato fuori scope in `2026-07-26-realtime-vocal-removal-design.md`
(sotto-progetto 1, streaming di un file caricato nell'app stessa).

## Storia della revisione

La revisione 1 di questa spec (stessa data, sostituita) proponeva una
modalità di ascolto **live**: cattura e riproduzione dello strumentale in
tempo (quasi) reale, mentre la sorgente continuava a suonare. Quell'
approccio aveva un problema architetturale irrisolvibile in modo pulito:
nessuna API Android permette di silenziare la sola sorgente catturata,
quindi l'utente avrebbe sentito contemporaneamente originale e
strumentale.

**Cambio di piano**: l'app non riproduce nulla in tempo reale. **Registra**
l'audio catturato dall'altra app, lo elabora (rimozione voce) **dopo** che
la registrazione è terminata, riusando la pipeline batch già esistente
(`VocalRemover.removeVocals`), e salva il risultato come file riproducibile
in seguito con la UI di riproduzione già presente nell'app. Questo elimina
del tutto il problema del doppio audio: la sorgente continua a suonare
normalmente durante la registrazione (comportamento atteso, come un
qualunque screen recorder con audio), e non c'è nessuna riproduzione
simultanea da gestire.

## Contesto e obiettivo

L'utente vuole poter registrare l'audio riprodotto da un'altra app sul
device (es. un video in un browser, un player locale — non sorgenti con
DRM che bloccano la cattura, vedi vincoli sotto), rimuovere la voce, e
riascoltare in seguito il risultato strumentale all'interno di
VocalRemoverApp.

**Questa è una funzione aggiuntiva, non un sostituto.** Il flusso attuale
dell'app — caricare un file audio già presente sul device tramite il
selettore file esistente ed elaborarlo con `VocalRemover.removeVocals` —
**resta invariato e continua a essere disponibile**. Le due modalità
coesistono e coprono due situazioni diverse:
- il brano è già disponibile come file sul dispositivo → si carica
  direttamente (flusso esistente, nessuna modifica);
- il brano è disponibile solo "al volo", in riproduzione in un'altra app
  (streaming, video online, ecc.) e non esiste come file scaricabile →
  si registra dal vivo mentre suona (nuovo flusso di questa spec), per
  poi elaborarlo esattamente con la stessa pipeline di rimozione voce.

In UI questo si traduce in due punti di ingresso distinti e paralleli
(vedi sezione UI), non in una sostituzione del selettore file esistente.

## Vincoli tecnici critici (invariati rispetto alla revisione 1, restano validi)

1. **Molte app bloccano esplicitamente la cattura.** Da Android 10 (Q) in
   poi un'app sorgente può impostare
   `AudioAttributes.ALLOW_CAPTURE_BY_NONE` (il default di sistema, se
   l'app non dice nulla, è invece "cattura permessa" — vedi sezione
   successiva). Spotify, YouTube Music, Apple Music, Netflix e la
   maggior parte delle app di streaming con licenza lo impostano per
   policy DRM/copyright: **con queste app la cattura non funzionerà
   mai**, indipendentemente da come implementiamo il resto. Non è un bug
   nostro da risolvere, è un blocco di sistema che rispetta la scelta
   dell'app sorgente (aggirarlo è esplicitamente fuori discussione).
2. **Restrizioni in evoluzione su Android 14+.** Segnalazioni pubbliche
   indicano che la cattura di app di terze parti non di sistema può
   essere bloccata anche con permessi e configurazione corretti. Va
   verificato empiricamente sulle versioni Android effettivamente
   supportate prima di investire nell'implementazione completa.
3. **Foreground Service obbligatorio.** La cattura tramite
   `MediaProjection` richiede un Foreground Service di tipo
   `mediaProjection` (manifest + permesso
   `FOREGROUND_SERVICE_MEDIA_PROJECTION`) con notifica persistente per
   tutta la durata della registrazione, e consenso utente esplicito ad
   ogni sessione tramite il dialog di sistema di `MediaProjectionManager`.

Il vincolo "nessuna API per silenziare la sola sorgente catturata" **non
si applica più**: non essendoci riproduzione simultanea da parte nostra,
non c'è nulla da silenziare.

## Come determinare quali sorgenti permettono la cattura (a runtime, non una lista fissa)

Il default di Android è "cattura permessa": un'app deve impostare
esplicitamente il blocco per impedirla. Non ha senso hardcodare una
whitelist di app nella nostra app (la policy può cambiare a ogni update
della sorgente). Approccio a runtime:

- **`AudioPlaybackCaptureConfiguration.Builder.addMatchingUid(uid)`**:
  invece di catturare genericamente tutto l'audio di sistema con usage
  MEDIA/GAME/UNKNOWN, filtriamo per l'UID di una singola app scelta
  dall'utente (elenco ottenuto da
  `AudioManager.getActivePlaybackConfigurations()`, che espone le sessioni
  audio attive con il relativo UID/package). Questo evita che il segnale
  catturato sia "sporco" per interferenza di altre app che suonano nello
  stesso momento (notifiche, altri player) — è un problema di pulizia del
  segnale, non legato al fatto di sentire o meno l'audio originale (che
  qui non è più un problema, vedi sopra).
- **Rilevamento a silenzio**: se la sorgente scelta blocca la cattura,
  `AudioRecord` non genera un errore, riceve semplicemente PCM a zero. Si
  legge un breve buffer di verifica (es. 300-500 ms) dopo l'avvio: se il
  livello RMS resta sotto soglia mentre la sorgente sta sicuramente
  riproducendo, si conclude "sorgente non catturabile" e si notifica
  `onCaptureBlocked()` in modo pulito invece di registrare minuti di
  silenzio inutile.

## Architettura

```
Utente sceglie l'app sorgente da registrare (da sessioni audio attive)
        │
        ▼
MainActivity: MediaProjectionManager.createScreenCaptureIntent()
        │  (consenso utente via dialog di sistema)
        ▼
SystemAudioCaptureService (Foreground Service, type=mediaProjection)
        │
        ├─ AudioPlaybackCaptureConfiguration.addMatchingUid(uid scelto)
        ├─ AudioRecord(config) → loop di lettura su thread dedicato
        │      │ verifica iniziale di silenzio (300-500ms) → onCaptureBlocked() se bloccata
        │      ▼
        │  scrittura incrementale su file WAV grezzo
        │  (per evitare di tenere in memoria l'intera registrazione
        │   durante sessioni lunghe)
        │
        ▼ (utente ferma la registrazione)
   File WAV grezzo completo
        │
        ▼
   VocalRemover.removeVocals(...)   ← pipeline batch già esistente, INVARIATA
        │  (stessa elaborazione a blocchi già usata oggi per i file caricati)
        ▼
   File strumentale risultante, salvato con nome (vedi sezione naming)
        │
        ▼
   Riproducibile con AudioPlayer esistente, come un file caricato normalmente
```

## Naming del file salvato

Per mantenere la semplicità, niente rilevamento automatico del titolo dai
metadati "now playing" della sorgente (avrebbe richiesto un permesso
extra — "Accesso alle notifiche" — e una nuova componente
`NotificationListenerService` solo per questo). **Il nome del file è
sempre inserito manualmente dall'utente** in una finestra di salvataggio
mostrata al termine dell'elaborazione, con un nome generico basato su
data/ora precompilato come punto di partenza modificabile (es.
`Registrazione_2026-09-12_1130`), coerente con l'assenza di editing dei
nomi già presente altrove nell'app.

## Componenti

### `SystemAudioCaptureService` (nuovo)

- Foreground Service `mediaProjection`; riceve il risultato del consenso
  MediaProjection da `MainActivity` tramite
  `registerForActivityResult(StartActivityForResult())`.
- Notifica persistente ("Registrazione audio in corso — tocca per
  fermare").
- Crea `AudioPlaybackCaptureConfiguration` filtrata per UID scelto,
  apre `AudioRecord`, esegue la verifica iniziale di silenzio, poi scrive
  incrementalmente su file WAV grezzo fino allo stop.
- Gestisce lo stop pulito (`AudioRecord.release()`,
  `MediaProjection.stop()`, chiusura file, rimozione notifica).

### Pipeline di elaborazione: **nessuna nuova classe**

- Riusa integralmente `VocalRemover.removeVocals` (già esistente, già a
  blocchi per la memoria) sul file WAV appena registrato, esattamente
  come oggi fa con un file scelto dall'utente da storage. Nessuna
  necessità di una pipeline "live"/streaming a bassa latenza,
  backpressure, o instradamento audio speciale.

### UI (`MainActivity`, minimale)

- Il flusso esistente di caricamento file da storage **resta invariato e
  visibile come oggi** (es. un pulsante "Carica file").
- Si aggiunge un secondo pulsante, alla pari del primo, **"Registra da
  un'altra app"**: mostra l'elenco delle sessioni audio attive (da
  `AudioManager.getActivePlaybackConfigurations()`), l'utente sceglie la
  sorgente, poi avvia il consenso MediaProjection.
- Durante la registrazione: indicatore di stato + pulsante "Ferma e
  elabora".
- Alla fine della registrazione: barra di progresso dell'elaborazione
  (stesso `onProgress` già usato oggi, condiviso con il flusso di
  caricamento file), poi finestra di salvataggio con nome inserito
  manualmente (vedi sopra).
- Gestione esplicita dell'errore `onCaptureBlocked()`: messaggio tipo
  "Questa app non consente la registrazione audio (protezione del
  contenuto)".

## Gestione errori

- **Sorgente blocca la cattura**: `onCaptureBlocked()` → messaggio UI
  dedicato, stop pulito del servizio, nessun file vuoto/silenzioso
  salvato.
- **Permesso MediaProjection negato dall'utente**: nessuna registrazione
  avviata, messaggio UI, nessuna eccezione non gestita.
- **Permesso "Accesso alle notifiche" non concesso**: nessun blocco,
  semplicemente niente nome automatico proposto (fallback nome
  generico/manuale).
- **Spazio di archiviazione insufficiente durante la registrazione**:
  interruzione controllata, messaggio UI, file parziale scartato.
- **Elaborazione (`VocalRemover.removeVocals`) fallisce**: stesso
  comportamento di errore già esistente oggi per un file caricato
  normalmente (nessuna modifica necessaria a questa parte).

## Testing

- **Spike preliminare** (ridotto rispetto alla revisione 1, non serve più
  validare il silenziamento della sorgente): verificare (a) che la
  cattura produca PCM non silenzioso con una sorgente nota per non
  bloccarla, e (b) che con una sorgente che blocca la cattura (es.
  Spotify) l'errore venga rilevato e segnalato in modo pulito.
- **Unit test Kotlin**: verifica della sanificazione del nome file
  proposto (caratteri non validi, stringa vuota → fallback), verifica
  che il fallback al nome generico scatti correttamente quando i
  metadati non sono disponibili.
- **Test manuale end-to-end**: registrazione di una sorgente compatibile,
  elaborazione, salvataggio con nome corretto, riproduzione successiva
  tramite la UI esistente.

## Fuori scope

- Bypassare `ALLOW_CAPTURE_BY_NONE`, agire a livello di HAL/driver audio,
  o qualunque altro aggiramento di protezioni DRM/copyright: esplicitamente
  escluso, non negoziabile.
- Riproduzione live/simultanea dello strumentale mentre la sorgente sta
  ancora suonando: esplicitamente abbandonata con questa revisione.
- Nome file automatico dai metadati "now playing" (`MediaSessionManager`
  + permesso "Accesso alle notifiche"): scartato per mantenere la
  semplicità, il nome è sempre inserito manualmente dall'utente.
- Testi sincronizzati (Musixmatch o simile) — resta il sotto-progetto 3,
  non affrontato qui.
- Cattura da app di sistema privilegiate o cross-profilo utente.
- Rimozione o modifica del flusso esistente di caricamento file da
  storage: resta invariato, questa spec aggiunge solo un secondo punto di
  ingresso parallelo.

## Nota sulle decisioni prese in autonomia

Questa spec riflette le decisioni prese esplicitamente dall'utente nel
corso della conversazione:
- passaggio da modalità live a "registra ora, elabora dopo";
- nome file sempre manuale, niente rilevamento automatico da metadati;
- il flusso di caricamento file esistente resta disponibile, la
  registrazione da altra app è un'opzione aggiuntiva, non sostitutiva.

Resta da confermare solo il punto tecnico non ancora deciso dall'utente:
se procedere prima con lo spike di validazione ridotto (vedi sezione
Testing) o passare direttamente al piano di implementazione completo
accettando il rischio residuo sui vincoli 1-2.
