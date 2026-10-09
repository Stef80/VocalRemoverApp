package com.example.vocalremover.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingFileNaming {

    private const val FALLBACK_NAME = "Registrazione"
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

    fun rawCaptureName(displayName: String): String = "${sanitize(displayName)}_capture"

    fun instrumentalName(sourceDisplayName: String?): String {
        val baseName = sourceDisplayName.orEmpty().trim().let { name ->
            val dot = name.lastIndexOf('.')
            if (dot >= 0) name.substring(0, dot) else name
        }
        return "${sanitize(baseName)}_strumentale"
    }
}
