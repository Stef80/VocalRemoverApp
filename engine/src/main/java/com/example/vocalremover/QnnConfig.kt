package com.example.vocalremover

import java.io.File

/** Configuration of the QNN provider (Hexagon HTP NPU on Snapdragon SoCs). */
object QnnConfig {

    /** Provider options: HTP backend loaded from the app's native library folder. */
    fun providerOptions(nativeLibraryDir: String): Map<String, String> = mapOf(
        "backend_path" to "$nativeLibraryDir/libQnnHtp.so",
        "htp_performance_mode" to "burst",
        // The model is FP32: HTP runs it in FP16 (supported by HTP v69+).
        "enable_htp_fp16_precision" to "1"
    )

    /**
     * The model input has a symbolic `batch_size` and no value_info: without pinning it
     * QNN cannot infer the intermediate shapes ("Cannot get shape") and everything falls back to CPU.
     */
    val freeDimensionOverrides: Map<String, Long> = mapOf("batch_size" to 1L)

    private const val CONTEXT_PREFIX = "qnn_ctx_"
    private const val CONTEXT_SUFFIX = ".onnx"

    /**
     * Already compiled HTP graph (EP context). Compiling takes minutes: it is done once
     * and reused. The key is the install time, so an update invalidates it.
     */
    fun contextCacheFile(dir: File, installKey: Long): File =
        File(dir, "$CONTEXT_PREFIX$installKey$CONTEXT_SUFFIX")

    /** Session config that makes ORT save the compiled context to [file]. */
    fun contextGenerationConfig(file: File): Map<String, String> = mapOf(
        "ep.context_enable" to "1",
        "ep.context_embed_mode" to "1",
        "ep.context_file_path" to file.absolutePath
    )

    fun staleContextCaches(fileNames: List<String>, currentName: String): List<String> =
        fileNames.filter {
            it != currentName && it.startsWith(CONTEXT_PREFIX) && it.endsWith(CONTEXT_SUFFIX)
        }
}
