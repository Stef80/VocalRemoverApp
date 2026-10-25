package com.stef.vocalremover.ui.library

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Permesso per leggere anche i brani della libreria che l'app non ha creato in questa
 * installazione (es. dopo una reinstallazione). Sotto Android 10 la libreria è sempre vuota,
 * quindi non serve nulla.
 */
// InlinedApi: la costante è solo una stringa e la si usa solo da API 33 (controllo su `sdkInt`).
@SuppressLint("InlinedApi")
internal fun libraryReadPermission(sdkInt: Int = Build.VERSION.SDK_INT): String? = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
    sdkInt >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}

internal enum class PermissionAction { Request, OpenSettings }

/**
 * Cosa fa il pulsante "Consenti": dopo una richiesta già fatta, se il sistema non mostrerebbe più
 * la finestra (negato definitivamente) l'unica strada sono le impostazioni dell'app.
 */
internal fun permissionAction(alreadyAsked: Boolean, showRationale: Boolean): PermissionAction =
    if (alreadyAsked && !showRationale) PermissionAction.OpenSettings else PermissionAction.Request

/** Stato del permesso della libreria per la schermata. */
internal class LibraryPermissionState(
    /** `true` se il permesso è concesso o non serve. */
    val granted: Boolean,
    /** Chiede il permesso oppure, se negato definitivamente, apre le impostazioni dell'app. */
    val requestOrOpenSettings: () -> Unit,
    /** Da chiamare al ritorno in primo piano (es. dalle impostazioni). */
    val recheck: () -> Unit,
)

/**
 * Chiede il permesso da sola la prima volta che la schermata compare; `onResult` viene chiamata
 * dopo ogni risposta, così la lista si può rileggere.
 */
@Composable
internal fun rememberLibraryPermissionState(onResult: () -> Unit): LibraryPermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = remember { libraryReadPermission() }
    var granted by remember { mutableStateOf(isGranted(context, permission)) }
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        onResult()
    }
    LaunchedEffect(permission) {
        if (permission != null && !granted && !alreadyAsked) {
            alreadyAsked = true
            launcher.launch(permission)
        }
    }
    return LibraryPermissionState(
        granted = granted,
        requestOrOpenSettings = {
            if (permission != null) {
                val showRationale = activity?.shouldShowRequestPermissionRationale(permission) ?: true
                when (permissionAction(alreadyAsked, showRationale)) {
                    PermissionAction.Request -> {
                        alreadyAsked = true
                        launcher.launch(permission)
                    }
                    PermissionAction.OpenSettings -> openAppSettings(context, activity)
                }
            }
        },
        recheck = { granted = isGranted(context, permission) },
    )
}

private fun isGranted(context: Context, permission: String?): Boolean =
    permission == null ||
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context, activity: Activity?) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
