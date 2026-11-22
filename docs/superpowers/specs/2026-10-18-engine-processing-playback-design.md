# Design: decodifica, elaborazione e riproduzione separate in `:engine`

Data: 2026-10-18
Sostituisce, per la nuova UI Compose, il vecchio `AudioPlayer` (che resta solo per la
`MainActivity` legacy fino alla sua cancellazione).

## Contesto e obiettivo

Oggi `AudioPlayer` (`engine/.../AudioPlayer.kt`) fa tre lavori in una classe: decodifica
del file (`decodeAudio`), orchestrazione di `VocalRemover.removeVocals`, riproduzione del
PCM in memoria con `AudioTrack` in streaming. Comunica con l'activity tramite callback
(`onProgress`, `onReady`, `onError`, `onPlaybackPositionChanged`). Lo usa solo la
`MainActivity` legacy di `:engine`, destinata a essere cancellata; le schermate Compose di
`:ui` (`PlayerScreen`, `ImportSongScreen`) sono ancora solo grafica.

Nel nuovo prodotto il player **non suona il risultato appena elaborato**: ogni brano
elaborato viene salvato come WAV nella libreria (`Music/VocalRemover`, via MediaStore con
`RecordingSaver.saveProcessedStereoAsWav`) e il player riproduce i file da lì.
Elaborazione e riproduzione si toccano quindi solo attraverso il file salvato.

Obiettivo: dare a `:engine` tre componenti con una responsabilità ciascuno e uno stato
osservabile (`StateFlow`) adatto a Compose, pronti per essere usati dai futuri ViewModel di
`:ui`.

```
file scelto ─► AudioDecoder ─► VocalRemover.removeVocals ─► RecordingSaver ─► Music/VocalRemover
               └──────────────────── SongProcessor ────────────────────┘              │
                                                                LibraryPlayer ◄────────┘
```

## Fuori scope

- Integrazione con la UI (ViewModel, `PlayerScreen`, `ImportSongScreen`): la UI deve
  ancora cambiare.
- Elenco dei WAV salvati nella libreria (alimenterà `PlayableTracks`).
- Collegamento della cattura audio di sistema (`SystemAudioCaptureService`,
  `CaptureTerminalResultStore`) a `SongProcessor`.
- Elaborazione in un servizio in primo piano: se l'app viene chiusa durante
  l'elaborazione, il lavoro si perde (come oggi).
- Aggiornamento di Media3 (resta 1.4.1) e rimozione della dipendenza `media3-ui`.
- Cancellazione di `MainActivity` legacy, XML di view e vecchio `AudioPlayer`.

## Componenti

Tutti in `:engine`, sotto `com.example.vocalremover`. `StereoPcm` resta dov'è
(`VocalRemover.kt`).

### `decode/AudioDecoder`

Decodifica qualsiasi file audio supportato da `MediaCodec` in PCM stereo float a 44,1 kHz.

```kotlin
class AudioDecoder(context: Context) {
    /** Bloccante: va chiamata fuori dal main thread. Lancia DecodeException se fallisce. */
    fun decode(uri: Uri): StereoPcm
}

class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

- È il codice di `AudioPlayer.decodeAudio` di oggi, con i fix già fatti (rilettura di
  sample rate e canali su `INFO_OUTPUT_FORMAT_CHANGED`, rilascio del codec nel `finally`,
  un solo `BUFFER_FLAG_END_OF_STREAM`), diviso in tre parti:
  1. ciclo `MediaExtractorCompat` + `MediaCodec` (in `AudioDecoder`);
  2. conversione di un buffer PCM interleaved (16 bit o float, 1/2/N canali) nei due
     array L/R → funzione pura in `decode/PcmDeinterleaver`;
  3. ricampionamento lineare verso 44,1 kHz → funzione pura in `decode/LinearResampler`.
- Non restituisce più `null`: ogni errore diventa `DecodeException` col motivo (nessuna
  traccia audio, formato non supportato, errore del codec, 0 frame decodificati).
- Correzioni incluse nello spostamento:
  - `MediaFormat.getInteger(KEY_PCM_ENCODING, default)` esiste solo da API 29 (minSdk 26):
    si sostituisce con `containsKey` + `getInteger(key)`;
  - `@OptIn(UnstableApi::class)` per `MediaExtractorCompat` (errore di lint).
- Comportamento invariato: mono copiato su L e R, oltre 2 canali si tengono i primi due.
  (La regola "mai mono duplicato come stereo" del modello resta un problema aperto, non
  affrontato qui.)

### `processing/SongProcessor`

Dal file scelto allo strumentale salvato nella libreria.

```kotlin
class SongProcessor(
    private val decoder: PcmDecoder,
    private val extractor: InstrumentalExtractor,
    private val saver: InstrumentalSaver,
) {
    val state: StateFlow<ProcessingState>

    /** Decodifica → removeVocals → salva. Restituisce l'Uri salvato. */
    suspend fun process(source: Uri, displayName: String): Uri

    companion object {
        /** Collega AudioDecoder, VocalRemover e RecordingSaver reali. */
        fun create(context: Context, remover: VocalRemover): SongProcessor
    }
}

fun interface PcmDecoder { fun decode(uri: Uri): StereoPcm }
fun interface InstrumentalExtractor {
    suspend fun extract(mix: StereoPcm, onProgress: (Int) -> Unit): StereoPcm
}
fun interface InstrumentalSaver { fun save(pcm: StereoPcm, displayName: String): Uri }
```

- Le tre interfacce esistono per poter testare `SongProcessor` con dipendenze finte;
  nell'app `create()` usa `AudioDecoder`, `VocalRemover.removeVocals` e
  `RecordingSaver.saveProcessedStereoAsWav`.
- `displayName` lo decide il chiamante (es. `RecordingFileNaming.instrumentalName`).
- Riferimento al mix decodificato rilasciato appena `removeVocals` restituisce, per non
  tenere in memoria due brani interi durante il salvataggio.

### `playback/LibraryPlayer`

Riproduce i WAV della libreria con ExoPlayer (`media3-exoplayer`, già dipendenza).

```kotlin
class LibraryPlayer(context: Context) {
    val state: StateFlow<PlayerState>
    fun play(uri: Uri)          // brano diverso: carica e suona; stesso brano: riprende
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}
```

- ExoPlayer configurato con `AudioAttributes` musica (`USAGE_MEDIA`,
  `AUDIO_CONTENT_TYPE_MUSIC`), `handleAudioFocus = true` e
  `setHandleAudioBecomingNoisy(true)` (pausa staccando le cuffie).
- `play(uri)` con lo stesso brano già finito riparte da 0.
- `seekTo` limita la posizione a `[0, durationMs]`.
- Dopo `release()` ogni altro metodo lancia `IllegalStateException`.

## Stati

### `ProcessingState`

```kotlin
sealed interface ProcessingState {
    data object Idle : ProcessingState
    data object Decoding : ProcessingState
    data class Processing(val percent: Int) : ProcessingState   // 0–100, da removeVocals
    data object Saving : ProcessingState
    data class Done(val savedUri: Uri) : ProcessingState
    data class Failed(val error: ProcessingError) : ProcessingState
}

enum class ProcessingError { DECODE_FAILED, INFERENCE_FAILED, SAVE_FAILED }

class ProcessingException(val error: ProcessingError, cause: Throwable) : Exception(cause)
```

- Sequenza normale: `Idle → Decoding → Processing(…) → Saving → Done(uri)`. `Done` e
  `Failed` restano finché non parte un nuovo `process()`.
- Errori come tipi, non testi: i messaggi per l'utente stanno in `strings.xml` di `:ui`
  (regola di CLAUDE.md); il dettaglio tecnico va nel log. Mappatura: eccezione di
  `decode` → `DECODE_FAILED`, di `extract` → `INFERENCE_FAILED`, di `save` →
  `SAVE_FAILED`. In caso di errore `process()` imposta `Failed` **e** lancia
  `ProcessingException`.
- Una sola elaborazione alla volta: `process()` chiamato mentre un'altra è in corso lancia
  `IllegalStateException` e non tocca lo stato.
- Annullamento: se la coroutine del chiamante viene cancellata, lo stato torna `Idle` e la
  `CancellationException` si propaga. Non resta nessun file a metà (`RecordingSaver`
  cancella già le voci incomplete). Una volta iniziato, il salvataggio non si interrompe e
  finisce in `Done(uri)` anche se nel frattempo arriva l'annullamento: il file esiste,
  quindi la UI deve saperlo.

### `PlayerState`

```kotlin
data class PlayerState(
    val currentUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,     // 0 finché ExoPlayer non la conosce
    val error: PlayerError? = null,
)

enum class PlayerError { SOURCE_UNAVAILABLE, PLAYBACK_FAILED }
```

- La posizione si legge da ExoPlayer ogni ~200 ms mentre `isPlaying`, più subito dopo
  `pause`, `seekTo` e a fine brano.
- Fine brano (`STATE_ENDED`): `isPlaying = false`, `positionMs = durationMs`.
- Errori: `PlaybackException` con codice `ERROR_CODE_IO_FILE_NOT_FOUND` o
  `ERROR_CODE_IO_NO_PERMISSION` → `SOURCE_UNAVAILABLE` (es. WAV cancellato dalla
  cartella); ogni altro codice → `PLAYBACK_FAILED`. Un nuovo `play()` azzera l'errore.

## Thread

- `SongProcessor.process` è main-safe: decodifica e salvataggio su `Dispatchers.IO`,
  `removeVocals` su `Dispatchers.Default` (lavoro di calcolo; oggi gira su IO).
- `LibraryPlayer` si costruisce e si usa solo dal main thread (vincolo di ExoPlayer);
  ogni metodo pubblico lo verifica con `check()`. Il polling della posizione gira in uno
  scope proprio su `Dispatchers.Main`, chiuso da `release()`.
- Gli `StateFlow` si leggono da qualsiasi thread; in Compose con
  `collectAsStateWithLifecycle`.
- Il `Mutex` già presente in `VocalRemover.removeVocals` resta come protezione di riserva.

## Codice legacy

- `AudioPlayer`: si elimina `decodeAudio`; `loadAndProcess` e `loadSavedRecording` usano
  `AudioDecoder`, convertendo `DecodeException` nel comportamento di prima (messaggio di
  errore via `onError`). La riproduzione `AudioTrack` (con i fix su seek e fine brano)
  resta com'è.
- `MainActivity` legacy non viene toccata.

## Test

### JVM (`gradle :engine:testCpuDebugUnitTest`)

Nuove dipendenze **solo di test** in `:engine`: `kotlinx-coroutines-test` e Robolectric
(serve per creare `Uri` nei test di `SongProcessor`). Se Robolectric non supporta ancora
compileSdk 37, i test fissano un SDK supportato con `@Config(sdk = [...])`.

- `PcmDeinterleaverTest`: 16 bit e float; 1, 2 e 6 canali; il mono finisce uguale su L e R;
  i canali oltre il secondo vengono scartati.
- `LinearResamplerTest`: lunghezza dell'uscita; 44100 → 44100 restituisce gli stessi dati;
  una sinusoide a 1 kHz campionata a 22050 Hz e ricampionata a 44100 Hz mantiene
  frequenza e ampiezza entro una tolleranza.
- `SongProcessorTest` (Robolectric + `runTest`): sequenza completa degli stati; ognuno dei
  tre errori (stato `Failed` + `ProcessingException`); annullamento durante l'elaborazione
  → `Idle`; seconda chiamata concorrente rifiutata.
- `PlayerErrorMappingTest`: codici `PlaybackException` → `PlayerError`.

### Sul dispositivo (`gradle :ui:connectedCpuDebugAndroidTest`, accanto a `BackendBenchmarkTest`)

- `AudioDecoderInstrumentedTest`: genera in cache un WAV stereo e un AAC (con
  `MediaMuxer`) di pochi secondi, li decodifica, controlla durata, canali e 44,1 kHz.
  Copre il ciclo `MediaCodec`.
- `LibraryPlayerInstrumentedTest`: suona il WAV generato; verifica `isPlaying`, posizione
  che avanza, `seekTo`, stato a fine brano, `SOURCE_UNAVAILABLE` su un `Uri` inesistente.

## Criteri di accettazione

- `AudioDecoder`, `SongProcessor`, `LibraryPlayer` esistono con le API sopra; nessuna
  dipendenza da `:ui`.
- Tutti i test sopra passano; `gradle :ui:assembleCpuDebug :ui:assembleWebgpuDebug
  :ui:assembleQnnDebug` compila.
- `:engine:lintCpuDebug` non ha più errori nei file nuovi (gli errori preesistenti in
  `MainActivity`, `CaptureSessionController` e nel manifest restano fuori scope).
- La `MainActivity` legacy importa, elabora e suona un brano come prima.
