package org.cryptgregresearch.dontree.core

import java.util.concurrent.CancellationException

/** Online lookup supplies the words. Local inference adds timing or handles a genuine miss. */
class LyricsWorkflow(
    private val identify: suspend (Song) -> Song,
    private val search: suspend (Song) -> Lyrics?,
    private val align: suspend (Song, Lyrics) -> Lyrics,
    private val transcribe: suspend (Song) -> Lyrics,
    private val hasModel: () -> Boolean,
) {
    suspend fun resolve(song: Song, progress: (String) -> Unit): Lyrics {
        progress("Matching song…")
        val matched = identify(song)
        progress("Finding lyrics…")
        val found = search(matched)
        if (found != null) {
            if (found.instrumental || found.fullySynced || !hasModel()) return found
            progress("Syncing found lyrics on this device…")
            return try {
                align(matched, found)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                found.copy(source = "${found.source} · local timing unavailable")
            }
        }
        check(hasModel()) { "No matching lyrics found. Import an LRC file or set up a local model to transcribe this song." }
        progress("No lyrics found. Transcribing on this device…")
        return transcribe(matched)
    }
}
