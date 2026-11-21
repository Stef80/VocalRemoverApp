package com.example.vocalremover.backend

import com.example.vocalremover.StereoPcm
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** Checks that the output of a backend is usable (finite, not silent, close to a reference). */
object OutputQuality {

    const val MIN_SNR_DB = 20.0
    const val MIN_OUTPUT_RMS = 1e-3

    /** Signal-to-error ratio (dB) pooling both channels; +infinity if identical. */
    fun stereoSnrDb(refL: FloatArray, refR: FloatArray, outL: FloatArray, outR: FloatArray): Double {
        require(refL.size == outL.size && refR.size == outR.size) { "Lengths differ" }
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

    /** Returns null if [out] passes, otherwise the reason it was rejected. */
    fun verdict(ref: StereoPcm, out: StereoPcm, minSnrDb: Double = MIN_SNR_DB): String? {
        if (!allFinite(out.left) || !allFinite(out.right)) return "output with NaN/Inf"
        val outRms = (rms(out.left) + rms(out.right)) / 2
        if (outRms < MIN_OUTPUT_RMS) return "silent output (rms=$outRms)"
        if (ref.left.size != out.left.size || ref.right.size != out.right.size) {
            return "output length differs from reference"
        }
        val snr = stereoSnrDb(ref.left, ref.right, out.left, out.right)
        if (snr < minSnrDb) {
            return "SNR against reference too low: ${String.format(Locale.ROOT, "%.1f", snr)} dB < $minSnrDb dB"
        }
        return null
    }
}
