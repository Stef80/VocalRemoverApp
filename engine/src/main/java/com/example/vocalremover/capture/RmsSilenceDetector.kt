package com.example.vocalremover.capture

/**
 * Detects whether a buffer of float PCM samples is "silence" (RMS below
 * threshold). Used by the system audio capture spike to tell apart
 * a source that blocks capture (AudioPlaybackCaptureConfiguration
 * returns zeroed PCM without raising a system error) from a
 * source that is actually sending audio.
 */
object RmsSilenceDetector {

    /** RMS threshold below which a buffer is considered silence. */
    const val DEFAULT_THRESHOLD = 0.001f

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) sumSquares += (s * s).toDouble()
        return Math.sqrt(sumSquares / samples.size).toFloat()
    }

    fun isSilent(samples: FloatArray, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        return rms(samples) < threshold
    }
}
