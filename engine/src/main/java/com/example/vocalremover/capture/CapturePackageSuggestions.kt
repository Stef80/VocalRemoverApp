package com.example.vocalremover.capture

/**
 * Pure logic (no Android dependencies) to sort and decode the
 * package suggestions used by the source app picker, shared
 * between the old XML screen (`MainActivity`) and the floating widget
 * (`BubbleOverlayService`), so the sorting is not duplicated in two places.
 */
object CapturePackageSuggestions {

    /**
     * Sorts the candidate packages putting the most recent first (in the order
     * they were used), then the rest alphabetically by label.
     *
     * @param recentPackages recently used package names, from most to least recent
     * @param candidates map packageName -> text to show in the picker
     *        (e.g. "YouTube (com.google.android.youtube)", or just the packageName
     *        if the label matches the package name)
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
     * Extracts the package name from the text shown in the picker. If the text
     * contains "(packageName)" it is extracted from there (label different from the
     * packageName), otherwise the text itself is already the package name.
     */
    fun packageNameFromDisplayText(displayText: String): String =
        displayText.substringAfterLast("(").trimEnd(')')
}
