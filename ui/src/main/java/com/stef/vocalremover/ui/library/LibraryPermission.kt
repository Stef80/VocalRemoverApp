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
 * Permission to also read the library tracks the app did not create in this
 * install (e.g. after a reinstall). Below Android 10 the library is always empty,
 * so nothing is needed.
 */
// InlinedApi: the constant is just a string and is used only from API 33 (check on `sdkInt`).
@SuppressLint("InlinedApi")
internal fun libraryReadPermission(sdkInt: Int = Build.VERSION.SDK_INT): String? = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
    sdkInt >= Build.VERSION_CODES.Q -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}

internal enum class PermissionAction { Request, OpenSettings }

/**
 * What the "Consenti" button does: after a request already made, if the system would no longer show
 * the dialog (permanently denied) the only way is the app settings.
 */
internal fun permissionAction(alreadyAsked: Boolean, showRationale: Boolean): PermissionAction =
    if (alreadyAsked && !showRationale) PermissionAction.OpenSettings else PermissionAction.Request

/** Library permission state for the screen. */
internal class LibraryPermissionState(
    /** `true` if the permission is granted or not needed. */
    val granted: Boolean,
    /** Requests the permission or, if permanently denied, opens the app settings. */
    val requestOrOpenSettings: () -> Unit,
    /** To be called when coming back to the foreground (e.g. from settings). */
    val recheck: () -> Unit,
)

/**
 * Requests the permission on its own the first time the screen appears; `onResult` is called
 * after every answer, so the list can be read again.
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
