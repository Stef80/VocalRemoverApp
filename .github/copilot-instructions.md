# Copilot Instructions for VocalRemoverApp

## Git commits

**Never run `git commit` (or otherwise commit) on the user's behalf**, in this
repository, regardless of how routine or low-risk the change seems (docs,
specs, plans, generated code, etc.). Stage/prepare changes and describe what
would be committed and why, but leave the actual commit action to the user to
run themselves. This applies even if earlier turns in the same session show
commits made automatically — that behavior is retired.

## Model file setup

ONNX assets are **not tracked in git** (`*.onnx` is ignored). `VocalRemover.MODEL_ASSET`
currently loads `engine/src/main/assets/vocal_remover_fp32.onnx`. The README setup still
mentions `vocal_remover.onnx`; verify the filename against `MODEL_ASSET` when changing
model setup, and keep the README aligned. See `README.md` for the model download source.
Do not force-add model files; keep them local.

## Build, test, and lint

This repository is an Android app built with Gradle Kotlin DSL (`build.gradle.kts`, `ui/build.gradle.kts`,
`engine/build.gradle.kts`). Modules:
- `:ui` — the **application** module: produces the APKs, owns the launcher (Compose
  `com.stef.vocalremover.ui.MainActivity`, namespace `com.example.vocalremover.ui`), the
  `cpu`/`webgpu`/`qnn` flavors with their `applicationIdSuffix`, QNN legacy JNI packaging,
  `google-services.json`, and the on-device `androidTest` benchmark. Depends on `:engine`.
- `:engine` — Android **library** (namespace `com.example.vocalremover`): decoding, ONNX inference,
  capture, the model asset, JVM unit tests, and the previous XML/ViewBinding
  `com.example.vocalremover.MainActivity` (opened from the Compose launcher until it is migrated and
  removed). Has the same `backend` flavors (selecting ORT dependency and `BuildConfig.EXECUTION_BACKEND`).
  `:engine` must never depend on `:ui`.
- Both use `compileSdk = 37` (needed by Compose BOM 2026.08+ / lifecycle-compose 2.11); `targetSdk` stays 36.

Use Gradle from the repo root:

```bash
gradle :ui:assembleCpuDebug :ui:assembleWebgpuDebug :ui:assembleQnnDebug
gradle :ui:lintCpuDebug :ui:lintWebgpuDebug :ui:lintQnnDebug :engine:lintCpuDebug
gradle :engine:testCpuDebugUnitTest :engine:testWebgpuDebugUnitTest :engine:testQnnDebugUnitTest
```

Run a single unit test method:

```bash
gradle :engine:testCpuDebugUnitTest :engine:testWebgpuDebugUnitTest --tests "com.example.vocalremover.YourTestClass.yourTestMethod"
```

If instrumentation tests are added/updated:

```bash
gradle :ui:connectedCpuDebugAndroidTest :ui:connectedWebgpuDebugAndroidTest :ui:connectedQnnDebugAndroidTest
```

`BackendBenchmarkTest` (`ui/src/androidTest`) is the on-device benchmark: it processes a deterministic
synthetic stereo signal with the flavor's backend, compares it against an in-process CPU-EP
reference (`VocalRemover(context, ExecutionBackend.CPU)`), and fails on NaN/Inf, silence, or
SNR < 20 dB. It reports a JSON line (logcat tag `VRBenchmark` and
`files/benchmark/report-<flavor>.json`), runnable on Firebase Test Lab (see `README.md`). Its pure
helpers (`BenchmarkMetrics`, `SyntheticStereoSignal`, `BenchmarkReport`) live in
`engine/src/benchmarkShared/java`, a source dir shared by `:engine` `test` and `:ui` `androidTest` only, so they never
ship in the app APK.

The `cpuDebug` and `webgpuDebug` flavors use separate application IDs and model caches. The
`cpu` flavor uses ORT's default CPU EP: XNNPACK was measured slower on Realme RMX3301 and crashed
natively inside `session.run` on OPPO CPH2791. Also measured without useful gains (2026-10, do not
retry without a new idea):
- Intra-op thread count (`setIntraOpNumThreads`): on Test Lab, the best explicit value was within
  ~4% of ORT's default on Galaxy A16 (Exynos 1330) and slower on Galaxy S24 (Exynos 2400).
- INT8 quantization (onnxruntime.quantization, calibrated on MUSDB18 7 s excerpts): static QDQ
  drops SDR vs the true accompaniment from 14.8 to 9–10 dB (any exclusion of edge layers/MatMul);
  dynamic MatMul-only drops it to 13.5 dB. The model needs quantization-aware training for INT8.
Keep CPU as the quality baseline; WebGPU is device-specific and must not be selected based on
speed alone. Compare backends on the same device with the same input and listen to both results.
Follow the existing logging pattern and remove temporary benchmark instrumentation after use.

The `qnn` flavor (Qualcomm NPU/HTP, `onnxruntime-android-qnn:1.24.3`, which cannot share an APK
with `onnxruntime-android`, hence per-flavor dependencies) needs all of the following. Each one was
required on Realme RMX3301 (SM8450, HTP v69):
- `engine/src/qnn/AndroidManifest.xml` declares `<uses-native-library android:name="libcdsprpc.so">`.
  Without it the HTP stub fails with `libcdsprpc.so not found` and ORT silently falls back to CPU.
  Do not override `ADSP_LIBRARY_PATH`.
- Legacy JNI packaging (`useLegacyPackaging`, in `ui/build.gradle.kts`) is enabled only for the qnn variant, so that
  `libQnnHtp.so` and the Skel libraries exist on disk in `nativeLibraryDir`.
- `setSymbolicDimensionValue("batch_size", 1)`: the model has a symbolic batch dimension and no
  `value_info`, and without this QNN rejects every node with "Cannot get shape".
- The HTP graph compile is cached as an EP context (`QnnConfig.contextCacheFile`, keyed by
  `lastUpdateTime`) in `filesDir`. `VocalRemover` creates its session lazily, off the UI thread
  (`warmUp()` from `MainActivity`), because the first compile takes tens of seconds.
Measured: ONNX run ~245 ms/chunk (CPU ~6 s), outputPeak 0.48093 vs CPU 0.48072, FP16 on HTP.

## High-level architecture

The app pipeline is:

1. `:ui` `MainActivity` (Compose) is the launcher; for now it only opens the previous `:engine` `MainActivity`, which handles permissions, file selection, UI state, and player controls.
2. `AudioPlayer` decodes input audio (`MediaExtractor` + `MediaCodec`) into true stereo float PCM at 44.1kHz (`StereoPcm`, no premature downmix), calls vocal-removal processing, and downmixes to mono only right before playback with `AudioTrack`.
3. For system-audio capture, the engine activity obtains MediaProjection consent and starts `SystemAudioCaptureService`; the service writes captured stereo PCM to a temporary WAV, and the activity consumes the terminal result, reads the WAV, and coordinates processing/saving.
4. `VocalRemover` runs model inference with **ONNX Runtime** (`onnxruntime-android:1.24.3`) using the stereo-aware **UVR-MDX-NET-Inst_HQ_5** ONNX model (`vocal_remover_fp32.onnx`):
   - The model outputs the **instrumental's complex spectrum directly** (no ratio-mask step, unlike the old Open-Unmix pipeline) — ONNX inference output goes straight into ISTFT.
   - `MdxStftProcessor` implements the model's exact **centered/reflect-padded STFT/ISTFT** convention (`n_fft=5120`, `hop_length=1024`, `dim_f=2560` frequency-bin crop) required by MDX-Net. Since `n_fft=5120 = 5·1024` is not a power of 2, it uses `MixedRadixFft` (radix-5 × table-driven radix-2, ~15x faster than Bluestein); other lengths (e.g. `n_fft=6` in tests) fall back to `BluesteinFft`. The pipeline calls `forwardStereoInto`/`inverseStereoInto`, which pack L and R into one complex FFT per frame (`z = L + i·R`) and must stay numerically identical to two per-channel `forwardInto`/`inverseInto` calls (see `MdxStftStereoTest`).
   - `MdxChunker` implements the reference tool's outer 25%-overlap waveform chunking (fixed `chunkSize = hopLength*(dimT-1)` samples per ONNX call, since the graph has a fixed `dim_t=256` frame constraint), overlap-added with a **symmetric** Hann window (`numpy.hanning` formula, denominator `len-1` — distinct from the STFT's **periodic** Hann window, denominator `nFft`). `processStereo` drives both channels in lockstep since the model takes a single joint 4-channel tensor (`[L_re, L_im, R_re, R_im]`) per chunk, not two independent per-channel passes.
   - Peak normalization quirk (replicated exactly from the reference tool): if `max(abs(signal)) > 0.9`, scale down by `0.9/peak` before processing, then multiply the final output by the **original** (pre-scaling) peak — not by `0.9`.
   - The first 3 frequency bins of the model's input spectrum are zeroed before every inference call (matches reference tool behavior).

Related offline tooling (deprecated, kept for reference only):

- `convert_spleeter.py` converts Spleeter 2-stems to TFLite; this pipeline has been superseded and is no longer wired into the app. Do not assume `spleeter_2stems.tflite`/TensorFlow Lite is in use.

## Key repository conventions

- For user-visible static text in the active `:ui` Compose app (labels, messages, and warnings), use Android string resources in `ui/src/main/res/values/strings.xml` and read them with `stringResource`; do not hard-code UI copy or use `.properties`. Do not modify the legacy UI in `:engine` for this rule; it is slated for removal.
- Every `:ui` Compose screen shows the app logo (`R.drawable.logo_corallo_blu`) next to its title.
- Keep audio constants aligned across components:
  - `MdxStftProcessor`/`MdxChunker` (`nFft=5120`, `hopLength=1024`, `dimF=2560`, `dimT=256`, `overlap=0.25`) — these match the UVR-MDX-NET-Inst_HQ_5 ONNX model's expected I/O contract exactly (`[batch,4,2560,256]` tensor, channel order `[L_re,L_im,R_re,R_im]`).
  - `AudioPlayer` decode/processing is **true stereo** float PCM at 44.1kHz (`StereoPcm`); downmix to mono happens only immediately before `AudioTrack` playback.
  - `VocalRemover.DIM_T = 256` requires **exactly** 256 STFT frames per inference call — this is a hard constraint baked into the model graph, not a tunable value. `MdxChunker`'s outer chunking exists specifically to feed the model fixed-size chunks regardless of song length.
- Never feed the model duplicated mono-as-stereo data — it is a genuinely stereo-aware model and requires real left/right channel differences for correct separation quality.
- Preserve progress-phase semantics exposed to UI:
  - processing progress is emitted from `VocalRemover.removeVocals` and mapped in `MainActivity` to user-facing phase labels.
- `AudioPlayer` is the orchestration boundary for decode/process/playback:
  - callbacks (`onProgress`, `onReady`, `onError`, `onPlaybackPositionChanged`) are the integration contract with `MainActivity`.
- In `:ui`, never hard-code user-visible text (labels, statuses, Toasts, error messages, notifications): put it in `ui/src/main/res/values/strings.xml` and read it with `stringResource` (composables) or `getString` (Activities/Services). Only the legacy `:engine` UI still uses direct string literals (mostly Italian); leave it as is.
- `onnxruntime-android` must stay at **1.24.3+** to satisfy the Android 16KB page size requirement (both `libonnxruntime.so` and `libonnxruntime4j_jni.so` must be 16KB-aligned; versions before 1.23.0 have a misaligned JNI lib). Verify alignment via `unzip -p app-debug.apk lib/arm64-v8a/lib*.so > /tmp/lib.so && readelf -lW /tmp/lib.so | awk '/LOAD/{print $NF}' | sort -u` (look for `0x4000`).
