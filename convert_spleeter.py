"""
convert_spleeter.py
===================
DEPRECATO: l'app ora usa ONNX Runtime + un modello MDX-Net
già pre-convertito in formato .onnx (community Ultimate Vocal Remover),
al posto di TensorFlow Lite/Spleeter. Non serve più eseguire questo
script né installare TensorFlow.

Per ottenere il modello:
    1. Scarica un modello MDX-Net in formato .onnx (es. da
       https://github.com/TRvlvr/model_repo o repository Hugging Face
       della community UVR).
    2. Verifica shape input/output con https://netron.app e allinea
       StftProcessor.kt (nFft/hopLength) e VocalRemover.kt se necessario.
    3. Rinomina il file "vocal_remover.onnx" e copialo in
       app/src/main/assets/.

Questo script è mantenuto solo per riferimento storico (pipeline
Spleeter → TFLite precedente).

Converte il modello Spleeter 2-stems (TensorFlow SavedModel) in un file
TFLite compatibile con l'app Android.

Requisiti:
    pip install spleeter tensorflow==2.12.*

Uso:
    python convert_spleeter.py

Output:
    spleeter_2stems.tflite   ← copia questo file in app/src/main/assets/
"""

import numpy as np
import tensorflow as tf
from spleeter.separator import Separator
from spleeter.audio.adapter import AudioAdapter
import os

# ── Parameters (must match StftProcessor.kt) ──────────────────────
SAMPLE_RATE  = 44100
N_FFT        = 4096
HOP_LENGTH   = 1024
FREQ_BINS    = N_FFT // 2 + 1   # 2049
CHUNK_FRAMES = 512               # must match VocalRemover.CHUNK_FRAMES
OUTPUT_FILE  = "spleeter_2stems.tflite"

def get_spleeter_model():
    """Carica Spleeter e restituisce il modello separatore."""
    separator = Separator("spleeter:2stems")
    # Force model loading by running a test separation
    dummy_audio = np.zeros((SAMPLE_RATE * 5, 2), dtype=np.float32)
    separator.separate(dummy_audio, SAMPLE_RATE)
    return separator._tf_graph, separator._sess

def build_wrapper_model():
    """
    Crea un modello Keras che wrappa la logica di Spleeter.
    Input:  [batch, frames, freq_bins]  (spettrogramma di magnitudine float32)
    Output: [vocals_mask, accompaniment_mask]  entrambi [batch, frames, freq_bins]
    """
    # Spleeter uses a U-Net internally.
    # Here we reload the weights saved by spleeter in SavedModel format.
    import pathlib

    # Spleeter saves the models here after the first use:
    pretrained_path = pathlib.Path.home() / ".cache" / "spleeter" / "2stems"
    if not pretrained_path.exists():
        raise FileNotFoundError(
            f"Modello Spleeter non trovato in {pretrained_path}.\n"
            "Esegui prima: python -c \"from spleeter.separator import Separator; "
            "Separator('spleeter:2stems').separate({'audio': ...})\""
        )

    # Load SavedModel
    loaded = tf.saved_model.load(str(pretrained_path))
    return loaded

def convert_to_tflite(saved_model_path: str, output_path: str):
    """Converte il SavedModel in TFLite con ottimizzazione float16."""

    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_path)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    # Float16 → good quality/size trade-off (~halved)
    converter.target_spec.supported_types = [tf.float16]
    # For full-integer quantization (faster on ARM, slightly lower quality):
    # converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]

    tflite_model = converter.convert()
    with open(output_path, "wb") as f:
        f.write(tflite_model)
    size_mb = os.path.getsize(output_path) / 1e6
    print(f"✅ TFLite model saved: {output_path} ({size_mb:.1f} MB)")

def verify_tflite_model(path: str):
    """Verifica che il modello TFLite funzioni con un input di test."""
    interpreter = tf.lite.Interpreter(model_path=path)
    interpreter.allocate_tensors()

    input_details  = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print("\n📐 Input tensors:")
    for t in input_details:
        print(f"   {t['name']}: shape={t['shape']}, dtype={t['dtype']}")

    print("📐 Output tensors:")
    for t in output_details:
        print(f"   {t['name']}: shape={t['shape']}, dtype={t['dtype']}")

    # Test with random input
    test_input = np.random.rand(1, CHUNK_FRAMES, FREQ_BINS).astype(np.float32)
    interpreter.resize_input_tensor(input_details[0]['index'], test_input.shape)
    interpreter.allocate_tensors()
    interpreter.set_tensor(input_details[0]['index'], test_input)
    interpreter.invoke()

    vocals_mask = interpreter.get_tensor(output_details[0]['index'])
    accomp_mask = interpreter.get_tensor(output_details[1]['index'])
    print(f"\n✅ Test inference OK")
    print(f"   vocals_mask:      {vocals_mask.shape}, min={vocals_mask.min():.3f}, max={vocals_mask.max():.3f}")
    print(f"   accomp_mask:      {accomp_mask.shape}, min={accomp_mask.min():.3f}, max={accomp_mask.max():.3f}")
    print(f"\n📋 Copy '{path}' to: app/src/main/assets/{path}")

if __name__ == "__main__":
    import pathlib

    spleeter_saved_model = str(
        pathlib.Path.home() / ".cache" / "spleeter" / "2stems"
    )

    print(f"🔄 Converting from: {spleeter_saved_model}")
    convert_to_tflite(spleeter_saved_model, OUTPUT_FILE)
    verify_tflite_model(OUTPUT_FILE)
