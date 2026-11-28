package com.stef.vocalremover.ui.projection

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.stef.vocalremover.ui.overlay.OverlayLifecycleOwner

/**
 * Compose [content] on a presentation display. Built with a Service context and the default window type:
 * never `setType` (an overlay type fails with "Window type mismatch") and never `FLAG_SECURE`.
 * One instance is shown once: after `onStop` its lifecycle is DESTROYED, so create a new one to show again.
 */
class TvPresentation(
    context: Context,
    display: Display,
    private val content: @Composable () -> Unit,
) : Presentation(context, display) {

    private val owner = OverlayLifecycleOwner()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner.attach()
        setContentView(
            ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent(content)
            }
        )
    }

    override fun onStop() {
        super.onStop()
        owner.detach()
    }
}
