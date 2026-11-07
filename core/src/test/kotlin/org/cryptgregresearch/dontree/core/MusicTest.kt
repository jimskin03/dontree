package org.cryptgregresearch.dontree.core

import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    @Test fun `library accepts only supported extensions regardless of case`() {
        assertTrue(MusicFiles.supported("thai.OGG")); assertTrue(MusicFiles.supported("a.Mp3"))
        listOf("a.wav", "a.flac", "a.mp3.exe", "mp3", "a.ogg ").forEach { assertFalse(MusicFiles.supported(it)) }
    }
    @Test fun `missing tags fall back to filename`() {
        assertEquals("my song", MusicFiles.title("<unknown>", "my_song.mp3"))
        assertEquals("เพลง", MusicFiles.title("เพลง", "file.ogg"))
    }
    @Test fun `lrc handles offset repeated stamps and stable sorting`() {
        val result = LrcParser.parse("[offset:-100]\n[00:05.25]B\n[00:01.5][00:03.005]A", 10_000).lines
        assertEquals(listOf(1400L, 2905L, 5150L), result.map { it.startMs })
        assertEquals(2905L, result[0].endMs); assertEquals(10_000L, result.last().endMs)
    }
    @Test fun `bilingual duplicate timestamps form one line`() {
        assertEquals("ไทย\nEnglish", LrcParser.parse("[00:01]ไทย\n[00:01]English", 3000).lines.single().text)
    }
    @Test fun `last imported line ends at track end even when less than five seconds remain`() {
        assertEquals(60_000L, LrcParser.parse("[00:59.5]End", 60_000).lines.single().endMs)
    }
    @Test fun `lrc drops out of range stamps and clamps negative offset`() {
        val lines = LrcParser.parse("[offset:-2000]\n[00:01]Start\n[02:00]Outside", 60_000).lines
        assertEquals(1, lines.size); assertEquals(0L, lines.single().startMs)
    }
    @Test fun `equal beat distances preserve deterministic earlier beat`() {
        assertEquals(1000L, Timeline.snap(1050, listOf(1000, 1100)))
    }
    @Test(expected = IllegalArgumentException::class) fun `untimed lyrics rejected`() { LrcParser.parse("[ar:Artist]\nNo time", 1000) }
    @Test fun `timeline supports seeking backwards and gaps`() {
        val lines = listOf(LyricLine(1000, 2000, "A"), LyricLine(3000, 4000, "B"))
        assertEquals(-1, Timeline.activeIndex(lines, 0)); assertEquals(1, Timeline.activeIndex(lines, 3500))
        assertEquals(0, Timeline.activeIndex(lines, 1500)); assertEquals(-1, Timeline.activeIndex(lines, 2500))
        assertEquals(-1, Timeline.activeIndex(lines, 4000))
    }
    @Test fun `snapping is bounded and retains distant vocals`() {
        assertEquals(1000L, Timeline.snap(1070, listOf(1000, 1500)))
        assertEquals(1200L, Timeline.snap(1200, listOf(1000, 1500)))
        assertEquals(100L, Timeline.snap(100, emptyList()))
    }
    @Test fun `analysis percent leaves zero as soon as audio is counted`() {
        assertEquals(0, AnalysisProgress.percent(0, 210_000))
        assertEquals(0f, AnalysisProgress.fraction(0, 0))
        assertEquals(7, AnalysisProgress.percent(15_000, 210_000))
        assertEquals(0.07f, AnalysisProgress.fraction(15_000, 210_000))
        assertEquals(1, AnalysisProgress.percent(1_000, 20 * 60_000))
        assertEquals(99, AnalysisProgress.percent(180_000, 180_000))
        assertEquals("Listening locally · 7%", AnalysisProgress.label("Listening locally", 15_000, 210_000))
    }
    @Test fun `silence has no beats and synthetic clicks yield tempo`() {
        assertTrue(BeatTracker.detect(FloatArray(16000)).isEmpty())
        val pcm = FloatArray(64000)
        for (start in 0 until pcm.size step 8000) for (i in start until start + 160) pcm[i] = 0.9f
        val beats = BeatTracker.detect(pcm)
        assertEquals(8, beats.size); assertEquals(120, BeatTracker.tempo(beats))
    }
}
