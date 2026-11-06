package org.cryptgregresearch.dontree.core

import java.util.Locale

data class Song(
    val id: Long, val uri: String, val title: String, val artist: String,
    val album: String, val durationMs: Long, val albumId: Long,
    val filename: String, val modified: Long, val size: Long,
) {
    val revision: String get() = "$id:$modified:$size"
}

object MusicFiles {
    fun supported(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mp3", "ogg")
    fun title(tag: String?, filename: String): String = tag?.trim()
        ?.takeUnless { it.isBlank() || it == "<unknown>" }
        ?: filename.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Untitled track" }
    fun artist(tag: String?): String = tag?.trim()?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist"
}

data class Word(val startMs: Long, val endMs: Long, val text: String)
data class LyricLine(
    val startMs: Long, val endMs: Long, val text: String,
    val romanization: String = "", val translation: String = "", val words: List<Word> = emptyList(),
)
data class Lyrics(val lines: List<LyricLine>, val beats: List<Long> = emptyList(), val source: String = "Imported LRC")

object Timeline {
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0; var high = lines.lastIndex; var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].startMs <= positionMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found.takeIf { it >= 0 && positionMs < lines[it].endMs } ?: -1
    }

    // Do not drag vocals across a rest just to meet a beat.
    fun snap(timeMs: Long, beats: List<Long>, toleranceMs: Long = 90): Long {
        require(toleranceMs >= 0)
        val insertion = beats.binarySearch(timeMs)
        if (insertion >= 0) return timeMs
        val i = -insertion - 1
        val nearest = listOfNotNull(beats.getOrNull(i - 1), beats.getOrNull(i)).minByOrNull { kotlin.math.abs(it - timeMs) }
        return if (nearest != null && kotlin.math.abs(nearest - timeMs) <= toleranceMs) nearest else timeMs
    }
}
