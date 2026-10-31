package com.stef.vocalremover.connections

import android.content.Context
import com.stef.vocalremover.connections.lrclib.LrclibLyricsProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Module entry point: `:engine` gets its implementations from here. */
object Connections {

    private const val LRCLIB_BASE_URL = "https://lrclib.net/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** [log] receives one line per request (path and outcome), never the lyrics. */
    fun lyricsProvider(context: Context, log: (String) -> Unit = {}): LyricsProvider =
        LrclibLyricsProvider(client, LRCLIB_BASE_URL.toHttpUrl(), userAgent(context), log = log)

    /** LRCLIB asks for a name, a version and a contact (Gradle property `lrclib.contact`). */
    private fun userAgent(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"
        val contact = BuildConfig.LRCLIB_CONTACT
        return if (contact.isBlank()) "VocalRemover/$version" else "VocalRemover/$version ($contact)"
    }
}
