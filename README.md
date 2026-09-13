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
gradle :app:assembleDebug
```

Vedi `.github/copilot-instructions.md` per i dettagli sull'architettura della pipeline audio.
