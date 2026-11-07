package org.cryptgregresearch.dontree.agent

import android.icu.text.Transliterator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cryptgregresearch.dontree.core.*
import org.cryptgregresearch.dontree.data.*
import org.json.JSONArray

data class PlaybackSession(val song: Song, val translate: Boolean, val timingOnly: Boolean = false)
data class AgentUpdate(val progress: String, val fraction: Float, val lyrics: Lyrics? = null)
interface PlayerAgent {
    suspend fun onAttach(session: PlaybackSession, onProgress: (AgentUpdate) -> Unit): Lyrics
    fun onDetach()
}

interface LanguagePack {
    val code: String
    fun romanize(text: String): String
}
class ThaiLanguagePack : LanguagePack {
    override val code = "th"
    // ICU uses a phonetic Thai transliteration; it is not certified RTGS.
    private val transliterator = Transliterator.getInstance("Thai-Latin; Latin-ASCII")
    override fun romanize(text: String): String = transliterator.transliterate(text)
}

class LocalLyricsAgent(
    private val repository: MusicRepository,
    private val models: ModelStore,
    private val decoder: AudioDecoder,
) : PlayerAgent {
    private val work = Mutex()
    private val handleLock = Any()
    private var handle = 0L

    override fun onDetach() { synchronized(handleLock) { if (handle != 0L) NativeAudio.cancel(handle) } }

    suspend fun align(session: PlaybackSession, reference: Lyrics, onProgress: (AgentUpdate) -> Unit): Lyrics {
        var previous: List<LyricLine> = emptyList()
        var aligned = reference
        val recognized = onAttach(session.copy(translate = false, timingOnly = true)) { update ->
            val lines = update.lyrics?.lines.orEmpty()
            if (lines != previous) {
                aligned = ReferenceLyricsSync.align(reference, lines, update.lyrics?.beats.orEmpty())
                previous = lines
            }
            onProgress(update.copy(progress = update.progress.replace("Listening locally", "Syncing found lyrics"), lyrics = aligned))
        }
        return ReferenceLyricsSync.align(reference, recognized.lines, recognized.beats)
    }

    override suspend fun onAttach(session: PlaybackSession, onProgress: (AgentUpdate) -> Unit): Lyrics = work.withLock {
        withContext(Dispatchers.Default) {
            check(models.ready) { "Import a multilingual Whisper model in Settings before running the agent. You can also import an LRC file." }
            require(session.song.durationMs <= 20 * 60_000) { "Local analysis currently supports tracks up to 20 minutes" }
            onProgress(AgentUpdate("Checking saved lyrics…", 0f))
            val hash = repository.hash(session.song.uri)
            val key = "$hash:whisper-1.7.6:thai-v1:${models.revision}:${session.translate}"
            val songKey = "generated:${session.song.revision}"
            repository.dao.lyrics(key)?.let { return@withContext LyricsJson.decode(it.json) }
            ensureActive()
            val localHandle = NativeAudio.open(models.file.absolutePath)
            synchronized(handleLock) { handle = localHandle }
            try {
                ensureActive()
                val language = ThaiLanguagePack()
                val lines = mutableListOf<LyricLine>()
                val beats = mutableListOf<Long>()
                var shownMs = 0L
                fun snapshot(done: Boolean) = Lyrics(
                    lines.filter { it.endMs > it.startMs }, beats,
                    if (done) "Generated on device · Whisper Thai · approximate timing" else "Generated on device · partial",
                )
                fun report(doneMs: Long, phase: String) {
                    shownMs = maxOf(shownMs, doneMs)
                    val partial = snapshot(false).takeIf { it.lines.isNotEmpty() }
                    onProgress(AgentUpdate(AnalysisProgress.label(phase, shownMs, session.song.durationMs), AnalysisProgress.fraction(shownMs, session.song.durationMs), partial))
                }
                suspend fun persistPartial() {
                    val partial = snapshot(false)
                    if (!session.timingOnly && partial.lines.isNotEmpty()) repository.dao.saveLyrics(LyricsRow(songKey, LyricsJson.encode(partial)))
                }
                decoder.chunks(session.song.uri).collect { chunk ->
                    for (slice in slices(chunk)) {
                        ensureActive()
                        val sliceMs = slice.samples.size * 1000L / 16000
                        val sliceEnd = slice.startMs + sliceMs
                        // The percent is the end of the section now being analyzed. Whisper stays at 0
                        // until that window finishes, which used to leave the whole song reading 0%.
                        report(sliceEnd, "Listening locally")
                        val rms = if (slice.samples.isEmpty()) 0.0 else kotlin.math.sqrt(slice.samples.sumOf { (it * it).toDouble() } / slice.samples.size)
                        val detected = BeatTracker.detect(slice.samples, offsetMs = slice.startMs)
                        beats += detected.filter { beats.isEmpty() || it - beats.last() >= 250 }
                        var phase = "Listening locally"
                        if (rms >= 0.005 && slice.samples.size >= 1600) {
                            val thai = parse(transcribe(localHandle, slice.samples, false) { percent ->
                                report(slice.startMs + sliceMs * percent / 100, "Listening locally")
                            }, slice.startMs, sliceEnd)
                            ensureActive()
                            val from = lines.size
                            thai.forEach { line ->
                                val start = Timeline.snap(line.startMs, detected).coerceIn(slice.startMs, line.endMs - 1)
                                lines += line.copy(startMs = maxOf(start, lines.lastOrNull()?.endMs ?: 0), romanization = language.romanize(line.text))
                            }
                            persistPartial()
                            report(sliceEnd, phase)
                            if (session.translate && thai.isNotEmpty()) {
                                phase = "Translating locally"
                                val english = parse(transcribe(localHandle, slice.samples, true) { percent ->
                                    report(slice.startMs + sliceMs * percent / 100, phase)
                                }, slice.startMs, sliceEnd)
                                thai.forEachIndexed { index, line ->
                                    val translated = english.filter { it.startMs < line.endMs && it.endMs > line.startMs }.joinToString(" ") { it.text }
                                    if (translated.isNotEmpty()) lines[from + index] = lines[from + index].copy(translation = translated)
                                }
                                persistPartial()
                            }
                        }
                        report(sliceEnd, phase)
                    }
                }
                ensureActive()
                val result = snapshot(true)
                check(result.lines.isNotEmpty()) { "No vocals were recognized. Try a larger multilingual model or import lyrics." }
                val encoded = LyricsJson.encode(result)
                repository.dao.saveLyrics(LyricsRow(key, encoded))
                if (!session.timingOnly) repository.dao.saveLyrics(LyricsRow(songKey, encoded))
                result
            } finally {
                synchronized(handleLock) { handle = 0; NativeAudio.close(localHandle) }
            }
        }
    }

    /**
     * whisper_full blocks the caller and only then advances its progress.
     * Poll from this coroutine so detach still aborts the native pass, and wait for that pass
     * to return before the handle is closed.
     */
    private suspend fun transcribe(handle: Long, pcm: FloatArray, translate: Boolean, onPercent: (Int) -> Unit): String = supervisorScope {
        val task = async(Dispatchers.Default) { NativeAudio.transcribe(handle, pcm, translate) }
        try {
            var armed = false
            while (task.isActive) {
                val value = NativeAudio.progress(handle).coerceIn(0, 100)
                if (!armed) armed = value == 0 else onPercent(value)
                delay(250)
            }
            onPercent(100)
            task.await()
        } catch (e: CancellationException) {
            val error = runCatching { task.await() }.exceptionOrNull()
            if (error != null && error !is CancellationException && error.message != "Analysis cancelled") throw error
            throw e
        }
    }

    private fun slices(chunk: PcmChunk): List<PcmChunk> {
        val sliceSamples = 16_000 * 15
        if (chunk.samples.size <= sliceSamples) return listOf(chunk)
        val result = ArrayList<PcmChunk>((chunk.samples.size + sliceSamples - 1) / sliceSamples)
        var offset = 0
        while (offset < chunk.samples.size) {
            val count = minOf(sliceSamples, chunk.samples.size - offset)
            result += PcmChunk(chunk.startMs + offset * 1000L / 16_000, chunk.samples.copyOfRange(offset, offset + count))
            offset += count
        }
        return result
    }

    private fun parse(json: String, offset: Long, end: Long): List<LyricLine> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val text = item.getString("text").trim()
            val start = (offset + item.getLong("start")).coerceIn(offset, end)
            val stop = (offset + item.getLong("end")).coerceIn(start, end)
            if (text.isBlank() || stop <= start) return@mapNotNull null
            val tokens = item.getJSONArray("words")
            LyricLine(start, stop, text, words = (0 until tokens.length()).mapNotNull { j ->
                val token = tokens.getJSONObject(j)
                val from = (offset + token.getLong("start")).coerceIn(start, stop)
                val to = (offset + token.getLong("end")).coerceIn(from, stop)
                if (to > from) Word(from, to, token.getString("text")) else null
            })
        }
    }
}
