package com.bipss.app.data

import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.sleep.DesaturationScreening
import com.bipss.app.domain.sleep.NightScreening

/** A night without oxygen readings is absent, not a zero-dip night. */
internal fun recordedScreening(session: SleepSessionEntity, valid: Int, rejected: Int): NightScreening? {
    if (session.avgSpo2 == null || !session.avgSpo2.isFinite() || session.totalSleepMs <= 0L) return null
    return NightScreening(session.startMs, session.endMs, valid, rejected,
        DesaturationScreening.computeOdi(valid, session.totalSleepMs))
}
