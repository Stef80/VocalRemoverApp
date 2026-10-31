package com.example.vocalremover.benchmark

import kotlin.math.log10
import kotlin.math.sqrt

object BenchmarkMetrics {

    /** Signal-to-error ratio (dB) of [out] against [ref]; +∞ if identical. */
    fun snrDb(ref: FloatArray, out: FloatArray): Double = stereoSnrDb(ref, FloatArray(0), out, FloatArray(0))

    /** Like [snrDb] but summing energy and error of both channels. */
    fun stereoSnrDb(refL: FloatArray, refR: FloatArray, outL: FloatArray, outR: FloatArray): Double {
        require(refL.size == outL.size && refR.size == outR.size) { "Lunghezze diverse" }
        var signal = 0.0
        var noise = 0.0
        for ((ref, out) in listOf(refL to outL, refR to outR)) {
            for (i in ref.indices) {
                val r = ref[i].toDouble()
                val e = out[i] - r
                signal += r * r
                noise += e * e
            }
        }
        if (noise == 0.0) return Double.POSITIVE_INFINITY
        return 10.0 * log10(signal / noise)
    }

    fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sqrt(sum / x.size)
    }

    fun allFinite(x: FloatArray): Boolean = x.all { it.isFinite() }
}
