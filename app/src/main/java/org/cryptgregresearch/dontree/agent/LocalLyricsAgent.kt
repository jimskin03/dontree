package org.cryptgregresearch.dontree.agent

import android.icu.text.Transliterator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.cryptgregresearch.dontree.core.*
import org.cryptgregresearch.dontree.data.*
import org.json.JSONArray

data class PlaybackSession(val song: Song, val translate: Boolean)
interface PlayerAgent {
    suspend fun onAttach(session: PlaybackSession, onProgress: (String) -> Unit): Lyrics
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

    override suspend fun onAttach(session: PlaybackSession, onProgress: (String) -> Unit): Lyrics = work.withLock {
        withContext(Dispatchers.Default) {
            check(models.ready) { "Import a multilingual Whisper model in Settings before running the agent. You can also import an LRC file." }
            require(session.song.durationMs <= 20 * 60_000) { "Local analysis currently supports tracks up to 20 minutes" }
            onProgress("Checking saved lyrics…")
            val hash = repository.hash(session.song.uri)
            val key = "$hash:whisper-1.7.6:thai-v1:${models.revision}:${session.translate}"
            repository.dao.lyrics(key)?.let { return@withContext LyricsJson.decode(it.json) }
            ensureActive()
            val localHandle = NativeAudio.open(models.file.absolutePath)
            synchronized(handleLock) { handle = localHandle }
            try {
                ensureActive()
                val language = ThaiLanguagePack()
                val lines = mutableListOf<LyricLine>()
                val beats = mutableListOf<Long>()
                decoder.chunks(session.song.uri).collect { chunk ->
                    ensureActive()
                    val end = chunk.startMs + chunk.samples.size * 1000L / 16000
                    onProgress("Listening locally · ${(chunk.startMs * 100 / session.song.durationMs.coerceAtLeast(1)).coerceAtMost(99)}%")
                    // Suppress silence before ASR to reduce hallucinations in instrumental gaps.
                    val rms = kotlin.math.sqrt(chunk.samples.sumOf { (it * it).toDouble() } / chunk.samples.size)
                    val detected = BeatTracker.detect(chunk.samples, offsetMs = chunk.startMs)
                    beats += detected.filter { beats.isEmpty() || it - beats.last() >= 250 }
                    if (rms < 0.005) return@collect
                    val thai = parse(NativeAudio.transcribe(localHandle, chunk.samples, false), chunk.startMs, end)
                    ensureActive()
                    val english = if (session.translate) {
                        onProgress("Translating locally · ${(chunk.startMs * 100 / session.song.durationMs.coerceAtLeast(1)).coerceAtMost(99)}%")
                        parse(NativeAudio.transcribe(localHandle, chunk.samples, true), chunk.startMs, end)
                    } else emptyList()
                    thai.forEach { line ->
                        val translated = english.filter { it.startMs < line.endMs && it.endMs > line.startMs }.joinToString(" ") { it.text }
                        val start = Timeline.snap(line.startMs, detected).coerceIn(chunk.startMs, line.endMs - 1)
                        lines += line.copy(startMs = maxOf(start, lines.lastOrNull()?.endMs ?: 0),
                            romanization = language.romanize(line.text), translation = translated)
                    }
                }
                ensureActive()
                val result = Lyrics(lines.filter { it.endMs > it.startMs }, beats, "Generated on device · Whisper Thai · approximate timing")
                check(result.lines.isNotEmpty()) { "No vocals were recognized. Try a larger multilingual model or import lyrics." }
                repository.dao.saveLyrics(LyricsRow(key, LyricsJson.encode(result)))
                result
            } finally {
                synchronized(handleLock) { handle = 0; NativeAudio.close(localHandle) }
            }
        }
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
