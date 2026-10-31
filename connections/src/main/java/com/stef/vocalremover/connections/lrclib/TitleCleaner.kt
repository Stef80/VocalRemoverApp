package com.stef.vocalremover.connections.lrclib

/**
 * Strips platform additions from the title ("- 2024 Remaster", "(Official Video)",
 * "feat. ...") that make the exact LRCLIB lookup fail.
 */
internal object TitleCleaner {

    private val NOISE_WORDS = Regex(
        """\b(official|video|audio|lyric|lyrics|visualizer|remaster|remastered|feat\.|ft\.)""",
        RegexOption.IGNORE_CASE,
    )

    // The title comes from any app's media session, so it is untrusted: every pattern must
    // stay linear. `(?<!\s)\s++` tries each whitespace run once, from its start, without
    // backtracking into it; otherwise long space runs cost quadratic time (ReDoS).

    // " - 2024 Remaster", " - Remastered 2011", " - Remaster"
    private val REMASTER_SUFFIX = Regex(
        """(?<!\s)\s++-\s++(\d{4}\s++)?remaster(ed)?(\s++\d{4})?(\s++version)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // One bracket group without nested brackets: the content run can't backtrack into a
    // closing bracket, so each group is scanned once. Noise words are checked afterwards.
    private val BRACKET_GROUP = Regex("""(?<!\s)\s*+[(\[]([^()\[\]]*+)[)\]]""")

    // " feat. Someone" at the end, without brackets
    private val FEAT_SUFFIX = Regex("""(?<!\s)\s++(feat\.|ft\.)\s+.*$""", RegexOption.IGNORE_CASE)

    private const val QUOTES = "'\"‘’“”"

    /**
     * With [artist] it also strips the "Artist - " prefix that YouTube puts in the title
     * ("Band - 'Song' (Music Video)") and the quotes left around the title.
     */
    fun clean(title: String, artist: String? = null): String {
        var cleaned = REMASTER_SUFFIX.replace(title, "")
        cleaned = BRACKET_GROUP.replace(cleaned) { group ->
            if (NOISE_WORDS.containsMatchIn(group.groupValues[1])) "" else group.value
        }
        cleaned = FEAT_SUFFIX.replace(cleaned, "").trim()
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
