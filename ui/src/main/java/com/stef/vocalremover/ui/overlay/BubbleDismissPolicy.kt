package com.stef.vocalremover.ui.overlay

/**
 * Regole pure (nessuna dipendenza Android) per la chiusura del bubble trascinandolo
 * sulla X in basso. `BubbleOverlayService` le usa per decidere se mostrare la X e
 * cosa fare al rilascio.
 */
object BubbleDismissPolicy {

    /**
     * Durante la registrazione (e il suo arresto) la X non compare: un trascinamento
     * sbagliato non deve far perdere il brano. Per chiudere si ferma prima la registrazione.
     */
    fun canDismiss(phase: BubbleOverlayPhase): Boolean =
        phase != BubbleOverlayPhase.RECORDING && phase != BubbleOverlayPhase.STOPPING

    /** In ascolto c'è una cattura attiva da annullare prima di chiudere il servizio. */
    fun mustCancelCapture(phase: BubbleOverlayPhase): Boolean =
        phase == BubbleOverlayPhase.LISTENING || phase == BubbleOverlayPhase.CANCELLING

    /** True se il centro del bubble è entro `radius` dal centro della X (coordinate schermo, px). */
    fun isOverTarget(
        bubbleCenterX: Float,
        bubbleCenterY: Float,
        targetCenterX: Float,
        targetCenterY: Float,
        radius: Float
    ): Boolean {
        val dx = bubbleCenterX - targetCenterX
        val dy = bubbleCenterY - targetCenterY
        return dx * dx + dy * dy <= radius * radius
    }
}
