package com.bipss.app.data

import com.bipss.app.data.db.dao.BioAgeDao
import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.entity.DailySummaryEntity
import com.bipss.app.domain.report.WeekDay
import com.bipss.app.domain.report.WeekSummary
import com.bipss.app.domain.report.WeeklyReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A week's report, with where BioAge stood at its start and end. */
data class WeeklyReportData(
    val summary: WeekSummary,
    val bioAgeAtStart: Double?,
    val bioAgeAtEnd: Double?
)

fun DailySummaryEntity.toWeekDay() = WeekDay(
    epochDay = epochDay, sleepMin = sleepMinutes, sleepScore = sleepScore, recovery = recovery,
    strain = strain?.toDouble(), steps = steps, restingHr = restingHr,
    activeMin = zoneMin1 + zoneMin2 + zoneMin3 + zoneMin45, workouts = workoutCount, waterMl = waterMl
)

class WeeklyReportRepository(
    private val summaries: DailySummaryDao,
    private val bioAge:    BioAgeDao
) {
    /** The week starting [startDay] (a Monday), compared with the one before it. */
    fun week(startDay: Long): Flow<WeeklyReportData> =
        combine(summaries.betweenFlow(startDay - 7, startDay + 6), bioAge.sinceFlow(startDay - 7)) { rows, history ->
            val summary = WeeklyReport.summarize(rows.map(DailySummaryEntity::toWeekDay), startDay)
            val inWeek = history.filter { it.epochDay in startDay..startDay + 6 }
            WeeklyReportData(summary, inWeek.firstOrNull()?.bioAge, inWeek.lastOrNull()?.bioAge)
        }

    suspend fun summary(startDay: Long): WeekSummary =
        WeeklyReport.summarize(summaries.between(startDay - 7, startDay + 6).map(DailySummaryEntity::toWeekDay), startDay)
}
