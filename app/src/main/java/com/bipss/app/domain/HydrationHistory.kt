package com.bipss.app.domain

import com.bipss.app.data.db.entity.WaterLogEntity
import com.bipss.app.domain.time.LocalDay
import java.time.ZoneId

data class WaterDay(val epochDay: Long, val totalMl: Int)

/** Seven local calendar days, oldest first. Missing logs stay zero, not inferred intake. */
fun waterHistory(entries: List<WaterLogEntity>, today: Long, zone: ZoneId = ZoneId.systemDefault()): List<WaterDay> {
    val totals = entries.groupBy { LocalDay.epochDay(it.timestampMs, zone) }
    return (today - 6..today).map { day -> WaterDay(day, totals[day].orEmpty().sumOf { it.amountMl }) }
}
