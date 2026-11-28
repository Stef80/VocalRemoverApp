package com.stef.vocalremover.ui.projection

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * First display of the presentation category in [DisplayManager]'s order, read again on every display event.
 * The id changes at every connection, so only the current value is kept. Main thread only.
 * Process-wide; the listener is never unregistered.
 */
class DisplayManagerPresentationDisplaySource(context: Context) : PresentationDisplaySource {

    private val displayManager = context.applicationContext.getSystemService(DisplayManager::class.java)

    private val _display = MutableStateFlow(read())
    override val display: StateFlow<ProjectionDisplay?> = _display.asStateFlow()

    init {
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refresh() {
        _display.value = read()
    }

    private fun read(): ProjectionDisplay? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull()
            ?.let { ProjectionDisplay(it.displayId, it.name) }
}
