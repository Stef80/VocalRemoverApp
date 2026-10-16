package com.example.vocalremover.capture

/**
 * Logica pura (nessuna dipendenza Android) per ordinare e decodificare i
 * suggerimenti di pacchetto usati dal picker delle app sorgente, condivisa
 * tra la vecchia schermata XML (`MainActivity`) e il widget flottante
 * (`BubbleOverlayService`), per evitare di duplicare l'ordinamento in due posti.
 */
object CapturePackageSuggestions {

    /**
     * Ordina i pacchetti candidati mettendo prima i più recenti (nell'ordine
     * in cui sono stati usati), poi il resto in ordine alfabetico per etichetta.
     *
     * @param recentPackages nomi di pacchetto usati di recente, dal più recente al meno recente
     * @param candidates mappa packageName -> testo da mostrare nel picker
     *        (es. "YouTube (com.google.android.youtube)", o solo il packageName
     *        se l'etichetta coincide col nome del pacchetto)
     */
    fun orderedDisplayTexts(
        recentPackages: List<String>,
        candidates: Map<String, String>
    ): List<String> =
        recentPackages
            .filter { it in candidates }
            .plus(
                candidates.keys
                    .filter { it !in recentPackages }
                    .sortedBy { candidates[it]?.lowercase() ?: it.lowercase() }
            )
            .distinct()
            .mapNotNull { candidates[it] }

    /**
     * Estrae il nome del pacchetto dal testo mostrato nel picker. Se il testo
     * contiene "(packageName)" lo estrae da lì (etichetta diversa dal
     * packageName), altrimenti il testo stesso è già il nome del pacchetto.
     */
    fun packageNameFromDisplayText(displayText: String): String =
        displayText.substringAfterLast("(").trimEnd(')')
}
