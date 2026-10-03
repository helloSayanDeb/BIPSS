package com.bipss.app.data

import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.sleep.DesaturationScreening
import org.junit.Assert.*
import org.junit.Test

class RecordedSleepScreeningTest {
    @Test fun `missing oxygen nights never satisfy the fourteen-night minimum or dilute the mean`() {
        fun night(index: Int, oxygen: Float?) = SleepSessionEntity(startMs = index * 86_400_000L,
            endMs = index * 86_400_000L + 8 * 3_600_000, totalSleepMs = 8 * 3_600_000L,
            efficiencyPct = 100, wakeAfterOnsetMs = 0, awakenings = 0, avgSpo2 = oxygen)
        val thirteen = (0 until 13).map { recordedScreening(night(it, 97f), 16, 2)!! }
        val missing = recordedScreening(night(13, null), 0, 0)
        assertNull(missing)
        assertNull(DesaturationScreening.screeningSummary(thirteen + listOfNotNull(missing)))
        val fourteen = thirteen + recordedScreening(night(14, 98f), 16, 0)!!
        assertNotNull(DesaturationScreening.screeningSummary(fourteen))
        assertEquals(2.0, fourteen.map { it.odi }.average(), 0.001)
        assertEquals(0f, recordedScreening(night(15, 98f), 0, 0)!!.odi, 0f)
    }
}
