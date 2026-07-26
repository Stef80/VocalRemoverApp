# Copilot Instructions for VocalRemoverApp

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
2. `AudioPlayer` decodes input audio (`MediaExtractor` + `MediaCodec`), normalizes it to mono float PCM at 44.1kHz, calls vocal-removal processing, and plays output with `AudioTrack`.
3. `VocalRemover` runs model inference with TensorFlow Lite:
   - STFT → magnitude spectrogram
   - chunked inference (`CHUNK_FRAMES`) to avoid OOM
   - accompaniment mask application
   - ISTFT reconstruction
4. `StftProcessor` provides in-project FFT/STFT/ISTFT primitives and window normalization (no external DSP library in the Android app path).

Related offline tooling:

- `convert_spleeter.py` converts Spleeter 2-stems to TFLite and must stay parameter-aligned with Kotlin audio constants.
- The app expects `spleeter_2stems.tflite` in `app/src/main/assets/`.

## Key repository conventions

- Keep audio constants aligned across components:
  - `StftProcessor` (`nFft=4096`, `hopLength=1024`, `sampleRate=44100`)
  - `AudioPlayer` playback/processing sample rate (44.1kHz mono float PCM)
  - `convert_spleeter.py` conversion/test constants (`N_FFT`, `HOP_LENGTH`, `SAMPLE_RATE`, `CHUNK_FRAMES`)
- Preserve progress-phase semantics exposed to UI:
  - processing progress is emitted from `VocalRemover.removeVocals` and mapped in `MainActivity` to user-facing phase labels.
- `AudioPlayer` is the orchestration boundary for decode/process/playback:
  - callbacks (`onProgress`, `onReady`, `onError`, `onPlaybackPositionChanged`) are the integration contract with `MainActivity`.
- The app currently uses direct string literals in UI/status updates (mostly Italian) in Kotlin/layout rather than fully centralized string resources; follow the existing pattern unless the task is a localization refactor.
