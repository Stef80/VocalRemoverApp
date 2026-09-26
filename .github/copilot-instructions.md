# Copilot Instructions for VocalRemoverApp

## Git commits

**Never run `git commit` (or otherwise commit) on the user's behalf**, in this
repository, regardless of how routine or low-risk the change seems (docs,
specs, plans, generated code, etc.). Stage/prepare changes and describe what
would be committed and why, but leave the actual commit action to the user to
run themselves. This applies even if earlier turns in the same session show
commits made automatically — that behavior is retired.

## Model file setup (required before build)

`app/src/main/assets/vocal_remover.onnx` (~59MB, UVR-MDX-NET-Inst_HQ_5) is **not tracked in git** (excluded via `.gitignore`, `*.onnx`) because it exceeds GitHub's 100MB file size limit. It must be downloaded manually and placed at that exact path before building — see `README.md` for the download link and steps. Do not attempt to `git add -f` this file; it will be rejected by GitHub's push size check.

## Build, test, and lint

This repository is an Android app built with Gradle Kotlin DSL (`build.gradle.kts`, `app/build.gradle.kts`).

Use Gradle from the repo root:

```bash
gradle :app:assembleCpuDebug :app:assembleWebgpuDebug
gradle :app:lintCpuDebug :app:lintWebgpuDebug
gradle :app:testCpuDebugUnitTest :app:testWebgpuDebugUnitTest
```

Run a single unit test method:

```bash
gradle :app:testCpuDebugUnitTest :app:testWebgpuDebugUnitTest --tests "com.example.vocalremover.YourTestClass.yourTestMethod"
```

If instrumentation tests are added/updated:

```bash
gradle :app:connectedCpuDebugAndroidTest :app:connectedWebgpuDebugAndroidTest
```

The `cpuDebug` and `webgpuDebug` flavors use separate application IDs and model caches. The
`cpu` flavor uses ORT's default CPU EP: XNNPACK was measured slower on Realme RMX3301 and crashed
natively inside `session.run` on OPPO CPH2791. Keep
CPU as the quality baseline; WebGPU is device-specific and must not be selected based on speed
alone. To compare, run both builds on the same device with the same input audio and listen to
both results. Logs report the configured backend and cached model path/size.

## High-level architecture

The app pipeline is:

1. `MainActivity` handles permissions, file selection, UI state, and player controls.
2. `AudioPlayer` decodes input audio (`MediaExtractor` + `MediaCodec`) into true stereo float PCM at 44.1kHz (`StereoPcm`, no premature downmix), calls vocal-removal processing, and downmixes to mono only right before playback with `AudioTrack`.
3. `VocalRemover` runs model inference with **ONNX Runtime** (`onnxruntime-android:1.24.3`) using the **UVR-MDX-NET-Inst_HQ_5** ONNX model (`vocal_remover.onnx`, ~59MB, stereo-aware, replaces the previous Open-Unmix model as of the 2026-09 migration — benchmarking on real audio showed 3-4x lower vocal cross-leak, 0.02-0.03 vs 0.08-0.14):
   - The model outputs the **instrumental's complex spectrum directly** (no ratio-mask step, unlike the old Open-Unmix pipeline) — ONNX inference output goes straight into ISTFT.
   - `MdxStftProcessor` implements the model's exact **centered/reflect-padded STFT/ISTFT** convention (`n_fft=5120`, `hop_length=1024`, `dim_f=2560` frequency-bin crop) required by MDX-Net, distinct from `StftProcessor`'s non-centered convention. Since `n_fft=5120 = 5·1024` is not a power of 2, it uses `MixedRadixFft` (radix-5 × table-driven radix-2, ~15x faster than Bluestein); other lengths (e.g. `n_fft=6` in tests) fall back to `BluesteinFft`. The pipeline calls `forwardStereoInto`/`inverseStereoInto`, which pack L and R into one complex FFT per frame (`z = L + i·R`) and must stay numerically identical to two per-channel `forwardInto`/`inverseInto` calls (see `MdxStftStereoTest`).
   - `MdxChunker` implements the reference tool's outer 25%-overlap waveform chunking (fixed `chunkSize = hopLength*(dimT-1)` samples per ONNX call, since the graph has a fixed `dim_t=256` frame constraint), overlap-added with a **symmetric** Hann window (`numpy.hanning` formula, denominator `len-1` — distinct from the STFT's **periodic** Hann window, denominator `nFft`). `processStereo` drives both channels in lockstep since the model takes a single joint 4-channel tensor (`[L_re, L_im, R_re, R_im]`) per chunk, not two independent per-channel passes.
   - Peak normalization quirk (replicated exactly from the reference tool): if `max(abs(signal)) > 0.9`, scale down by `0.9/peak` before processing, then multiply the final output by the **original** (pre-scaling) peak — not by `0.9`.
   - The first 3 frequency bins of the model's input spectrum are zeroed before every inference call (matches reference tool behavior).
4. `StftProcessor` provides the legacy FFT/STFT/ISTFT primitives (radix-2 only, non-centered, `n_fft=4096`) — still used by its own tests; `MdxStftProcessor`/`MixedRadixFft` are the primitives actually used by the current `VocalRemover` pipeline.

Related offline tooling (deprecated, kept for reference only):

- `convert_spleeter.py` converts Spleeter 2-stems to TFLite; this pipeline has been superseded and is no longer wired into the app. Do not assume `spleeter_2stems.tflite`/TensorFlow Lite is in use.

## Key repository conventions

- Keep audio constants aligned across components:
  - `MdxStftProcessor`/`MdxChunker` (`nFft=5120`, `hopLength=1024`, `dimF=2560`, `dimT=256`, `overlap=0.25`) — these match the UVR-MDX-NET-Inst_HQ_5 ONNX model's expected I/O contract exactly (`[batch,4,2560,256]` tensor, channel order `[L_re,L_im,R_re,R_im]`).
  - `AudioPlayer` decode/processing is **true stereo** float PCM at 44.1kHz (`StereoPcm`); downmix to mono happens only immediately before `AudioTrack` playback.
  - `VocalRemover.DIM_T = 256` requires **exactly** 256 STFT frames per inference call — this is a hard constraint baked into the model graph, not a tunable value. `MdxChunker`'s outer chunking exists specifically to feed the model fixed-size chunks regardless of song length.
- Never feed the model duplicated mono-as-stereo data — it is a genuinely stereo-aware model and requires real left/right channel differences for correct separation quality.
- Preserve progress-phase semantics exposed to UI:
  - processing progress is emitted from `VocalRemover.removeVocals` and mapped in `MainActivity` to user-facing phase labels.
- `AudioPlayer` is the orchestration boundary for decode/process/playback:
  - callbacks (`onProgress`, `onReady`, `onError`, `onPlaybackPositionChanged`) are the integration contract with `MainActivity`.
- The app currently uses direct string literals in UI/status updates (mostly Italian) in Kotlin/layout rather than fully centralized string resources; follow the existing pattern unless the task is a localization refactor.
- `onnxruntime-android` must stay at **1.24.3+** to satisfy the Android 16KB page size requirement (both `libonnxruntime.so` and `libonnxruntime4j_jni.so` must be 16KB-aligned; versions before 1.23.0 have a misaligned JNI lib). Verify alignment via `unzip -p app-debug.apk lib/arm64-v8a/lib*.so > /tmp/lib.so && readelf -lW /tmp/lib.so | awk '/LOAD/{print $NF}' | sort -u` (look for `0x4000`).
