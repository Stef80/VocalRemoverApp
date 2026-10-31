package com.example.vocalremover.benchmark

import java.util.Locale

/** Result of a benchmark on one device, serialized as a single JSON line. */
data class BenchmarkReport(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val sdk: Int,
    val backend: String,
    val audioSeconds: Double,
    val sessionLoadMs: Long,
    val processMs: Long,
    val referenceProcessMs: Long?,
    val snrDb: Double,
    val outputRms: Double,
    val passed: Boolean,
    val failure: String?
) {
    /** Seconds of processing per second of audio (< 1 = faster than real time). */
    val realtimeFactor: Double get() = processMs / 1000.0 / audioSeconds

    fun toJson(): String = buildString {
        append('{')
        append("\"manufacturer\":").append(str(manufacturer)).append(',')
        append("\"model\":").append(str(model)).append(',')
        append("\"soc\":").append(str(soc)).append(',')
        append("\"sdk\":").append(sdk).append(',')
        append("\"backend\":").append(str(backend)).append(',')
        append("\"audioSeconds\":").append(num(audioSeconds)).append(',')
        append("\"sessionLoadMs\":").append(sessionLoadMs).append(',')
        append("\"processMs\":").append(processMs).append(',')
        append("\"referenceProcessMs\":").append(referenceProcessMs?.toString() ?: "null").append(',')
        append("\"realtimeFactor\":").append(num(realtimeFactor)).append(',')
        append("\"snrDb\":").append(num(snrDb)).append(',')
        append("\"outputRms\":").append(num(outputRms)).append(',')
        append("\"passed\":").append(passed).append(',')
        append("\"failure\":").append(failure?.let(::str) ?: "null")
        append('}')
    }

    private fun num(v: Double): String =
        if (v.isFinite()) String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.') else "null"

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
}
