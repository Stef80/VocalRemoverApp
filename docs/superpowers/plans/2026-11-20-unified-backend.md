# Unified Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una sola app `com.stef.vocalremover` con un ONNX Runtime compilato da noi (CPU + WebGPU + QNN) che al primo avvio prova tutti i backend, scarta quelli rotti e usa il più veloce tra i corretti.

**Architecture:** Uno script compila l'AAR di ORT e lo pubblica in un repository Maven nel repo. I flavor `backend` spariscono. In `:engine` un `ProbingBackendSelector` (dietro l'interfaccia `BackendSelector`) usa un `BackendProber` per provare ogni candidato su un segnale fisso confrontato con un riferimento CPU precalcolato, e ricorda la scelta. Un `FallbackInstrumentalExtractor` passa al backend successivo se quello scelto fallisce durante l'uso. `:ui` collega le implementazioni concrete.

**Precisazioni rispetto alla spec:**
- La prova passa da `removeVocals` su un segnale lungo esattamente un chunk (261 120 campioni), invece di dare in pasto alla sessione un tensore grezzo. Così prova anche STFT e ISTFT e il riferimento è un audio stereo da circa 1 MB in fp16 invece di circa 5 MB.
- Il riferimento si genera con la CPU di un telefono ARM, usando un test instrumented, invece che con uno script su PC: la pipeline STFT esiste solo in Kotlin.

**Tech Stack:** Kotlin, Android (minSdk 26, compileSdk 37), ONNX Runtime 1.24.3 custom, QAIRT 2.42.0.251225, NDK 28.0.13004108, kotlinx.serialization, JUnit 4 + Robolectric 4.16, Gradle 9.8.0 dal wrapper.

**Spec:** `docs/superpowers/specs/2026-11-20-unified-backend-design.md`

## Global Constraints

- **Non fare mai `git commit`.** Ogni step "Stage" fa `git add` e mostra all'utente cosa verrebbe committato; il commit lo fa l'utente (CLAUDE.md).
- `applicationId = "com.stef.vocalremover"`, nessun `applicationIdSuffix`; solo `arm64-v8a`.
- Versioni bloccate: ORT **1.24.3** (tag `v1.24.3`), QAIRT **2.42.0.251225**, NDK **28.0.13004108**, JDK **17** solo per il packaging AAR. ORT deve restare ≥ 1.24.3 con `libonnxruntime*.so` allineate a 16 KB (`0x4000`).
- Coordinate dell'AAR: `com.stef.vocalremover.thirdparty:onnxruntime-android-vr:1.24.3`, in `third_party/maven/`.
- Requisiti QNN invariati: `<uses-native-library android:name="libcdsprpc.so" android:required="false">`, legacy JNI packaging, `batch_size = 1`, context cache HTP in `filesDir` con chiave `lastUpdateTime`, nessun override di `ADSP_LIBRARY_PATH`.
- Soglia di qualità: **SNR ≥ 20 dB** rispetto alla CPU, uscita tutta finita, RMS uscita ≥ `1e-3`.
- `:engine` non dipende mai da `:ui`; i moduli parlano tramite interfacce, le implementazioni concrete si istanziano in `:ui`.
- Nessun testo hard-coded nella UI di `:ui`: le stringhe vanno in `ui/src/main/res/values/strings.xml`. Commenti, log e messaggi delle eccezioni in inglese; documentazione in italiano.
- Gradle: `G=$(ls -d ~/.gradle/wrapper/dists/gradle-9.8.0*/*/gradle-9.8.0/bin/gradle | head -1)`, poi `$G ...` dalla root.
- Telefoni di riferimento: Realme RMX3301 (SM8450, adb `6112f1a4`, deve scegliere QNN) e OPPO CPH2791 (MT6993, debug wireless, deve scegliere WebGPU).

## Review Focus

1. **App chiusa durante i ~40 s di compilazione HTP** → al riavvio QNN non deve risultare guastato e la prova deve ripartire (test `user-killed probe does not blacklist backend`, Task 4).
2. **Aggiornamento di sistema** (`Build.FINGERPRINT` cambiato) con scelta salvata → la scelta va rifatta e i backend scartati vanno riammessi (test `fingerprint change invalidates choice and failures`, Task 4).
3. **Elaborazione avviata mentre la prova è in corso** → la canzone aspetta la prova, mostra lo stato "preparazione" e poi usa il backend scelto; non parte una seconda prova (test `concurrent current calls share one probe`, Task 4, e `shows preparing state while backend is not ready`, Task 6).
4. **QNN che ricade in silenzio sulla CPU** (manifest o libreria mancanti) → la creazione della sessione deve fallire e QNN va scartato, invece di vincere con tempi da CPU (verifica sul dispositivo, Task 5 step 6).
5. **Backend che si guasta a metà canzone** (eccezione o NaN) → la stessa canzone si rielabora da capo con il backend successivo e il backend guasto non si riprova (test `retries whole song on next backend after failure`, Task 6).

---

## File Structure

| File | Responsabilità |
|---|---|
| `scripts/build-ort-aar.sh` | Prepara gli strumenti, compila ORT e pubblica l'AAR in `third_party/maven` |
| `scripts/ort/vr_aar_build_settings.json` | La ricetta di build unita (versionata) |
| `third_party/maven/com/stef/vocalremover/thirdparty/onnxruntime-android-vr/1.24.3/` | AAR + POM |
| `engine/src/main/java/com/example/vocalremover/backend/BackendCandidates.kt` | Elenco dei candidati dato il SoC |
| `engine/src/main/java/com/example/vocalremover/backend/OutputQuality.kt` | SNR, RMS, finitezza (spostati da `BenchmarkMetrics`) |
| `engine/src/main/java/com/example/vocalremover/backend/ProbeSignal.kt` | Segnale di prova deterministico (spostato da `SyntheticStereoSignal`) |
| `engine/src/main/java/com/example/vocalremover/backend/BackendSelector.kt` | Interfacce `BackendSelector`, `BackendProber`, `ProbeResult` |
| `engine/src/main/java/com/example/vocalremover/backend/ProbingBackendSelector.kt` | Logica di scelta, memoria, marcatore dei crash |
| `engine/src/main/java/com/example/vocalremover/backend/BackendChoiceStore.kt` | Stato persistito (interfaccia + implementazione `SharedPreferences`) |
| `engine/src/main/java/com/example/vocalremover/backend/ProcessExitInfo.kt` | "L'ultima uscita è stata un crash?" (API 30+) |
| `engine/src/main/java/com/example/vocalremover/backend/OrtBackendProber.kt` | Prova reale con `VocalRemover` e l'asset di riferimento |
| `engine/src/main/java/com/example/vocalremover/processing/FallbackInstrumentalExtractor.kt` | Ripiego durante l'uso |
| `engine/src/main/assets/backend_probe/expected_fp16.bin` | Uscita CPU attesa (fp16 LE, L poi R) |
| `ui/src/main/java/com/stef/vocalremover/ui/VocalRemoverApplication.kt` | Contiene il selettore per tutta l'app |
| `ui/src/androidTest/java/com/example/vocalremover/benchmark/ProbeReferenceGenerator.kt` | Genera l'asset sul telefono con la CPU |

---

### Task 1: Script di build di ORT e AAR nel repo

**Files:**
- Create: `scripts/build-ort-aar.sh`, `scripts/ort/vr_aar_build_settings.json`
- Create (output): `third_party/maven/com/stef/vocalremover/thirdparty/onnxruntime-android-vr/1.24.3/onnxruntime-android-vr-1.24.3.{aar,pom}`

**Interfaces:**
- Produces: dipendenza Maven `com.stef.vocalremover.thirdparty:onnxruntime-android-vr:1.24.3`, che dichiara a sua volta `com.qualcomm.qti:qnn-runtime:2.42.0`.

- [ ] **Step 1: Scrivere `scripts/ort/vr_aar_build_settings.json`**, identico a `~/ort-build/vr_webgpu_qnn_aar_settings.json` dello spike: `build_abis ["arm64-v8a"]`, min SDK 26, target SDK 36, `--enable_lto --android --parallel 4 --cmake_generator=Ninja --build_java --build_shared_lib --use_webgpu --use_qnn=static_lib`, `CMAKE_CXX_SCAN_FOR_MODULES=OFF`, `onnxruntime_BUILD_UNIT_TESTS=OFF`, `CMAKE_POLICY_VERSION_MINIMUM=3.5`, `--skip_tests`.

- [ ] **Step 2: Scrivere `scripts/build-ort-aar.sh`** (`set -euo pipefail`, stile di `scripts/run-testlab.sh`). Variabili d'ambiente con default:

| Variabile | Default |
|---|---|
| `ORT_WORK_DIR` | `$HOME/ort-build` |
| `QAIRT_DIR` | `$HOME/qairt` |
| `ANDROID_SDK` | valore di `sdk.dir` letto da `local.properties` |
| `PARALLEL` | `4`, sovrascrive la ricetta |

Lo script fa, in ordine, e salta ogni passo già fatto:
1. scarica lo zip QAIRT con `curl --http1.1 --retry 5 --retry-all-errors -C -` da `https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.42.0.251225/v2.42.0.251225.zip`, lo verifica con `unzip -tq` e lo estrae in `$QAIRT_DIR/extract`; poi controlla che `sdk.yaml` dica `version: 2.42.0`;
2. installa `ndk;28.0.13004108` con `sdkmanager`;
3. scarica Temurin 17 in `$ORT_WORK_DIR/jdk17` (`https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse`);
4. crea il venv in `$ORT_WORK_DIR/venv` con `cmake ninja pkgconf` e il link `pkg-config → pkgconf`;
5. fa `git clone --depth 1 --branch v1.24.3`;
6. esporta `JAVA_HOME`, `PATH` e **`ANDROID_NDK_HOME`**, poi lancia `build_aar_package.py` con `--build_dir $ORT_WORK_DIR/out`;
7. copia AAR e POM in `third_party/maven/...`, riscrivendo nel POM **solo** il `groupId`/`artifactId` del progetto, non quelli della dipendenza `qnn-runtime`;
8. stampa la verifica dello Step 3.

- [ ] **Step 3: Eseguire e verificare.** Riusare la build dello spike per non ricompilare da zero: `scripts/build-ort-aar.sh`.
  Atteso: termina con codice 0; `unzip -p third_party/maven/.../onnxruntime-android-vr-1.24.3.aar jni/arm64-v8a/libonnxruntime.so | strings | grep -E "^(WebGpu|QNN)ExecutionProvider$" | sort -u` stampa entrambe le righe; `llvm-readelf -lW` sulla `.so` mostra allineamento `0x4000` per i LOAD; `grep -c qnn-runtime` sul POM vale 1; `grep "<artifactId>onnxruntime-android-vr</artifactId>"` trova la riga.

- [ ] **Step 4: Stage.** `git add scripts/build-ort-aar.sh scripts/ort/ third_party/maven/`. Messaggio proposto: `build: script and in-repo Maven artifact for custom ORT (CPU+WebGPU+QNN)`.

### Task 2: Un'unica app

**Prerequisito (utente):** `ui/google-services.json` con il client `com.stef.vocalremover`. Il file è in `.gitignore`. Senza questo file la build fallisce al task `processDebugGoogleServices`: prima di iniziare chiedilo all'utente.

**Files:**
- Modify: `settings.gradle.kts`, `engine/build.gradle.kts`, `ui/build.gradle.kts`, `engine/src/main/AndroidManifest.xml`
- Delete: `engine/src/qnn/AndroidManifest.xml`
- Create: `engine/src/main/java/com/example/vocalremover/backend/BackendCandidates.kt`, `engine/src/test/java/com/example/vocalremover/backend/BackendCandidatesTest.kt`
- Modify: `engine/src/main/java/com/example/vocalremover/VocalRemover.kt:34-37`, `ui/src/main/java/com/stef/vocalremover/ui/ImportViewModel.kt:402`, `ui/src/androidTest/java/com/example/vocalremover/benchmark/BackendBenchmarkTest.kt:41`, `scripts/run-testlab.sh`, `CLAUDE.md`, `.github/copilot-instructions.md`, `README.md`

**Interfaces:**
- Produces: `object BackendCandidates { fun forSoc(socManufacturer: String, hardware: String): List<ExecutionBackend>; fun forThisDevice(): List<ExecutionBackend> }`
- Produces: `class VocalRemover(context: Context, private val backend: ExecutionBackend)`, senza valore di default.

- [ ] **Step 1: Test che fallisce** in `BackendCandidatesTest`:

```kotlin
@Test fun `qualcomm by soc manufacturer`() =
    assertEquals(listOf(QNN, WEBGPU, CPU), BackendCandidates.forSoc("QTI", "mt6993"))
@Test fun `qualcomm by legacy hardware name`() =
    assertEquals(listOf(QNN, WEBGPU, CPU), BackendCandidates.forSoc("", "qcom"))
@Test fun `mediatek gets webgpu then cpu`() =
    assertEquals(listOf(WEBGPU, CPU), BackendCandidates.forSoc("Mediatek", "mt6993"))
```

- [ ] **Step 2: Eseguire.** `$G :engine:testCpuDebugUnitTest --tests "*BackendCandidatesTest"` → FAIL (simbolo non risolto).

- [ ] **Step 3: Implementare `BackendCandidates`.** Qualcomm vuol dire `socManufacturer` uguale a `"QTI"` senza distinzione di maiuscole, oppure `hardware` che inizia con `"qcom"`. `forThisDevice()` legge `Build.SOC_MANUFACTURER` se l'API è ≥ 31 (altrimenti `""`) e `Build.HARDWARE`.

- [ ] **Step 4: Togliere i flavor.**
  - `settings.gradle.kts`: come **primo** repository in `dependencyResolutionManagement`, `maven { url = uri(settingsDir.resolve("third_party/maven")); content { includeGroup("com.stef.vocalremover.thirdparty") } }`.
  - `engine/build.gradle.kts`: via `flavorDimensions` e `productFlavors`; le tre righe `*Implementation` diventano `implementation("com.stef.vocalremover.thirdparty:onnxruntime-android-vr:1.24.3")`. Il commento sopra va aggiornato: un solo AAR custom, il perché, rimando allo script.
  - `engine/src/main/AndroidManifest.xml`: assorbe il blocco `<uses-native-library>` del manifest `qnn`, con il suo commento; poi va cancellato `engine/src/qnn/`.
  - `ui/build.gradle.kts`: `applicationId = "com.stef.vocalremover"`, `ndk { abiFilters += "arm64-v8a" }` in `defaultConfig`, via flavor e blocco `androidComponents`, `packaging.jniLibs.useLegacyPackaging = true` accanto a `pickFirsts`, con il commento sugli Skel.
  - `VocalRemover`: senza default. `ImportViewModel.Factory` usa temporaneamente `VocalRemover(app, BackendCandidates.forThisDevice().first())`; il Task 6 lo sostituisce.

- [ ] **Step 5: Benchmark e Test Lab senza flavor.**
  - `BackendBenchmarkTest`: il backend arriva da `args.getString("backend")`, con default `"auto"`. Per ora `auto` vuol dire `BackendCandidates.forThisDevice().first()`; il Task 5 lo collega al selettore.
  - `scripts/run-testlab.sh <cpu|webgpu|qnn|auto>`:
    - `APP_ID=com.stef.vocalremover`;
    - APK `ui/build/outputs/apk/debug/ui-debug.apk` e `ui/build/outputs/apk/androidTest/debug/ui-debug-androidTest.apk`;
    - `backend=$BACKEND` in `--environment-variables`;
    - build con `:ui:assembleDebug :ui:assembleDebugAndroidTest`;
    - `auto` usa l'unione delle liste di dispositivi di `webgpu` e `qnn`.

- [ ] **Step 6: Documentazione.** In `CLAUDE.md` vanno aggiornate tre sezioni:
  - "Commands": senza flavor (`:ui:assembleDebug`, `:engine:testDebugUnitTest`, `:ui:testDebugUnitTest`, `:ui:lintDebug`, `:ui:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.backend=qnn`, `scripts/run-testlab.sh auto`), più `scripts/build-ort-aar.sh`;
  - "Modules and flavors": niente più dimensione `backend`;
  - "Backend constraints": AAR custom, legacy packaging per tutti.

  Stessi concetti, in italiano, in `.github/copilot-instructions.md` e nel README (build di ORT, nuovo `applicationId`, prerequisito Firebase).

- [ ] **Step 7: Verificare.** Comando: `$G :engine:testDebugUnitTest :ui:testDebugUnitTest :ui:assembleDebug :ui:assembleDebugAndroidTest :ui:lintDebug`. Atteso: BUILD SUCCESSFUL. Poi `aapt2 dump badging ui/build/outputs/apk/debug/ui-debug.apk | grep -E "package: name|native-code"` → `name='com.stef.vocalremover'`, `native-code: 'arm64-v8a'`.
  Sul Realme: `adb -s 6112f1a4 install -r` dell'APK, poi `adb -s 6112f1a4 shell am instrument -w -e backend qnn -e durationSec 20 com.stef.vocalremover.test/androidx.test.runner.AndroidJUnitRunner`. Atteso: `"passed":true`, `processMs` ≈ 3200–3300.

- [ ] **Step 8: Stage**, compresa la cancellazione con `git rm engine/src/qnn/AndroidManifest.xml`. Messaggio proposto: `build: single app com.stef.vocalremover on the custom ORT AAR, drop backend flavors`.

### Task 3: Qualità dell'uscita e segnale di prova in `main`

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/backend/OutputQuality.kt`, `.../backend/ProbeSignal.kt`
- Delete: `engine/src/benchmarkShared/java/com/example/vocalremover/benchmark/BenchmarkMetrics.kt`, `.../SyntheticStereoSignal.kt` (con i loro test, spostati)
- Modify: `BackendBenchmarkTest.kt`, i test che usano le due classi (`grep -rl "BenchmarkMetrics\|SyntheticStereoSignal" engine/src ui/src`)
- Test: `engine/src/test/java/com/example/vocalremover/backend/OutputQualityTest.kt`, `.../ProbeSignalTest.kt`

**Interfaces:**
- Produces: `object OutputQuality { fun stereoSnrDb(refL: FloatArray, refR: FloatArray, outL: FloatArray, outR: FloatArray): Double; fun rms(x: FloatArray): Double; fun allFinite(x: FloatArray): Boolean; fun verdict(ref: StereoPcm, out: StereoPcm, minSnrDb: Double = MIN_SNR_DB): String? }`, dove `verdict` restituisce `null` se l'uscita passa, altrimenti il motivo in inglese. Costanti `MIN_SNR_DB = 20.0` e `MIN_OUTPUT_RMS = 1e-3`.
- Produces: `object ProbeSignal { const val SAMPLE_RATE = 44100; const val FRAMES = 261_120; fun generate(): StereoPcm }`. `FRAMES` vale `hop*(DIM_T-1)` = `1024*255`. Il segnale è quello di `SyntheticStereoSignal.generate` con seed `42`, troncato a `FRAMES`.

- [ ] **Step 1: Test che falliscono.** Si spostano i test esistenti di `BenchmarkMetrics`/`SyntheticStereoSignal` sui nuovi nomi e se ne aggiungono quattro:
  - `verdict passes identical output`: `verdict(x, x) == null`;
  - `verdict rejects NaN`: il motivo contiene `"NaN"`;
  - `verdict rejects silence`: uscita a zeri → il motivo contiene `"silent"`;
  - `verdict rejects low snr`: rumore aggiunto per circa 10 dB → il motivo contiene `"SNR"`.

  In più `probe signal is deterministic and stereo`: due `generate()` uguali, `left != right`, `size == 261_120`.

- [ ] **Step 2: Eseguire.** `$G :engine:testDebugUnitTest --tests "*OutputQualityTest" --tests "*ProbeSignalTest"` → FAIL.

- [ ] **Step 3: Spostare il codice** nei nuovi file con la logica invariata, aggiungere `verdict` e aggiornare i punti che usavano le vecchie classi, compreso il `when` dei controlli in `BackendBenchmarkTest`, che diventa `OutputQuality.verdict`.

- [ ] **Step 4: Verificare.** Comando: `$G :engine:testDebugUnitTest :ui:assembleDebugAndroidTest`. Atteso: PASS e BUILD SUCCESSFUL.

- [ ] **Step 5: Stage.** Messaggio proposto: `refactor: move output quality checks and probe signal into engine main`.

### Task 4: `ProbingBackendSelector` (logica, memoria, marcatore dei crash)

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/backend/{BackendSelector,ProbingBackendSelector,BackendChoiceStore,ProcessExitInfo}.kt`
- Test: `engine/src/test/java/com/example/vocalremover/backend/ProbingBackendSelectorTest.kt`, `.../SharedPrefsBackendChoiceStoreTest.kt` (Robolectric `@Config(sdk = [35])`, come `SongProcessorTest`)

**Interfaces:**
- Consumes: `BackendCandidates` (Task 2).
- Produces:

```kotlin
interface BackendSelector {
    suspend fun current(): ExecutionBackend
    suspend fun reportFailure(backend: ExecutionBackend, cause: Throwable): ExecutionBackend
    fun isReady(): Boolean                       // choice already in memory/store for this key
    fun markInProgress(backend: ExecutionBackend)
    fun markCompleted(backend: ExecutionBackend)
}
sealed interface ProbeResult {
    data class Passed(val runMs: Long, val snrDb: Double) : ProbeResult
    data class Rejected(val reason: String) : ProbeResult
}
fun interface BackendProber { suspend fun probe(backend: ExecutionBackend): ProbeResult }
fun interface ProcessExitInfo { fun lastExitWasCrash(): Boolean? }   // null = unknown (API < 30)

@Serializable data class BackendChoice(
    val key: String,
    val chosen: ExecutionBackend? = null,
    val failed: Map<ExecutionBackend, String> = emptyMap(),
    val timingsMs: Map<ExecutionBackend, Long> = emptyMap(),
    val inProgress: ExecutionBackend? = null,
    val unexplainedExits: Map<ExecutionBackend, Int> = emptyMap(),
)
interface BackendChoiceStore { fun load(): BackendChoice?; fun save(choice: BackendChoice) }
class SharedPrefsBackendChoiceStore(context: Context) : BackendChoiceStore   // prefs "backend_choice", one JSON string

class ProbingBackendSelector(
    private val candidates: List<ExecutionBackend>,
    private val key: String,                   // "$lastUpdateTime|${Build.FINGERPRINT}"
    private val store: BackendChoiceStore,
    private val prober: BackendProber,
    private val exitInfo: ProcessExitInfo,
) : BackendSelector {
    companion object { fun create(context: Context): ProbingBackendSelector }   // wires the real parts (Task 5)
}
class AndroidProcessExitInfo(context: Context) : ProcessExitInfo
```

- [ ] **Step 1: Test che falliscono**, con un `FakeProber` (mappa backend → `ProbeResult`, oppure eccezione, più un contatore delle chiamate) e un `InMemoryStore`:
  - `picks fastest passing backend`: QNN `Passed(250)`, WEBGPU `Passed(1500)`, CPU `Passed(6000)` → `QNN`.
  - `quality beats speed`: WEBGPU `Rejected("SNR 3.1 dB")`, CPU `Passed(6000)`, candidati `[WEBGPU, CPU]` → `CPU`, con `failed[WEBGPU]` che contiene `"SNR"`.
  - `exception during probe rejects candidate`: QNN lancia un'eccezione e WEBGPU passa → `WEBGPU`.
  - `nothing passes falls back to cpu`: tutti `Rejected` → `CPU`.
  - `stored choice skips probe`: lo store ha già una scelta con la stessa chiave → nessuna chiamata al prober.
  - `fingerprint change invalidates choice and failures`: lo store ha la chiave `"1|A"` con `chosen = WEBGPU` e `failed = {QNN}`; il selettore ha la chiave `"1|B"` → si riprova QNN.
  - `concurrent current calls share one probe`: due `async { current() }` → ogni candidato viene provato una sola volta.
  - `reportFailure returns next fastest passing and persists`: dopo la scelta `QNN`, `reportFailure(QNN, e)` → `WEBGPU`, e lo store ha `QNN` tra i `failed`. Se il prossimo è CPU e fallisce anche lei, restituisce `CPU`: non esiste un backend dopo la CPU.
  - `native crash marker blacklists backend`: lo store ha `inProgress = QNN`, `exitInfo` restituisce `true` → QNN scartato senza provarlo.
  - `user-killed probe does not blacklist backend`: `inProgress = QNN`, `exitInfo` restituisce `false` → QNN viene riprovato.
  - `unknown exit needs two consecutive markers`: `exitInfo` restituisce `null`. Al primo avvio con il marcatore, QNN viene riprovato e `unexplainedExits[QNN] == 1`; al secondo avvio con il marcatore, QNN viene scartato.
  - `cpu crash is only logged`: `inProgress = CPU`, `exitInfo` restituisce `true` → la CPU resta tra i candidati di ripiego, ma non viene provata in questa prova.
  - `markCompleted clears marker and counter`.
  - `SharedPrefsBackendChoiceStoreTest`: save → load restituisce lo stesso valore; con il JSON corrotto `load()` restituisce `null`.

- [ ] **Step 2: Eseguire.** `$G :engine:testDebugUnitTest --tests "*ProbingBackendSelectorTest" --tests "*SharedPrefsBackendChoiceStoreTest"` → FAIL.

- [ ] **Step 3: Implementare.**
  - Un `Mutex` protegge `current()` e la scelta in memoria.
  - Alla prima chiamata, `current()` legge lo store e scarta lo stato se `key` è diversa.
  - Poi applica le regole del marcatore: `true` → scartato, salvo la CPU; `false` → ignorato; `null` → contatore, scartato a 2.
  - Se c'è una scelta valida la restituisce; altrimenti prova i candidati non scartati. Per ogni candidato chiama `markInProgress`, poi `probe`, poi `markCompleted`, e salva lo store dopo ognuno.
  - Una prova della CPU vale solo se non è stata scartata dal marcatore; tempi e motivi vanno salvati e scritti nei log (`Log.i`, tag `BackendSelector`).
  - `AndroidProcessExitInfo`: su API ≥ 30 legge `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.reason` e restituisce `true` se è `REASON_CRASH_NATIVE` o `REASON_CRASH`, `false` se è un'altra ragione o se non ce ne sono; su API < 30 restituisce `null`.

- [ ] **Step 4: Verificare.** Stesso comando dello Step 2 → PASS. Poi `$G :engine:testDebugUnitTest` → PASS.

- [ ] **Step 5: Stage.** Messaggio proposto: `feat(engine): probing backend selector with persisted choice and crash marker`.

### Task 5: Prova reale sul telefono e asset di riferimento

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/backend/OrtBackendProber.kt`, `ui/src/androidTest/java/com/example/vocalremover/benchmark/ProbeReferenceGenerator.kt`, `engine/src/main/assets/backend_probe/expected_fp16.bin`
- Modify: `engine/src/main/java/com/example/vocalremover/QnnConfig.kt`, `VocalRemover.kt` (il ramo QNN di `createSession`), `ProbingBackendSelector.create`, `BackendBenchmarkTest.kt`, `engine/src/benchmarkShared/.../BenchmarkReport.kt`
- Test: `engine/src/test/java/com/example/vocalremover/ExecutionBackendTest.kt`, `engine/src/test/java/com/example/vocalremover/backend/ProbeReferenceFormatTest.kt`

**Interfaces:**
- Consumes: `ProbeSignal`, `OutputQuality.verdict` (Task 3); `BackendProber`, `ProbeResult` (Task 4).
- Produces: `class OrtBackendProber(context: Context, private val removerFactory: (ExecutionBackend) -> VocalRemover = { VocalRemover(context, it) }) : BackendProber`; `object ProbeReference { const val ASSET = "backend_probe/expected_fp16.bin"; fun encode(pcm: StereoPcm): ByteArray; fun decode(bytes: ByteArray): StereoPcm }`, con fp16 little-endian, L e poi R.
- Produces: `QnnConfig.sessionConfig: Map<String, String> = mapOf("session.disable_cpu_ep_fallback" to "1")`.

- [ ] **Step 1: Test che falliscono.**
  - In `ExecutionBackendTest`: `qnn session config disables cpu fallback`, con `assertEquals("1", QnnConfig.sessionConfig["session.disable_cpu_ep_fallback"])`.
  - In `ProbeReferenceFormatTest`: `encode then decode round-trips within fp16 precision`, cioè uno stereo di 1000 campioni in [-1, 1] con errore massimo ≤ `1e-3`, e `encoded size is 4 bytes per frame`. Per la conversione fp16 serve un'implementazione Kotlin pura (bit manipulation): `android.util.Half` sulla JVM non c'è.

- [ ] **Step 2: Eseguire.** `$G :engine:testDebugUnitTest --tests "*ExecutionBackendTest" --tests "*ProbeReferenceFormatTest"` → FAIL.

- [ ] **Step 3: Implementare.**
  - `QnnConfig.sessionConfig` e `ProbeReference`.
  - In `VocalRemover.createSession`, nel ramo QNN: `QnnConfig.sessionConfig.forEach { (k, v) -> addConfigEntry(k, v) }`.
  - `OrtBackendProber.probe(backend)`:
    1. crea il remover con `removerFactory(backend)`;
    2. chiama `warmUp()`; un'eccezione dà `Rejected("session: …")`;
    3. esegue `removeVocals(ProbeSignal.generate())` due volte e cronometra solo la seconda;
    4. confronta il risultato con `OutputQuality.verdict(ProbeReference.decode(asset), out)`: un motivo dà `Rejected(motivo)`, altrimenti `Passed(runMs, snr)`;
    5. chiude il remover in ogni caso.
  - `ProbingBackendSelector.create(context)` collega `BackendCandidates.forThisDevice()`, la chiave `"$lastUpdateTime|${Build.FINGERPRINT}"`, `SharedPrefsBackendChoiceStore`, `OrtBackendProber` e `AndroidProcessExitInfo`.

- [ ] **Step 4: Generare l'asset con la CPU del telefono.**
  - Scrivere `ProbeReferenceGenerator`: un test instrumented che esegue `VocalRemover(ctx, CPU).removeVocals(ProbeSignal.generate())` e scrive `ProbeReference.encode(out)` in `getExternalFilesDir(null)/backend_probe/expected_fp16.bin`. Va escluso dal benchmark normale con `@Ignore`, oppure va lanciato solo con `-e class`.
  - Lanciarlo sul Realme: `adb -s 6112f1a4 shell am instrument -w -e class com.example.vocalremover.benchmark.ProbeReferenceGenerator com.stef.vocalremover.test/androidx.test.runner.AndroidJUnitRunner`.
  - Recuperare il file: `adb -s 6112f1a4 pull /sdcard/Android/data/com.stef.vocalremover/files/backend_probe/expected_fp16.bin engine/src/main/assets/backend_probe/`.
  - Atteso: il file pesa `261120 * 4` = 1 044 480 byte.

- [ ] **Step 5: Benchmark in modalità `auto`.** `BackendBenchmarkTest` con `backend=auto` usa `ProbingBackendSelector.create(context)` e misura il backend scelto. In `BenchmarkReport` va aggiunto il campo `selection: String?`, che riassume per ogni candidato il tempo oppure il motivo dello scarto ed è serializzato nel JSON.

- [ ] **Step 6: Verificare sui due telefoni**, partendo da un'installazione pulita con `adb uninstall com.stef.vocalremover`. Comando: `... am instrument -w -e backend auto -e durationSec 20 com.stef.vocalremover.test/androidx.test.runner.AndroidJUnitRunner`.
  - Realme → `"backend":"qnn"` e `passed: true`; la `selection` contiene i tempi di qnn, webgpu e cpu, oppure il motivo dello scarto di webgpu.
  - OPPO → `"backend":"webgpu"` e `passed: true`.
  - Ripiego silenzioso: sul Realme, `-e backend qnn` con una build temporanea senza `<uses-native-library>` deve finire in `"passed":false` e la sessione deve fallire. La modifica temporanea non va messa in stage.
  - Se sul Realme la sessione QNN fallisce anche con il manifest corretto, a causa di `disable_cpu_ep_fallback` (nodi rimasti alla CPU): togliere `sessionConfig` e sostituirlo con il rifiuto per tempo previsto dalla spec, cioè QNN `Rejected` se `runMs` supera la metà del tempo della CPU misurato nella stessa prova. Va segnalato all'utente.
  - Indagare perché nello spike il context cache sia stato ricompilato senza reinstallare: dopo una prova completa, un secondo `-e backend qnn` deve dare `sessionLoadMs` < 2000. Se non è così, fermarsi e riferire.

- [ ] **Step 7: Stage**, compreso l'asset con `git add -f engine/src/main/assets/backend_probe/expected_fp16.bin`, perché `engine/src/main/assets/` è ignorata. Messaggio proposto: `feat(engine): on-device backend probe against a CPU reference`.

### Task 6: Integrazione nell'app (ripiego durante l'uso, stato "preparazione")

**Files:**
- Create: `engine/src/main/java/com/example/vocalremover/processing/FallbackInstrumentalExtractor.kt`, `ui/src/main/java/com/stef/vocalremover/ui/VocalRemoverApplication.kt`
- Modify:
  - `engine/.../processing/SongProcessor.kt`, `engine/.../processing/ProcessingState.kt`, `VocalRemover.kt`, il punto in cui viene letta l'uscita di ogni chunk;
  - `ui/src/main/AndroidManifest.xml`, `ImportViewModel.kt`, `ImportUiState.kt`, `PendingCaptureCards.kt:170-176`, `MainActivity.kt`, `ui/src/main/res/values/strings.xml`.
- Test: `engine/src/test/java/com/example/vocalremover/processing/FallbackInstrumentalExtractorTest.kt`, `SongProcessorTest.kt`, `ui/src/test/java/com/stef/vocalremover/ui/ImportViewModelTest.kt`

**Interfaces:**
- Consumes: `BackendSelector` (Task 4), `ProbingBackendSelector.create` (Task 5).
- Produces:
  - in `InstrumentalExtractor`: `fun isReady(): Boolean = true` e `suspend fun prepare() {}`, con implementazione di default;
  - `ProcessingState.PreparingBackend` e `ProcessingPhase.PreparingBackend`;
  - `class BackendOutputException(message: String) : RuntimeException(message)`;
  - `class FallbackInstrumentalExtractor(private val selector: BackendSelector, private val removerFactory: (ExecutionBackend) -> InstrumentalRemover) : InstrumentalExtractor, AutoCloseable`;
  - `interface InstrumentalRemover : AutoCloseable { suspend fun removeVocals(signal: StereoPcm, onProgress: (Int) -> Unit): StereoPcm }`, implementata da `VocalRemover`;
  - `class VocalRemoverApplication : Application() { val backendSelector: BackendSelector; fun startBackendProbe() }`.

- [ ] **Step 1: Test che falliscono.**
  - `FallbackInstrumentalExtractorTest`, con un selettore e un remover finti:
    - `uses selected backend`;
    - `retries whole song on next backend after failure`: il primo remover lancia `OrtException`, o `BackendOutputException`, al 40%; il secondo restituisce il risultato. Verifiche: `reportFailure` viene chiamato una volta, l'uscita è quella del secondo remover e il primo remover è chiuso;
    - `marks in progress and completed around a run`;
    - `cancellation is not a backend failure`: con `CancellationException`, `reportFailure` non viene chiamato e l'eccezione si propaga;
    - `cpu failure propagates`: se anche la CPU fallisce, l'eccezione arriva a `SongProcessor`, che finisce in `Failed(INFERENCE_FAILED)` come oggi.
  - `SongProcessorTest`: `shows preparing state while backend is not ready`. Con un extractor che ha `isReady()` falso e `prepare()` sospeso, la sequenza degli stati contiene `Decoding`, poi `PreparingBackend`, poi `Processing(0)`. Con `isReady()` vero, `PreparingBackend` non compare.
  - `ImportViewModelTest`: `maps preparing backend state to phase`.

- [ ] **Step 2: Eseguire.** `$G :engine:testDebugUnitTest --tests "*FallbackInstrumentalExtractorTest" --tests "*SongProcessorTest" :ui:testDebugUnitTest --tests "*ImportViewModelTest"` → FAIL.

- [ ] **Step 3: Implementare.**
  - **Controllo di ogni chunk in `VocalRemover`.** Dopo `session.run`, sull'uscita del chunk: se ci sono valori non finiti, oppure se l'RMS dell'ingresso del chunk è > `1e-3` e quello dell'uscita è < `1e-6`, lancia `BackendOutputException` con il backend e il motivo.
  - **`FallbackInstrumentalExtractor.extract`**, ciclo:
    1. `backend = selector.current()`;
    2. ottiene il remover, che riusa finché il backend non cambia;
    3. `markInProgress`, poi `removeVocals`, poi `markCompleted`;
    4. su `OrtException` o `BackendOutputException` con un backend diverso dalla CPU: chiude il remover, `reportFailure` e ricomincia da capo con `onProgress(0)`.
  - **`isReady`/`prepare`** delegano a `selector.isReady()` e `selector.current()`.
  - **`SongProcessor.process`.** Dopo il decode: `if (!extractor.isReady()) { _state.value = PreparingBackend; extractor.prepare() }`. `SongProcessor.create` riceve un `InstrumentalExtractor` al posto del `VocalRemover`.
  - **`VocalRemoverApplication`.**
    - `backendSelector` è creato con `by lazy { ProbingBackendSelector.create(this) }`.
    - `startBackendProbe()` lancia `backendSelector.current()` su `appScope` (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`) e registra nei log un eventuale errore.
    - Va registrata in `android:name`. La prova **non** parte in `onCreate`, altrimenti i test Robolectric caricherebbero ORT: la chiama `MainActivity.onCreate`.
  - **`ImportViewModel.Factory`.** Prende il selettore da `app as VocalRemoverApplication`, crea `FallbackInstrumentalExtractor(selector) { VocalRemover(app, it) }` e usa `releaseResources = extractor::close`.
  - **Stringa** `import_processing_preparing_backend` = `Preparazione dell'acceleratore per questo telefono (solo la prima volta)…` in `strings.xml`, mostrata da `ProcessingProgressCard` senza barra di percentuale.

- [ ] **Step 4: Verificare.** Comando: `$G :engine:testDebugUnitTest :ui:testDebugUnitTest :ui:lintDebug :ui:assembleDebug` → PASS. Poi, sul Realme dopo `adb uninstall`, installare l'app, aprirla e importare subito una canzone. Atteso: compare la scritta di preparazione; la canzone si completa; `adb logcat -s BackendSelector VocalRemover` mostra la scelta `qnn` con i tempi dei candidati. Al secondo avvio nessuna prova: il log riporta la scelta salvata.

- [ ] **Step 5: Stage.** Messaggio proposto: `feat: automatic backend selection with in-use fallback and preparing state`.

### Task 7: Dimensione dell'APK

**Files:**
- Modify: `ui/build.gradle.kts` (`packaging.jniLibs.excludes`)

- [ ] **Step 1: Misurare la base.** Annotare `ls -l ui/build/outputs/apk/debug/ui-debug.apk` e l'elenco `unzip -l … | grep "lib/arm64-v8a"`.

- [ ] **Step 2: Escludere i backend QNN non usati**: `"**/libQnnDsp*.so"`, `"**/libQnnGpu*.so"` e i `libQnnHtpV*` di generazione inferiore a 68, se presenti. Restano **tutti** gli HTP V68–V81, `libQnnHtp.so`, `libQnnHtpPrepare.so` e `libQnnSystem.so`.

- [ ] **Step 3: Verificare.** Ripetere il Task 5 step 6 su entrambi i telefoni con un'installazione pulita: Realme → `qnn` e `passed: true`; OPPO → `webgpu`. Riportare all'utente la dimensione prima e dopo. Se QNN fallisce, togliere l'esclusione che ha causato il problema.

- [ ] **Step 4: Stage.** Messaggio proposto: `build: exclude unused QNN backends from the APK`.
