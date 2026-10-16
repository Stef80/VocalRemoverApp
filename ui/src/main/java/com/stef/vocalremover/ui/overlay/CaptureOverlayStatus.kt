package com.stef.vocalremover.ui.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stato condiviso in-process che riflette se il widget flottante sta
 * attualmente registrando. `BubbleOverlayService` è l'unico scrittore (aggiorna
 * questo stato ad ogni transizione di fase); altre schermate come
 * `ImportSongScreen` lo osservano in sola lettura per riflettere lo stato della
 * registrazione senza dover parlare direttamente con il servizio o con
 * `SystemAudioCaptureService`.
 */
object CaptureOverlayStatus {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    internal fun update(recording: Boolean) {
        _isRecording.value = recording
    }
}
