package com.example.vocalremover.capture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

/**
 * Legge/scrive i pacchetti candidati e recenti per il picker dell'app sorgente
 * da catturare. Condivisa tra la vecchia schermata XML (`MainActivity`) e il
 * widget flottante (`BubbleOverlayService`) per non duplicare l'accesso a
 * PackageManager/SharedPreferences/AndroidManifest in due posti.
 */
class CapturePackageDirectory(private val context: Context) {

    companion object {
        private const val PREFS_CAPTURE_PACKAGE_PICKER = "capture_package_picker"
        private const val KEY_RECENT_CAPTURE_PACKAGES = "recent_capture_packages"
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val MAX_RECENT = 10
    }

    /** Pacchetti usati di recente per la cattura, dal più recente al meno recente. */
    fun readRecentPackages(): List<String> {
        val prefs = context.getSharedPreferences(PREFS_CAPTURE_PACKAGE_PICKER, Context.MODE_PRIVATE)
        return prefs.getString(KEY_RECENT_CAPTURE_PACKAGES, "")
            ?.split('|')
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()
    }

    fun rememberPackage(packageName: String) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_CAPTURE_PACKAGE_PICKER, Context.MODE_PRIVATE)
        val recent = readRecentPackages().filter { it != normalized }.toMutableList()
        recent.add(0, normalized)
        prefs.edit()
            .putString(KEY_RECENT_CAPTURE_PACKAGES, recent.take(MAX_RECENT).joinToString("|"))
            .apply()
    }

    /**
     * Legge i pacchetti dichiarati in <queries><package android:name="..."/></queries>
     * direttamente dall'AndroidManifest.xml compilato: è l'unica fonte di verità,
     * non esiste una lista duplicata in Kotlin. Se il parsing fallisce, ritorna vuoto.
     */
    fun readCandidatePackagesFromManifest(): List<String> {
        var parser: android.content.res.XmlResourceParser? = null
        return try {
            parser = context.assets.openXmlResourceParser("AndroidManifest.xml")
            val packages = mutableListOf<String>()
            var insideQueries = false
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "queries" -> insideQueries = true
                        "package" -> if (insideQueries) {
                            parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.let(packages::add)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "queries") {
                        insideQueries = false
                    }
                }
                eventType = parser.next()
            }
            packages
        } catch (_: Exception) {
            emptyList()
        } finally {
            parser?.close()
        }
    }

    /** Pacchetti candidati installati e abilitati, con l'etichetta da mostrare nel picker. */
    fun candidateDisplayNames(): Map<String, String> {
        val packageManager = context.packageManager
        return readCandidatePackagesFromManifest()
            .mapNotNull { packageName ->
                val appInfo = try {
                    packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
                appInfo?.takeIf { it.enabled }?.let { packageName to it }
            }
            .associate { (packageName, appInfo) ->
                val label = packageManager.getApplicationLabel(appInfo)?.toString()?.trim()
                val displayText = when {
                    label.isNullOrBlank() -> packageName
                    packageName == label -> packageName
                    else -> "$label ($packageName)"
                }
                packageName to displayText
            }
    }

    fun resolveTargetUid(packageName: String): Int? =
        try {
            context.packageManager.getApplicationInfo(packageName, ApplicationInfo.FLAG_INSTALLED).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
}
