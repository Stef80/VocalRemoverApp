package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionBackendTest {

    @Test
    fun `parses cpu backend`() {
        assertEquals(ExecutionBackend.CPU, ExecutionBackend.fromId("cpu"))
    }

    @Test
    fun `parses webgpu backend`() {
        assertEquals(ExecutionBackend.WEBGPU, ExecutionBackend.fromId("webgpu"))
    }

    @Test
    fun `parses qnn backend`() {
        assertEquals(ExecutionBackend.QNN, ExecutionBackend.fromId("qnn"))
    }

    @Test
    fun `qnn provider options use htp backend from native lib dir in burst fp16 mode`() {
        val options = QnnConfig.providerOptions("/data/app/x/lib/arm64")

        assertEquals("/data/app/x/lib/arm64/libQnnHtp.so", options["backend_path"])
        assertEquals("burst", options["htp_performance_mode"])
        assertEquals("1", options["enable_htp_fp16_precision"])
    }

    @Test
    fun `qnn fixes the symbolic batch dimension to one`() {
        assertEquals(mapOf("batch_size" to 1L), QnnConfig.freeDimensionOverrides)
    }

    @Test
    fun `qnn context cache file is keyed by app install time`() {
        val dir = java.io.File("/data/cache")
        assertEquals(java.io.File(dir, "qnn_ctx_42.onnx"), QnnConfig.contextCacheFile(dir, 42L))
    }

    @Test
    fun `qnn context cache generation writes an embedded context to the given file`() {
        val file = java.io.File("/data/cache/qnn_ctx_42.onnx")
        assertEquals(
            mapOf(
                "ep.context_enable" to "1",
                "ep.context_embed_mode" to "1",
                "ep.context_file_path" to file.absolutePath
            ),
            QnnConfig.contextGenerationConfig(file)
        )
    }

    @Test
    fun `qnn stale context caches exclude the current one and unrelated files`() {
        val names = listOf("qnn_ctx_1.onnx", "qnn_ctx_42.onnx", "vocal_remover_fp32.onnx", "qnn_ctx_7.onnx")
        assertEquals(
            listOf("qnn_ctx_1.onnx", "qnn_ctx_7.onnx"),
            QnnConfig.staleContextCaches(names, "qnn_ctx_42.onnx")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown backend`() {
        ExecutionBackend.fromId("nnapi")
    }
}
