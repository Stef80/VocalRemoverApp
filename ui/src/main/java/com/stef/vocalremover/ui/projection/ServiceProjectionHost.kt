package com.stef.vocalremover.ui.projection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** [ProjectionHost] that runs the presentation in [TvProjectionService]. */
class ServiceProjectionHost(private val context: Context) : ProjectionHost {

    override fun show(display: ProjectionDisplay) {
        ContextCompat.startForegroundService(context, TvProjectionService.showIntent(context, display.id))
    }

    /**
     * A hide command, not `stopService`: it arrives in order after the show command (and never cancels a newer one), whereas stopping the service
     * between `startForegroundService` and `startForeground` crashes the app. If the service is already dead and
     * the app is in the background the start is refused ([IllegalStateException]): then `stopService` is harmless.
     */
    override fun hide() {
        try {
            context.startService(TvProjectionService.hideIntent(context))
        } catch (e: IllegalStateException) {
            context.stopService(Intent(context, TvProjectionService::class.java))
        }
    }
}
