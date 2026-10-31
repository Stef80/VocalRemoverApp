package com.example.vocalremover.capture

import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Empty on purpose: it does not read notifications. It exists only because the notification
 * access granted to this service allows reading media sessions (title, artist).
 */
class NowPlayingListenerService : NotificationListenerService()

object NowPlayingAccess {
    fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}
