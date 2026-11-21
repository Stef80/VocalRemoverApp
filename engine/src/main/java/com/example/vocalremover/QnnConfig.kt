package com.example.vocalremover

import android.content.Context
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

    /**
     * Fail the session instead of silently running unsupported nodes on CPU: a "qnn" session that is
     * really CPU would be mislabelled and slower.
     */
    val sessionConfig: Map<String, String> = mapOf("session.disable_cpu_ep_fallback" to "1")

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

    /**
     * The context cache of this install, in noBackupFilesDir: at ~40 MB it would exceed the Auto
     * Backup quota (and skip the whole app backup), and it is useless after a restore anyway.
     */
    fun contextCacheFile(context: Context, installKey: Long): File =
        contextCacheFile(context.noBackupFilesDir, installKey)

    /**
     * Deletes the caches of previous installs next to [current], and every cache left in filesDir
     * by builds that stored it there.
     */
    fun deleteStaleContextCaches(context: Context, current: File) {
        val dir = current.parentFile ?: return
        staleContextCaches(dir.list()?.toList().orEmpty(), current.name).forEach { File(dir, it).delete() }
        val legacyDir = context.filesDir
        if (legacyDir != dir) {
            staleContextCaches(legacyDir.list()?.toList().orEmpty(), currentName = "")
                .forEach { File(legacyDir, it).delete() }
        }
    }

    fun staleContextCaches(fileNames: List<String>, currentName: String): List<String> =
        fileNames.filter {
            it != currentName && it.startsWith(CONTEXT_PREFIX) && it.endsWith(CONTEXT_SUFFIX)
        }
}
