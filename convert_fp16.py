import onnx
from onnxconverter_common import float16

SRC = "app/src/main/assets/vocal_remover.onnx"
DST = "app/src/main/assets/vocal_remover_fp16.onnx"

m = onnx.load(SRC)
m16 = float16.convert_float_to_float16(
    m,
    keep_io_types=True,      # I/O rimangono FP32 (sicuro per il runtime)
    disable_shape_infer=False
)
onnx.save(m16, DST)
print("Scritto:", DST)