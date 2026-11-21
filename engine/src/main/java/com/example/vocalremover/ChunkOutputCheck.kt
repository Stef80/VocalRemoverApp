package com.example.vocalremover

import kotlin.math.sqrt

/** Cheap per-chunk sanity check of a backend's output, to detect a broken accelerator mid-song. */
internal object ChunkOutputCheck {

    /** Input RMS above which the chunk is considered non-silent. */
    const val MIN_INPUT_RMS = 1e-3

    /** Output RMS below which a non-silent chunk is considered silenced by the backend. */
    const val MIN_OUTPUT_RMS = 1e-6

    /**
     * Returns null if [modelOutput] has only finite values and the audio output is plausible, else
     * the reason. Only the first `inputL.size` samples of the (reused, possibly longer) output
     * buffers are considered. CPU is the quality reference with no backend to fall back to, so its
     * output is never rejected (a quiet CPU result is legitimate).
     */
    fun problem(
        backend: ExecutionBackend,
        modelOutput: FloatArray,
        inputL: FloatArray,
        inputR: FloatArray,
        outputL: FloatArray,
        outputR: FloatArray,
    ): String? {
        if (backend == ExecutionBackend.CPU) return null
        var nonFinite = 0
        for (v in modelOutput) if (!v.isFinite()) nonFinite++
        if (nonFinite > 0) return "$nonFinite non-finite values in the model output"
        val inputRms = rms(inputL, inputR, inputL.size)
        val outputRms = rms(outputL, outputR, minOf(inputL.size, outputL.size))
        if (inputRms > MIN_INPUT_RMS && outputRms < MIN_OUTPUT_RMS) {
            return "silent output (RMS $outputRms) for a non-silent input (RMS $inputRms)"
        }
        return null
    }

    private fun rms(l: FloatArray, r: FloatArray, length: Int): Double {
        if (length == 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val a = l[i].toDouble()
            val b = r[i].toDouble()
            sum += a * a + b * b
        }
        return sqrt(sum / (2 * length))
    }
}
