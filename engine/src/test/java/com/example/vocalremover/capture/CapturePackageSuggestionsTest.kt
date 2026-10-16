package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePackageSuggestionsTest {

    @Test
    fun `recent packages come first in their recency order`() {
        val candidates = mapOf(
            "com.a" to "App A (com.a)",
            "com.b" to "App B (com.b)",
            "com.c" to "App C (com.c)"
        )
        val result = CapturePackageSuggestions.orderedDisplayTexts(
            recentPackages = listOf("com.c", "com.a"),
            candidates = candidates
        )
        assertEquals(listOf("App C (com.c)", "App A (com.a)", "App B (com.b)"), result)
    }

    @Test
    fun `non recent candidates are sorted alphabetically by label`() {
        val candidates = mapOf(
            "com.z" to "Zeta (com.z)",
            "com.a" to "Alpha (com.a)"
        )
        val result = CapturePackageSuggestions.orderedDisplayTexts(
            recentPackages = emptyList(),
            candidates = candidates
        )
        assertEquals(listOf("Alpha (com.a)", "Zeta (com.z)"), result)
    }

    @Test
    fun `recent packages not in candidates are ignored`() {
        val candidates = mapOf("com.a" to "App A (com.a)")
        val result = CapturePackageSuggestions.orderedDisplayTexts(
            recentPackages = listOf("com.uninstalled", "com.a"),
            candidates = candidates
        )
        assertEquals(listOf("App A (com.a)"), result)
    }

    @Test
    fun `packageNameFromDisplayText extracts the package name in parentheses`() {
        assertEquals("com.a", CapturePackageSuggestions.packageNameFromDisplayText("App A (com.a)"))
    }

    @Test
    fun `packageNameFromDisplayText returns the text itself when there are no parentheses`() {
        assertEquals("com.a", CapturePackageSuggestions.packageNameFromDisplayText("com.a"))
    }
}
