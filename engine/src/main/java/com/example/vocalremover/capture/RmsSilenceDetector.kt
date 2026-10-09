package com.example.vocalremover.capture

/**
 * Rileva se un buffer di campioni PCM float è "silenzio" (RMS sotto
 * soglia). Usato dallo spike di cattura audio di sistema per distinguere
 * una sorgente che blocca la cattura (AudioPlaybackCaptureConfiguration
 * restituisce PCM a zero senza generare un errore di sistema) da una
 * sorgente che sta effettivamente inviando audio.
 */
object RmsSilenceDetector {

    /** Soglia RMS sotto la quale un buffer è considerato silenzio. */
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
