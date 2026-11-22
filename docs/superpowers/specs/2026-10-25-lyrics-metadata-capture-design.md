# Metadati del brano dalla cattura e download dei testi

## Obiettivo

Quando l'utente registra l'audio di un'altra app (YouTube, Spotify, ...), riconoscere il brano
dalla sessione multimediale dell'app sorgente, stimare in che punto del brano inizia la
registrazione e, dopo il salvataggio, scaricare in background il testo sincronizzato da
LRCLIB, salvandolo in un formato neutro pronto per il player.

Questa prima iterazione **non** mostra il testo nel player, non gestisce i file importati e
non offre correzioni manuali. L'unico effetto visibile è il nome del brano precompilato con
"Artista - Titolo" e il dialogo che chiede l'accesso alle notifiche.

## Risultati dello spike (branch `worktree-spike-media-session`)

- Spotify e YouTube espongono titolo, artista e durata nella sessione multimediale; Spotify
  anche album e `MEDIA_ID`. YouTube non sempre ha una sessione all'avvio della cattura: serve
  l'ascolto delle nuove sessioni.
- Posizione nel brano al primo campione registrato: errore misurato circa ±0,1 s, usando
  `AudioRecord.getTimestamp` e lo stato PLAYING della sessione letto durante la registrazione.
  La posizione di pausa non è un riferimento affidabile.
- La durata è decisiva per scegliere la versione giusta su LRCLIB (es. 20 versioni dello
  stesso brano con durate fra 374 e 427 s).

## Architettura

Dipendenze: `:ui` → `:engine` → `:connections`. `:engine` non dipende da `:ui`; `:ui` non
dipende da `:connections` e non ne vede i tipi.

### Modulo `:connections`

- Libreria Android senza flavor, namespace e package `com.stef.vocalremover.connections`.
- Permesso `INTERNET` nel suo manifest (si unisce a quello dell'app).
- Dipendenze: OkHttp, kotlinx.serialization; nei test MockWebServer.
- `:engine` la include con `implementation(project(":connections"))`.

API pubblica:

```kotlin
interface LyricsProvider {
    suspend fun find(query: TrackQuery): LyricsResult
}

data class TrackQuery(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
)

sealed interface LyricsResult {
    data class Found(val lyrics: TimedLyrics, val sourceId: String) : LyricsResult // es. "lrclib:761859"
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
    data class RetryLater(val afterMs: Long?) : LyricsResult
    data class Failed(val reason: String) : LyricsResult
}

data class TimedLyrics(val lines: List<LyricLine>, val plainText: String?)
data class LyricLine(val startMs: Long, val text: String)

object Connections {
    fun lyricsProvider(context: Context): LyricsProvider
}
```

Parte interna (`internal`): `LrclibLyricsProvider`, parser LRC, pulizia dei titoli.

Ricerca in tre passi, ognuno eseguito solo se il precedente ha dato 404, con 300 ms di pausa
fra le richieste:

1. `GET /api/get` con titolo, artista, album e durata grezzi (durata in secondi, 1–3600).
2. `GET /api/get` con il titolo ripulito (rimossi suffissi come "- 2024 Remaster",
   "(Official Video)", "(feat. ...)").
3. `GET /api/search` con titolo ripulito e artista, scegliendo il primo risultato con durata
   entro ±2 s (senza durata: il primo risultato).

Regole HTTP:

- User-Agent `VocalRemover/<versionName> (<contatto>)`; il contatto viene dalla proprietà
  Gradle `lrclib.contact`, impostata dall'utente, mai scritta nel codice.
- Prima di leggere JSON si controlla il `Content-Type`: alcuni errori arrivano in testo
  semplice.
- `instrumental: true` → `Instrumental`; `syncedLyrics` → righe dal parser LRC;
  solo `plainLyrics` → `Found` con `lines` vuote e `plainText`.

| Caso | Risultato |
|---|---|
| `IOException` o timeout | `RetryLater(null)` |
| 404 su tutti e tre i passi | `NotFound` |
| 429 o 5xx (anche in testo semplice) | `RetryLater(Retry-After in ms, o null)` |
| Altri 4xx, JSON non valido con 200 | `Failed` |

Rinviati: sincronizzazione parola per parola (`lyricsfile`), header `Lrclib-App-Id`.

### `:engine`: lettura della sessione multimediale (`engine/capture/`)

- `NowPlayingListenerService`: `NotificationListenerService` vuoto, dichiarato nel manifest
  di `:engine`. Non sovrascrive `onNotificationPosted`: non legge le notifiche, serve solo a
  ottenere l'accesso a `MediaSessionManager.getActiveSessions`.
- `NowPlayingTracker`: all'avvio della cattura cerca la sessione del package che corrisponde
  al `targetUid`, ascolta le nuove sessioni finché non la trova, registra i cambi di metadati
  e di stato. Fornisce uno snapshot (metadati, ultimo `PlaybackState`, istante di un eventuale
  cambio brano). Se il permesso manca o viene revocato (`SecurityException`) non fa nulla.
- `SongPositionEstimator` (logica pura): dato lo stato di riproduzione e l'istante
  (`elapsedRealtime`) del primo frame registrato, calcola la posizione nel brano:
  `position + (istante - lastPositionUpdateTime) * playbackSpeed`. Restituisce `null` se lo
  stato non è PLAYING.
- `PlaybackActivityGate` espone `firstWrittenFrame`, l'indice del primo frame scritto su file
  (pre-roll compreso).
- `SystemAudioCaptureService` converte quel frame in `elapsedRealtime` con
  `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` e chiede la stima. Se lo stato PLAYING non
  arriva entro 5 s dal primo audio, oppure `getTimestamp` fallisce, `songOffsetMs` resta
  `null`.

```kotlin
data class CapturedTrackInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val trackDurationMs: Long?,
    val songOffsetMs: Long?,      // posizione nel brano all'inizio del file registrato
    val sourcePackage: String,
    val mediaId: String?,         // solo per uso locale, mai inviato in rete
    val trackChangedAtMs: Long?,  // ms dall'inizio del file in cui è cambiato brano
)
```

- Senza sessione, oppure con titolo o artista vuoti, non si crea nessun `CapturedTrackInfo`.
- `CaptureTerminalResult` ottiene il campo facoltativo `trackInfo: CapturedTrackInfo?`.
- `PendingCapture` lo espone; il nome proposto diventa "Artista - Titolo".

### `:engine`: persistenza (`engine/lyrics/`)

`TrackLyricsStore` scrive un file JSON per brano salvato in
`filesDir/lyrics/<sha1(savedUri)>.json`, serializzato con kotlinx.serialization (plugin
aggiunto anche a `:engine`). Il formato appartiene a `:engine`, non al fornitore.

Contenuto:

- `savedUri`;
- i metadati (`CapturedTrackInfo`, con lo sfasamento già corretto per il taglio);
- `status`: `PENDING`, `FOUND`, `NOT_FOUND`, `INSTRUMENTAL`, `FAILED`;
- `sourceId`, `lines` (`startMs`, `text`), `plainText`, `fetchedAt`.

Un file corrotto viene trattato come assente e registrato nel log.

`TrackLyricsRepository.get(uri): TrackLyrics?` è l'accesso pubblico per `:ui` (usato nella
prossima iterazione), con modelli di `:engine`: `:engine` converte i tipi di `:connections`
nei propri e non li espone.

### `:engine`: download con WorkManager

Dipendenza `androidx.work:work-runtime-ktx` in `:engine`.

In `PendingCaptureRepository.completeProcessing(savedUri)`, se c'è `trackInfo`:

1. Sfasamento finale = `songOffsetMs + trimStartMs` (`null` se `songOffsetMs` è `null`).
2. Se `trackChangedAtMs` non è `null` e `trimStartMs >= trackChangedAtMs`, i metadati
   descrivono il brano sbagliato: si scartano e non si scrive niente.
3. Si scrive il file con stato `PENDING` e si accoda il lavoro unico `lyrics-<sha1>`
   (`ExistingWorkPolicy.KEEP`, vincolo `NetworkType.CONNECTED`).

`LyricsFetchWorker` (`CoroutineWorker`):

- Se il file non esiste più (brano cancellato) termina con successo senza fare nulla.
- Costruisce la `TrackQuery` dai metadati originali della sessione, non dal nome scelto
  dall'utente, e chiama `Connections.lyricsProvider(context).find(...)`.
- La decisione su cosa fare è in una funzione pura `LyricsFetchPolicy`:
  - `Found` / `NotFound` / `Instrumental` → scrive lo stato, fine;
  - `Failed` → stato `FAILED`, fine;
  - `RetryLater(afterMs)` con `afterMs` nullo o ≤ 30 s → `Result.retry()` (backoff
    esponenziale da 30 s);
  - `RetryLater(afterMs)` con `afterMs` > 30 s → riaccoda lo stesso lavoro
    (`ExistingWorkPolicy.REPLACE`) con ritardo `afterMs` + fino al 30% casuale, conservando
    il numero di tentativi nei dati di input;
  - dopo 5 tentativi → stato `FAILED`.

### Ciclo di vita con la libreria

- `LibraryRepository.delete(uri)` cancella anche il file del testo e annulla il lavoro.
- `rename(uri, title)` non lo tocca, perché l'URI MediaStore non cambia. Da verificare in
  implementazione: se cambiasse, il file va spostato.

### `:ui`: permesso di accesso alle notifiche

- Alla prima cattura avviata dalla bolla, se l'accesso non è concesso, un dialogo spiega a
  cosa serve e offre di aprire le Impostazioni
  (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
- Si chiede una sola volta (flag salvato); se l'utente rifiuta, la cattura funziona senza
  metadati.
- Testi in `ui/src/main/res/values/strings.xml`. La vecchia `MainActivity` di `:engine` resta
  senza dialogo.

## Privacy

- A LRCLIB vanno solo titolo, artista, album e durata: niente identificativi del dispositivo
  né `MEDIA_ID`.
- I log non contengono mai testi delle canzoni, solo stato e `sourceId`.
- I testi restano in `filesDir`, privati dell'app. Uso personale; per una distribuzione
  commerciale servirebbe un licenziatario dei testi.

## Test

Le risposte di prova usano solo testi inventati.

- `:connections` (JVM, MockWebServer): get trovato; 404 seguito da get ripulito e search;
  filtro della durata ±2 s; `instrumental`; solo `plainLyrics`; 429 con `Retry-After`; 503 in
  testo semplice; connessione interrotta; User-Agent presente. Parser LRC: più tag sulla
  stessa riga, tag di metadati ignorati, righe vuote, `mm:ss.xx` e `mm:ss.xxx`. Pulizia dei
  titoli.
- `:engine` (JVM puro): `SongPositionEstimator` (PLAYING, PAUSED, velocità ≠ 1, stato
  assente); `firstWrittenFrame` con pre-roll; sfasamento con taglio e `trackChangedAtMs`;
  andata e ritorno di `TrackLyricsStore` su cartella temporanea, file corrotto;
  `LyricsFetchPolicy`.
- Sul telefono (OPPO, variante webgpu debug) con YouTube: nome precompilato, file JSON in
  `filesDir/lyrics`, sfasamento entro ±0,2 s rispetto al brano.

## Fuori ambito

- Testo nel player, correzione manuale dello sfasamento, file importati.
- Riconoscimento audio (AcoustID, ACRCloud, AudD) e allineamento on-device.
- Il problema della cattura da Spotify sul flusso della bolla (l'audio resta
  sull'altoparlante): bug separato, da correggere a parte.
