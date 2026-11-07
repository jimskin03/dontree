package org.cryptgregresearch.dontree.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class LyricsWorkflowTest {
    private val song = Song(1, "content://song", "เพลง", "ศิลปิน", "Album", 100_000, 1, "song.mp3", 1, 10)
    private val plain = Lyrics(listOf(LyricLine(-1, -1, "ต้นฉบับ")), source = "Provider")
    private val timed = plain.copy(lines = listOf(plain.lines.single().copy(startMs = 100, endMs = 500)))

    @Test fun `recognition feeds search and timed lyrics bypass inference without a model`() = runBlocking {
        val calls = mutableListOf<String>()
        val result = LyricsWorkflow(
            identify = { calls += "identify"; it.copy(title = "Matched") },
            search = { calls += "search"; assertEquals("Matched", it.title); timed },
            align = { _, _ -> error("Should not align timed lyrics") },
            transcribe = { error("Should not transcribe found lyrics") },
            hasModel = { false },
        ).resolve(song) {}
        assertEquals(timed, result)
        assertEquals(listOf("identify", "search"), calls)
    }

    @Test fun `plain lyrics are retained without a model`() = runBlocking {
        assertEquals(plain, workflow(found = plain, model = false).resolve(song) {})
    }

    @Test fun `plain lyrics use local model for timing only`() = runBlocking {
        var aligned = false
        val result = LyricsWorkflow({ it }, { plain }, { _, reference ->
            aligned = true
            assertEquals(plain, reference)
            timed
        }, { error("Fallback replaced provider words") }, { true }).resolve(song) {}
        assertTrue(aligned)
        assertEquals(timed, result)
    }

    @Test fun `genuine miss triggers fallback exactly once`() = runBlocking {
        var count = 0
        val result = LyricsWorkflow({ it }, { null }, { _, _ -> error("No reference") }, {
            count++
            timed
        }, { true }).resolve(song) {}
        assertEquals(timed, result)
        assertEquals(1, count)
    }

    @Test fun `instrumental match does not generate imaginary lyrics`() = runBlocking {
        val instrumental = Lyrics(emptyList(), source = "Provider", instrumental = true)
        assertEquals(instrumental, workflow(instrumental, true).resolve(song) {})
    }

    @Test fun `failed timing preserves all provider words`() = runBlocking {
        val result = workflow(plain, true).resolve(song) {}
        assertEquals(plain.lines, result.lines)
        assertTrue(result.source.contains("timing unavailable"))
    }

    @Test(expected = CancellationException::class)
    fun `cancelled alignment propagates cancellation`() { runBlocking {
        LyricsWorkflow({ it }, { plain }, { _, _ -> throw CancellationException() }, { error("Fallback") }, { true }).resolve(song) {}
    } }

    @Test fun `service failure does not silently start fallback`() = runBlocking {
        var fallbackCalled = false
        val failure = runCatching {
            LyricsWorkflow({ it }, { error("HTTP 401") }, { _, _ -> error("Align") }, {
                fallbackCalled = true
                timed
            }, { true }).resolve(song) {}
        }.exceptionOrNull()
        assertEquals("HTTP 401", failure?.message)
        assertFalse(fallbackCalled)
    }

    @Test fun `matching rejects another recording and prefers synchronized lyrics`() {
        val candidates = listOf(
            LyricsCandidate(song.title, "Other artist", song.durationMs, timed),
            LyricsCandidate(song.title, song.artist, song.durationMs + 30_000, timed),
            LyricsCandidate(song.title, song.artist, song.durationMs, plain),
            LyricsCandidate(song.title, song.artist, song.durationMs + 1000, timed),
        )
        assertEquals(timed, LyricsMatching.choose(song, candidates))
        assertNull(LyricsMatching.choose(song, candidates.take(2)))
    }

    @Test fun `Thai matching keeps vowel and tone marks`() {
        assertNotEquals(LyricsMatching.normalize("มา"), LyricsMatching.normalize("ม้า"))
    }

    @Test fun `alignment preserves unmatched lines and handles Thai without spaces`() {
        val reference = Lyrics(listOf("รักเธอ", "unheard bridge", "ตลอดไป", "รักเธอ").map { LyricLine(-1, -1, it) })
        val recognized = listOf(LyricLine(1000, 3000, "รัก เธอ ตลอดไป"), LyricLine(4000, 5000, "รักเธอ"))
        val result = ReferenceLyricsSync.align(reference, recognized)
        assertEquals(reference.lines.map { it.text }, result.lines.map { it.text })
        assertEquals(-1L, result.lines[1].startMs)
        assertTrue(result.lines[2].startMs > result.lines[0].startMs)
        assertEquals(4000L, result.lines[3].startMs)
        assertEquals(-1, Timeline.activeIndex(result.lines, 3500))
        assertEquals(3, Timeline.activeIndex(result.lines, 4500))
    }

    @Test fun `partial speech recognition cannot invent timing for unrecognized lines`() {
        val result = ReferenceLyricsSync.align(plain, listOf(LyricLine(100, 1000, "unrelated words")))
        assertEquals(plain.lines, result.lines)
        assertFalse(result.fullySynced)
    }

    @Test fun `service configuration rejects insecure URLs and header injection`() {
        LyricsServiceConfig().validate()
        LyricsServiceConfig("https://example.org/api/", "token").validate()
        for (url in listOf("http://example.org/api", "https://user:pass@example.org/api", "https://example.org/api?key=value", "file:///api")) {
            assertTrue(runCatching { LyricsServiceConfig(url, "secret").validate() }.isFailure)
        }
        assertTrue(runCatching { LyricsServiceConfig("https://example.org/api", "bad\r\nkey").validate() }.isFailure)
    }

    private fun workflow(found: Lyrics?, model: Boolean) = LyricsWorkflow(
        { it }, { found }, { _, _ -> error("No alignment available") }, { error("Unexpected fallback") }, { model },
    )
}
