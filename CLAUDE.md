# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Android app for on-device vocal/instrumental separation with ONNX Runtime and the stereo-aware
UVR-MDX-NET-Inst_HQ_5 model. Code comments, log messages and developer-facing exception
messages are in **English**; docs (`docs/`, README) and user-visible UI copy stay in Italian.
`.github/copilot-instructions.md` holds the full rationale and measurements behind the rules below.

## Hard rules

- **Never `git commit` on the user's behalf**, however routine the change. Prepare/stage changes and
  describe what would be committed; the user commits.
- ONNX models are not in git (`*.onnx` ignored); never force-add them. `VocalRemover.MODEL_ASSET`
  loads `engine/src/main/assets/vocal_remover_fp32.onnx` (the README's model setup says the same;
  keep them aligned if you touch it). `vocal_remover.onnx` is only the input of `convert_fp16.py` and
  is excluded from the APK by `ui/build.gradle.kts`.
- `:engine` must never depend on `:ui`.

## Commands

There is no `gradlew` script in the repo; run `gradle` from the root. AGP needs Gradle ≥ 9.6, and
the system `gradle` may be older (9.3.1 fails at configuration): use the version pinned in
`gradle/wrapper/gradle-wrapper.properties` (9.8.0, cached under `~/.gradle/wrapper/dists/`).
There are no product flavors: one app, `applicationId` `com.stef.vocalremover`.

```bash
gradle :ui:assembleDebug
gradle :ui:lintDebug :engine:lintDebug
gradle :engine:testDebugUnitTest
gradle :ui:testDebugUnitTest                         # JVM tests in ui/src/test
# single test
gradle :engine:testDebugUnitTest --tests "com.example.vocalremover.MdxChunkerTest.someMethod"
# on-device benchmark (phone via adb); result is a JSON line, logcat tag VRBenchmark.
# backend = cpu|webgpu|qnn|auto (default auto)
gradle :ui:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.backend=qnn
scripts/run-testlab.sh auto [--dry-run]              # same benchmark on Firebase Test Lab (or cpu|webgpu|qnn)
scripts/build-ort-aar.sh                             # rebuild the custom ORT AAR into third_party/maven
```

The build needs `ui/google-services.json` (gitignored) with the client `com.stef.vocalremover`.

Release builds are signed with the user's own key, read from the Gradle properties
`vocalremover.release.storeFile|storePassword|keyAlias[|keyPassword]` in `~/.gradle/gradle.properties`
(PKCS12: `keyPassword` defaults to the store password). Without them the release APK is unsigned: never
reintroduce the debug key as a fallback, and never put keystores (`*.jks`, `*.keystore`) or passwords in
the repo. Release runs R8 (`isMinifyEnabled` + `isShrinkResources`): ORT's JNI looks up its Java classes by
name, so `engine/consumer-rules.pro` keeps `ai.onnxruntime.**`. Code reached only via reflection or JNI
needs a keep rule there, and R8 regressions show up only at runtime: test the release APK on a device.

## Modules

- `:ui` — the **application** (namespace `com.example.vocalremover.ui`, Kotlin package
  `com.stef.vocalremover.ui`). Compose launcher `MainActivity` (Splash → `PlayerScreen` /
  `ImportSongScreen`), the floating capture bubble in `ui/overlay/` (`BubbleOverlayService`,
  `MediaProjectionConsentActivity`), `applicationId` `com.stef.vocalremover` (arm64-v8a only),
  legacy JNI packaging, and the `androidTest` `BackendBenchmarkTest`.
- `:engine` — Android **library** (`com.example.vocalremover`): decoding, inference, system-audio
  capture (`capture/`), processing (`processing/`), library playback (`playback/`), the model asset
  and most unit tests. It has no screens of its own (only the capture-service notification).
- `:connections` — Android library (`com.stef.vocalremover.connections`): network lookups, today only
  lyrics on LRCLIB (OkHttp). `:engine` depends on it, never the other way round. The server is untrusted
  (`LrclibSecurityTest`): bodies are capped at 2 MB, redirects are never followed (a 3xx is a failure),
  and logs never carry titles, artists or lyrics. The contact in the User-Agent comes from the Gradle
  property `lrclib.contact`, never from the code.
- No product flavors: the backend (`ExecutionBackend`) is chosen at runtime, not at build time.
  `backend/BackendCandidates` (engine) lists the backends to try for the device's SoC, best first;
  `VocalRemover` takes the backend as a required constructor argument.
  `ProbingBackendSelector` (`BackendSelector`) probes the candidates once per install/OS build
  (key `lastUpdateTime|Build.FINGERPRINT`, SharedPreferences `backend_choice`): `OrtBackendProber`
  runs exactly one model chunk of `ProbeSignal` (`VocalRemover.runSingleChunk`: one `session.run`,
  no chunker) twice, times the second `session.run` and checks the chunk's ISTFT against the CPU
  reference asset `backend_probe/expected_fp16.bin` (SNR ≥ 20 dB); the fastest passing one wins, and
  CPU is probed only when no accelerator passed. Only backend-attributable failures are persisted
  (`OrtException`, `LinkageError`, `BackendOutputException`, quality verdicts); environment failures
  (model copy/reference load in `BackendProber.prepare`, `IOException`, `OutOfMemoryError`) are
  inconclusive: the result is used in memory for this process and the next start probes again.
  A crash marker (`markInProgress`, written with a synchronous `commit()`, / `markCompleted`, read
  with `AndroidProcessExitInfo`, native crashes only) blacklists a backend that killed the process.
  `:ui`'s `VocalRemoverApplication` owns the selector; `MainActivity.onCreate` starts the probe
  (never `Application.onCreate`: Robolectric would load ORT). `FallbackInstrumentalExtractor` retries
  the whole song on the next backend on `OrtException`/`BackendOutputException` (per-chunk
  NaN/silence check, never on CPU; log tag `FallbackExtractor`); `SongProcessor` shows
  `PreparingBackend` while the probe runs and `RetryingBackend` after a mid-song backend switch,
  until the new run reports real progress.
  Regenerate the asset with `ProbeReferenceGenerator` (`-e generateProbeReference true`) whenever
  the model changes.
- `engine/src/benchmarkShared/java` (`BenchmarkReport`) is a source dir added only to `:engine` `test`
  and `:ui` `androidTest`, so it never ships in the APK. Output-quality checks (`OutputQuality`) and the
  deterministic probe signal (`ProbeSignal`) live in `:engine` main (`backend/`): the app's backend probe uses them.
- compileSdk 37 (required by the Compose BOM), targetSdk 36, minSdk 26, JVM 17.

## Audio pipeline (spans several files)

1. `SongProcessor` (engine `processing/`) is the decode → extract → save boundary: `AudioDecoder`
   (`MediaExtractor` + `MediaCodec`) → true-stereo float PCM at 44.1 kHz (`StereoPcm`) →
   `InstrumentalExtractor` (`FallbackInstrumentalExtractor` around `VocalRemover`) → stereo WAV in
   Music/VocalRemover (`RecordingSaver`). Its `ProcessingState` flow is the contract with `:ui`:
   `ImportViewModel` maps it to `ProcessingPhase`; percentages come from `VocalRemover.removeVocals`.
   Saved WAVs are played by `LibraryPlayer` (ExoPlayer). `AudioPlayer` (AudioTrack, mono downmix
   right before playback) is legacy and has no callers.
2. System-audio capture: MediaProjection consent → `SystemAudioCaptureService` writes stereo PCM to a
   temp WAV → a terminal result (`CaptureTerminalResultStore`) is consumed, the WAV read and processed/saved.
3. `VocalRemover` runs ORT. The model outputs the **instrumental complex spectrum directly** (no
   mask), straight into ISTFT. Session is created lazily off the UI thread (`warmUp()`).
   - `MdxStftProcessor`: centered/reflect-padded STFT/ISTFT, `nFft=5120`, `hop=1024`, `dimF=2560`
     crop, **periodic** Hann. Uses `MixedRadixFft` (5×2^n); other sizes fall back to `BluesteinFft`.
     The stereo path packs L+iR into one FFT and must stay numerically identical to per-channel calls
     (`MdxStftStereoTest`).
   - `MdxChunker`: 25%-overlap outer chunking, `chunkSize = hop*(dimT-1)`, **symmetric** Hann
     (`numpy.hanning`), both channels in lockstep as one `[1,4,2560,256]` tensor `[L_re,L_im,R_re,R_im]`.
   - `DIM_T = 256` is fixed by the graph, not tunable. Zero the first 3 bins before inference.
   - Peak quirk from the reference tool: if peak > 0.9, scale by `0.9/peak` before, then multiply
     output by the **original** peak.
   - Never feed duplicated mono as stereo.

## Backend constraints (measured; don't regress)

- CPU (ORT default EP) is the quality baseline. XNNPACK, intra-op thread tuning and INT8
  quantization were all tried and rejected (see copilot-instructions). WebGPU is device-specific and
  never chosen on speed alone — it can produce ruined audio without crashing (the benchmark's SNR check catches this).
- ORT is a single custom AAR `com.stef.vocalremover.thirdparty:onnxruntime-android-vr:1.24.3` with
  the CPU, WebGPU and QNN EPs (the official `onnxruntime-android` and `onnxruntime-android-qnn` can't
  share an APK). It is built from source by `scripts/build-ort-aar.sh` and committed in
  `third_party/maven` (first repository in `settings.gradle.kts`); its POM pulls
  `com.qualcomm.qti:qnn-runtime` from Maven Central.
- `qnn` needs all of: `<uses-native-library libcdsprpc.so>` in `engine/src/main/AndroidManifest.xml`
  (else silent CPU fallback; don't override `ADSP_LIBRARY_PATH`), legacy JNI packaging (on for the
  whole app, so the Skels exist on disk), `setSymbolicDimensionValue("batch_size", 1)`, and the HTP
  EP-context cache (`QnnConfig.contextCacheFile`, keyed by install `lastUpdateTime`, in
  `noBackupFilesDir`: at ~42 MB it would exceed the 25 MB Auto Backup quota and skip the whole app
  backup; caches left in `filesDir` by older builds are deleted).
  `QnnConfig.sessionConfig` sets `session.disable_cpu_ep_fallback = 1`, so a missing piece makes the
  QNN session fail (and the probe reject it) instead of silently running on CPU.
- ORT must stay ≥ 1.24.3 for 16 KB page alignment of `libonnxruntime*.so`.

## Conventions

- **No hard-coded user-visible text in `:ui`**: every label, status, Toast, error message and
  notification text goes in `ui/src/main/res/values/strings.xml`, read with `stringResource` in
  composables and `getString(R.string...)` in Activities/Services (placeholders as `%1$s`). In
  `:engine`, only the `SystemAudioCaptureService` notification still uses literals; leave it.
- Backup is an allowlist (`ui/src/main/res/xml/data_extraction_rules.xml` for API 31+,
  `backup_rules.xml` for older; keep them aligned): only the `capture_package_picker` and
  `now_playing_access` preferences. New persisted data stays out by default; lyrics and WorkManager state
  are keyed by MediaStore Uris, which differ on another device, so they must never be added.
- Every `:ui` screen shows the logo `R.drawable.logo_corallo_blu` next to its title.
- Design specs and implementation plans live in `docs/superpowers/specs` and `docs/superpowers/plans`.
- `convert_spleeter.py` / TFLite are deprecated and not wired into the app.
