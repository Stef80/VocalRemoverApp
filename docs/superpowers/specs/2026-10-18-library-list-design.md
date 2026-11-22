# Design: elenco delle canzoni elaborate (schermata Libreria)

Data: 2026-10-18
Dipende da: `2026-10-18-engine-processing-playback-design.md` (salvataggio in `Music/VocalRemover`
via `RecordingSaver.saveProcessedStereoAsWav`), `2026-10-18-capture-trim-editor-design.md`
(nome `<titolo>_strumentale.wav` da `RecordingFileNaming.instrumentalNameForTitle`).

## Contesto e obiettivo

Ogni brano elaborato finisce come WAV in `Music/VocalRemover` (MediaStore, `RELATIVE_PATH`
`Music/VocalRemover`); le registrazioni grezze vanno in `Music/VocalRemover/raw`. La lista
`PlayableTracks` (`ui/.../PlayableTracks.kt`) esiste ma mostra solo dati finti e nessuna
schermata la usa.

Obiettivo: una schermata **Libreria** che mostra i brani elaborati davvero presenti nella
libreria, più recenti in alto. **Aggiornamento 2026-10-24:** la Libreria è la schermata
principale (dopo lo splash, al posto di `PlayerScreen`): lista in alto, sotto il pulsante
"Importa canzone" e il link alla schermata legacy. `PlayerScreen` resta invariata nel codice ma
per ora non è raggiungibile.

## Fuori scope

- Modifiche a `PlayerScreen`.
- Riproduzione dalla lista (le card non sono cliccabili finché non viene collegata), eliminazione, rinomina.
- Aggiornamento in tempo reale (`ContentObserver`): la lista si rilegge quando la schermata
  torna visibile.
- Richiesta di permessi. Da Android 10 l'app vede senza permessi i file che ha creato lei;
  **limite noto:** dopo disinstallazione e reinstallazione i brani salvati prima non compaiono
  (restano sul disco). Si risolverà chiedendo `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE`, già
  dichiarati nel manifest di `:engine`.
- Android 8–9 (`minSdk` 26): `RecordingSaver` usa `RELATIVE_PATH`, che esiste solo da API 29,
  quindi lì il salvataggio già non funziona; sotto API 29 la libreria è vuota.

## Componenti

### `:engine` — `library/` (package `com.example.vocalremover.library`)

```kotlin
/** Brano elaborato presente in Music/VocalRemover. */
data class LibraryTrack(val uri: Uri, val title: String, val durationMs: Long)

interface LibraryRepository {
    /** Bloccante: va chiamata fuori dal main thread. Più recenti per primi. */
    fun load(): List<LibraryTrack>
    companion object { fun create(context: Context): LibraryRepository }
}
```

- **`MediaStoreLibraryRepository(context)`** (internal): sotto API 29 restituisce una lista
  vuota. Altrimenti interroga `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` con
  - proiezione `_ID`, `DISPLAY_NAME`, `DURATION`, `SIZE`;
  - selezione `RELATIVE_PATH = ? AND IS_PENDING = 0 AND DISPLAY_NAME LIKE ?`, argomenti
    `"Music/VocalRemover/"` (MediaStore salva il percorso con la barra finale, quindi `raw/`
    resta escluso) e `"%.wav"`;
  - ordinamento `DATE_ADDED DESC`.
  Un cursore `null` è una lista vuota. Eccezioni del resolver (es. `SecurityException`) si
  propagano: le gestisce il ViewModel.
- **`LibraryCursorReader.read(cursor, collection: Uri): List<LibraryTrack>`** (internal):
  converte le righe nell'ordine del cursore. Uri = `ContentUris.withAppendedId(collection, id)`.
  Separato dalla query per poterlo testare con un `MatrixCursor`.
- **Titolo** — `RecordingFileNaming.titleFromLibraryName(displayName): String`: toglie `.wav`
  (senza distinzione maiuscole/minuscole), poi il suffisso `_strumentale`. Se il risultato è
  vuoto restituisce il nome senza estensione (es. `_strumentale.wav` → `_strumentale`).
  Esempi: `Mr. Brightside_strumentale.wav` → `Mr. Brightside`; `demo.WAV` → `demo`.
- **Durata** — `LibraryDuration.resolve(durationMs: Long?, sizeBytes: Long?): Long`: se
  MediaStore dà una durata > 0 si usa quella. Altrimenti, se la dimensione è > 44 byte, la si
  calcola dal formato fisso con cui scriviamo i WAV (PCM 16 bit stereo 44,1 kHz, header 44
  byte): `(size - 44) * 1000 / 176_400`. Altrimenti 0 (= sconosciuta).

### `:ui`

- **`LibraryUiState`**: `Loading` (solo al primo caricamento), `Empty`, `Tracks(list)`,
  `Error`.
- **`LibraryViewModel(repository, ioDispatcher = Dispatchers.IO)`** + `Factory`
  (`LibraryRepository.create(app)`, come `ImportViewModel.Factory`).
  - `state: StateFlow<LibraryUiState>`, iniziale `Loading`.
  - `refresh()`: legge sul dispatcher IO. Un `refresh()` mentre ne è in corso un altro annulla
    il precedente (vince l'ultimo). Durante un refresh successivo al primo lo stato resta quello
    attuale (niente lampeggio di `Loading`). Lista vuota → `Empty`; eccezione → `Error` (anche
    se prima c'erano brani: la lista potrebbe non essere più valida); il dettaglio va nel log.
  - Nessun refresh in `init`: lo scatena la schermata quando diventa visibile.
- **`PlayableTracks`**: prende `List<LibraryTrack>` e `onTrackClick: (LibraryTrack) -> Unit`;
  la card intera è cliccabile. `PlayableTrack` (dati finti) sparisce. Durata con
  `formatMinutesSeconds`; durata 0 → `library_duration_unknown` ("--:--"). Il "▶" scritto a
  mano diventa l'icona `ic_play` decorativa (`contentDescription = null`: l'azione è sulla card).
- **`LibraryScreen(state, onTrackClick)`** (stateless): logo `logo_corallo_blu` + titolo
  "Libreria", poi in base allo stato: indicatore di caricamento, messaggio vuoto
  ("Nessuna canzone elaborata. Importane una per iniziare."), messaggio d'errore
  ("Impossibile leggere la libreria."), oppure `PlayableTracks`. `@Preview` per lista e vuoto.
- **`LibraryRoute(viewModel, onTrackClick)`**: collega il ViewModel alla schermata e chiama
  `refresh()` a ogni `ON_RESUME` con `LifecycleResumeEffect` (nuova dipendenza
  `androidx.lifecycle:lifecycle-runtime-compose`, versione `lifecycleVersion` = 2.11.0 nel
  catalogo). Così, quando verrà collegata, la lista sarà aggiornata anche rientrando dopo
  un'elaborazione. È il punto d'aggancio per il footer.
- Tutti i testi nuovi in `strings.xml` (`library_title`, `library_empty`, `library_error`,
  `library_duration_unknown`, `library_loading_description`).

## Test

- `:engine`, JVM: `titleFromLibraryName` (suffisso, estensione maiuscola, nome con punto, nome
  senza suffisso, solo suffisso); `LibraryDuration.resolve` (durata MediaStore, ripiego sulla
  dimensione, dimensione ≤ 44 o `null`).
- `:engine`, Robolectric: `LibraryCursorReader` con `MatrixCursor` (ordine conservato, Uri con
  id, `DURATION` nullo → ripiego). La query MediaStore reale non si testa in JVM (Robolectric
  non implementa il provider): si verifica a mano sul telefono.
- `:ui`, coroutines-test: stato iniziale `Loading`; `refresh` → `Tracks`; lista vuota →
  `Empty`; eccezione → `Error`; refresh successivo non torna a `Loading`; due refresh
  ravvicinati → vince l'ultimo.
- Verifica manuale: possibile solo quando `LibraryRoute` sarà collegata al footer (nessun
  collegamento temporaneo nel codice). Atteso: i brani elaborati compaiono con il titolo senza
  `_strumentale`, più recenti in alto, senza le registrazioni di `raw/`.
