package com.stef.vocalremover.connections.lrclib

import com.stef.vocalremover.connections.LyricLine

/** Parses the LRC format (`[mm:ss.xx]text`) into lines sorted by start. */
internal object LrcParser {

    // Minutes capped at 5 digits: longer values would overflow toLong() or the ms math,
    // so such tags don't match and the line is dropped like any untagged line.
    private val TIME_TAG = Regex("""\[(\d{1,5}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val line = raw.trimEnd('\r')
            val starts = mutableListOf<Long>()
            // Time tags sit at the start of the line, possibly more than one (choruses).
            // Advance an index instead of slicing the line per tag: slicing is quadratic.
            var pos = 0
            while (true) {
                val match = TIME_TAG.matchAt(line, pos) ?: break
                starts += toMs(match)
                pos = match.range.last + 1
            }
            // Lines without a time tag: metadata ([ar:...]) or loose text, ignored.
            val text = line.substring(pos).trim()
            starts.forEach { lines += LyricLine(it, text) }
        }
        return lines.sortedBy { it.startMs }
    }

    private fun toMs(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }
}
