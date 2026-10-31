package com.example.vocalremover.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingFileNaming {

    private const val FALLBACK_NAME = "Registrazione"
    private const val INSTRUMENTAL_SUFFIX = "_strumentale"
    private const val WAV_EXTENSION = ".wav"
    private val invalidCharacters = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return FALLBACK_NAME

        val hasUsableCharacter = trimmed.any { !Character.isISOControl(it) && it !in invalidCharacters }
        if (!hasUsableCharacter) return FALLBACK_NAME

        return buildString(trimmed.length) {
            for (character in trimmed) {
                append(
                    if (Character.isISOControl(character) || character in invalidCharacters) {
                        '_'
                    } else {
                        character
                    }
                )
            }
        }
    }

    fun defaultName(timestampMs: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
        return "${FALLBACK_NAME}_${formatter.format(Date(timestampMs))}"
    }

    /** Proposed name for a recognized capture: "Artist - Title" (files sanitize it later). */
    fun nameFromTrack(info: CapturedTrackInfo): String = "${info.artist.trim()} - ${info.title.trim()}"

    fun rawCaptureName(displayName: String): String = "${sanitize(displayName)}_capture"

    fun trimmedName(displayName: String): String = "${sanitize(displayName)}_tagliato"

    /** For a title typed by the user: no extension to strip (e.g. "Mr. Brightside"). */
    fun instrumentalNameForTitle(title: String): String = "${sanitize(title)}$INSTRUMENTAL_SUFFIX"

    /**
     * Title to show for a library WAV: strips `.wav` (any case) and a single trailing
     * `_strumentale`. Never empty: if nothing is left, the name without extension is kept.
     */
    fun titleFromLibraryName(displayName: String): String {
        val withoutExtension = if (displayName.endsWith(WAV_EXTENSION, ignoreCase = true)) {
            displayName.dropLast(WAV_EXTENSION.length)
        } else {
            displayName
        }
        return withoutExtension.removeSuffix(INSTRUMENTAL_SUFFIX).ifEmpty { withoutExtension }
    }

    fun instrumentalName(sourceDisplayName: String?): String {
        val baseName = sourceDisplayName.orEmpty().trim().let { name ->
            val dot = name.lastIndexOf('.')
            if (dot >= 0) name.substring(0, dot) else name
        }
        return "${sanitize(baseName)}$INSTRUMENTAL_SUFFIX"
    }
}
