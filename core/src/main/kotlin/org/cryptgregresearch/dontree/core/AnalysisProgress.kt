package org.cryptgregresearch.dontree.core

/** Maps analyzed audio onto the 0–99% range shown while the lyrics agent is still working. */
object AnalysisProgress {
    fun percent(doneMs: Long, totalMs: Long): Int {
        if (doneMs <= 0L || totalMs <= 0L) return 0
        return ((doneMs.coerceAtMost(totalMs) * 100) / totalMs).toInt().coerceIn(1, 99)
    }

    fun fraction(doneMs: Long, totalMs: Long): Float = percent(doneMs, totalMs) / 100f

    fun label(phase: String, doneMs: Long, totalMs: Long): String = "$phase · ${percent(doneMs, totalMs)}%"
}
