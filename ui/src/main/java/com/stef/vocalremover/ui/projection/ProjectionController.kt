package com.stef.vocalremover.ui.projection

import com.stef.vocalremover.ui.player.PlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * `NoDisplay` -> `Ready` -> `Projecting` state machine. Must be used from the main thread. A display that goes
 * away or is replaced while projecting pauses the player and ends the projection (no automatic resume);
 * a player error also ends it. A new track opened in [session] does not change the state.
 */
class ProjectionController(
    private val source: PresentationDisplaySource,
    private val host: ProjectionHost,
    private val session: PlayerSession,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ProjectionState>(ProjectionState.NoDisplay)
    val state: StateFlow<ProjectionState> = _state.asStateFlow()

    private val _events = Channel<ProjectionEvent>(Channel.CONFLATED)

    /**
     * Last event not yet consumed: it waits while nobody collects (app in background) and is delivered once.
     * Meant for a single collector at a time.
     */
    val events: Flow<ProjectionEvent> = _events.receiveAsFlow()

    val isProjecting: Boolean get() = _state.value is ProjectionState.Projecting

    init {
        scope.launch { source.display.collect(::onDisplayChanged) }
        scope.launch {
            session.state.map { it?.error }.distinctUntilChanged().collect { error ->
                if (error != null && isProjecting) {
                    stop()
                    _events.trySend(ProjectionEvent.PLAYBACK_ERROR)
                }
            }
        }
    }

    fun start(): ProjectionStartResult {
        if (isProjecting) return ProjectionStartResult.STARTED
        if (session.state.value == null) return ProjectionStartResult.NO_TRACK
        val display = source.display.value ?: return ProjectionStartResult.NO_DISPLAY
        host.show(display)
        _state.value = ProjectionState.Projecting(display)
        return ProjectionStartResult.STARTED
    }

    fun stop() {
        if (!isProjecting) return
        host.hide()
        _state.value = idleState(source.display.value)
    }

    private fun onDisplayChanged(display: ProjectionDisplay?) {
        val current = _state.value
        if (current is ProjectionState.Projecting && display?.id == current.display.id) {
            _state.value = ProjectionState.Projecting(display)
            return
        }
        if (current is ProjectionState.Projecting) {
            session.pause()
            host.hide()
            _events.trySend(ProjectionEvent.DISCONNECTED)
        }
        _state.value = idleState(display)
    }

    private fun idleState(display: ProjectionDisplay?): ProjectionState =
        if (display == null) ProjectionState.NoDisplay else ProjectionState.Ready(display)
}
