package com.example.vocalremover.library

/** Duration of a library track, falling back to the file size. */
object LibraryDuration {

    /** Header of the WAVs written by `WavFileWriter`. */
    private const val WAV_HEADER_BYTES = 44L

    /** 16-bit stereo PCM at 44.1 kHz: 44 100 × 2 channels × 2 bytes. */
    private const val WAV_BYTES_PER_SECOND = 176_400L

    /**
     * The MediaStore duration if positive; otherwise (file just published, not yet scanned)
     * it is derived from the size. 0 = unknown.
     */
    fun resolve(durationMs: Long?, sizeBytes: Long?): Long = when {
        durationMs != null && durationMs > 0 -> durationMs
        sizeBytes != null && sizeBytes > WAV_HEADER_BYTES ->
            (sizeBytes - WAV_HEADER_BYTES) * 1000 / WAV_BYTES_PER_SECOND
        else -> 0L
    }
}
