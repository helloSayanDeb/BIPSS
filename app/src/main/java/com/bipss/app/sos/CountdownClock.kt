package com.bipss.app.sos

/** Both visual values derive from the same monotonic deadline, without a second animation clock. */
object CountdownClock {
    fun secondsLeft(remainingMs: Long): Int = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L).toInt()
    fun progress(remainingMs: Long, durationMs: Long): Float =
        (1f - remainingMs.coerceAtLeast(0L).toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
}
