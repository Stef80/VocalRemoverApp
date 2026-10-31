package com.stef.vocalremover.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process shared state reflecting whether the floating widget is
 * currently recording. `BubbleOverlayService` is the only writer (it updates
 * this state on every stage transition); other screens such as
 * `ImportSongScreen` observe it read-only to reflect the recording
 * state without having to talk directly to the service or to
 * `SystemAudioCaptureService`.
 */
object CaptureOverlayStatus {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    internal fun update(recording: Boolean) {
        _isRecording.value = recording
    }
}
