package org.cryptgregresearch.dontree.core

import kotlin.math.sqrt

/** Lightweight energy-onset detector. Returns measured onsets, not an invented tempo grid. */
object BeatTracker {
    fun detect(pcm: FloatArray, sampleRate: Int = 16000, offsetMs: Long = 0): List<Long> {
        require(sampleRate > 0)
        val hop = (sampleRate / 100).coerceAtLeast(1)
        val energy = DoubleArray((pcm.size + hop - 1) / hop) { frame ->
            val start = frame * hop
            val end = minOf(start + hop, pcm.size)
            var sum = 0.0
            for (i in start until end) sum += pcm[i].toDouble() * pcm[i]
            sqrt(sum / (end - start))
        }
        val result = mutableListOf<Long>()
        var previous = 0.0
        energy.forEachIndexed { i, value ->
            val start = (i - 30).coerceAtLeast(0)
            var sum = 0.0
            for (j in start until i) sum += energy[j]
            val baseline = if (i > start) sum / (i - start) else 0.0
            val time = offsetMs + i.toLong() * hop * 1000 / sampleRate
            if (value > 0.015 && value > baseline * 1.6 && value > previous * 1.25 &&
                (result.isEmpty() || time - result.last() >= 250)) result += time
            previous = value
        }
        return result
    }

    fun tempo(beats: List<Long>): Int? {
        val intervals = beats.zipWithNext { a, b -> b - a }.filter { it in 250..1500 }.sorted()
        if (intervals.size < 3) return null
        return (60_000 / intervals[intervals.size / 2]).toInt()
    }
}
