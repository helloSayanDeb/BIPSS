package com.bipss.app.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bipss.app.data.BioAgeRepository
import com.bipss.app.data.SleepRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.WeeklyReportRepository
import com.bipss.app.data.db.dao.DailySummaryDao
import com.bipss.app.data.db.entity.DailySummaryEntity
import com.bipss.app.domain.report.HighlightKind
import com.bipss.app.domain.report.Stat
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the files a user can take away: a health report, the overnight
 * oxygen record for a clinician, the weekly report, and all the data as CSV.
 * Documents are in English because they are meant to be handed to someone
 * else. Files are written to the app cache and handed out through a
 * FileProvider; the previous ones are removed each time.
 */
class DataExporter(
    private val context: Context,
    private val daily: DailySummaryDao,
    private val csv: CsvExport,
    private val prefs: UserPreferencesRepository,
    private val bioAge: BioAgeRepository,
    private val sleep: SleepRepository,
    private val weekly: WeeklyReportRepository
) {
    private val dir get() = File(context.cacheDir, "exports")
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.UK)

    private fun fresh(name: String): File {
        dir.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
        return File(dir, name)
    }

    private fun today() = LocalDate.now()
    private fun hm(minutes: Int) = "%d h %02d min".format(Locale.UK, minutes / 60, minutes % 60)

    /** A share sheet for the file, with read access granted to whichever app is picked. */
    fun shareIntent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
        return Intent.createChooser(send, null)
    }

    suspend fun csvBundle(): File = withContext(Dispatchers.IO) {
        val out = fresh("bipss-data-" + today().format(stamp) + ".zip")
        csv.write(out)
        out
    }

    suspend fun breathingRecordPdf(): File = withContext(Dispatchers.IO) {
        val text = sleep.buildPhysicianReport()
        val pdf = PdfBuilder("BIPSS overnight oxygen record")
        pdf.title("Overnight oxygen record", "Generated " + today().format(dayFmt))
        pdf.monospaced(text.lines().drop(2).joinToString("\n"))
        pdf.finish(fresh("bipss-overnight-oxygen-record-" + today().format(stamp) + ".pdf"))
    }

    suspend fun healthReportPdf(days: Int): File = withContext(Dispatchers.IO) {
        val end = LocalDay.epochDay(System.currentTimeMillis())
        val rows = daily.between(end - days + 1, end)
        val snapshot = bioAge.snapshot.first()
        val profile = prefs.userProfile.first()
        val pdf = PdfBuilder("BIPSS health report")
        pdf.title("BIPSS health report", "Generated " + today().format(dayFmt) + " - last " + days + " days")
        profile?.let { p ->
            pdf.heading("About")
            pdf.keyValue("Name", p.name.ifBlank { "-" })
            pdf.keyValue("Age", p.age.toString())
            pdf.keyValue("Height / weight", "%.0f cm / %.1f kg".format(Locale.UK, p.heightCm, p.weightKg))
        }
        pdf.heading("Fitness estimates")
        val r = snapshot.result
        pdf.keyValue("BIPSS BioAge", r?.bioAge?.let { "%.1f (%+.1f years)".format(Locale.UK, it, r.yearsDelta ?: 0.0) } ?: "not yet available")
        pdf.keyValue("VO2 max", snapshot.vo2?.let { "%.1f +/- %.0f mL/kg/min".format(Locale.UK, it.value, it.sigma) } ?: "not yet available")
        pdf.paragraph("Estimates worked out from a consumer wrist band. They are not measurements from a medical device and not a medical biological age.", muted = true)
        pdf.heading("Averages")
        fun avg(f: (DailySummaryEntity) -> Double?) = rows.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        pdf.keyValue("Sleep", avg { it.sleepMinutes?.toDouble() }?.let { hm(it.toInt()) } ?: "-")
        pdf.keyValue("Steps a day", avg { it.steps?.toDouble() }?.let { "%,.0f".format(Locale.UK, it) } ?: "-")
        pdf.keyValue("Resting heart rate (sleep)", avg { it.restingHr }?.let { "%.0f bpm".format(Locale.UK, it) } ?: "-")
        pdf.keyValue("Recovery", avg { it.recovery?.toDouble() }?.let { "%.0f".format(Locale.UK, it) } ?: "-")
        pdf.heading("Day by day")
        pdf.table(
            listOf("Date", "Sleep", "Steps", "Rest HR", "Active min", "Strain", "Recovery"),
            rows.takeLast(60).reversed().map { d ->
                listOf(LocalDate.ofEpochDay(d.epochDay).format(stamp),
                    d.sleepMinutes?.let { "%d:%02d".format(Locale.UK, it / 60, it % 60) } ?: "-",
                    d.steps?.toString() ?: "-", d.restingHr?.let { "%.0f".format(Locale.UK, it) } ?: "-",
                    (d.zoneMin1 + d.zoneMin2 + d.zoneMin3 + d.zoneMin45).toString(),
                    d.strain?.let { "%.1f".format(Locale.UK, it) } ?: "-", d.recovery?.toString() ?: "-")
            },
            listOf(0.18f, 0.12f, 0.14f, 0.14f, 0.14f, 0.14f, 0.14f)
        )
        pdf.finish(fresh("bipss-health-report-" + today().format(stamp) + ".pdf"))
    }

    suspend fun weeklyReportPdf(startDay: Long): File = withContext(Dispatchers.IO) {
        val s = weekly.summary(startDay)
        val pdf = PdfBuilder("BIPSS weekly report")
        val from = LocalDate.ofEpochDay(startDay)
        pdf.title("Weekly report", from.format(dayFmt) + " to " + from.plusDays(6).format(dayFmt))
        fun line(label: String, stat: Stat, format: (Double) -> String) {
            val v = stat.value?.let(format) ?: "-"
            val c = stat.change?.let { " (" + (if (it >= 0) "+" else "-") + format(kotlin.math.abs(it)) + " vs the week before)" } ?: ""
            pdf.keyValue(label, v + c)
        }
        line("Sleep", s.sleepMin) { hm(it.toInt()) }
        line("Recovery", s.recovery) { "%.0f".format(Locale.UK, it) }
        line("Strain", s.strain) { "%.1f".format(Locale.UK, it) }
        line("Steps a day", s.steps) { "%,.0f".format(Locale.UK, it) }
        line("Resting heart rate", s.restingHr) { "%.0f bpm".format(Locale.UK, it) }
        line("Active minutes", s.activeMin) { "%.0f".format(Locale.UK, it) }
        line("Workouts", s.workouts) { "%.0f".format(Locale.UK, it) }
        if (s.highlights.isNotEmpty()) {
            pdf.heading("Highlights")
            s.highlights.forEach { h ->
                val d = LocalDate.ofEpochDay(h.epochDay).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK)
                pdf.paragraph(when (h.kind) {
                    HighlightKind.BEST_RECOVERY -> "Best recovery: " + d + ", " + h.value.toInt()
                    HighlightKind.LONGEST_SLEEP -> "Longest sleep: " + d + ", " + hm(h.value.toInt())
                    HighlightKind.MOST_STEPS -> "Most steps: " + d + ", " + "%,d".format(Locale.UK, h.value.toInt())
                    HighlightKind.HARDEST_DAY -> "Hardest day: " + d + ", strain " + "%.1f".format(Locale.UK, h.value)
                })
            }
        }
        pdf.finish(fresh("bipss-weekly-report-" + from.format(stamp) + ".pdf"))
    }
}
