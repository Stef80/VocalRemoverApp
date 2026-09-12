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

Domanda: è possibile ottenere automaticamente il titolo del brano dalla
app sorgente, invece di chiedere sempre all'utente di nominare il file a
mano?

**Sì, esiste un modo legittimo**: Android espone i metadati "now playing"
(titolo, artista, album) tramite `MediaSessionManager` /
`MediaController.getMetadata()`, la stessa API usata da widget "now
playing", smartwatch e schermate di blocco. Per accedervi serve che
l'utente conceda il permesso speciale **"Accesso alle notifiche"**
(implementando un `NotificationListenerService`, requisito Android per
leggere le sessioni multimediali attive di altre app) — è un permesso
distinto da quello di cattura audio/MediaProjection, va richiesto a parte
e spiegato chiaramente in UI (va concesso una tantum dalle impostazioni
di sistema). Non è un aggiramento di alcuna protezione: sono metadati che
l'app sorgente espone volontariamente per la visualizzazione (notifica,
lock screen).

**Comportamento proposto**:
1. Se l'utente ha concesso l'accesso alle notifiche ed è disponibile una
   sessione multimediale attiva con metadati (titolo + artista) al
   momento dell'avvio della registrazione, il nome proposto è
   `"<titolo> - <artista>"` (sanificato per caratteri non validi nel
   filesystem), precompilato in una finestra di salvataggio.
2. L'utente può sempre modificare il nome proposto prima di confermare
   il salvataggio (mai un salvataggio "silenzioso" senza conferma).
3. **Fallback su nome manuale** quando: permesso non concesso, nessuna
   sessione attiva rilevata, metadati mancanti/vuoti, o l'utente ha
   scelto di non usare questa funzione — in questi casi si propone un
   nome generico basato su data/ora (es. `Registrazione_2026-09-12_1130`)
   comunque modificabile dall'utente.

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

### `NowPlayingMetadataReader` (nuovo, opzionale/facoltativo)

- `NotificationListenerService` + `MediaSessionManager.getActiveSessions()`
  per leggere titolo/artista della sessione multimediale attiva al
  momento dell'avvio registrazione.
- Se il permesso non è concesso, la funzione restituisce semplicemente
  "nessun metadato disponibile" (fallback nome manuale/generico) — non è
  un requisito bloccante per usare la funzione di registrazione.

### Pipeline di elaborazione: **nessuna nuova classe**

- Riusa integralmente `VocalRemover.removeVocals` (già esistente, già a
  blocchi per la memoria) sul file WAV appena registrato, esattamente
  come oggi fa con un file scelto dall'utente da storage. Nessuna
  necessità di una pipeline "live"/streaming a bassa latenza,
  backpressure, o instradamento audio speciale.

### UI (`MainActivity`, minimale)

- Un pulsante "Registra da un'altra app": mostra l'elenco delle sessioni
  audio attive (da `AudioManager.getActivePlaybackConfigurations()`),
  l'utente sceglie la sorgente, poi avvia il consenso MediaProjection.
- Durante la registrazione: indicatore di stato + pulsante "Ferma e
  elabora".
- Alla fine della registrazione: barra di progresso dell'elaborazione
  (stesso `onProgress` già usato oggi), poi finestra di salvataggio con
  nome precompilato (vedi sopra) ed editabile.
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
- Testi sincronizzati (Musixmatch o simile) — resta il sotto-progetto 3,
  non affrontato qui.
- Cattura da app di sistema privilegiate o cross-profilo utente.

## Nota sulle decisioni prese in autonomia

Questa spec è stata scritta in una sessione senza un ciclo interattivo
completo di approvazione (alcune domande sono state poste e risposte
dall'utente nel corso della conversazione, ma non è stata effettuata una
revisione formale riga-per-riga dell'intero documento). Prima di generare
il piano di implementazione dettagliato (`writing-plans`), confermare che
questa architettura "registra ora, elabora ed elenca dopo" rispecchi
correttamente l'intento, in particolare:
- l'assenza di riproduzione live è accettata come scelta definitiva;
- la funzione di nome automatico dai metadati "now playing" è desiderata
  come funzione opzionale (permesso extra "Accesso alle notifiche"), o si
  preferisce ometterla e chiedere sempre il nome manualmente per
  semplificare l'ambito.
