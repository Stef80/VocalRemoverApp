package com.example.vocalremover.lyrics

import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** One JSON file per saved track, named after the SHA-1 of its MediaStore Uri. */
class TrackLyricsStore(private val dir: File) {

    private companion object {
        const val TAG = LYRICS_LOG_TAG
        val json = Json { ignoreUnknownKeys = true }
    }

    fun key(savedUri: String): String =
        MessageDigest.getInstance("SHA-1").digest(savedUri.toByteArray()).joinToString("") { "%02x".format(it) }

    @Synchronized
    fun read(savedUri: String): TrackLyrics? {
        val file = fileFor(savedUri)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<TrackLyrics>(file.readText())
        } catch (e: SerializationException) {
            Log.w(TAG, "Lyrics file unreadable, ignoring it: ${file.name}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Lyrics file unreadable, ignoring it: ${file.name}")
            null
        } catch (e: IOException) {
            Log.w(TAG, "Lyrics file not readable: ${file.name}")
            null
        }
    }

    /** Writes to a temporary file and renames it: an interruption leaves no half-written file. */
    @Synchronized
    fun write(lyrics: TrackLyrics) {
        dir.mkdirs()
        val target = fileFor(lyrics.savedUri)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json.encodeToString(TrackLyrics.serializer(), lyrics))
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot save ${target.name}")
        }
    }

    @Synchronized
    fun delete(savedUri: String) {
        fileFor(savedUri).delete()
    }

    fun exists(savedUri: String): Boolean = fileFor(savedUri).exists()

    private fun fileFor(savedUri: String) = File(dir, "${key(savedUri)}.json")
}
