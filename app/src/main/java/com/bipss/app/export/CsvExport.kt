package com.bipss.app.export

import com.bipss.app.data.MetricTypes
import com.bipss.app.data.MinuteData
import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.dao.DesaturationEventDao
import com.bipss.app.data.db.dao.DetectedWorkoutDao
import com.bipss.app.data.db.dao.JournalDao
import com.bipss.app.data.db.dao.SleepSessionDao
import com.bipss.app.data.db.dao.WaterLogDao
import com.bipss.app.domain.time.LocalDay
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The data behind the screens as plain CSV files in one ZIP: everything the app keeps that is not raw waveform. */
class CsvExport(
    private val daily: DailySummaryDao,
    private val sleep: SleepSessionDao,
    private val desat: DesaturationEventDao,
    private val water: WaterLogDao,
    private val workouts: DetectedWorkoutDao,
    private val journal: JournalDao,
    private val minutes: MinuteData
) {
    private fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun ZipOutputStream.csv(name: String, header: List<String>, rows: List<List<Any?>>) {
        putNextEntry(ZipEntry(name))
        val w = bufferedWriter()
        w.write(header.joinToString(",") + "\n")
        rows.forEach { r -> w.write(r.joinToString(",") { cell(it) } + "\n") }
        w.flush()
        closeEntry()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    suspend fun write(out: File, nowMs: Long = System.currentTimeMillis()) {
        out.parentFile?.mkdirs()
        out.outputStream().use { write(it, nowMs) }
    }

    private suspend fun write(stream: OutputStream, nowMs: Long) = ZipOutputStream(stream).use { zip ->
        zip.csv("daily_summary.csv",
            listOf("date", "valid_heart_rate_minutes", "sleep_minutes", "sleep_start_minute_of_day", "sleep_end_minute_of_day",
                "resting_hr_bpm", "steps", "minutes_40_60_hrr", "minutes_60_70_hrr", "minutes_70_80_hrr", "minutes_80_plus_hrr",
                "strength_minutes", "workouts", "recovery", "sleep_score", "strain", "water_ml"),
            daily.all().map { d -> listOf(LocalDate.ofEpochDay(d.epochDay), d.validMinutes, d.sleepMinutes, d.sleepStartMin, d.sleepEndMin,
                d.restingHr?.let { "%.1f".format(java.util.Locale.ROOT, it) }, d.steps, d.zoneMin1, d.zoneMin2, d.zoneMin3, d.zoneMin45,
                d.strengthMin, d.workoutCount, d.recovery, d.sleepScore, d.strain, d.waterMl) })
        val sessions = sleep.all()
        zip.csv("sleep_sessions.csv",
            listOf("start", "end", "total_sleep_minutes", "efficiency_pct", "awake_after_onset_minutes", "awakenings", "avg_hr", "min_hr", "avg_spo2", "counted_oxygen_dips"),
            sessions.map { s -> listOf(iso(s.startMs), iso(s.endMs), s.totalSleepMs / 60_000, s.efficiencyPct, s.wakeAfterOnsetMs / 60_000,
                s.awakenings, s.avgHr, s.minHr, s.avgSpo2, desat.validCountForSession(s.id)) })
        zip.csv("oxygen_dips.csv", listOf("night_start", "dip_start", "duration_seconds", "baseline_spo2", "lowest_spo2", "drop_points", "counted"),
            sessions.flatMap { s -> desat.forSession(s.id).map { e ->
                listOf(iso(s.startMs), iso(e.startMs), e.durationMs / 1000, e.baselineSpo2, e.nadirSpo2, e.dropPercent,
                    !e.motionArtefact && e.durationMs >= 10_000) } })
        zip.csv("water_log.csv", listOf("time", "amount_ml"), water.all().map { listOf(iso(it.timestampMs), it.amountMl) })
        zip.csv("detected_workouts.csv", listOf("start", "end", "minutes", "type", "status", "mean_hr", "max_hr", "mean_hrr", "trimp"),
            workouts.all().map { w -> listOf(iso(w.startMs), iso(w.endMs), w.minutes, w.type, w.status, w.meanHr, w.maxHr, w.meanHrr, w.trimp) })
        zip.csv("journal.csv", listOf("date", "habit", "answer"),
            journal.all().map { listOf(LocalDate.ofEpochDay(it.epochDay), it.habit, if (it.yes) "yes" else "no") })
        readings(zip, nowMs)
    }

    private suspend fun readings(zip: ZipOutputStream, nowMs: Long) {
        val types = listOf(MetricTypes.HR, MetricTypes.SPO2, MetricTypes.SKIN_TEMP, MetricTypes.PRV, MetricTypes.ACTIVITY, MetricTypes.STEPS)
        val hourStart = (nowMs - 90 * 86_400_000L) / 3_600_000L * 3_600_000L
        zip.csv("readings_hourly_last_90_days.csv", listOf("hour_start", "metric", "average", "min", "max", "samples"),
            types.flatMap { t -> minutesBucketed(t, hourStart, nowMs, 3_600_000L) })
        val minuteStart = (nowMs - 7 * 86_400_000L) / 60_000L * 60_000L
        zip.csv("readings_per_minute_last_7_days.csv", listOf("minute_start", "metric", "average", "min", "max", "samples"),
            types.flatMap { t -> minutesBucketed(t, minuteStart, nowMs, 60_000L) })
    }

    private suspend fun minutesBucketed(type: String, from: Long, to: Long, bucket: Long): List<List<Any?>> =
        minutes.rebucket(type, from, to, bucket).map { b ->
            listOf(iso(b.bucketStartMs), type, "%.2f".format(java.util.Locale.ROOT, b.avgValue),
                "%.2f".format(java.util.Locale.ROOT, b.minValue), "%.2f".format(java.util.Locale.ROOT, b.maxValue), b.sampleCount)
        }
}
