package com.example.vocalremover.library

/** Durata di un brano della libreria, con ripiego sulla dimensione del file. */
object LibraryDuration {

    /** Header dei WAV scritti da `WavFileWriter`. */
    private const val WAV_HEADER_BYTES = 44L

    /** PCM 16 bit stereo a 44,1 kHz: 44 100 × 2 canali × 2 byte. */
    private const val WAV_BYTES_PER_SECOND = 176_400L

    /**
     * La durata di MediaStore se positiva; altrimenti (file appena pubblicato, non ancora analizzato)
     * la si ricava dalla dimensione. 0 = sconosciuta.
     */
    fun resolve(durationMs: Long?, sizeBytes: Long?): Long = when {
        durationMs != null && durationMs > 0 -> durationMs
        sizeBytes != null && sizeBytes > WAV_HEADER_BYTES ->
            (sizeBytes - WAV_HEADER_BYTES) * 1000 / WAV_BYTES_PER_SECOND
        else -> 0L
    }
}
