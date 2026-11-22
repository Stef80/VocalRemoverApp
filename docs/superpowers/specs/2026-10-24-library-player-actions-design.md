# Design: riproduzione, rinomina ed eliminazione dalla Libreria

Data: 2026-10-24
Dipende da: `2026-10-18-library-list-design.md` (Libreria come schermata principale).

## Obiettivo

- **Tocco** su un brano della Libreria → si apre `PlayerScreen` e il brano parte.
- **Tocco lungo** → menu con **Rinomina** ed **Elimina**.

## Fuori scope

- Mini-player nella Libreria, riproduzione in background, coda/brano successivo.
- Taglio di un brano già elaborato (l'editor resta solo per le catture in attesa).
- Testi veri: la card dei testi di esempio di `PlayerScreen` resta com'è.

## `:engine` — `LibraryRepository`

```kotlin
sealed interface LibraryEditResult {
    data object Done : LibraryEditResult
    /** File non creato da questa installazione: serve il consenso dell'utente, poi si riprova. */
    data class NeedsConsent(val intentSender: IntentSender) : LibraryEditResult
}
fun delete(uri: Uri): LibraryEditResult        // bloccanti, fuori dal main thread
fun rename(uri: Uri, title: String): LibraryEditResult
```

- `delete`: `contentResolver.delete(uri)`. `rename`: `update` di `DISPLAY_NAME` a
  `RecordingFileNaming.instrumentalNameForTitle(title) + ".wav"` (MediaStore rinomina il file;
  l'Uri resta lo stesso).
- Da API 29 MediaStore lancia `RecoverableSecurityException` per i file di un'installazione
  precedente → `NeedsConsent(userAction.actionIntent.intentSender)`. Ogni altra eccezione si propaga.

## `:ui`

### Player — `PlayerViewModel(player: PreviewPlayer)` (Activity-scoped)

- `open(track)`: memorizza il brano e lo riproduce da capo (`stop()` + `play(uri)`).
- `togglePlayback`/`play`/`pause`, `seekTo(ms)`, `rewind()`/`forward()` = ∓10 s limitati a
  [0, durata], `leave()` = pausa (si chiama uscendo dal player).
- `state: StateFlow<PlayerUiState?>` (`track`, `isPlaying`, `positionMs`, `durationMs`, `error`):
  `null` finché non si è aperto un brano.
- `onCleared` rilascia il player.

### `PlayerScreen`

Struttura invariata (header con logo, card testi, quattro pulsanti, "Importa canzone",
link alla schermata legacy). Cambia:
- freccia indietro nell'header e `BackHandler` → torna alla Libreria (e mette in pausa);
- sottotitolo = titolo del brano al posto del segnaposto;
- sopra i pulsanti una barra di avanzamento trascinabile con `m:ss / m:ss`;
- `<<` / `>>` = indietro / avanti di 10 s; `>` e `||` riproducono / mettono in pausa;
- errore di riproduzione → messaggio sotto la barra.

### Libreria

- Card: `combinedClickable` (tocco → `onTrackClick`, tocco lungo → menu a tendina sulla card con
  Rinomina / Elimina, con vibrazione).
- **Rinomina**: `AlertDialog` con campo testo precompilato; "Salva" disabilitato se vuoto o
  invariato.
- **Elimina**: `AlertDialog` "Eliminare «titolo»? Il file verrà cancellato dal telefono."
- `LibraryViewModel.rename(track, title)` / `delete(track)` su IO; esito:
  - `Done` → `refresh()`;
  - `NeedsConsent` → evento `LibraryEvent.ConsentRequired(intentSender)`; la schermata lo lancia
    con `StartIntentSenderForResult`; `onConsentResult(granted)` riprova l'azione una sola volta
    (con consenso negato non si fa nulla);
  - eccezione → evento `RenameFailed` / `DeleteFailed` → snackbar.
- Se si elimina o rinomina il brano aperto nel player: il player si ferma (eliminazione) o
  aggiorna il titolo (rinomina).

## Test

- `:ui`: `PlayerViewModel` con `PreviewPlayer` finto (apertura, ±10 s con limiti, pausa all'uscita,
  aggiornamento/fermata dopo rinomina/eliminazione); `LibraryViewModel` (rinomina/eliminazione ok →
  refresh; consenso → evento e nuovo tentativo solo se concesso; errore → evento; titolo vuoto
  ignorato).
- La parte MediaStore reale (`delete`/`update`, `RecoverableSecurityException`) si verifica a mano:
  Robolectric non implementa il provider.
