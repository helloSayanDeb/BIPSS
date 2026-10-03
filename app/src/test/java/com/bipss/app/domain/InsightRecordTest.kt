package com.bipss.app.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class InsightRecordTest {
    @Test fun weatherSuccessDoesNotInventAnAlertCheckAndInputsMatchCalculation() {
        fun compute(checked: Long) = RiskScoreCalculator().computeAll(80, 97, 34f,
            RiskScoreCalculator.Personal(), com.bipss.app.domain.environmental.EnvironmentalSnapshot(
                ambientTempC = 32f, humidityPercent = 70f, fetchedAtMs = 1000, alertsCheckedAtMs = checked,
                districtName = "Test district"), 2f, null, null, RiskScoreCalculator.Pillar.Missing("No strain"), null, DataMode.FULL)
        val unchecked = compute(0)
        val flood = unchecked.first { it.type == RiskScoreType.FLOOD }
        assertFalse(flood.hasNumber)
        assertNull(flood.inputs.first { it.key == "warning_count" }.value)
        val heat = unchecked.first { it.type == RiskScoreType.HEAT_STRESS }
        assertEquals(32.0, heat.inputs.first { it.key == "ambient" }.value!!, 0.0)
        assertTrue(heat.missingInputs.contains("weather alerts"))
        assertTrue(compute(1000).first { it.type == RiskScoreType.FLOOD }.hasNumber)
    }
    private fun score(n: Int, at: Long = 100) = RiskScore(RiskScoreType.HEAT_STRESS, n, RiskLevel.LOW, emptyList(), computedAt = at,
        inputs = listOf(InsightInput("ambient", "Temperature", 30.0, "°C")))
    @Test fun missingScoresAndCategoricalLoadDoNotBecomeNumericZeroes() {
        assertNull(InsightRecord.from(score(0).copy(availability = ScoreAvailability.NEEDS_BAND)).value)
        assertNull(InsightRecord.from(score(55).copy(type = RiskScoreType.AUTONOMIC_LOAD, displayValue = "Elevated", scaleMax = 0)).value)
        assertEquals(0.0, InsightRecord.from(score(0)).value!!, 0.0)
    }
    @Test fun strainUsesItsOwnScaleIncludingCommaLocale() {
        val s = score(40).copy(type = RiskScoreType.STRAIN, displayValue = "8,4", scaleMax = 21)
        assertEquals(8.4, InsightRecord.from(s).value!!, 0.001)
    }
    @Test fun dailyHistoryPreservesInputsAndDoesNotBackfillGaps() {
        val first = InsightRecord.from(score(20))
        val old = mergeInsightRecords(emptyMap(), 100, listOf(first))
        val next = mergeInsightRecords(old, 102, listOf(InsightRecord.from(score(80, 200).copy(inputs = listOf(InsightInput("ambient", "Temperature", 42.0, "°C"))))))
        assertFalse(next.containsKey(101))
        assertEquals(30.0, next[100]!![first.type]!!.inputs.first().value!!, 0.0)
        val decoded = Json.decodeFromString<Map<Long, Map<String, InsightRecord>>>(Json.encodeToString(next))
        assertEquals(next, decoded)
    }
    @Test fun unavailableAndOlderCalculationsCannotEraseSavedUsableDay() {
        val first = InsightRecord.from(score(20, 100))
        val old = mergeInsightRecords(emptyMap(), 100, listOf(first))
        val missing = InsightRecord.from(score(0, 200).copy(availability = ScoreAvailability.NEEDS_BAND))
        assertEquals(first, mergeInsightRecords(old, 100, listOf(missing))[100]!![first.type])
        assertEquals(first, mergeInsightRecords(old, 100, listOf(InsightRecord.from(score(50, 90))))[100]!![first.type])
        assertFalse(mergeInsightRecords(old, 190, emptyList()).containsKey(100))
    }
}
