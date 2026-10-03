package com.bipss.app.domain

import kotlin.math.roundToInt

object DailyWellness {
    fun bmi(heightCm: Float, weightKg: Float, age: Int): Float? =
        if (age >= 20 && heightCm in 100f..250f && weightKg in 25f..350f)
            weightKg / (heightCm / 100f) / (heightCm / 100f) else null

    /** Editable logging heuristic, not a medical fluid prescription. Food contributes too. */
    fun waterStartingGoal(weightKg: Float, age: Int): Int? =
        if (age in 20..64 && weightKg in 40f..150f)
            ((weightKg * 30f * 0.8f / 250f).roundToInt() * 250).coerceIn(1500, 3000) else null

    /** Do not assign an entire overnight/reboot gap to today's count. */
    fun stepDelta(previous: Long?, current: Long, sameDay: Boolean, sameBoot: Boolean): Int =
        if (previous == null || !sameDay || !sameBoot || current < previous) 0
        else (current - previous).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
