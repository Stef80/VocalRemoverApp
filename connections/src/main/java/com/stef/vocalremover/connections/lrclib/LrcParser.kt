package com.stef.vocalremover.connections.lrclib

import com.stef.vocalremover.connections.LyricLine

/** Parses the LRC format (`[mm:ss.xx]text`) into lines sorted by start. */
internal object LrcParser {

    private val TIME_TAG = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            var rest = raw.trimEnd('\r')
            val starts = mutableListOf<Long>()
            // Time tags sit at the start of the line, possibly more than one (choruses).
            while (true) {
                val match = TIME_TAG.matchAt(rest, 0) ?: break
                starts += toMs(match)
                rest = rest.substring(match.range.last + 1)
            }
            // Lines without a time tag: metadata ([ar:...]) or loose text, ignored.
            starts.forEach { lines += LyricLine(it, rest.trim()) }
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
