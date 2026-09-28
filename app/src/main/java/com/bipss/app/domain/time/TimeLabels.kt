package com.bipss.app.domain.time

/**
 * "Just now" / "12m ago" / "3h ago" / "2d ago" for an event at [sinceMs], as seen
 * at [nowMs]. Pure so screens can recompute it on a ticking clock rather than
 * freezing an age that was computed when the data loaded.
 */
fun relativeAgeLabel(sinceMs: Long, nowMs: Long): String {
    if (sinceMs <= 0L) return "never"
    val minutes = ((nowMs - sinceMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1     -> "just now"
        minutes < 60    -> "${minutes}m ago"
        minutes < 48 * 60 -> "${minutes / 60}h ago"
        else            -> "${minutes / (60 * 24)}d ago"
    }
}
