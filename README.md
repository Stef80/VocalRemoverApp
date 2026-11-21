# VocalRemoverApp

App Android per la separazione voce/strumentale on-device tramite ONNX Runtime e un modello UVR-MDX-NET-Inst_HQ_5.

## Setup del modello ONNX (obbligatorio)

Il modello `vocal_remover_fp32.onnx` (~59MB, UVR-MDX-NET-Inst_HQ_5) **non è incluso nel repository** perché supera il limite di 100MB imposto da GitHub (o comunque per non appesantire il repo). Va scaricato manualmente prima di poter compilare/eseguire l'app.

1. Scarica `UVR-MDX-NET-Inst_HQ_5.onnx` da [Ultimate Vocal Remover models](https://github.com/TRvlvr/model_repo/releases) (o dal repository/hub da cui è stato ottenuto).
2. Rinominalo in `vocal_remover_fp32.onnx`.
3. Copialo in `engine/src/main/assets/vocal_remover_fp32.onnx` (è il nome che carica
   `VocalRemover.MODEL_ASSET`).

Un eventuale `engine/src/main/assets/vocal_remover.onnx` serve solo come ingresso di
`convert_fp16.py` ed è escluso dall'APK (`ui/build.gradle.kts`): con quel nome l'app non trova il
modello.

Il file è elencato in `.gitignore` (pattern `*.onnx`): resterà sul tuo disco ma non verrà mai committato per errore. Ogni sviluppatore/macchina di build deve ripetere questo passaggio dopo il clone.

## Build

Prerequisito Firebase: serve `ui/google-services.json` (non è nel repository, è in `.gitignore`)
con il client `com.stef.vocalremover`. Senza questo file la build fallisce al task
`processDebugGoogleServices`: scaricalo dalla console Firebase.

```bash
gradle :ui:assembleDebug
gradle :engine:testDebugUnitTest :ui:testDebugUnitTest
```

Moduli: `:ui` è il modulo application (APK unico `ui/build/outputs/apk/debug/ui-debug.apk`,
launcher Compose `MainActivity`, application ID `com.stef.vocalremover`, solo `arm64-v8a`);
`:engine` è la library con decodifica, inferenza ONNX, cattura, elaborazione (`SongProcessor`) e
riproduzione della libreria; non ha schermate proprie.
Non ci sono più flavor: c'è una sola app e il backend (CPU, WebGPU o QNN) si sceglie a runtime.
I candidati dipendono dal SoC (Snapdragon: QNN → WebGPU → CPU; altri: WebGPU → CPU). Al primo
avvio, e dopo ogni aggiornamento dell'app o del sistema, `ProbingBackendSelector` li prova su un
chunk del modello e tiene il più veloce tra quelli che danno audio corretto (SNR ≥ 20 dB rispetto al
riferimento CPU in `engine/src/main/assets/backend_probe/`). Un errore dovuto all'ambiente
(spazio di archiviazione esaurito, RAM insufficiente) non scarta il backend: la prova si ripete all'avvio successivo.

## ONNX Runtime (AAR personalizzato)

Le AAR ufficiali `onnxruntime-android` (con WebGPU) e `onnxruntime-android-qnn` non possono stare
nello stesso APK, quindi l'app usa un'unica AAR compilata dai sorgenti con i tre execution provider
(CPU, WebGPU, QNN): `com.stef.vocalremover.thirdparty:onnxruntime-android-vr:1.24.3`, nel repository
Maven locale `third_party/maven` (già nel repo). Stessa API Java delle AAR ufficiali; il POM
dipende da `com.qualcomm.qti:qnn-runtime` su Maven Central. Per ricompilarla:

```bash
scripts/build-ort-aar.sh     # scarica QAIRT SDK, NDK e JDK 17 se mancano; passi già fatti vengono saltati
```

## Confronto dei backend

Il backend CPU è il riferimento di qualità. Per confrontare i backend usa lo stesso file audio e lo
stesso modello, ascolta e confronta prima la CPU; se WebGPU rovina l'audio, non usarlo su quel
dispositivo. Nessun backend viene promosso in base alla sola velocità.

## QNN (NPU Snapdragon)

Il backend `qnn` usa l'NPU Hexagon ed è disponibile solo su SoC Qualcomm (testato su Realme
RMX3301, Snapdragon 8 Gen 1): circa 0,25 s per chunk contro circa
6 s della CPU, 8 s di inferenza per un brano di 3,5 minuti, outputPeak uguale alla CPU (0,4809 contro 0,4807).
Al primo avvio dopo l'installazione l'app compila il grafo HTP in background (~30 s) e lo salva in
`no_backup/qnn_ctx_<installTime>.onnx` (~42 MB, `noBackupFilesDir`: escluso dal backup automatico,
che ha un limite di 25 MB). Dagli avvii successivi la sessione si apre in ~0,3 s.
Sui dispositivi non Qualcomm si usano WebGPU o CPU.

## Benchmark su dispositivo (`BackendBenchmarkTest`)

Test di strumentazione che elabora un brano sintetico deterministico (20 s, nessun file audio nel
repo) con il backend scelto (argomento `backend`: `cpu`, `webgpu`, `qnn` oppure `auto`, il
default, che usa il backend scelto da `ProbingBackendSelector`, cioè il più veloce tra i candidati
che passano la prova; l'esito della prova è nel campo `selection` del JSON) e lo confronta con la CPU nello stesso processo. Misura
caricamento sessione, tempo di elaborazione e SNR rispetto alla CPU, e fallisce se l'uscita ha
NaN/Inf, è silenziosa o ha SNR < 20 dB. In questo modo coglie anche i backend che producono audio
rovinato senza andare in crash (WebGPU su Realme RMX3301: 2 dB).

Valori misurati: Realme qnn 3,3 s (CPU 85 s), SNR 63,5 dB; OPPO webgpu 21 s (CPU 35 s), SNR 121 dB.

In locale, con il telefono collegato via adb:

```bash
gradle :ui:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.backend=qnn
# argomenti opzionali: -Pandroid.testInstrumentationRunnerArguments.durationSec=10
#                      -Pandroid.testInstrumentationRunnerArguments.minSnrDb=25
```

Il risultato è una riga JSON nel logcat (tag `VRBenchmark`). Con Gradle viene copiato anche in
`ui/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/<dispositivo>/`
(Gradle disinstalla l'app a fine test). Con `adb shell am instrument` resta invece in
`/sdcard/Android/data/com.stef.vocalremover/files/benchmark/report-<backend>.json`.
Con qnn su installazione pulita `sessionLoadMs` include la compilazione HTP (~50 s, una tantum).

Su dispositivi che non possiedi, usa Firebase Test Lab (telefoni reali). Lo script compila gli APK,
lancia il test su un elenco di dispositivi scelti per SoC e scarica i report in `build/testlab/`:

```bash
scripts/run-testlab.sh auto              # 5 telefoni (limite giornaliero Spark), backend scelto dall'app
scripts/run-testlab.sh webgpu            # Exynos 2400, Tensor G4, Dimensity 6300, SD 8 Gen 2/3
scripts/run-testlab.sh qnn               # solo Snapdragon: S23, S24 (Snapdragon), S25
scripts/run-testlab.sh webgpu --dry-run  # mostra il comando gcloud senza lanciarlo
DEVICES="frankel:36 r0q:36" DURATION_SEC=10 scripts/run-testlab.sh webgpu
```

Piano Spark: 5 test fisici al giorno, uno per dispositivo. Equivalente manuale:

```bash
gradle :ui:assembleDebug :ui:assembleDebugAndroidTest
gcloud firebase test android models list            # elenco dei modelli disponibili
gcloud firebase test android run --type instrumentation \
  --app  ui/build/outputs/apk/debug/ui-debug.apk \
  --test ui/build/outputs/apk/androidTest/debug/ui-debug-androidTest.apk \
  --device model=<MODELLO>,version=<API> --device model=<ALTRO>,version=<API> \
  --environment-variables backend=qnn,durationSec=20 \
  --directories-to-pull /sdcard/Android/data/com.stef.vocalremover/files \
  --timeout 20m
```

Per i SoC Snapdragon è disponibile anche Qualcomm Device Cloud, dove gli stessi APK
si installano e si lanciano via adb con `adb shell am instrument -w -r -e backend qnn
com.stef.vocalremover.test/androidx.test.runner.AndroidJUnitRunner`.

Vedi `.github/copilot-instructions.md` per i dettagli sull'architettura della pipeline audio.
