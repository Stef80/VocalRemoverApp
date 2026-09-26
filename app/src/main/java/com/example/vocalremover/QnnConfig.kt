package com.example.vocalremover

import java.io.File

/** Configurazione del provider QNN (NPU Hexagon HTP sui SoC Snapdragon). */
object QnnConfig {

    /** Opzioni del provider: backend HTP caricato dalla cartella delle librerie native dell'app. */
    fun providerOptions(nativeLibraryDir: String): Map<String, String> = mapOf(
        "backend_path" to "$nativeLibraryDir/libQnnHtp.so",
        "htp_performance_mode" to "burst",
        // Il modello è FP32: HTP lo esegue in FP16 (supportato da HTP v69+).
        "enable_htp_fp16_precision" to "1"
    )

    /**
     * L'input del modello ha `batch_size` simbolico e nessuna value_info: senza fissarlo
     * QNN non ricava le forme intermedie ("Cannot get shape") e ricade tutto su CPU.
     */
    val freeDimensionOverrides: Map<String, Long> = mapOf("batch_size" to 1L)

    private const val CONTEXT_PREFIX = "qnn_ctx_"
    private const val CONTEXT_SUFFIX = ".onnx"

    /**
     * Grafo HTP già compilato (EP context). La compilazione richiede minuti: si fa una volta
     * e si riusa. La chiave è l'istante d'installazione, così un aggiornamento la invalida.
     */
    fun contextCacheFile(dir: File, installKey: Long): File =
        File(dir, "$CONTEXT_PREFIX$installKey$CONTEXT_SUFFIX")

    /** Config di sessione che fa salvare a ORT il contesto compilato in [file]. */
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
