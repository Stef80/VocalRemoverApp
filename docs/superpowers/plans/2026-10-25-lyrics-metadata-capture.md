# Metadati del brano dalla cattura e download dei testi: piano di implementazione

> **Per chi esegue:** SOTTO-SKILL OBBLIGATORIA: usare superpowers:subagent-driven-development
> (consigliata) oppure superpowers:executing-plans per eseguire il piano task per task. I passi
> usano le caselle (`- [ ]`) per tenere traccia dell'avanzamento.

**Obiettivo:** riconoscere il brano catturato dalla sessione multimediale dell'app sorgente,
stimare la posizione nel brano all'inizio del file e, dopo il salvataggio, scaricare in
background il testo sincronizzato da LRCLIB in un formato neutro.

**Architettura:** nuovo modulo `:connections` (interfaccia `LyricsProvider` + implementazione
LRCLIB interna). `:engine` legge la sessione multimediale durante la cattura, salva i metadati
nel risultato della cattura e, al salvataggio, scrive un file JSON e accoda un lavoro
WorkManager che chiama il provider. `:ui` aggiunge solo il dialogo del permesso.

**Stack:** Kotlin 2.2.10, AGP 9.4.1, OkHttp 4.12.0, kotlinx-serialization-json 1.9.0,
WorkManager 2.10.1, JUnit 4, MockWebServer 4.12.0, Robolectric 4.16.

**Spec:** `docs/superpowers/specs/2026-10-25-lyrics-metadata-capture-design.md`

## Vincoli globali

- **Mai `git commit`**: ogni task termina mettendo in stage i file e descrivendo il commit; il
  commit lo fa l'utente (CLAUDE.md).
- Lavorare su un branch nuovo creato da `main` (es. `feature/lyrics-metadata`), **non** nel
  worktree `spike-media-session`, che è usa e getta. Copiare spec e piano sul nuovo branch.
- Dipendenze fra moduli: `:ui` → `:engine` → `:connections`. `:engine` non dipende da `:ui`;
  `:ui` non importa nulla da `com.stef.vocalremover.connections`.
- `:connections`: namespace e package `com.stef.vocalremover.connections`, nessun flavor.
- In `:ui` nessun testo visibile nel codice: tutto in `ui/src/main/res/values/strings.xml`.
- Codice, commenti e testi in italiano.
- Nei test e nei log mai testi di canzoni reali: solo righe inventate.
- A LRCLIB si inviano solo titolo, artista, album, durata. `mediaId` resta locale.
- User-Agent `VocalRemover/<versionName> (<contatto>)`, contatto dalla proprietà Gradle
  `lrclib.contact`; se assente, `VocalRemover/<versionName>`.
- Gradle: `G=/home/stefano/.gradle/wrapper/dists/gradle-9.8.0-bin/3m7h6ceboy5k31n8kzwzuxssm/gradle-9.8.0/bin/gradle`
  (non esiste `gradlew`; il gradle di sistema non funziona).
- Modelli `*.onnx` mai aggiunti a git.

## Punti di attenzione per la revisione

1. **Cattura senza permesso di accesso alle notifiche** → la cattura deve funzionare come
   oggi, nessun crash, `trackInfo` nullo. Test nel Task 6 (tracker con `SecurityException`).
2. **Brano cambiato durante la cattura, taglio dopo il cambio** → metadati scartati, nessun
   file di testo. Test nel Task 3 (`TrackInfoFinalizer`).
3. **Recupero dopo chiusura dell'app fra `markSaved` e la pulizia** (`load()` con stato
   SAVED) → il download deve partire comunque. Test nel Task 8.
4. **Brano cancellato dalla libreria mentre il lavoro è in coda** → il worker termina senza
   fare nulla e il file non ricompare. Test nel Task 8 (policy con file assente).
5. **Titoli con caratteri speciali o molto lunghi** (es. "AC/DC", "Sigur Rós", 300 caratteri)
   → la query viene codificata correttamente nell'URL. Test nel Task 2.

---

### Task 1: modulo `:connections`, parser LRC e pulizia dei titoli

**File:**
- Modifica: `settings.gradle.kts` (`include(":connections")`), `gradle/libs.versions.toml`
  (okhttp, mockwebserver, kotlinx-serialization-json, plugin `kotlin-serialization` con
  `version.ref = "kotlin"`), `build.gradle.kts` radice (plugin serialization `apply false`
  se il progetto dichiara lì i plugin)
- Crea: `connections/build.gradle.kts`, `connections/src/main/AndroidManifest.xml`
  (solo `<uses-permission android:name="android.permission.INTERNET" />`)
- Crea: `connections/src/main/java/com/stef/vocalremover/connections/LyricsProvider.kt`
- Crea: `connections/src/main/java/com/stef/vocalremover/connections/lrclib/LrcParser.kt`
- Crea: `connections/src/main/java/com/stef/vocalremover/connections/lrclib/TitleCleaner.kt`
- Test: `connections/src/test/java/com/stef/vocalremover/connections/lrclib/LrcParserTest.kt`,
  `.../lrclib/TitleCleanerTest.kt`

**Interfacce:**
- Produce (pubbliche, in `LyricsProvider.kt`): `LyricsProvider`, `TrackQuery`,
  `LyricsResult` (`Found`, `Instrumental`, `NotFound`, `RetryLater(afterMs: Long?)`,
  `Failed(reason: String)`), `TimedLyrics`, `LyricLine`, esattamente come nella spec.
- Produce (`internal`): `object LrcParser { fun parse(lrc: String): List<LyricLine> }`,
  `object TitleCleaner { fun clean(title: String): String }`.

`connections/build.gradle.kts`: `android-library` + `kotlin-android` + serialization,
`compileSdk = 37`, `minSdk = 26`, Java/JVM 17 come `:engine`, `buildFeatures.buildConfig = true`,
`buildConfigField("String", "LRCLIB_CONTACT", "\"${providers.gradleProperty("lrclib.contact").getOrElse("")}\"")`.
Dipendenze: okhttp, kotlinx-serialization-json, kotlinx-coroutines-android 1.8.1;
test: junit 4.13.2, mockwebserver, kotlinx-coroutines-test 1.8.1.
Stesso blocco `testOptions` di `:engine` per `java.io.tmpdir`.

- [ ] **Passo 1: scrivere i test che falliscono**

```kotlin
class LrcParserTest {
    @Test fun righeConCentesimiEMillesimi() = assertEquals(
        listOf(LyricLine(12_340, "prima riga inventata"), LyricLine(65_007, "seconda riga")),
        LrcParser.parse("[00:12.34] prima riga inventata\n[01:05.007]seconda riga"))
    @Test fun piuTagSullaStessaRigaGeneranoPiuRigheOrdinate() = assertEquals(
        listOf(LyricLine(1_000, "ritornello"), LyricLine(2_000, "strofa"), LyricLine(30_000, "ritornello")),
        LrcParser.parse("[00:01.00][00:30.00]ritornello\n[00:02.00]strofa"))
    @Test fun tagDiMetadatiIgnorati() = assertEquals(
        listOf(LyricLine(500, "riga")),
        LrcParser.parse("[ar:Artista]\n[ti:Titolo]\n[offset:+100]\n[00:00.50]riga"))
    @Test fun rigaVuotaConservataComePausa() = assertEquals(
        listOf(LyricLine(1_000, "a"), LyricLine(4_000, "")),
        LrcParser.parse("[00:01.00]a\n[00:04.00]\n\nrumore senza tag"))
}

class TitleCleanerTest {
    @Test fun remaster() = assertEquals("Canzone Finta (Take Me Out)",
        TitleCleaner.clean("Canzone Finta (Take Me Out) - 2024 Remaster"))
    @Test fun videoUfficiale() = assertEquals("Brano", TitleCleaner.clean("Brano (Official Video)"))
    @Test fun featuring() = assertEquals("Brano", TitleCleaner.clean("Brano (feat. Qualcuno)"))
    @Test fun featSenzaParentesi() = assertEquals("Brano", TitleCleaner.clean("Brano feat. Qualcuno"))
    @Test fun titoloPulitoInvariato() = assertEquals("Brano", TitleCleaner.clean("Brano"))
}
```

- [ ] **Passo 2: verificare che falliscano**
  `$G :connections:testDebugUnitTest` → errore di compilazione (`LrcParser` non definito).

- [ ] **Passo 3: implementare** i tipi pubblici, `LrcParser` (regex
  `\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]`; frazione a 2 cifre = centesimi, a 3 = millesimi;
  righe senza tag temporale e tag non numerici ignorati; risultato ordinato per `startMs`) e
  `TitleCleaner` (rimuove, senza distinzione maiuscole/minuscole, i suffissi
  ` - <anno>? Remaster(ed)? ...`, ` - Remastered <anno>`, parentesi tonde o quadre contenenti
  `official`, `video`, `audio`, `lyric`, `remaster`, `feat.`, `ft.`, e ` feat. ...` /
  ` ft. ...` finali; poi `trim`).

- [ ] **Passo 4: verificare che passino**
  `$G :connections:testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Passo 5: mettere in stage** i file del task e proporre all'utente il messaggio
  `connections: nuovo modulo, parser LRC e pulizia titoli`.

### Task 2: `LrclibLyricsProvider` e factory `Connections`

**File:**
- Crea: `connections/src/main/java/com/stef/vocalremover/connections/lrclib/LrclibLyricsProvider.kt`
- Crea: `connections/src/main/java/com/stef/vocalremover/connections/lrclib/LrclibDto.kt`
- Crea: `connections/src/main/java/com/stef/vocalremover/connections/Connections.kt`
- Test: `connections/src/test/java/com/stef/vocalremover/connections/lrclib/LrclibLyricsProviderTest.kt`

**Interfacce:**
- Consuma: `LrcParser.parse`, `TitleCleaner.clean` (Task 1).
- Produce: `internal class LrclibLyricsProvider(client: OkHttpClient, baseUrl: HttpUrl,
  userAgent: String, pauseMs: Long = 300, delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) : LyricsProvider`;
  `object Connections { fun lyricsProvider(context: Context): LyricsProvider }` (base
  `https://lrclib.net/`, user agent con `versionName` letto da `PackageManager` e
  `BuildConfig.LRCLIB_CONTACT`, timeout di connessione e lettura 15 s).

DTO `@Serializable` con `ignoreUnknownKeys = true`: `id: Long`, `trackName`, `artistName`,
`albumName?`, `duration: Double?`, `instrumental: Boolean = false`, `plainLyrics: String?`,
`syncedLyrics: String?`.

- [ ] **Passo 1: scrivere i test che falliscono** (MockWebServer; `delay` finto che registra
  le attese; query base `TrackQuery("Brano Finto - 2024 Remaster", "Gruppo Finto", "Album", 189_000)`).
  - `getTrovatoRestituisceRigheSincronizzate`: 200 JSON con `id=42`,
    `syncedLyrics="[00:01.00]riga inventata"` → `Found(TimedLyrics([LyricLine(1000,"riga inventata")], plain), "lrclib:42")`;
    la richiesta è `/api/get?track_name=...&artist_name=...&album_name=Album&duration=189`.
  - `userAgentInviato`: header `User-Agent` uguale a quello passato al costruttore.
  - `404PoiGetRipulitoTrovato`: 404, poi 200 → la 2ª richiesta ha `track_name=Brano Finto`;
    `delay` chiamato con 300.
  - `404PoiSearchFiltraDurata`: 404, 404, poi search con durate 150 e 190.5 → `Found` con
    l'id di quella da 190.5 (entro ±2 s da 189); 3ª richiesta su `/api/search`.
  - `searchSenzaDurataCompatibile` → `NotFound`.
  - `strumentale`: `instrumental: true` → `Instrumental`.
  - `soloTestoSemplice`: `syncedLyrics` null, `plainLyrics="testo inventato"` →
    `Found(TimedLyrics(emptyList(), "testo inventato"), ...)`.
  - `429ConRetryAfter`: 429 con `Retry-After: 120` → `RetryLater(120_000)`; nessuna richiesta
    successiva.
  - `503InTestoSemplice`: 503, `Content-Type: text/plain`, corpo "Service Unavailable" →
    `RetryLater(null)`.
  - `200NonJson`: 200 con `text/html` → `Failed`.
  - `400` → `Failed`.
  - `connessioneInterrotta`: `SocketPolicy.DISCONNECT_AT_START` → `RetryLater(null)`.
  - `caratteriSpecialiCodificati`: titolo `"AC/DC & Sigur Rós ?"` + 300 caratteri → il
    `track_name` letto da `request.requestUrl!!.queryParameter("track_name")` coincide con
    l'originale.
  - `durataAssenteNonInviata`: `durationMs = null` → nessun parametro `duration`.

- [ ] **Passo 2: verificare che falliscano**
  `$G :connections:testDebugUnitTest` → errore di compilazione.

- [ ] **Passo 3: implementare** seguendo la tabella della spec. Durata in secondi
  arrotondata e limitata a 1..3600; il passo 2 si salta se il titolo ripulito è uguale a
  quello grezzo; HTTP eseguito in `Dispatchers.IO`; JSON letto solo se il `Content-Type`
  contiene `application/json`; `Retry-After` solo in secondi interi (altrimenti `null`).

- [ ] **Passo 4: verificare che passino** — `$G :connections:testDebugUnitTest`.

- [ ] **Passo 5: mettere in stage**; messaggio proposto `connections: provider LRCLIB`.

### Task 3: modello `CapturedTrackInfo`, stima della posizione e correzione per il taglio

**File:**
- Crea: `engine/src/main/java/com/example/vocalremover/capture/CapturedTrackInfo.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/capture/SongPositionEstimator.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/capture/TrackInfoFinalizer.kt`
- Modifica: `engine/build.gradle.kts` (plugin serialization, `kotlinx-serialization-json`,
  `implementation(project(":connections"))`)
- Test: `engine/src/test/java/com/example/vocalremover/capture/SongPositionEstimatorTest.kt`,
  `.../capture/TrackInfoFinalizerTest.kt`

**Interfacce:**
- Produce: `@Serializable data class CapturedTrackInfo(...)` con i campi della spec.
- Produce: `data class PlaybackSnapshot(val playing: Boolean, val positionMs: Long,
  val lastPositionUpdateTime: Long, val speed: Float)` (nessun tipo Android, per i test);
  `object SongPositionEstimator { fun positionAt(state: PlaybackSnapshot?, elapsedRealtimeMs: Long): Long? }`.
- Produce: `object TrackInfoFinalizer { fun finalize(info: CapturedTrackInfo, trimStartMs: Long): CapturedTrackInfo? }`
  — restituisce la copia con `songOffsetMs + trimStartMs` (resta `null` se era `null`) e
  `trackChangedAtMs` riportato al file tagliato; `null` se `trackChangedAtMs != null && trimStartMs >= trackChangedAtMs`.

- [ ] **Passo 1: test che falliscono**
  - `positionAt(PlaybackSnapshot(true, 93_000, 10_000, 1f), 12_500) == 95_500`
  - velocità 2: `positionAt(PlaybackSnapshot(true, 0, 0, 2f), 1_000) == 2_000`
  - in pausa → `null`; stato `null` → `null`; risultato negativo limitato a 0.
  - `finalize(info(songOffsetMs = 40_000, trackChangedAtMs = null), 5_000)!!.songOffsetMs == 45_000`
  - `finalize(info(songOffsetMs = null), 5_000)!!.songOffsetMs == null`
  - `finalize(info(trackChangedAtMs = 60_000), 60_000) == null`
  - `finalize(info(trackChangedAtMs = 60_000), 10_000)!!.trackChangedAtMs == 50_000`

- [ ] **Passo 2:** `$G :engine:testCpuDebugUnitTest --tests '*SongPositionEstimatorTest' --tests '*TrackInfoFinalizerTest'` → falliscono.
- [ ] **Passo 3: implementare.**
- [ ] **Passo 4:** stesso comando → passano.
- [ ] **Passo 5: mettere in stage**; messaggio `engine: stima posizione nel brano e metadati catturati`.

### Task 4: `PlaybackActivityGate.firstWrittenFrame`

**File:**
- Modifica: `engine/src/main/java/com/example/vocalremover/capture/PlaybackActivityGate.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/PlaybackActivityGateTest.kt`

**Interfacce:**
- Produce: `val firstWrittenFrame: Long?` — indice (in frame, contati dal primo byte passato a
  `process`) del primo frame consegnato al `sink`, pre-roll compreso; `null` finché si è in
  ascolto.

- [ ] **Passo 1: test che falliscono** (44 100 Hz, stereo, finestra 20 ms = 882 frame,
  pre-roll 300 ms = 15 finestre):
  - 100 finestre di silenzio poi audio → `firstWrittenFrame == (100 - 15) * 882L`.
  - 5 finestre di silenzio poi audio → `firstWrittenFrame == 0L`.
  - solo silenzio → `null`.
  - con `preRollMs = 0`, 10 finestre di silenzio → `10 * 882L`.
- [ ] **Passo 2:** `$G :engine:testCpuDebugUnitTest --tests '*PlaybackActivityGateTest'` → falliscono.
- [ ] **Passo 3: implementare** contando le finestre analizzate; al passaggio a RECORDING
  `(finestreAnalizzate - preRoll.size) * frameFinestra`.
- [ ] **Passo 4:** stesso comando → passano (inclusi i test esistenti).
- [ ] **Passo 5: mettere in stage**; messaggio `engine: primo frame scritto dal gate`.

### Task 5: `trackInfo` nel risultato della cattura e nome precompilato

**File:**
- Modifica: `engine/src/main/java/com/example/vocalremover/capture/CaptureTerminalResultStore.kt`
- Modifica: `engine/src/main/java/com/example/vocalremover/capture/PendingCaptureRepository.kt`
- Modifica: `engine/src/main/java/com/example/vocalremover/capture/RecordingFileNaming.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/PendingCaptureRepositoryTest.kt`,
  `.../capture/RecordingFileNamingTest.kt`

**Interfacce:**
- Consuma: `CapturedTrackInfo` (Task 3).
- Produce: `CaptureTerminalResult.trackInfo: CapturedTrackInfo? = null`;
  `persist(kind, outputPath, errorMessage, trackInfo: CapturedTrackInfo? = null)` (salvato
  come JSON nella chiave `track_info`; JSON illeggibile → `trackInfo = null`, il resto del
  risultato resta valido); `PendingCapture.trackInfo: CapturedTrackInfo?`;
  `RecordingFileNaming.nameFromTrack(info: CapturedTrackInfo): String` = `"Artista - Titolo"`.

- [ ] **Passo 1: test che falliscono**
  - `nameFromTrack(info(artist = "Gruppo", title = "Brano"))  == "Gruppo - Brano"`
  - repository: con `persist(STOPPED, raw.path, trackInfo = info)`, `load()!!.displayName == "Gruppo - Brano"` e `load()!!.trackInfo == info`.
  - senza `trackInfo` il nome resta `RecordingFileNaming.defaultName(...)` (test esistenti).
  - chiave `track_info` corrotta nelle preferenze → `load()` funziona e `trackInfo == null`.
- [ ] **Passo 2:** `$G :engine:testCpuDebugUnitTest --tests '*PendingCaptureRepositoryTest' --tests '*RecordingFileNamingTest'` → falliscono.
- [ ] **Passo 3: implementare**; in `draftFor` il nome iniziale usa `nameFromTrack` se c'è
  `trackInfo`. Il nome passa anche dalle stesse regole di sanificazione già usate per i nomi
  dei file.
- [ ] **Passo 4:** stesso comando → passano.
- [ ] **Passo 5: mettere in stage**; messaggio `engine: metadati del brano nel risultato della cattura`.

### Task 6: `NowPlayingTracker` e integrazione nel servizio di cattura

**File:**
- Crea: `engine/src/main/java/com/example/vocalremover/capture/NowPlayingListenerService.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/capture/NowPlayingTracker.kt`
- Modifica: `engine/src/main/AndroidManifest.xml` (servizio con
  `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`,
  `android:exported="true"`, intent-filter `android.service.notification.NotificationListenerService`)
- Modifica: `engine/src/main/java/com/example/vocalremover/capture/SystemAudioCaptureService.kt`
- Test: `engine/src/test/java/com/example/vocalremover/capture/NowPlayingTrackerTest.kt` (Robolectric)

**Interfacce:**
- Consuma: `PlaybackSnapshot`, `SongPositionEstimator` (Task 3), `firstWrittenFrame` (Task 4),
  `persist(..., trackInfo)` (Task 5).
- Produce:
  - `object NowPlayingAccess { fun isGranted(context: Context): Boolean }` (usa
    `NotificationManagerCompat.getEnabledListenerPackages`), usato da `:ui` nel Task 9.
  - `class NowPlayingTracker(context: Context, targetUid: Int, clock: () -> Long = SystemClock::elapsedRealtime)`
    con `start()`, `stop()`, `snapshot(): NowPlayingSnapshot?`;
    `data class NowPlayingSnapshot(title: String, artist: String, album: String?, durationMs: Long?, sourcePackage: String, mediaId: String?, playback: PlaybackSnapshot?, metadataChangedAtElapsedMs: Long?)`.
    `metadataChangedAtElapsedMs` è l'istante dell'ultimo cambio di titolo/artista dopo il
    primo aggancio. Ogni `SecurityException` (iniziale o durante la cattura) porta a
    `snapshot() == null` senza propagarsi.

Integrazione nel servizio (codice di riferimento: `MediaSessionProbe.kt` nel worktree dello
spike, da **non** copiare così com'è):
- `start()` del tracker prima di `startRecording`, `stop()` nel `finally`.
- Nel `captureLoop`, quando `gate.firstWrittenFrame` diventa non nullo: istante del frame
  con `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` convertito in `elapsedRealtime`
  (`ts.nanoTime + (frame - ts.framePosition) * 1e9 / 44100 + (elapsedRealtimeNanos - nanoTime)`).
  Se `getTimestamp` fallisce l'istante è `null`.
- Durante la registrazione, finché la stima è `null` e non sono passati 5 s dal primo audio,
  si riprova a ogni lettura (il tracker riceve lo stato PLAYING con ritardo).
- A fine cattura si costruisce `CapturedTrackInfo` dallo snapshot (titolo o artista vuoti →
  nessun `trackInfo`); `trackChangedAtMs` = `metadataChangedAtElapsedMs` meno l'istante del
  primo frame, se positivo. Si passa a `persist` tramite `emitStopped`.

- [ ] **Passo 1: test che falliscono** (Robolectric, `ShadowMediaSessionManager`):
  - permesso mancante (`getActiveSessions` lancia `SecurityException`) → `start()` non lancia,
    `snapshot() == null`.
  - sessione di un altro package → `snapshot() == null`.
  - sessione del package del target con metadati → snapshot con titolo, artista, durata.
  - sessione che compare dopo `start()` (notifica `OnActiveSessionsChangedListener`) → agganciata.
  - cambio di metadati dopo l'aggancio → `metadataChangedAtElapsedMs == clock()` di quel momento.

  Se gli shadow di Robolectric non permettono uno di questi casi, isolare la scelta della
  sessione in `internal fun selectController(controllers: List<MediaController>, packages: Set<String>)`
  e testare quella; annotare nel task quale variante è stata usata.
- [ ] **Passo 2:** `$G :engine:testCpuDebugUnitTest --tests '*NowPlayingTrackerTest'` → falliscono.
- [ ] **Passo 3: implementare** tracker, listener e integrazione.
- [ ] **Passo 4:** test passano; `$G :engine:testCpuDebugUnitTest` tutto verde;
  `$G :ui:assembleWebgpuDebug` compila.
- [ ] **Passo 5: mettere in stage**; messaggio `engine: metadati della sessione multimediale durante la cattura`.

### Task 7: `TrackLyricsStore` e `TrackLyricsRepository`

**File:**
- Crea: `engine/src/main/java/com/example/vocalremover/lyrics/TrackLyrics.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/lyrics/TrackLyricsStore.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/lyrics/TrackLyricsRepository.kt`
- Test: `engine/src/test/java/com/example/vocalremover/lyrics/TrackLyricsStoreTest.kt`

**Interfacce:**
- Consuma: `CapturedTrackInfo` (Task 3).
- Produce:
  - `enum class LyricsStatus { PENDING, FOUND, NOT_FOUND, INSTRUMENTAL, FAILED }`
  - `@Serializable data class TrackLyrics(val savedUri: String, val track: CapturedTrackInfo, val status: LyricsStatus, val sourceId: String? = null, val lines: List<TimedLine> = emptyList(), val plainText: String? = null, val fetchedAt: Long? = null)`
  - `@Serializable data class TimedLine(val startMs: Long, val text: String)`
  - `class TrackLyricsStore(dir: File)` con `key(savedUri: String): String` (sha1 esadecimale),
    `read(savedUri): TrackLyrics?`, `write(lyrics: TrackLyrics)` (scrittura su `.tmp` poi
    rename), `delete(savedUri)`, `exists(savedUri)`.
  - `interface TrackLyricsRepository { fun get(uri: Uri): TrackLyrics?; companion object { fun create(context: Context): TrackLyricsRepository } }`
    (cartella `filesDir/lyrics`).

- [ ] **Passo 1: test che falliscono** (cartella in `build/test-lyrics/<nanoTime>`):
  - scrittura e rilettura uguali; `key` stabile e lunga 40 caratteri.
  - file con contenuto `"{non json"` → `read == null`, nessuna eccezione.
  - `delete` poi `read == null`; `read` di un uri mai scritto → `null`.
- [ ] **Passo 2:** `$G :engine:testCpuDebugUnitTest --tests '*TrackLyricsStoreTest'` → falliscono.
- [ ] **Passo 3: implementare.**
- [ ] **Passo 4:** passano.
- [ ] **Passo 5: mettere in stage**; messaggio `engine: archivio locale dei testi`.

### Task 8: `LyricsFetchPolicy`, worker, accodamento e cancellazione

**File:**
- Crea: `engine/src/main/java/com/example/vocalremover/lyrics/LyricsFetchPolicy.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/lyrics/LyricsFetchWorker.kt`
- Crea: `engine/src/main/java/com/example/vocalremover/lyrics/LyricsScheduler.kt`
- Modifica: `engine/build.gradle.kts` (`androidx.work:work-runtime-ktx:2.10.1`; test
  `androidx.work:work-testing:2.10.1`)
- Modifica: `PendingCaptureRepository.kt` (`completeProcessing` e ramo SAVED di `load()`)
- Modifica: `library/LibraryRepository.kt` (`delete`)
- Test: `engine/src/test/java/com/example/vocalremover/lyrics/LyricsFetchPolicyTest.kt`,
  `.../capture/PendingCaptureRepositoryTest.kt`

**Interfacce:**
- Consuma: `LyricsProvider`, `LyricsResult`, `Connections.lyricsProvider` (Task 2);
  `TrackInfoFinalizer` (Task 3); `TrackLyricsStore` (Task 7).
- Produce:
  - `sealed interface FetchDecision { data class Save(val lyrics: TrackLyrics) ; data object Retry ; data class Reschedule(val delayMs: Long, val attempt: Int) ; data object Skip }`
  - `object LyricsFetchPolicy { fun decide(stored: TrackLyrics?, result: LyricsResult?, attempt: Int, now: Long, random: () -> Double): FetchDecision }`
    — `stored == null` → `Skip`; costanti `MAX_ATTEMPTS = 5`, `RETRY_THRESHOLD_MS = 30_000`,
    jitter `afterMs * random() * 0.3`. Converte `TimedLyrics` in `TimedLine`.
  - `interface LyricsScheduler { fun schedule(savedUri: String); fun cancel(savedUri: String) }`
    con implementazione WorkManager: lavoro unico `lyrics-<key>`, `KEEP`,
    `NetworkType.CONNECTED`, backoff esponenziale 30 s, dati di input `savedUri` e `attempt`.
  - `DefaultPendingCaptureRepository` riceve `lyricsStore: TrackLyricsStore` e
    `lyricsScheduler: LyricsScheduler` nel costruttore (con default reali in `create`).
  - `MediaStoreLibraryRepository.delete`: solo con esito `Done` cancella file e lavoro.

`LyricsFetchWorker.doWork()`: legge il file; se assente → `Result.success()`; costruisce
`TrackQuery(track.title, track.artist, track.album, track.trackDurationMs)`; applica la
decisione (`Save` → `write`, `Retry` → `Result.retry()`, `Reschedule` → nuovo lavoro
`REPLACE` con `setInitialDelay` e `attempt + 1`, poi `Result.success()`). L'ultimo tentativo
non concluso scrive `FAILED`. Nei log solo stato e `sourceId`.

- [ ] **Passo 1: test che falliscono**
  - policy: `Found` → `Save` con `status = FOUND`, `sourceId`, `lines`, `fetchedAt = now`.
  - `NotFound` / `Instrumental` / `Failed` → `Save` con lo stato corrispondente.
  - `RetryLater(null)`, attempt 1 → `Retry`; `RetryLater(20_000)` → `Retry`.
  - `RetryLater(120_000)`, random 0.5 → `Reschedule(138_000, 2)`.
  - qualunque `RetryLater` con attempt 5 → `Save` con `FAILED`.
  - `stored == null` → `Skip`.
  - repository (scheduler finto che registra le chiamate): `completeProcessing` con
    `trackInfo` → file `PENDING` con `songOffsetMs` corretto per il taglio e `schedule(savedUri)`;
    senza `trackInfo` → nessun file, nessuna chiamata; taglio dopo il cambio brano → nessun file.
  - recupero: risultato già SAVED con `trackInfo` e nessun file → `load()` scrive il file e
    accoda; se il file esiste già non lo riscrive.
- [ ] **Passo 2:** `$G :engine:testCpuDebugUnitTest --tests '*LyricsFetchPolicyTest' --tests '*PendingCaptureRepositoryTest'` → falliscono.
- [ ] **Passo 3: implementare.** Scrittura del file e accodamento avvengono dopo `markSaved`
  e prima di `finishSavedHandoff`, così il ramo di recupero in `load()` li ripete se l'app
  muore nel mezzo.
- [ ] **Passo 4:** passano; `$G :engine:testCpuDebugUnitTest` tutto verde.
- [ ] **Passo 5: mettere in stage**; messaggio `engine: download dei testi con WorkManager`.

### Task 9: dialogo per l'accesso alle notifiche in `:ui`

**File:**
- Modifica: `ui/src/main/java/com/stef/vocalremover/ui/overlay/MediaProjectionConsentActivity.kt`
- Crea: `ui/src/main/java/com/stef/vocalremover/ui/overlay/NowPlayingAccessPrompt.kt`
- Modifica: `ui/src/main/res/values/strings.xml`
- Test: `ui/src/test/java/com/stef/vocalremover/ui/overlay/NowPlayingAccessPromptTest.kt`

**Interfacce:**
- Consuma: `NowPlayingAccess.isGranted` (Task 6).
- Produce: `class NowPlayingAccessPrompt(prefs: SharedPreferences, isGranted: () -> Boolean)`
  con `shouldAsk(): Boolean` (`!isGranted() && !giàChiesto`) e `markAsked()`.

Flusso nell'activity: dopo i permessi esistenti e prima di `requestMediaProjectionConsent()`,
se `shouldAsk()` mostra un `AlertDialog` (titolo, spiegazione, "Apri impostazioni", "Non ora");
entrambe le scelte chiamano `markAsked()`. "Apri impostazioni" lancia
`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` con un launcher di risultato e, al ritorno,
prosegue con il consenso. "Non ora" o chiusura del dialogo → prosegue subito. Il dialogo
mostra il logo `R.drawable.logo_corallo_blu` (`setIcon`).

Stringhe nuove (testo indicativo, da rivedere con l'utente):
`now_playing_access_title` "Riconoscere il brano", `now_playing_access_message` "Per dare
un nome alla registrazione e cercare il testo, VocalRemover legge titolo e artista dall'app
che stai registrando. Serve l'accesso alle notifiche: le notifiche non vengono lette.",
`now_playing_access_open_settings` "Apri impostazioni", `now_playing_access_later` "Non ora".

- [ ] **Passo 1: test che falliscono** (Robolectric): non concesso e mai chiesto → `true`;
  dopo `markAsked()` → `false`; concesso → `false`.
- [ ] **Passo 2:** `$G :ui:testWebgpuDebugUnitTest --tests '*NowPlayingAccessPromptTest'` → falliscono.
- [ ] **Passo 3: implementare.**
- [ ] **Passo 4:** passano; `$G :ui:assembleWebgpuDebug` compila.
- [ ] **Passo 5: mettere in stage**; messaggio `ui: richiesta dell'accesso alle notifiche alla prima cattura`.

### Task 10: verifica sul telefono

- [ ] **Passo 1:** installare `:ui` webgpu debug sull'OPPO
  (`adb -s <dispositivo> install -r ui/build/outputs/apk/webgpu/debug/*.apk`).
- [ ] **Passo 2:** prima cattura dalla bolla → compare il dialogo; concedere l'accesso.
- [ ] **Passo 3:** catturare un brano da YouTube (avviare la cattura, poi play); nell'editor
  il nome proposto è "Artista - Titolo".
- [ ] **Passo 4:** tagliare l'inizio di qualche secondo, elaborare e salvare; con
  `adb shell run-as <package> cat files/lyrics/*.json` controllare `status` (`FOUND` o
  `NOT_FOUND`) e `songOffsetMs`, **senza stampare le righe del testo** (es. `| head -c 400`
  sui soli metadati, oppure `jq 'del(.lines,.plainText)'`).
- [ ] **Passo 5:** confrontare `songOffsetMs` con la posizione mostrata da YouTube all'inizio
  della registrazione più il taglio: scarto entro ±0,2 s.
- [ ] **Passo 6:** cancellare il brano dalla libreria → il file JSON sparisce.
- [ ] **Passo 7:** cattura negando l'accesso (revocarlo da Impostazioni) → la cattura
  funziona, nome predefinito, nessun file JSON.
- [ ] **Passo 8:** riassumere all'utente esito e file in stage; il commit lo fa l'utente.
