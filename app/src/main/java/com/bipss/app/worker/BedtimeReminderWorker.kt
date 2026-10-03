package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.R
import com.bipss.app.data.SleepPlanRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.localization.AppText
import com.bipss.app.notifications.BipssNotifications
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * A nudge half an hour before the planned bedtime, when the user has asked
 * for one. It books its own next run: the plan changes from day to day, so
 * each run works out the next reminder from the plan as it stands then.
 */
class BedtimeReminderWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val prefs: UserPreferencesRepository by inject()
    private val planner: SleepPlanRepository by inject()

    override suspend fun doWork(): Result {
        if (!prefs.bedtimeReminderEnabled.first()) return Result.success()
        val plan = planner.current().plan
        val now = ZonedDateTime.now()
        val target = remindAt(now, plan.bedtimeMin)
        // Fired on time (or a little late): say it. Anything else is a scheduling run.
        val late = Duration.between(target, now)
        if (!late.isNegative && late <= Duration.ofMinutes(45)) {
            val h = plan.needMin / 60
            val m = plan.needMin % 60
            BipssNotifications.showBedtime(
                applicationContext,
                AppText.text(R.string.copy_f190b1f7fd28, "Time to wind down"),
                AppText.text(R.string.copy_a0f27f3fd25b, "Be in bed by %1\$s for about %2\$sh %3\$sm of sleep.",
                    java.time.LocalTime.of(plan.bedtimeMin / 60, plan.bedtimeMin % 60)
                        .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)), h, m)
            )
        }
        schedule(applicationContext, plan.bedtimeMin, now.plusMinutes(46))
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "bedtime_reminder"
        const val LEAD_MIN = 30L

        /** The reminder time on the calendar day of [on]. */
        private fun remindAt(on: ZonedDateTime, bedtimeMin: Int): ZonedDateTime =
            on.toLocalDate().atStartOfDay(on.zone).plusMinutes(bedtimeMin - LEAD_MIN)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Books the next reminder at its time after [after]. */
        private fun schedule(context: Context, bedtimeMin: Int, after: ZonedDateTime) {
            var next = remindAt(after, bedtimeMin)
            if (!next.isAfter(after)) next = remindAt(after.plusDays(1), bedtimeMin)
            val request = OneTimeWorkRequestBuilder<BedtimeReminderWorker>()
                .setInitialDelay(Duration.between(ZonedDateTime.now(), next).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Works out the next reminder now (after the toggle or the wake time changed). */
        fun reschedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BedtimeReminderWorker>().build()
            )
        }

        /** At app start: make sure one is booked, without disturbing a booked one. */
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BedtimeReminderWorker>().build()
            )
        }
    }
}
