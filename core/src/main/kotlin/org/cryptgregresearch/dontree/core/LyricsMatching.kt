package org.cryptgregresearch.dontree.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class LyricsCandidate(val title: String, val artist: String, val durationMs: Long, val lyrics: Lyrics)

object LyricsMatching {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter {
            val type = Character.getType(it)
            it.isLetterOrDigit() || type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        }

    fun choose(song: Song, candidates: List<LyricsCandidate>): Lyrics? = candidates
        .filter {
            normalize(song.title).isNotEmpty() && normalize(song.artist).isNotEmpty() &&
                normalize(it.title) == normalize(song.title) && normalize(it.artist) == normalize(song.artist) &&
                it.durationMs > 0 && song.durationMs > 0 && abs(it.durationMs - song.durationMs) <= 5000 &&
                (it.lyrics.instrumental || it.lyrics.lines.isNotEmpty())
        }
        .sortedWith(compareByDescending<LyricsCandidate> { it.lyrics.fullySynced }
            .thenBy { abs(it.durationMs - song.durationMs) })
        .firstOrNull()?.lyrics
}
