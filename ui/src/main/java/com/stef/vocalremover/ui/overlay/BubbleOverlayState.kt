package com.stef.vocalremover.ui.overlay

/**
 * Stato puro (nessuna dipendenza Android) del widget flottante di registrazione.
 * Le transizioni sono funzioni pure: non effettuano IO, non toccano WindowManager
 * né i servizi di cattura. `BubbleOverlayService` le usa per decidere cosa fare
 * e `BubbleOverlayView` per decidere cosa mostrare.
 */
enum class BubbleOverlayPhase {
    IDLE,
    APP_SELECTED,
    AWAITING_CONSENT,
    RECORDING,
    STOPPING,
    ERROR
}

data class BubbleOverlayState(
    val phase: BubbleOverlayPhase = BubbleOverlayPhase.IDLE,
    val packageName: String? = null,
    val targetUid: Int? = null,
    val errorMessage: String? = null
)

/**
 * Transizioni valide tra stati del bubble. Ogni funzione ritorna il nuovo stato,
 * oppure null se la transizione non è valida nello stato corrente (il chiamante
 * ignora l'evento invece di propagare un'eccezione: eventi fuori ordine possono
 * arrivare da broadcast asincroni del servizio di cattura).
 */
object BubbleOverlayTransitions {

    fun selectApp(
        current: BubbleOverlayState,
        packageName: String,
        targetUid: Int
    ): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.IDLE || current.phase == BubbleOverlayPhase.ERROR) {
            BubbleOverlayState(BubbleOverlayPhase.APP_SELECTED, packageName, targetUid)
        } else {
            null
        }

    fun requestConsent(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.APP_SELECTED) {
            current.copy(phase = BubbleOverlayPhase.AWAITING_CONSENT)
        } else {
            null
        }

    fun startRecording(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.AWAITING_CONSENT) {
            current.copy(phase = BubbleOverlayPhase.RECORDING)
        } else {
            null
        }

    fun requestStop(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.RECORDING) {
            current.copy(phase = BubbleOverlayPhase.STOPPING)
        } else {
            null
        }

    fun stopped(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.STOPPING) {
            BubbleOverlayState(BubbleOverlayPhase.IDLE)
        } else {
            null
        }

    fun failed(current: BubbleOverlayState, message: String): BubbleOverlayState =
        current.copy(phase = BubbleOverlayPhase.ERROR, errorMessage = message)

    fun dismissError(current: BubbleOverlayState): BubbleOverlayState? =
        if (current.phase == BubbleOverlayPhase.ERROR) {
            BubbleOverlayState(BubbleOverlayPhase.IDLE)
        } else {
            null
        }
}
