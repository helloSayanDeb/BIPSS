package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.R
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.WeeklyReportRepository
import com.bipss.app.domain.report.WeeklyReport
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.localization.AppText
import com.bipss.app.notifications.BipssNotifications
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Monday morning: tells the user the week just ended has a report. Runs
 * every day at about 08:00 and acts only on Mondays, once per Monday, and only
 * when the week had at least three days with readings.
 */
class WeeklyReportWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val prefs: UserPreferencesRepository by inject()
    private val reports: WeeklyReportRepository by inject()

    override suspend fun doWork(): Result {
        val today = LocalDay.epochDay(System.currentTimeMillis())
        if (LocalDate.ofEpochDay(today).dayOfWeek != DayOfWeek.MONDAY) return Result.success()
        if (!prefs.weeklyReportNotification.first()) return Result.success()
        if (prefs.lastWeeklyReportDay() == today) return Result.success()
        val lastWeek = reports.summary(WeeklyReport.weekStart(today - 1))
        if (lastWeek.daysWithData < 3) return Result.success()
        BipssNotifications.showWeeklyReport(
            applicationContext,
            AppText.text(R.string.copy_61373d5ac8a5, "Your week in review"),
            AppText.text(R.string.copy_ae29a50d063d, "Last week's sleep, recovery, strain and steps are ready to look at.")
        )
        prefs.setLastWeeklyReportDay(today)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "weekly_report"

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun schedule(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.withHour(8).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<WeeklyReportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(java.time.Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
