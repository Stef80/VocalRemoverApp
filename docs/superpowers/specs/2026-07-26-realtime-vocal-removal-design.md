# Design: Motore di rimozione voce in tempo reale (streaming)

Data: 2026-07-26
Sotto-progetto: 1 di 3 (rimozione voce realtime). Cattura audio di sistema
(MediaProjection) e testi sincronizzati (Musixmatch o simile) sono
sotto-progetti separati, fuori scope da questa spec.

## Contesto

L'app VocalRemoverApp attualmente elabora un intero file audio in un'unica
passata (`VocalRemover.removeVocals`) usando un modello Spleeter 2-stems
convertito in TFLite, prima di avviare la riproduzione con `AudioTrack`.

L'obiettivo del progetto (uso karaoke) richiede che la voce venga rimossa
**durante** la riproduzione, non prima, per poter in futuro estendere l'uso
anche a sorgenti audio catturate a runtime.

Vincolo tecnico noto e fuori scope: la cattura dell'audio in uscita da app
di terze parti come Spotify è bloccata dalla policy
`ALLOW_CAPTURE_BY_NONE` che tali app impostano; non verrà affrontato un
bypass di questa protezione (violerebbe i Termini di Servizio e le
protezioni sui contenuti). La cattura di sistema, quando affrontata in una
spec futura, riguarderà solo sorgenti che consentono esplicitamente la
cattura.

Il toolchain Python attuale (`convert_spleeter.py`) richiede Python ≤3.11
(spleeter + tensorflow 2.12), incompatibile con l'ambiente disponibile
(Python 3.13). Questo motiva anche il cambio di modello.

## Decisioni chiave

- **Modello**: Open-Unmix (target "vocals" soltanto), più leggero di
  Demucs, usa lo stesso principio a maschera sullo spettrogramma di
  magnitudine già implementato in `StftProcessor.kt`
  (`nFft=4096`, `hopLength=1024` — combaciano senza modifiche).
- **Runtime mobile**: ONNX Runtime Mobile
  (`com.microsoft.onnxruntime:onnxruntime-android`) al posto di
  TensorFlow Lite.
- **Conversione**: si esporta in ONNX solo la rete che mappa
  spettrogramma di magnitudine → maschera vocals. STFT/ISTFT restano in
  Kotlin (`StftProcessor.kt`, invariato). L'accompagnamento si ottiene
  come `accompMask = 1 - vocalsMask`.
- **Streaming**: elaborazione a chunk (~3s, configurabile) con crossfade
  (finestra Hann, ~25% overlap) tra chunk successivi, invece
  dell'elaborazione "tutta in una volta" attuale.
- **Latenza accettata**: dell'ordine di qualche secondo (durata di un
  chunk + tempo di inferenza), accettabile per uso karaoke perché il
  testo/video sincronizzato segue i timestamp del file originale e solo
  l'audio viene ritardato.

## Architettura

```
File audio ──▶ MediaExtractor/MediaCodec (AudioPlayer, invariato)
             ──▶ buffer PCM mono 44.1kHz float
                     │ (ogni ~3s di audio accumulato)
                     ▼
             RealtimeVocalRemover
               ├─ StftProcessor.stft(chunk + contesto precedente)
               ├─ ONNX Runtime: magnitudine → vocalsMask
               ├─ accompMask = 1 - vocalsMask
               ├─ applica accompMask alla STFT complessa
               ├─ StftProcessor.istft(...)
               └─ crossfade con coda del chunk precedente
                     │
                     ▼
             AudioTrack.write(chunk elaborato)
```

## Componenti

### `convert_openunmix.py` (sostituisce `convert_spleeter.py`)

- Carica solo il target "vocals" di Open-Unmix:
  `torch.hub.load('sigsep/open-unmix-pytorch', 'umxhq', target='vocals')`.
- Esporta in ONNX unicamente la sotto-rete
  magnitudine → maschera vocals (input `[1, frames, 2049]`, output
  `[1, frames, 2049]`).
- Compatibile con Python 3.13 (PyTorch), nessun bisogno di ambienti
  virtuali con Python più vecchio.
- Include una funzione di verifica analoga a `verify_tflite_model`: carica
  l'ONNX con `onnxruntime`, controlla shape/range di input-output su un
  tensore di test casuale.
- Output: `openunmix_vocals.onnx`, da copiare in
  `app/src/main/assets/`.

### `RealtimeVocalRemover` (nuova classe Kotlin)

- Sostituisce l'uso "batch" di `VocalRemover` (che viene rimosso/superato).
- Usa ONNX Runtime Mobile invece di TFLite.
- Mantiene un buffer PCM scorrevole dei campioni decodificati in arrivo.
- Ad ogni chunk da `CHUNK_SECONDS` (~3s, costante configurabile):
  1. STFT del chunk, includendo margine di contesto dal chunk precedente
     per continuità spettrale.
  2. Inferenza ONNX → `vocalsMask`.
  3. `accompMask = 1 - vocalsMask`.
  4. Applica `accompMask` alla STFT complessa.
  5. ISTFT → segnale ricostruito.
  6. Crossfade (finestra Hann, ~25% overlap) con la coda del chunk
     precedente già emesso, per evitare click/discontinuità udibili ai
     bordi.
- Callback: `onChunkReady(FloatArray)`, `onProgress(Int)`,
  `onError(Throwable)`, `onPerformanceWarning()` — stile coerente con i
  callback esistenti di `AudioPlayer`.

### `AudioPlayer` (modifiche)

- Invece di chiamare l'elaborazione una sola volta su tutto il file prima
  di avviare `AudioTrack`, inoltra i campioni decodificati a
  `RealtimeVocalRemover` man mano che arrivano.
- Scrive su `AudioTrack` i chunk elaborati appena pronti tramite
  `onChunkReady`.
- Prebuffering iniziale di 1-2 chunk prima di avviare la riproduzione, per
  assorbire il jitter di inferenza.

## Gestione errori

- **Modello ONNX assente/corrotto**: eccezione controllata in fase di
  init di `RealtimeVocalRemover`; propagata via `onError` (stesso
  contratto già usato da `AudioPlayer`). La UI mostra un messaggio e non
  avvia la riproduzione.
- **Inferenza più lenta della durata reale del chunk** (dispositivo
  lento): `RealtimeVocalRemover` monitora il rapporto tempo-di-inferenza /
  durata-chunk; se supera una soglia (es. 1.5×) notifica
  `onPerformanceWarning` così la UI può suggerire di ridurre la qualità o
  la dimensione del chunk.
- **Buffer underrun in `AudioTrack`**: mitigato dal prebuffering iniziale;
  gestione dell'errore stesso comportamento già esistente.

## Testing

- **Unit test Kotlin** (`testDebugUnitTest`): `RealtimeVocalRemoverTest`
  con un modello ONNX fittizio di dimensioni ridotte (mock), per
  verificare:
  - chunking corretto (dimensioni, contesto tra chunk successivi);
  - crossfade senza click/discontinuità (continuità di energia ai bordi);
  - calcolo corretto di `accompMask = 1 - vocalsMask`.
- **Script Python**: `convert_openunmix.py` verifica shape/range di
  input-output dell'ONNX esportato su un tensore di test casuale.
- **Test manuale end-to-end**: riproduzione di un file noto, verifica
  soggettiva che la voce sia rimossa e assenza di artefatti udibili ai
  confini dei chunk.

## Nota UI

Questa spec non include modifiche UI (l'app usa oggi XML/View binding in
`MainActivity`). Le prossime spec che toccano l'UI (es. esposizione di
`onPerformanceWarning`, controlli per la cattura di sistema, testi
sincronizzati) dovranno essere implementate in **Jetpack Compose**
anziché estendere il layout XML esistente.

## Fuori scope (sotto-progetti futuri)

- Cattura audio di sistema da altre app (`MediaProjection` +
  `AudioPlaybackCaptureConfiguration`), limitata a sorgenti che
  consentono esplicitamente la cattura.
- Testi sincronizzati (Musixmatch o simile) per i brani riprodotti
  dall'app.
