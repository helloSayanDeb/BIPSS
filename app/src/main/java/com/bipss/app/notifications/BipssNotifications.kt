package com.bipss.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bipss.app.domain.environmental.ImdAlert

private const val TAG = "BipssNotifications"

/** Deep-link extra read by MainActivity to pick the tab to land on. */
private const val EXTRA_NAVIGATE_TO = "navigate_to"

private const val NAV_INSIGHTS = "insights"
private const val NAV_COACH    = "coach"
private const val NAV_TODAY    = "today"

/** Fully-qualified so this file does not have to import the activity class. */
private const val MAIN_ACTIVITY = "com.bipss.app.MainActivity"

/**
 * Stable notification ids.
 *
 * Coaching, reminders and the overnight summary each reuse one id so a newer post
 * replaces the older one rather than stacking a queue the user has to dismiss by hand.
 * Disaster alerts are the exception - they use the alert's own identifier hash so two
 * genuinely different warnings can sit in the shade at once, and a re-fetch of the same
 * warning updates in place instead of duplicating.
 */
object NotificationIds {
    const val SERVICE        = 1001
    const val COACHING       = 1002
    const val REMINDER       = 1003
    const val APNEA_SUMMARY  = 1004
}

/**
 * Single entry point for everything this app posts to the notification shade.
 *
 * Centralised because two rules are easy to get wrong in scattered call sites: a missing
 * POST_NOTIFICATIONS grant must degrade to a logged no-op rather than a SecurityException
 * that takes down a background worker, and the foreground-service notification must stay
 * on a minimum-importance channel so keeping the band connected never buzzes anyone.
 */
object BipssNotifications {

    const val CH_ALERTS   = "bipss_alerts"
    const val CH_COACHING = "bipss_coaching"
    const val CH_REMINDER = "bipss_reminders"
    const val CH_SERVICE  = "bipss_service"

    // TODO: replace these platform icons with a branded monochrome 24dp notification
    // drawable once the icon set lands. Android tints and masks the small icon, so a
    // colour asset would render as a white blob - it must be a single-colour alpha shape.
    private const val ICON_ALERT    = android.R.drawable.ic_dialog_alert
    private const val ICON_REMINDER = android.R.drawable.ic_popup_reminder
    private const val ICON_COACHING = android.R.drawable.ic_menu_info_details

    /**
     * Idempotent - safe to call on every app start. The guard is kept even though minSdk
     * is already 26 so the intent stays obvious if the floor ever moves back down.
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "NotificationManager unavailable, channels not created")
            return
        }

        val alerts = NotificationChannel(
            CH_ALERTS, "Disaster & health alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cyclone, flood, heatwave and air quality warnings for your area."
        }

        val coaching = NotificationChannel(
            CH_COACHING, "Daily coaching", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Readiness, recovery and sleep guidance based on your own trends."
        }

        val reminders = NotificationChannel(
            CH_REMINDER, "Reminders", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Gentle nudges to wear the band, log a reading or check in."
        }

        val service = NotificationChannel(
            CH_SERVICE, "Band connection", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "The quiet ongoing notice Android requires while the band stays connected."
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(alerts, coaching, reminders, service))
    }

    /**
     * On API 33+ the runtime grant is what decides. Below that there is no grant to check,
     * so the only meaningful question is whether the user has switched the app off in
     * system settings.
     */
    fun hasPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /**
     * A disaster warning. [personalNote] carries the ranked-alert explanation of why this
     * one matters to this user; it goes in the sub-text so the personalised line survives
     * into the collapsed shade rather than only appearing once the user expands the card.
     */
    fun showAlert(context: Context, alert: ImdAlert, personalNote: String?) {
        val id = alert.identifier.hashCode()

        val builder = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(ICON_ALERT)
            .setContentTitle(alert.title)
            .setContentText(alert.areaLabel)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.description))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, NAV_INSIGHTS, id))

        if (personalNote != null) builder.setSubText(personalNote)

        safeNotify(context, id, builder.build())
    }

    fun showCoaching(context: Context, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CH_COACHING)
            .setSmallIcon(ICON_COACHING)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, NAV_COACH, NotificationIds.COACHING))
            .build()

        safeNotify(context, NotificationIds.COACHING, notification)
    }

    fun showReminder(context: Context, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CH_REMINDER)
            .setSmallIcon(ICON_REMINDER)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, NAV_TODAY, NotificationIds.REMINDER))
            .build()

        safeNotify(context, NotificationIds.REMINDER, notification)
    }

    /**
     * Overnight desaturation count.
     *
     * RULE: screening language only. This wording must never say "apnea detected", never
     * use the word diagnosis, and never imply a condition has been identified. It reports
     * the count of events observed and how far the collection window has got, nothing more
     * - interpreting that belongs to a clinician, not to a wrist sensor.
     */
    fun showApneaSummary(
        context:        Context,
        eventCount:     Int,
        nightsCollected: Int,
        nightsRequired: Int
    ) {
        val body = "$eventCount desaturation events recorded last night. " +
                   "$nightsCollected of $nightsRequired nights collected."

        val notification = NotificationCompat.Builder(context, CH_COACHING)
            .setSmallIcon(ICON_COACHING)
            .setContentTitle("Overnight breathing summary")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, NAV_INSIGHTS, NotificationIds.APNEA_SUMMARY))
            .build()

        safeNotify(context, NotificationIds.APNEA_SUMMARY, notification)
    }

    /**
     * The ongoing notice for the BLE foreground service.
     *
     * Returned rather than posted: Android requires the service itself to hand this to
     * startForeground within its own start window. The text must track the real link state
     * - claiming "Connected" while the band is out of range would make the one piece of
     * always-visible UI this app has into a lie.
     */
    fun buildServiceNotification(context: Context, connected: Boolean): Notification {
        val text = if (connected) {
            "Band connected. Readings are being recorded."
        } else {
            "Band not connected. Waiting to reconnect."
        }

        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(ICON_COACHING)
            .setContentTitle("BIPSS")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(deepLink(context, NAV_TODAY, NotificationIds.SERVICE))
            .build()
    }

    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (e: Exception) {
            Log.w(TAG, "Cancel failed for id $id", e)
        }
    }

    /**
     * Every post goes through here. Workers and BLE callbacks run well away from any UI
     * that could ask for the grant, so a refused or revoked POST_NOTIFICATIONS must cost a
     * log line and nothing else.
     */
    private fun safeNotify(context: Context, id: Int, notification: Notification) {
        if (!hasPermission(context)) {
            Log.i(TAG, "Notification $id skipped, POST_NOTIFICATIONS not granted")
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification $id refused by the platform", e)
        } catch (e: Exception) {
            Log.w(TAG, "Notification $id failed to post", e)
        }
    }

    /**
     * CLEAR_TOP keeps a single MainActivity task rather than stacking a copy per tap.
     * IMMUTABLE is mandatory from API 31; UPDATE_CURRENT refreshes the extras of a
     * pending intent already held for the same request code.
     */
    private fun deepLink(context: Context, destination: String, requestCode: Int): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY)
            putExtra(EXTRA_NAVIGATE_TO, destination)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
