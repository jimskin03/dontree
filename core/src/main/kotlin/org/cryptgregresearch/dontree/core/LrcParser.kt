package org.cryptgregresearch.dontree.core

object LrcParser {
    private val stamp = Regex("\\[(\\d{1,4}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    fun parse(input: String, durationMs: Long): Lyrics {
        require(input.length <= 2_000_000) { "Lyrics file is too large" }
        val shift = offset.find(input)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-3_600_000, 3_600_000) ?: 0
        val entries = input.lineSequence().flatMap { raw ->
            val matches = stamp.findAll(raw).toList()
            val text = stamp.replace(raw, "").trim()
            matches.asSequence().mapNotNull { m ->
                val millis = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                val time = (m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + millis + shift).coerceAtLeast(0)
                if (text.isEmpty() || (durationMs > 0 && time >= durationMs)) null else time to text
            }
        }.toList().groupBy({ it.first }, { it.second }).toSortedMap().entries.toList()
        require(entries.isNotEmpty()) { "No timestamped lyrics found. Import an LRC file with [mm:ss.xx] lines." }
        return Lyrics(entries.mapIndexed { i, entry ->
            LyricLine(entry.key, entries.getOrNull(i + 1)?.key ?: if (durationMs > 0) durationMs else entry.key + 5000, entry.value.distinct().joinToString("\n"))
        })
    }
}
