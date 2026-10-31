package com.example.vocalremover.lyrics

import android.content.Context
import android.net.Uri
import java.io.File

/** Read access to the downloaded lyrics, for `:ui`. Blocking (I/O). */
interface TrackLyricsRepository {
    fun get(uri: Uri): TrackLyrics?

    companion object {
        fun create(context: Context): TrackLyricsRepository {
            val store = lyricsStore(context)
            return object : TrackLyricsRepository {
                override fun get(uri: Uri): TrackLyrics? = store.read(uri.toString())
            }
        }

        internal fun lyricsStore(context: Context) = TrackLyricsStore(File(context.filesDir, "lyrics"))
    }
}
