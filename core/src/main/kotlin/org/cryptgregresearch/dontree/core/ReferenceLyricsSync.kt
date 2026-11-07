package org.cryptgregresearch.dontree.core

/** Matches known lyric lines to local recognition. Unmatched words remain visible and untimed. */
object ReferenceLyricsSync {
    fun align(reference: Lyrics, recognized: List<LyricLine>, beats: List<Long> = emptyList()): Lyrics {
        val text = StringBuilder()
        val starts = mutableListOf<Long>()
        val ends = mutableListOf<Long>()
        recognized.forEach { line ->
            val normalized = LyricsMatching.normalize(line.text)
            if (line.startMs >= 0 && line.endMs > line.startMs && text.length + normalized.length <= 40_000) {
                normalized.forEachIndexed { i, char ->
                    text.append(char)
                    starts += line.startMs + (line.endMs - line.startMs) * i / normalized.length
                    ends += line.startMs + (line.endMs - line.startMs) * (i + 1) / normalized.length
                }
            }
        }
        var cursor = 0
        var remainingCells = 12_000_000L
        val lines = reference.lines.map { line ->
            val query = LyricsMatching.normalize(line.text)
            val window = text.substring(cursor, minOf(text.length, cursor + 1200))
            val cost = query.length.toLong() * window.length
            val allowApproximate = cost <= remainingCells
            if (allowApproximate) remainingCells -= cost
            val match = locate(query, window, allowApproximate)
            if (match == null) line.copy(startMs = -1, endMs = -1, words = emptyList())
            else {
                val start = starts[cursor + match.first]
                val end = ends[cursor + match.last]
                cursor += match.last + 1
                line.copy(startMs = start, endMs = end, words = emptyList())
            }
        }
        val source = reference.source.substringBefore(" · local timing")
        return reference.copy(lines = lines, beats = beats.toList(), source = "$source · local timing is approximate")
    }

    private fun locate(query: String, text: String, allowApproximate: Boolean): IntRange? {
        if (query.isEmpty() || text.isEmpty() || query.length > 500) return null
        val exact = text.indexOf(query)
        if (exact >= 0) return exact until exact + query.length
        // Semi-global edit distance: allow missing/misheard characters without assuming spaces in Thai.
        if (query.length < 5 || !allowApproximate) return null
        var previous = IntArray(text.length + 1)
        var previousStart = IntArray(text.length + 1) { it }
        for (i in query.indices) {
            val current = IntArray(text.length + 1)
            val currentStart = IntArray(text.length + 1)
            current[0] = i + 1
            for (j in 1..text.length) {
                var cost = previous[j - 1] + if (query[i] == text[j - 1]) 0 else 1
                var start = previousStart[j - 1]
                if (previous[j] + 1 < cost) { cost = previous[j] + 1; start = previousStart[j] }
                if (current[j - 1] + 1 < cost) { cost = current[j - 1] + 1; start = currentStart[j - 1] }
                current[j] = cost
                currentStart[j] = start
            }
            previous = current
            previousStart = currentStart
        }
        val end = (1..text.length).minByOrNull { previous[it] } ?: return null
        val start = previousStart[end]
        return if (previous[end] <= query.length / 4 && end > start) start until end else null
    }
}
