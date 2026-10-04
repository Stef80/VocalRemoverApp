# VocalRemoverApp

App Android per la separazione voce/strumentale on-device tramite ONNX Runtime e un modello UVR-MDX-NET-Inst_HQ_5.

## Setup del modello ONNX (obbligatorio)

Il modello `vocal_remover.onnx` (~59MB, UVR-MDX-NET-Inst_HQ_5) **non è incluso nel repository** perché supera il limite di 100MB imposto da GitHub (o comunque per non appesantire il repo). Va scaricato manualmente prima di poter compilare/eseguire l'app.

1. Scarica `UVR-MDX-NET-Inst_HQ_5.onnx` da [Ultimate Vocal Remover models](https://github.com/TRvlvr/model_repo/releases) (o dal repository/hub da cui è stato ottenuto).
2. Rinominalo in `vocal_remover.onnx`.
3. Copialo in `app/src/main/assets/vocal_remover.onnx`.

Il file è elencato in `.gitignore` (pattern `*.onnx`): resterà sul tuo disco ma non verrà mai committato per errore. Ogni sviluppatore/macchina di build deve ripetere questo passaggio dopo il clone.

## Build

```bash
gradle :app:assembleCpuDebug :app:assembleWebgpuDebug :app:assembleQnnDebug
```

## Confronto CPU/WebGPU

Installa entrambe le build debug sullo stesso dispositivo per confrontare i backend.
Gli APK sono in `app/build/outputs/apk/cpu/debug/` e `app/build/outputs/apk/webgpu/debug/`.
Hanno application ID distinti (`.cpu` e `.webgpu`), quindi cache separate. Usa lo stesso file
audio e lo stesso modello per entrambe. Ascolta e confronta prima la CPU; se WebGPU rovina l'audio, non usarlo su
quel dispositivo. Nessun backend viene promosso in base alla sola velocità. I test unitari si
eseguono con `gradle :app:testCpuDebugUnitTest :app:testWebgpuDebugUnitTest`.

## Flavor QNN (NPU Snapdragon)

Il flavor `qnn` (`.qnn`) usa l'NPU Hexagon tramite `onnxruntime-android-qnn:1.24.3`. È pensato solo
per SoC Qualcomm (testato su Realme RMX3301, Snapdragon 8 Gen 1): circa 0,25 s per chunk contro circa
6 s della CPU, 8 s di inferenza per un brano di 3,5 minuti, outputPeak uguale alla CPU (0,4809 contro 0,4807).
Al primo avvio dopo l'installazione l'app compila il grafo HTP in background (~30 s) e lo salva in
`files/qnn_ctx_<installTime>.onnx` (~42 MB). Dagli avvii successivi la sessione si apre in ~0,3 s.
Su dispositivi non Qualcomm usa le build `cpu` o `webgpu`.

## Benchmark su dispositivo (`BackendBenchmarkTest`)

Test di strumentazione che elabora un brano sintetico deterministico (20 s, nessun file audio nel
repo) con il backend del flavor installato e lo confronta con la CPU nello stesso processo. Misura
caricamento sessione, tempo di elaborazione e SNR rispetto alla CPU, e fallisce se l'uscita ha
NaN/Inf, è silenziosa o ha SNR < 20 dB. In questo modo coglie anche i backend che producono audio
rovinato senza andare in crash (WebGPU su Realme RMX3301: 2 dB).

Valori misurati: Realme qnn 3,3 s (CPU 85 s), SNR 63,5 dB; OPPO webgpu 21 s (CPU 35 s), SNR 121 dB.

In locale, con il telefono collegato via adb:

```bash
gradle :app:connectedQnnDebugAndroidTest      # oppure Cpu / Webgpu
# argomenti opzionali: -Pandroid.testInstrumentationRunnerArguments.durationSec=10
#                      -Pandroid.testInstrumentationRunnerArguments.minSnrDb=25
```

Il risultato è una riga JSON nel logcat (tag `VRBenchmark`). Con Gradle viene copiato anche in
`app/build/outputs/connected_android_test_additional_output/<flavor>DebugAndroidTest/connected/<dispositivo>/`
(Gradle disinstalla l'app a fine test). Con `adb shell am instrument` resta invece in
`/sdcard/Android/data/com.example.vocalremover.<flavor>/files/benchmark/report-<flavor>.json`.
Con qnn su installazione pulita `sessionLoadMs` include la compilazione HTP (~50 s, una tantum).

Su dispositivi che non possiedi, usa Firebase Test Lab (telefoni reali). Lo script compila gli APK,
lancia il test su un elenco di dispositivi scelti per SoC e scarica i report in `build/testlab/`:

```bash
scripts/run-testlab.sh webgpu            # Exynos 2400, Tensor G4, Dimensity 6300, SD 8 Gen 2/3
scripts/run-testlab.sh qnn               # solo Snapdragon: S23, S24 (Snapdragon), S25
scripts/run-testlab.sh webgpu --dry-run  # mostra il comando gcloud senza lanciarlo
DEVICES="frankel:36 r0q:36" DURATION_SEC=10 scripts/run-testlab.sh webgpu
```

Piano Spark: 5 test fisici al giorno, uno per dispositivo. Equivalente manuale:

```bash
gradle :app:assembleQnnDebug :app:assembleQnnDebugAndroidTest
gcloud firebase test android models list            # elenco dei modelli disponibili
gcloud firebase test android run --type instrumentation \
  --app  app/build/outputs/apk/qnn/debug/app-qnn-debug.apk \
  --test app/build/outputs/apk/androidTest/qnn/debug/app-qnn-debug-androidTest.apk \
  --device model=<MODELLO>,version=<API> --device model=<ALTRO>,version=<API> \
  --environment-variables durationSec=20 \
  --directories-to-pull /sdcard/Android/data/com.example.vocalremover.qnn/files \
  --timeout 20m
```

Per i SoC Snapdragon (flavor `qnn`) è disponibile anche Qualcomm Device Cloud, dove gli stessi APK
si installano e si lanciano via adb con `adb shell am instrument -w -r
com.example.vocalremover.qnn.test/androidx.test.runner.AndroidJUnitRunner`.

Vedi `.github/copilot-instructions.md` per i dettagli sull'architettura della pipeline audio.
