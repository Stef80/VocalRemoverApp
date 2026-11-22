# Design: un'unica app con scelta automatica dell'acceleratore (QNN / WebGPU / CPU)

Data: 2026-11-20
Dipende da: `2026-07-26-realtime-vocal-removal-design.md` (obiettivo tempo reale, non coperto qui).

## Intento

Oggi esistono tre varianti di build (`cpu`, `webgpu`, `qnn`), ognuna con il suo `applicationId`, e
la variante giusta per il telefono va scelta a mano. L'app va distribuita su **Play Store**, dove non
si può consegnare una variante diversa per chip: Play divide gli APK solo per ABI, densità, lingua e
SDK, e il targeting per SoC vale solo per gli asset pack, non per le librerie native (QNN le vuole in
`nativeLibraryDir`).

Lo scopo vero è la velocità: l'engine deve poter rimuovere la voce **mentre la musica suona**, quindi
ogni telefono deve usare l'acceleratore più veloce che ha *e che dà audio corretto*. La CPU da sola è
troppo lenta (~6 s per chunk da ~5,9 s di audio) e sull'OPPO crasha a metà elaborazione; gli
Snapdragon hanno bisogno di QNN (Realme RMX3301: ~245 ms/chunk), i MediaTek di WebGPU (OPPO CPH2791:
~1,5 s/chunk).

Soluzione: **un solo APK/AAB** con una build di ONNX Runtime compilata da noi che contiene CPU +
WebGPU + QNN, e la **scelta del backend a runtime**, verificata sul telefono.

## Goal

- Un unico `applicationId`: **`com.stef.vocalremover`**, niente suffissi.
- Al primo avvio (e dopo ogni aggiornamento dell'app o del sistema) l'app sceglie da sola il backend:
  prova tutti quelli disponibili, scarta quelli che falliscono o danno audio sbagliato e tiene il più
  veloce.
- La scelta viene ricordata: gli avvii successivi non rifanno la prova.
- Se durante un'elaborazione vera il backend scelto fallisce, si passa al successivo senza perdere
  la canzone.
- La build di ORT è riproducibile da script nel repo.

## Non-goal

- Streaming in tempo reale (elaborazione mentre si ascolta): progetto separato, successivo.
- Il crash della CPU sull'OPPO CPH2791: bug separato. Qui la CPU resta l'ultimo ripiego, come oggi.
- Throttling termico (`ThermalControllHandler tempLevel=[4]` sull'OPPO): monitoraggio separato.
- ABI diverse da arm64-v8a.
- Migrazione dei dati delle app di sviluppo `com.example.vocalremover.*`: l'app non è ancora su Play,
  quindi la nuova app parte con libreria vuota.

## Anti-goal (fallimento anche se "funziona")

- **Scegliere un backend solo perché è veloce.** WebGPU sul Realme RMX3301 produce audio rovinato
  senza crashare: un backend va accettato solo dopo il controllo di qualità.
- **Ricadere silenziosamente sulla CPU credendo di usare QNN.** ORT, se QNN non si carica (manifest,
  packaging, libreria mancante), esegue tutto su CPU senza errori: questo va rilevato.
- Rendere più lenta o peggiore la situazione attuale su Realme o OPPO (riferimento: tabella "Misure").
- Far aspettare l'utente in silenzio i ~40 s di compilazione HTP senza dirgli cosa succede.

## Misure di partenza (spike del 2026-11-15)

Benchmark `BackendBenchmarkTest`, 20 s di audio sintetico, SNR contro CPU nello stesso processo:

| Telefono | Backend | ORT ufficiale | ORT custom (CPU+WebGPU+QNN) | SNR |
|---|---|---|---|---|
| OPPO CPH2791 (MT6993) | WebGPU | 10,6 s | 10,2 s | 121 dB |
| Realme RMX3301 (SM8450) | QNN | 3,29–3,30 s | 3,23–3,29 s | 63,6 dB |

`sessionLoadMs` QNN al primo avvio dopo l'installazione: **36–48 s** (compilazione del grafo HTP),
poi ~0,5 s dal context cache. Uguale tra build ufficiale e custom. In una ripetizione la compilazione
è stata rifatta senza reinstallare: va capito il perché (vedi "Lasciato al builder").

## Approcci considerati

1. **AAR QNN ufficiale per tutti** (`onnxruntime-android-qnn`, CPU + QNN). Niente build custom, ma
   niente WebGPU: l'OPPO tornerebbe sulla CPU, che è lenta e crasha. **Scartato.**
2. **ORT compilato da noi con CPU + WebGPU + QNN, scelta a runtime.** Una libreria, un APK,
   costo: manutenzione della build (script, QAIRT, NDK). **Scelto** dall'utente: «gli snapdragon ne
   hanno bisogno, […] la qualità ne risente parecchio».
3. **Entrambi gli AAR ufficiali nello stesso APK, caricati a mano.** Stesso nome di libreria
   (`libonnxruntime.so`) e stesse classi Java `ai.onnxruntime.*`: servirebbero rinomina e
   classloader separati. Fragile. **Scartato.**
4. **Dynamic feature module per backend / asset pack per SoC.** Conflitto su `libonnxruntime.so` e
   sulle classi ORT tra modulo base e feature; gli asset pack non possono fornire librerie native
   caricabili da QNN. **Scartato.**

## Decisioni

### 1. Build di ONNX Runtime

- **Ricetta**: `default_full_aar_build_settings.json` + `default_qnn_aar_build_settings.json` di ORT
  1.24.3, unite: `--use_webgpu`, `--use_qnn=static_lib`, tolti `--use_nnapi` e `--use_xnnpack`
  (non usati; XNNPACK già scartato), `build_abis = ["arm64-v8a"]`, min SDK 26, target SDK 36,
  `--parallel 4`, `CMAKE_POLICY_VERSION_MINIMUM=3.5` (cmake 4.x).
- **Versioni bloccate**: ORT **1.24.3** (tag `v1.24.3`), QAIRT **2.42.0.251225** (la versione con cui
  Microsoft compila 1.24.3, allineata a `com.qualcomm.qti:qnn-runtime:2.42.0`), NDK
  **28.0.13004108**, JDK **17** per il packaging AAR (AGP 7.4.2 interno a ORT fallisce con JDK 21).
  La regola "ORT ≥ 1.24.3 per l'allineamento a 16 KB" resta: l'AAR dello spike è allineato a `0x4000`.
- **Script**: `scripts/build-ort-aar.sh` (nuovo) fa tutto quello che lo spike ha fatto a mano:
  scarica/verifica QAIRT, NDK e JDK 17 se mancano, crea il venv con `cmake`, `ninja`, `pkgconf`
  (con link `pkg-config`), clona ORT al tag, scrive la ricetta, lancia `build_aar_package.py` con
  `ANDROID_NDK_HOME` esportata, e pubblica l'AAR nel repository Maven locale del progetto
  (punto successivo). Gli strumenti restano fuori dal repo (default `~/ort-build`, `~/qairt`,
  sovrascrivibili da variabili d'ambiente). La ricetta JSON sta in `scripts/ort/`, versionata.
- **Dove sta l'AAR** *(mia scelta)*: repository Maven nel repo, `third_party/maven/`, versionato in
  git (~10 MB). Motivo: chiunque cloni il progetto compila l'app senza rifare la build di ORT
  (oltre un'ora) e senza scaricare 1,5 GB di QAIRT. Il modello ONNX resta fuori da git come oggi.
- **Coordinate Maven proprie** *(mia scelta)*: `com.stef.vocalremover.thirdparty:onnxruntime-android-vr:1.24.3`
  (lo script riscrive il POM). Lo spike usava le stesse coordinate dell'AAR ufficiale QNN con un
  filtro sul repository: funziona, ma un errore di ordine dei repository farebbe usare l'AAR
  ufficiale senza accorgersene. La dipendenza transitiva `com.qualcomm.qti:qnn-runtime:2.42.0` resta
  nel POM e arriva da Maven Central.

### 2. Un'unica app

- Rimossa la dimensione di flavor `backend` da `:engine` e `:ui`, con `BuildConfig.EXECUTION_BACKEND`.
  Restano i build type `debug` / `release`.
- `:ui`: `applicationId = "com.stef.vocalremover"`, nessun `applicationIdSuffix`,
  `ndk { abiFilters += "arm64-v8a" }`. Il namespace Gradle (`com.example.vocalremover.ui`) e i
  package Kotlin non cambiano: è fuori scope.
- `:engine`: un'unica dipendenza ORT, quella del punto 1.
- Quello che oggi vale solo per `qnn` vale per tutti:
  - `engine/src/qnn/AndroidManifest.xml` (`<uses-native-library libcdsprpc.so required="false">`)
    confluisce nel manifest `main` di `:engine`. Su telefoni non Qualcomm la libreria non esiste e
    `required="false"` evita il blocco dell'installazione.
  - `useLegacyPackaging = true` per tutte le varianti (blocco `androidComponents.onVariants` con
    `withFlavor("backend" to "qnn")` sostituito da `packaging.jniLibs.useLegacyPackaging = true`).
- **Firebase**: `google-services.json` deve contenere il client `com.stef.vocalremover`. Azione
  dell'utente su Firebase Console (aggiungere l'app Android e scaricare il nuovo file); senza, la
  build fallisce al task `processDebugGoogleServices`. Oggi Firebase è usato solo per Test Lab
  (nel codice c'è solo la BOM).

### 3. Scelta del backend a runtime

**Contratto in `:engine`** (moduli collegati tramite interfacce, implementazioni concrete collegate in
`:ui`):

```kotlin
interface BackendSelector {
    /** Backend to use now; runs the probe if no valid choice is stored. Background thread only. */
    suspend fun current(): ExecutionBackend
    /** Marks [backend] as broken on this install and returns the next candidate. */
    suspend fun reportFailure(backend: ExecutionBackend, cause: Throwable): ExecutionBackend
}
```

`VocalRemover` riceve il backend come oggi (parametro del costruttore); non sceglie da solo.
L'implementazione concreta (`ProbingBackendSelector`, in `:engine`) si istanzia in `:ui`
(`ImportViewModel` e chiunque crei un `VocalRemover`).

**Candidati, in ordine**:

- SoC Qualcomm (`Build.SOC_MANUFACTURER == "QTI"` su API 31+, altrimenti `Build.HARDWARE` che inizia
  con `qcom`): **QNN → WebGPU → CPU**.
- Altri SoC: **WebGPU → CPU**.

L'elenco dato dal SoC serve solo a non perdere tempo: QNN su un non-Qualcomm fallirebbe comunque.
La decisione vera la prende la prova.

**Scelta (decisa dall'utente): si provano tutti i candidati, CPU compresa, e vince il più veloce
tra quelli che passano il controllo di qualità.** La qualità viene prima: un backend veloce ma con
audio sbagliato è scartato comunque (anti-goal). Approcci scartati per questa scelta: "il primo
candidato corretto in ordine fisso" (può tenere una GPU più lenta della CPU) e "soglia fissa di
tempo" (approssimativa). Il costo accettato è un primo avvio più lungo: ~40 s di compilazione
HTP più alcuni secondi per ogni chunk di prova (la CPU ~6 s sul Realme).

**Aggiornamento (decisione dell'utente, 2026-11-21):** la CPU, ultima nell'elenco, viene provata
**solo se nessun candidato precedente ha passato la prova**. Misurato sul telefono, il chunk di prova
costa alla CPU ~40 s sul Realme e ~20 s sull'OPPO (×2 esecuzioni): provarla sempre portava il primo
avvio a ~3,5 min sul Realme. Si rinuncia al caso, mai osservato, di una CPU più veloce
dell'acceleratore; la CPU resta comunque l'ultimo ripiego.

**Prova di un candidato** (ogni candidato dell'elenco, in sequenza):

1. Crea la sessione. Per QNN si aggiunge `session.disable_cpu_ep_fallback = "1"`: se QNN non si
   carica, o non riesce a prendere tutti i nodi del grafo, la creazione fallisce invece di girare in
   silenzio su CPU (anti-goal). La prova dev'essere fatta con le stesse opzioni dell'uso reale,
   compreso il context cache HTP: la sessione compilata durante la prova è quella che verrà
   riusata. *Da verificare dal builder* sul Realme: con questa opzione la sessione deve ancora
   crearsi, cioè il modello dev'essere tutto su QNN come misurato oggi. Se non lo è, vedi
   "Lasciato al builder".
2. Esegue **un chunk** (`[1,4,2560,256]`) di un segnale di prova fisso e confronta l'uscita con
   un'uscita di riferimento calcolata con la CPU EP. Il backend passa se l'uscita è tutta finita
   (niente NaN/Inf), non è silenziosa e ha **SNR ≥ 20 dB** rispetto al riferimento (stessa soglia
   di `BackendBenchmarkTest`; i backend buoni misurano 63–121 dB, quello rotto molto meno).
3. Se la sessione o il run lanciano un'eccezione, il candidato è scartato e si passa al successivo.
   **Aggiornamento (revisione finale, 2026-11-21):** si salvano come scarto solo gli errori
   attribuibili al backend (`OrtException`, `UnsatisfiedLinkError`/`LinkageError`, uscita non valida,
   verdetto di qualità). Gli errori dell'ambiente (`IOException`, per esempio disco pieno durante la
   copia del modello, e `OutOfMemoryError`) non dicono nulla sul backend: l'esito vale solo per il
   processo corrente e al prossimo avvio la prova si ripete. La copia del modello e la lettura del
   riferimento si fanno una volta sola prima dei candidati; se falliscono la prova si interrompe
   senza salvare nulla e fino al prossimo avvio si usa la CPU.
4. **Tempo**: si misura la durata di `session.run` sul chunk di prova, escludendo la creazione della
   sessione. *Mia scelta*: il chunk viene eseguito due volte e si tiene il tempo della seconda,
   perché il primo run di WebGPU include la compilazione degli shader e non è rappresentativo.

Tra i candidati che hanno passato i punti 1–3 vince quello con il tempo più basso. Tempi e motivi
di scarto di tutti i candidati vengono salvati con la scelta e scritti nei log: serviranno al
progetto del tempo reale.

**Riferimento della prova** *(mia scelta)*: l'input del chunk di prova si genera sul telefono dal
segnale sintetico deterministico (`SyntheticStereoSignal`, seed fisso; va spostato da
`benchmarkShared` a `main` o duplicato in forma minima). Solo l'**uscita attesa** è un asset
precalcolato con ORT CPU (`engine/src/main/assets/backend_probe/`), salvata in fp16. Come uscita si
confronta l'ISTFT stereo del chunk (261 120 frame, canale L poi R), non il tensore
`[1,4,2560,256]`: l'asset pesa 1 044 480 byte (~1 MB) invece dei ~10,5 MB in float32 del tensore. In alternativa si calcolerebbe il riferimento CPU sul telefono, ma costerebbe ~6 s in più alla
prima apertura, e sull'OPPO la CPU è proprio il backend instabile. Il riferimento CPU calcolato su
x86 differisce da quello ARM di molto meno della soglia di 20 dB.

**CPU**: viene provata come gli altri e confrontata con l'asset, quindi passa per costruzione salvo
crash. Resta comunque l'ultimo ripiego: se nessun candidato passa, CPU compresa, si usa la CPU come
oggi. Un crash nativo della CPU durante la prova (il bug noto sull'OPPO) viene gestito dal marcatore
descritto sotto: la prova riparte al prossimo avvio saltando la CPU.

**Memoria della scelta**: in `SharedPreferences` (`backend_choice`), insieme a una chiave che
invalida la scelta quando cambia qualcosa che può cambiare il risultato:
`installKey` (`lastUpdateTime`, come la cache del modello) + `Build.FINGERPRINT` (un aggiornamento di
sistema cambia i driver GPU/NPU). Si salvano anche i backend scartati, con il motivo, per i log.

**Fallimento durante l'uso**: se `removeVocals` lancia un'eccezione ORT con un backend diverso dalla
CPU, `:ui` chiama `reportFailure`, ricrea il `VocalRemover` con il backend successivo e rielabora la
stessa canzone dall'inizio. Il backend fallito resta scartato fino al prossimo cambio di chiave.
Anche un'uscita **silenziosamente sbagliata** durante l'uso reale conta come fallimento *(mia
scelta)*: dopo ogni chunk si fa un controllo economico (tutti valori finiti, uscita non silenziosa se
l'ingresso non lo è) e, se fallisce, si segue lo stesso percorso di `reportFailure`.

Un crash nativo (processo ucciso) non si può intercettare. *Mia scelta*: prima della prova e prima
di ogni elaborazione si scrive in preferenze il marcatore "in corso con backend X", e lo si cancella
alla fine. Un marcatore rimasto all'avvio **non basta** da solo: l'app può essere stata chiusa
dall'utente durante i ~40 s di compilazione HTP, o uccisa dal sistema in background, e scartare
QNN per questo violerebbe gli anti-goal. Quindi:
- API 30+: si legge `ActivityManager.getHistoricalProcessExitReasons` e X è scartato solo se
  l'ultima uscita del processo è `REASON_CRASH_NATIVE` o `REASON_CRASH`;
- API 26–29: X è scartato solo dopo **due** marcatori rimasti consecutivi con lo stesso backend.
Il marcatore vale anche per la CPU, ma la CPU non ha ripieghi: un suo crash viene solo registrato
nei log (il crash della CPU sull'OPPO è un bug separato).

### 4. I ~40 secondi di compilazione HTP

- Un'animazione dedicata per l'attesa del primo avvio è **fuori scope**: l'utente la vuole, ma in un
  intervento successivo. Qui basta il testo di avanzamento descritto sotto.
- La prova parte **in background al primo avvio**, da `MainActivity`/splash, senza bloccare la UI:
  l'utente può navigare la Libreria nel frattempo.
- Se l'utente avvia un'elaborazione mentre la prova è in corso, l'elaborazione la aspetta e la
  barra di avanzamento mostra un testo dedicato: «Preparazione dell'acceleratore per questo
  telefono…», in `strings.xml` (senza «solo la prima volta»: la prova si ripete dopo ogni
  aggiornamento dell'app o del sistema). Nessun dialogo modale. Se durante l'uso il backend fallisce
  e la canzone riparte su quello successivo, la barra mostra «Acceleratore non disponibile,
  riprovo…» finché la nuova elaborazione non avanza.
- Si usa un normale coroutine scope legato all'app, non WorkManager *(mia scelta)*: la prova serve
  solo ad app aperta. Se l'app viene chiusa a metà, la prova riparte al prossimo avvio, ed è innocua
  perché il context cache HTP si salva solo a compilazione finita.

### 5. Benchmark e Test Lab

- `BackendBenchmarkTest`: il backend non viene più da `BuildConfig`, ma dall'argomento
  dell'instrumentation `-e backend cpu|webgpu|qnn`. Con `-e backend auto`, il default, usa
  `ProbingBackendSelector` e riporta nel JSON sia il backend scelto sia i candidati scartati.
- `scripts/run-testlab.sh <cpu|webgpu|qnn|auto>`: un solo APK di app e un solo APK di test, con
  l'argomento passato come `-e backend`. Le liste di dispositivi per backend restano.
- Comandi Gradle senza flavor: `:ui:assembleDebug`, `:ui:connectedDebugAndroidTest`,
  `:engine:testDebugUnitTest`, `:ui:lintDebug`.

### 6. Documentazione

Da aggiornare: `CLAUDE.md` (sezioni Commands, Modules and flavors, Backend constraints),
`.github/copilot-instructions.md`, README (build di ORT, nuovo `applicationId`).

## Vincoli

- `:engine` non dipende da `:ui`. I moduli parlano tramite interfacce; le implementazioni concrete
  si collegano in `:ui`.
- Nessun testo hard-coded nella UI: le nuove stringhe vanno in `ui/src/main/res/values/strings.xml`.
- Commenti e log in inglese; documentazione in italiano.
- Restano validi tutti i requisiti QNN: `uses-native-library`, legacy packaging, `batch_size = 1`,
  context cache HTP con chiave di installazione (in `noBackupFilesDir`, escluso dal backup
  automatico: a ~42 MB supererebbe il limite di 25 MB e farebbe saltare tutto il backup dell'app), nessun override di `ADSP_LIBRARY_PATH`.
- Nessun `git commit` da parte dell'assistente.

## Costi noti

- **Dimensione**: con legacy packaging per tutti, le librerie `qnn-runtime` (~200 MB non compresse,
  Skel per HTP v68–v81 + `libQnnHtpPrepare.so` da 84 MB) vengono estratte su disco anche su telefoni
  non Qualcomm. L'APK debug dello spike pesa 156 MB. *Mia scelta*: escludere con `packaging.jniLibs.excludes`
  i backend QNN inutilizzati (`libQnnDsp*`, `libQnnGpu*`, i `libQnnHtp*` di generazioni <v68 se
  presenti), dopo aver verificato sul Realme che QNN funzioni ancora. Il resto si accetta.
- **Manutenzione**: aggiornare ORT significa rifare la build: script, nuova versione di QAIRT
  allineata, verifica sui due telefoni.

## Lasciato al builder

- La struttura interna di `ProbingBackendSelector` e la generazione degli asset di prova, cioè quale
  chunk del segnale sintetico (`SyntheticStereoSignal`, seed fisso) usare e in che formato salvarlo.
- Se `session.disable_cpu_ep_fallback=1` fa fallire QNN sul Realme perché qualche nodo resta su CPU:
  allora niente opzione, e si rileva il ripiego silenzioso confrontando il tempo del chunk di prova
  con una soglia (QNN ~250 ms contro CPU ~6 s), registrando il motivo nei log.
- Perché in una ripetizione dello spike il context cache HTP è stato ricompilato senza reinstallare
  (forse un run interrotto che ha lasciato un file non valido, poi cancellato da `createQnnSession`).
  Da capire prima di affidarsi al cache per la prova.
- Ordine e granularità dei commit, test unitari della logica di scelta (scelta del più veloce tra i
  corretti, invalidazione, marcatore di crash) con un finto prober.
- Se un candidato con la sessione già creata durante la prova venga tenuto aperto e riusato da
  `VocalRemover`, per evitare di ricrearla, o chiuso e ricreato. Va bene in entrambi i modi, purché
  il context cache HTP non venga compilato due volte.

## Prerequisiti a carico dell'utente

- Aggiungere l'app Android `com.stef.vocalremover` al progetto Firebase e sostituire
  `ui/google-services.json`, che oggi contiene solo `com.example.vocalremover.cpu/.webgpu/.qnn`.
  Senza questo passo la build si ferma.
