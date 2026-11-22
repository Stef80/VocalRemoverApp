# Design: editor di taglio della registrazione ed elaborazione dalla schermata Import

Data: 2026-10-18
Dipende da: `2026-10-18-engine-processing-playback-design.md` (`SongProcessor`, `LibraryPlayer`).

## Contesto e obiettivo

Quando la cattura dell'audio di sistema si ferma, `BubbleOverlayService.onCaptureStopped()`
riapre `MainActivity` su `ImportSongScreen`; il WAV catturato (PCM 16 bit stereo 44,1 kHz in
`cacheDir/system_audio_capture_<ts>.wav`) resta in `CaptureTerminalResultStore.outputPath`, ma
nessuna schermata Compose lo usa e "Elabora" mostra ancora "anteprima non collegata".

Le registrazioni contengono spesso pubblicità o secondi inutili **all'inizio o alla fine**
(durante una canzone non ci sono spot). Obiettivo: dopo la cattura l'utente taglia inizio e fine
in un editor, ascolta il risultato, gli dà un nome ed elabora; a elaborazione riuscita lo
strumentale è nella libreria e i file della registrazione spariscono dal disco.

## Flusso utente

1. La cattura si ferma → si apre **automaticamente** l'editor sul WAV appena catturato.
2. Nell'editor: nome del brano, forma d'onda con due maniglie (inizio/fine), ascolto, **Salva**
   o **Chiudi**.
3. **Salva** → il file tagliato si scrive come `<nome>_tagliato.wav`, l'editor si chiude, si
   torna a `ImportSongScreen`.
4. **Chiudi** (o indietro) → si torna a `ImportSongScreen` senza salvare; resta l'ultimo stato
   salvato (all'inizio, la cattura intera).
5. In `ImportSongScreen`, al posto della card "Registra audio interno" c'è la **card del
   brano**: nome, **Modifica** (riapre l'editor), **Scarta** (con conferma). Sotto il pulsante
   "Elabora" c'è una **card con un player** per ascoltare il risultato.
6. **Elabora** → il player lascia il posto alla **progress bar**. A elaborazione riuscita la
   schermata torna allo stato iniziale e i WAV della registrazione (originale e tagliato) sono
   cancellati.

## Fuori scope

- Riconoscimento del brano (titolo/artista automatici). È l'evoluzione prevista: il nome
  iniziale si calcola in un solo punto (`RecordingFileNaming.defaultName()`, chiamato da
  `PendingCaptureRepository`), dove si aggancerà il riconoscitore.
- Tagli interni (più segmenti), zoom della forma d'onda, dissolvenze.
- Editor per i file importati dalla memoria (il selettore file resta non collegato).
- Elaborazione in servizio in primo piano: chiudendo l'app durante l'elaborazione il lavoro si
  perde (si riparte da `CaptureReady`).
- `MainActivity` legacy: consuma anch'essa i risultati `STOPPED` (`acquireStoppedResult`); se
  aperta con una cattura in attesa può elaborarla lei. Limite noto, non corretto (va cancellata).

## Componenti

### `:engine` (package `com.example.vocalremover.capture`, nessun testo per l'utente)

#### `WavTrimmer`

```kotlin
object WavTrimmer {
    /** Bloccante. Copia [startMs, endMs) del chunk data di [source] in [target]. */
    fun trim(source: File, target: File, startMs: Long, endMs: Long)
}
```

- Lavora in streaming sui byte (PCM 16 bit, nessuna decodifica in float): legge l'header con
  `WavFileReader`, converte i ms in frame (`frame = ms * sampleRate / 1000`), copia
  `[startFrame, endFrame) * blockAlign` byte a blocchi e scrive un header nuovo con le stesse
  caratteristiche (sample rate, canali, 16 bit).
- Scrive su `target.tmp` e lo rinomina in `target` solo a copia completa; in caso di errore
  cancella il temporaneo e lascia intatto un eventuale `target` precedente.
- `IllegalArgumentException` se `startMs < 0`, `endMs > durata`, `endMs - startMs < 1000`;
  `WavFileReader.UnsupportedWavFormatException` se il formato non è PCM 16 bit.

#### `WavWaveform`

```kotlin
object WavWaveform {
    /** Bloccante. [buckets] picchi 0–1: per ogni blocco il massimo di |L| e |R|. */
    fun peaks(file: File, buckets: Int): FloatArray
}
```

- Legge in streaming; se il file ha meno frame di `buckets`, restituisce un array lungo quanto
  i frame. L'editor chiede 1000 bucket.

#### `WavFileReader` (modifica)

`WavInfo` guadagna `dataOffset: Long` e `frameCount: Long` (servono a trimmer, forma d'onda e
durata senza leggere tutto il file in memoria). Nuova `fun durationMs(info): Long`.

#### `RecordingFileNaming` (modifica)

Nuova `fun trimmedName(displayName: String) = "${sanitize(displayName)}_tagliato"`.

#### `CaptureDraftStore`

SharedPreferences privato (`capture_draft`) con la bozza di modifica, legata all'`id` del
risultato di cattura:

```kotlin
internal data class CaptureDraft(
    val captureId: String,
    val displayName: String,
    val trimmedPath: String?,
    val trimStartMs: Long,
    val trimEndMs: Long,
)
```

- `read(captureId)`: restituisce la bozza solo se `captureId` coincide; altrimenti la cancella
  e restituisce `null`.
- Separato da `CaptureTerminalResultStore` per non toccarne la macchina a stati, che resta la
  fonte unica del WAV originale e delle fasi `PENDING → PROCESSING → SAVED`.

#### `PendingCaptureRepository`

Facciata pubblica usata da `:ui`; le API di `CaptureTerminalResultStore` che servono
(`acquireStoppedResult`, `CaptureProcessingLease`) restano `internal`.

```kotlin
data class PendingCapture(
    val id: String,
    val rawFile: File,
    val trimmedFile: File?,
    val displayName: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val durationMs: Long,       // della cattura originale
) {
    val playableFile: File get() = trimmedFile ?: rawFile
}

interface PendingCaptureRepository {
    /** Cattura STOPPED in attesa, con la bozza (creata se manca). null se non c'è o se il WAV
     *  è sparito/illeggibile (in quel caso il risultato e la bozza vengono scartati). */
    fun load(): PendingCapture?
    fun peaks(buckets: Int): FloatArray
    /** Salva nome e maniglie; taglia dall'originale o rimuove il taglio. */
    fun saveEdit(displayName: String, startMs: Long, endMs: Long): PendingCapture
    /** PENDING → PROCESSING per questo proprietario. false se non acquisibile. */
    fun beginProcessing(): Boolean
    /** markSaved, cancella WAV originale e tagliato, completeSavedHandoff, svuota la bozza. */
    fun completeProcessing(savedUri: Uri)
    /** Rilascia la cattura dopo un errore; file e bozza restano. */
    fun abortProcessing()
    /** Cancella WAV originale e tagliato, libera lo store, svuota la bozza. */
    fun discard()
    /** Cancella da cacheDir i system_audio_capture_*.wav e *_tagliato.wav non della cattura corrente. */
    fun cleanOrphans()

    companion object {
        fun create(context: Context): PendingCaptureRepository
    }
}
```

- Tutti i metodi sono bloccanti (I/O): il chiamante li usa su `Dispatchers.IO`.
- Ogni istanza ha un `ownerId` UUID proprio, usato con lo store.
- Bozza nuova: `displayName = RecordingFileNaming.defaultName()`, maniglie `0` e `durationMs`,
  nessun file tagliato.
- `saveEdit`:
  - nome ripulito con `sanitize`; vuoto → `IllegalArgumentException`;
  - intervallo completo (`0`, `durationMs`) → nessun taglio, il file tagliato esistente si
    cancella;
  - altrimenti `WavTrimmer.trim(rawFile, cacheDir/<trimmedName>.wav, …)`; se il nome è cambiato
    il vecchio file tagliato si cancella **dopo** che il nuovo è scritto;
  - la bozza si aggiorna solo a taglio riuscito; un errore si propaga e lascia bozza e file
    come prima.

### `:ui`

#### `ImportViewModel`

Primo ViewModel dell'app, legato a `MainActivity` (condiviso da `ImportSongScreen` e
`TrimEditorScreen`; l'elaborazione continua anche tornando a `PlayerScreen`).

```kotlin
class ImportViewModel(
    private val captures: PendingCaptureRepository,
    private val processor: SongProcessor,
    private val player: PreviewPlayer,
) : ViewModel() {
    val importState: StateFlow<ImportUiState>
    val editorState: StateFlow<EditorUiState?>   // null = editor chiuso
    val playerState: StateFlow<PlayerState>
    val events: Flow<ImportEvent>                 // one-shot → snackbar

    fun loadPendingCapture(openEditor: Boolean)
    fun openEditor(); fun closeEditor()
    fun onNameChange(name: String)
    fun onHandlesChange(startMs: Long, endMs: Long)   // durante il trascinamento
    fun onHandleReleased(which: TrimHandle)           // salto della riproduzione
    fun onSeek(positionMs: Long)
    fun togglePlayback()
    fun saveEdit()
    fun process()
    fun discard()
}
```

- `PreviewPlayer`: interfaccia in `:ui` (`state`, `play(uri)`, `pause()`, `seekTo(ms)`,
  `release()`) con un adattatore su `LibraryPlayer`, per poter testare il ViewModel con un
  player finto. Una sola istanza serve editor e card del player.
- Factory con `viewModelFactory { }`: `PendingCaptureRepository.create(app)`,
  `SongProcessor.create(app, VocalRemover(app))`, adattatore su `LibraryPlayer(app)`. Oggi
  `:ui` non crea nessun `VocalRemover`: lo possiede il ViewModel (sessione ORT creata
  pigramente alla prima elaborazione, backend da `BuildConfig.EXECUTION_BACKEND` come default
  del costruttore). `onCleared` rilascia il player e chiama `VocalRemover.close()`.
- Nel costruttore: `cleanOrphans()` e `loadPendingCapture(openEditor = false)` su IO.

#### `TrimEditorScreen`

Layout dall'alto: barra (indietro = Chiudi, logo `logo_corallo_blu`, titolo "Modifica
registrazione"); campo **Nome brano**; **forma d'onda** a tutta larghezza (~160 dp) con barre
attenuate fuori dalle maniglie e colore primario dentro, linea verticale della posizione di
riproduzione; riga **Inizio · Fine · Durata** (`m:ss`); pulsante **play/pausa**; in fondo
**Chiudi** (outlined) e **Salva** (pieno, con indicatore mentre `isSaving`).

#### `ImportSongScreen` (modifica)

- `Empty`: come oggi.
- `CaptureReady` / `Processing`: la `CaptureCard` è sostituita da `PendingCaptureCard` (nome,
  "Modifica", "Scarta"); sotto il pulsante "Elabora" compare `PreviewPlayerCard` (play/pausa,
  slider trascinabile, tempo corrente/totale) in `CaptureReady`, oppure la progress bar con la
  fase in `Processing`.
- "Elabora" chiama `vm.process()` se c'è una cattura; senza cattura resta il comportamento
  attuale (snackbar "anteprima non collegata").
- "Scarta" apre un `AlertDialog` di conferma; conferma → `vm.discard()`.

#### `MainActivity` (modifica)

- Nuovo stato di navigazione `showTrimEditor`, derivato da `editorState != null`.
- Con `EXTRA_OPEN_IMPORT_SCREEN` → `vm.loadPendingCapture(openEditor = true)`: apre l'editor se
  c'è una cattura in attesa, altrimenti solo `ImportSongScreen`.
- Avvio normale con una cattura in attesa: nessun editor automatico, `ImportSongScreen` la
  mostra in `CaptureReady` quando l'utente ci arriva.

## Stati

```kotlin
sealed interface ImportUiState {
    data object Empty : ImportUiState
    data class CaptureReady(
        val name: String, val playableUri: Uri, val durationMs: Long, val isTrimmed: Boolean,
    ) : ImportUiState
    data class Processing(val name: String, val phase: ProcessingPhase) : ImportUiState
}

sealed interface ProcessingPhase {        // da SongProcessor.state
    data object Decoding : ProcessingPhase
    data class Running(val percent: Int) : ProcessingPhase
    data object Saving : ProcessingPhase
}

data class EditorUiState(
    val name: String,
    val peaks: FloatArray?,       // null mentre si calcolano
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isSaving: Boolean,
    val previewAvailable: Boolean,
)

enum class TrimHandle { START, END }

sealed interface ImportEvent {
    data object EditSaved : ImportEvent
    data object EditFailed : ImportEvent
    data object NameRequired : ImportEvent
    data object CaptureUnavailable : ImportEvent
    data object ProcessingDone : ImportEvent
    data class ProcessingFailed(val error: ProcessingError) : ImportEvent
    data object PreviewUnavailable : ImportEvent
}
```

Transizioni: `Empty → CaptureReady` (load) `→ Processing` (process) `→ Empty` (riuscita) oppure
`→ CaptureReady` (errore). `CaptureReady → Empty` con Scarta.

## Comportamento

### Elabora

1. Mette in pausa il player; `beginProcessing()` su IO. Se `false` (cattura presa da un altro
   proprietario, es. la `MainActivity` legacy) → evento `CaptureUnavailable` e si ricarica lo
   stato con `loadPendingCapture(openEditor = false)`.
2. `processor.process(Uri.fromFile(playableFile), RecordingFileNaming.instrumentalName(name))`;
   lo stato `Processing` segue `processor.state`.
3. Riuscita → `completeProcessing(savedUri)`, stato `Empty`, evento `ProcessingDone`
   ("Salvato in libreria").
4. `ProcessingException` → `abortProcessing()`, stato `CaptureReady`, evento
   `ProcessingFailed(error)`; file e bozza restano.
5. Durante `Processing`: Modifica, Scarta ed Elabora disabilitati.
6. App chiusa durante l'elaborazione: alla riapertura lo store recupera il lease del processo
   morto e si torna a `CaptureReady`.

### Salva / Chiudi

- Validazione nel ViewModel: nome non vuoto (altrimenti `NameRequired`, editor aperto).
- `saveEdit` su IO con `isSaving = true`; riuscita → editor chiuso, `importState` aggiornato
  (nuovo `playableUri`), evento `EditSaved`. Errore → editor aperto con le modifiche, evento
  `EditFailed`; il taglio precedente resta.
- Chiudi/indietro: nessuna conferma; si scarta lo stato dell'editor.
- Apertura e chiusura dell'editor mettono in pausa il player; dopo Salva il player punta al
  nuovo `playableUri`.

### Maniglie e ascolto (logica pura in `TrimHandles`, testabile)

- Passo 10 ms; le maniglie non si incrociano e distano almeno 1000 ms; limitate a
  `[0, durationMs]`. Conversione px ↔ ms in base alla larghezza della forma d'onda.
- Area di tocco ≥ 48 dp per maniglia; `contentDescription` e azioni di accessibilità ±1 s.
- Tocco sulla forma d'onda fuori dalle maniglie → `onSeek`.
- Play: parte dalla posizione corrente, o dalla maniglia di inizio se la posizione è fuori
  dall'intervallo; arrivata alla maniglia di fine la riproduzione si mette in pausa (controllo
  sulla posizione pubblicata da `PreviewPlayer`, ~200 ms).
- Rilascio della maniglia di inizio → seek all'inizio; della maniglia di fine → seek a
  `max(startMs, endMs - 3000)`.
- Prima che arrivino i picchi: placeholder con indicatore; maniglie bloccate finché
  `durationMs` non è nota (arriva da `load()`, quindi subito).

### Errori

- WAV mancante o illeggibile al caricamento → `load()` scarta la cattura, stato `Empty`, evento
  `CaptureUnavailable`.
- Errore del player (`PlayerState.error`) → `previewAvailable = false`, evento
  `PreviewUnavailable`; taglio e salvataggio restano possibili.
- Tutti i testi in `ui/src/main/res/values/strings.xml` (titolo editor, etichette, pulsanti,
  dialogo di Scarta, snackbar per ogni `ImportEvent` e per ciascun `ProcessingError`, fasi della
  progress bar).

## Thread

- Repository e `WavTrimmer`/`WavWaveform` su `Dispatchers.IO` (`viewModelScope`).
- `PreviewPlayer`/`LibraryPlayer` solo dal main thread.
- `SongProcessor.process` è già main-safe.

## Test

### JVM `:engine` (`gradle :engine:testCpuDebugUnitTest`)

- `WavTrimmerTest`: header (RIFF size, data size) corretto; campioni nell'intervallo identici
  all'originale; allineamento al frame; intervallo completo; intervalli non validi →
  eccezione; errore a metà copia (scrittura finta che fallisce) → nessun `target`, temporaneo
  cancellato, `target` precedente intatto.
- `WavWaveformTest`: numero di bucket; picco atteso su segnale sintetico; file più corto dei
  bucket.
- `WavFileReaderTest` (estensione): `frameCount`, `dataOffset`, `durationMs`.
- `CaptureDraftStoreTest` (Robolectric): bozza scartata se l'`id` è diverso.
- `PendingCaptureRepositoryTest` (Robolectric): load crea la bozza col nome di default; WAV
  mancante → `null` e store vuoto; `saveEdit` con intervallo completo cancella il taglio;
  cambio nome cancella il vecchio file; `completeProcessing` cancella i file e svuota store e
  bozza; `abortProcessing` lascia tutto; `discard`; `cleanOrphans` non tocca i file della
  cattura corrente.
- `RecordingFileNamingTest`: `trimmedName`.

### JVM `:ui` (`gradle :ui:testCpuDebugUnitTest`)

Nuova dipendenza di test: `kotlinx-coroutines-test:1.8.1` (Main dispatcher di test).

- `ImportViewModelTest` (repository, processor con dipendenze finte e player finti):
  `Empty → CaptureReady → Processing → Empty`; errore → `CaptureReady` con file intatti;
  `loadPendingCapture(openEditor = true)` apre l'editor; nome vuoto → `NameRequired`;
  Scarta → `Empty`; pausa automatica a fine intervallo.
- `TrimHandlesTest`: vincoli, passo, conversione px ↔ ms, punti di salto al rilascio.

### Sul dispositivo (manuale)

Cattura → editor automatico → taglio inizio e fine → Salva → ascolto nella card → Modifica
(maniglie dove erano) → Elabora → progress bar → brano `<nome>_strumentale` in
`Music/VocalRemover`, nessun `system_audio_capture_*.wav` né `*_tagliato.wav` in cache. Scarta
con conferma su una seconda cattura.

## Criteri di accettazione

- Il flusso utente sopra funziona sul dispositivo nei flavor `cpu`, `webgpu`, `qnn`.
- Tutti i test sopra passano; `gradle :ui:assembleCpuDebug :ui:assembleWebgpuDebug
  :ui:assembleQnnDebug` compila; nessun nuovo errore di lint nei file toccati.
- `:engine` non dipende da `:ui`; nessun testo per l'utente nei file nuovi di `:engine`; nessun
  testo hard-coded nelle schermate di `:ui`.
