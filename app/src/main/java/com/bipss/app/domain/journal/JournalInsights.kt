package com.bipss.app.domain.journal

import kotlin.math.sqrt

/** The daily journal questions. The key is what is stored; the wording lives in the UI. */
enum class Habit(val key: String) {
    CAFFEINE("caffeine_late"),
    ALCOHOL("alcohol"),
    LATE_MEAL("late_meal"),
    SCREENS("screens_in_bed"),
    STRESS("stressful_day"),
    MEDITATION("mindful_minutes");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class JournalAnswer(val epochDay: Long, val habit: Habit, val yes: Boolean)

/** How the morning after differed between days a habit was and was not logged. */
data class HabitEffect(
    val habit: Habit,
    val yesDays: Int,
    val noDays: Int,
    val meanAfterYes: Double?,
    val meanAfterNo: Double?,
    /** meanAfterYes minus meanAfterNo. */
    val difference: Double?,
    /** Enough days of each, and a gap bigger than chance would usually leave. */
    val reliable: Boolean
)

/**
 * Compares the next morning recovery after yes days with after no days.
 * Nothing is shown until each side has MIN_DAYS_EACH days, and then only
 * when the gap is at least MIN_T standard errors (Welch). It is an
 * association in one person own data, not a cause.
 */
object JournalInsights {
    const val MIN_DAYS_EACH = 5
    const val MIN_T = 1.3

    fun effects(answers: List<JournalAnswer>, outcomeByDay: Map<Long, Double>): List<HabitEffect> =
        Habit.entries.map { habit ->
            val mine = answers.filter { it.habit == habit }
            // The day after the journal day is the morning that follows it.
            val yes = mine.filter { it.yes }.mapNotNull { outcomeByDay[it.epochDay + 1] }
            val no = mine.filter { !it.yes }.mapNotNull { outcomeByDay[it.epochDay + 1] }
            val my = yes.takeIf { it.isNotEmpty() }?.average()
            val mn = no.takeIf { it.isNotEmpty() }?.average()
            val diff = if (my != null && mn != null) my - mn else null
            HabitEffect(habit, yes.size, no.size, my, mn, diff, reliable = diff != null && isReliable(yes, no, diff))
        }

    private fun variance(v: List<Double>): Double {
        val m = v.average()
        return v.sumOf { (it - m) * (it - m) } / (v.size - 1)
    }

    private fun isReliable(yes: List<Double>, no: List<Double>, diff: Double): Boolean {
        if (yes.size < MIN_DAYS_EACH || no.size < MIN_DAYS_EACH) return false
        val se = sqrt(variance(yes) / yes.size + variance(no) / no.size)
        if (se == 0.0) return diff != 0.0
        return kotlin.math.abs(diff) / se >= MIN_T
    }
}
