package com.stef.vocalremover.connections.lrclib

/**
 * Strips platform additions from the title ("- 2024 Remaster", "(Official Video)",
 * "feat. ...") that make the exact LRCLIB lookup fail.
 */
internal object TitleCleaner {

    private val NOISE_WORDS = "official|video|audio|lyric|lyrics|visualizer|remaster|remastered|feat\\.|ft\\."

    private val PATTERNS = listOf(
        // " - 2024 Remaster", " - Remastered 2011", " - Remaster"
        Regex("""\s+-\s+(\d{4}\s+)?remaster(ed)?(\s+\d{4})?(\s+version)?\s*$""", RegexOption.IGNORE_CASE),
        // Round or square brackets containing a service word
        Regex("""\s*[(\[][^)\]]*\b($NOISE_WORDS)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
        // " feat. Someone" at the end, without brackets
        Regex("""\s+(feat\.|ft\.)\s+.*$""", RegexOption.IGNORE_CASE),
    )

    private const val QUOTES = "'\"‘’“”"

    /**
     * With [artist] it also strips the "Artist - " prefix that YouTube puts in the title
     * ("Band - 'Song' (Music Video)") and the quotes left around the title.
     */
    fun clean(title: String, artist: String? = null): String {
        var cleaned = PATTERNS.fold(title) { current, pattern -> pattern.replace(current, "") }.trim()
        if (!artist.isNullOrBlank()) {
            val prefix = Regex("""^\s*${Regex.escape(artist.trim())}\s+[-–—]\s+""", RegexOption.IGNORE_CASE)
            cleaned = prefix.replace(cleaned, "")
        }
        if (cleaned.length >= 2 && cleaned.first() in QUOTES && cleaned.last() in QUOTES) {
            cleaned = cleaned.substring(1, cleaned.length - 1)
        }
        return cleaned.trim()
    }
}
