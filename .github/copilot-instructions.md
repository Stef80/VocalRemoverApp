# Copilot Instructions for VocalRemoverApp

## Git commits

**Never run `git commit` (or otherwise commit) on the user's behalf**, in this
repository, regardless of how routine or low-risk the change seems (docs,
specs, plans, generated code, etc.). Stage/prepare changes and describe what
would be committed and why, but leave the actual commit action to the user to
run themselves. This applies even if earlier turns in the same session show
commits made automatically — that behavior is retired.

## Model file setup (required before build)

`app/src/main/assets/vocal_remover.onnx` (~108MB, Open-Unmix UMX-L) is **not tracked in git** (excluded via `.gitignore`, `*.onnx`) because it exceeds GitHub's 100MB file size limit. It must be downloaded manually and placed at that exact path before building — see `README.md` for the download link and steps. Do not attempt to `git add -f` this file; it will be rejected by GitHub's push size check.

## Build, test, and lint

This repository is an Android app built with Gradle Kotlin DSL (`build.gradle.kts`, `app/build.gradle.kts`).

Use Gradle from the repo root:

```bash
gradle :app:assembleDebug
gradle :app:lintDebug
gradle :app:testDebugUnitTest
```

Run a single unit test method:

```bash
gradle :app:testDebugUnitTest --tests "com.example.vocalremover.YourTestClass.yourTestMethod"
```

If instrumentation tests are added/updated:

```bash
gradle :app:connectedDebugAndroidTest
```

## High-level architecture

The app pipeline is:

1. `MainActivity` handles permissions, file selection, UI state, and player controls.
2. `AudioPlayer` decodes input audio (`MediaExtractor` + `MediaCodec`) into true stereo float PCM at 44.1kHz (`StereoPcm`, no premature downmix), calls vocal-removal processing, and downmixes to mono only right before playback with `AudioTrack`.
3. `VocalRemover` runs model inference with **ONNX Runtime** (`onnxruntime-android:1.24.3`) using an **Open-Unmix (UMX-L) ONNX model** (`vocal_remover.onnx`, ~108MB, stereo-aware):
   - Processes audio in bounded **blocks** (`BLOCK_FRAMES = 200` STFT frames, ~4.6s) to keep peak memory constant regardless of song length (avoids OOM on long tracks).
   - Per block: block-wise STFT (`StftProcessor.stftRange`) on both channels → magnitude → ONNX inference in fixed **100-frame sub-chunks** (`CHUNK_FRAMES = 100`, hardcoded because the model's ONNX graph has fixed internal `Reshape` nodes that only accept exactly 100 frames; zero-padded on the last partial chunk) → ratio mask vs. original magnitude → reconstruct vocal Re/Im using original phase.
   - Vocal Re/Im frames are fed into a **streaming ISTFT** (`StftProcessor.StreamingIstft`) which maintains only a small `carryData`/`carryNorm` buffer (size `nFft`) per channel and emits `hopLength` finalized samples per frame — mathematically identical to whole-signal overlap-add ISTFT (verified numerically, zero diff), so no additional artifacts vs. the old whole-song approach.
   - Instrumental output is computed immediately per emitted sample (`original - vocals`), written into pre-allocated full-length output arrays — no full-song spectrogram is ever held in memory.
   - The model applies input/output normalization (`input_mean`/`input_scale`/`output_scale`/`output_mean`) internally in the ONNX graph; Kotlin code must NOT apply this normalization again.
4. `StftProcessor` provides FFT/STFT/ISTFT primitives (no external DSP library in the Android app path):
   - Whole-signal `stft()`/`istft()` (legacy, still used by tests/other call sites).
   - Block-wise `frameCount()`/`stftRange()` and the `StreamingIstft` inner class for bounded-memory streaming processing — this is what `VocalRemover` uses now.

Related offline tooling (deprecated, kept for reference only):

- `convert_spleeter.py` converts Spleeter 2-stems to TFLite; this pipeline has been superseded by the ONNX Runtime + Open-Unmix pipeline above and is no longer wired into the app. Do not assume `spleeter_2stems.tflite`/TensorFlow Lite is in use.

## Key repository conventions

- Keep audio constants aligned across components:
  - `StftProcessor` (`nFft=4096`, `hopLength=1024`, `sampleRate=44100`) — these match the Open-Unmix ONNX model's expected STFT params exactly.
  - `AudioPlayer` decode/processing is **true stereo** float PCM at 44.1kHz (`StereoPcm`); downmix to mono happens only immediately before `AudioTrack` playback.
  - The ONNX model (`VocalRemover.CHUNK_FRAMES = 100`) requires **exactly** 100 STFT frames per inference call — this is a hard constraint baked into the model graph, not a tunable value.
  - `VocalRemover.BLOCK_FRAMES` (currently 200) controls per-block memory/granularity for the streaming pipeline; safe to tune for memory/performance tradeoffs, unlike `CHUNK_FRAMES`.
- Never feed the model duplicated mono-as-stereo data — it is a genuinely stereo-aware model and requires real left/right channel differences for correct separation quality.
- Preserve progress-phase semantics exposed to UI:
  - processing progress is emitted from `VocalRemover.removeVocals` and mapped in `MainActivity` to user-facing phase labels.
- `AudioPlayer` is the orchestration boundary for decode/process/playback:
  - callbacks (`onProgress`, `onReady`, `onError`, `onPlaybackPositionChanged`) are the integration contract with `MainActivity`.
- The app currently uses direct string literals in UI/status updates (mostly Italian) in Kotlin/layout rather than fully centralized string resources; follow the existing pattern unless the task is a localization refactor.
- `onnxruntime-android` must stay at **1.24.3+** to satisfy the Android 16KB page size requirement (both `libonnxruntime.so` and `libonnxruntime4j_jni.so` must be 16KB-aligned; versions before 1.23.0 have a misaligned JNI lib). Verify alignment via `unzip -p app-debug.apk lib/arm64-v8a/lib*.so > /tmp/lib.so && readelf -lW /tmp/lib.so | awk '/LOAD/{print $NF}' | sort -u` (look for `0x4000`).
