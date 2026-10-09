package com.example.vocalremover

enum class ExecutionBackend(val id: String) {
    CPU("cpu"),
    WEBGPU("webgpu"),
    QNN("qnn");

    companion object {
        fun fromId(id: String): ExecutionBackend = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported execution backend: $id")
    }
}
