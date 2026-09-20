package com.example.vocalremover

internal fun canShowCaptureDropdown(
    isAttachedToWindow: Boolean,
    hasWindowToken: Boolean,
    isActivityFinishing: Boolean,
    isActivityDestroyed: Boolean
): Boolean {
    return isAttachedToWindow &&
        hasWindowToken &&
        !isActivityFinishing &&
        !isActivityDestroyed
}
