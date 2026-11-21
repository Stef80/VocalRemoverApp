package com.example.vocalremover.backend

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Tells how the previous process of this app ended. */
fun interface ProcessExitInfo {
    /** True if it crashed, false if it ended for another reason, null if unknown (API < 30). */
    fun lastExitWasCrash(): Boolean?
}

class AndroidProcessExitInfo(private val context: Context) : ProcessExitInfo {

    override fun lastExitWasCrash(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val reason = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()?.reason
        return reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_CRASH
    }
}
