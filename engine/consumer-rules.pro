# Applied to the app that minifies :engine (consumerProguardFiles).

# ONNX Runtime: the custom AAR ships no keep rules, and libonnxruntime4j_jni.so looks up its Java
# classes, fields, constructors and methods by name (e.g. OnnxTensor, OrtException, the EP
# providers). Renaming or removing any of them only fails at runtime, inside native code.
-keep class ai.onnxruntime.** { *; }
