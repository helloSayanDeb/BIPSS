package com.bipss.app.worker

import android.content.Context
import androidx.work.*
import com.bipss.app.notifications.BipssNotifications
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daytime hydration nudge.
 *
 * Deliberately silent between 22:00 and 07:00 - a reminder to drink water is
 * never worth waking someone up for, and night pings would also corrupt the
 * sleep session the app is trying to record.
 *
 * Fires only when a drink has actually been logged before and it has been a
 * while. A user who has never logged anything gets no nagging, because the app
 * has no evidence they are not drinking.
 */
class HydrationReminderWorker(
    appContext:   Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    private val waterRepo: com.bipss.app.data.WaterRepository by inject()

    override suspend fun doWork(): Result {
        // Nothing to do if the user has not allowed notifications.
        if (!BipssNotifications.hasPermission(applicationContext)) return Result.success()

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour !in QUIET_END until QUIET_START) return Result.success()

        val hours = waterRepo.hoursSinceLastLog() ?: return Result.success()
        if (hours >= REMIND_AFTER_HOURS) {
            val message = MESSAGES.random()
            BipssNotifications.showReminder(
                applicationContext,
                "Time for water",
                message
            )
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "hydration_reminder"
        private const val QUIET_END = 7      // first hour a reminder may fire
        private const val QUIET_START = 22   // first hour it may not
        private const val REMIND_AFTER_HOURS = 2f

        private val MESSAGES = listOf(
            "Your body called — it wants water. 💧",
            "Even mild dehydration affects focus. Take a sip!",
            "Quick water break? Your future self will thank you.",
            "Hydration check! Your last drink was a while ago.",
            "Water is the cheapest health hack. Drink up! 🥤",
            "Your Heat Stress score improves when you stay hydrated.",
            "Fun fact: by the time you feel thirsty, you're already dehydrated.",
            "H₂O o'clock! Time for a refill. 💦"
        )

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HydrationReminderWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
