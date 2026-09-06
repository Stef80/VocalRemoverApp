# VocalRemoverApp

App Android per la separazione voce/strumentale on-device tramite ONNX Runtime e un modello Open-Unmix (UMX-L).

## Setup del modello ONNX (obbligatorio)

Il modello `vocal_remover.onnx` (~108MB) **non è incluso nel repository** perché supera il limite di 100MB imposto da GitHub. Va scaricato manualmente prima di poter compilare/eseguire l'app.

1. Scarica `umxl_vocals.onnx` da [Generalclassic1700/GEC1700-vocal-remover](https://huggingface.co/Generalclassic1700/GEC1700-vocal-remover) su Hugging Face.
2. Rinominalo in `vocal_remover.onnx`.
3. Copialo in `app/src/main/assets/vocal_remover.onnx`.

Il file è elencato in `.gitignore` (pattern `*.onnx`): resterà sul tuo disco ma non verrà mai committato per errore. Ogni sviluppatore/macchina di build deve ripetere questo passaggio dopo il clone.

## Build

```bash
gradle :app:assembleDebug
```

Vedi `.github/copilot-instructions.md` per i dettagli sull'architettura della pipeline audio.
