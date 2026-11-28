package com.stef.vocalremover.ui.projection

import kotlinx.coroutines.flow.StateFlow

/** A presentation display (Miracast, HDMI, USB-C). [id] is valid only for the current connection: never persist it. */
data class ProjectionDisplay(val id: Int, val name: String)

/** The first presentation display currently connected, `null` if there is none. */
interface PresentationDisplaySource {
    val display: StateFlow<ProjectionDisplay?>
}

/** Shows and hides the lyrics view on the presentation display. */
interface ProjectionHost {
    fun show(display: ProjectionDisplay)
    fun hide()
}

sealed interface ProjectionState {
    data object NoDisplay : ProjectionState
    data class Ready(val display: ProjectionDisplay) : ProjectionState
    data class Projecting(val display: ProjectionDisplay) : ProjectionState
}

enum class ProjectionStartResult { STARTED, NO_DISPLAY, NO_TRACK }

enum class ProjectionEvent { DISCONNECTED, PLAYBACK_ERROR }

/** Tracks cannot be deleted while projecting. */
fun deleteEnabled(state: ProjectionState): Boolean = state !is ProjectionState.Projecting
