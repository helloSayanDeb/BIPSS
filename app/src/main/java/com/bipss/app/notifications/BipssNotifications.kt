package com.bipss.app.notifications

import com.bipss.app.localization.AppText
import com.bipss.app.R

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

// Deep-link targets are shared with MainActivity through navigation.DeepLink.
private val NAV_INSIGHTS = com.bipss.app.navigation.DeepLink.Target.INSIGHTS
private val NAV_COACH    = com.bipss.app.navigation.DeepLink.Target.COACH
private val NAV_TODAY    = com.bipss.app.navigation.DeepLink.Target.TODAY
private val NAV_SLEEP    = com.bipss.app.navigation.DeepLink.Target.SLEEP

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
    const val BREATHING_SUMMARY  = 1004
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

    // The BIPSS logo as a single-colour alpha shape: Android tints and masks the
    // small icon, so a colour asset would render as a white blob.
    private val ICON_ALERT    = com.bipss.app.R.drawable.ic_stat_bipss
    private val ICON_REMINDER = com.bipss.app.R.drawable.ic_stat_bipss
    private val ICON_COACHING = com.bipss.app.R.drawable.ic_stat_bipss

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
            CH_ALERTS, AppText.text(R.string.copy_bac9339bf154, "Disaster & health alerts"), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = AppText.text(R.string.copy_dd6408d1d33e, "Cyclone, flood, heatwave and air quality warnings for your area.")
        }

        val coaching = NotificationChannel(
            CH_COACHING, AppText.text(R.string.copy_183b639e79a0, "Daily coaching"), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = AppText.text(R.string.copy_a76f2a3c0c37, "Readiness, recovery and sleep guidance based on your own trends.")
        }

        val reminders = NotificationChannel(
            CH_REMINDER, AppText.text(R.string.copy_7971dd752669, "Reminders"), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = AppText.text(R.string.copy_94b6e43ef3c2, "Gentle nudges to wear the band, log a reading or check in.")
        }

        val service = NotificationChannel(
            CH_SERVICE, AppText.text(R.string.copy_96929b66956d, "Band connection"), NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = AppText.text(R.string.copy_f50ab4911a57, "The quiet ongoing notice Android requires while the band stays connected.")
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
     * RULE: counting language only. This wording must never name or grade a condition,
     * and never imply one has been identified. It reports
     * the count of events observed and how far the collection window has got, nothing more
     * - interpreting that belongs to a clinician, not to a wrist sensor.
     */
    fun showBreathingSummary(
        context:        Context,
        eventCount:     Int,
        nightsCollected: Int,
        nightsRequired: Int
    ) {
        val body = AppText.text(R.string.copy_483048335267, "%1\$s oxygen dips counted last night. ", eventCount) +
                   AppText.text(R.string.copy_f2a6991c77c7, "%1\$s of %2\$s nights collected.", nightsCollected, nightsRequired)

        val notification = NotificationCompat.Builder(context, CH_COACHING)
            .setSmallIcon(ICON_COACHING)
            .setContentTitle(AppText.text(R.string.copy_4795a7408610, "Overnight oxygen summary"))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(deepLink(context, NAV_SLEEP, NotificationIds.BREATHING_SUMMARY))
            .build()

        safeNotify(context, NotificationIds.BREATHING_SUMMARY, notification)
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
        val text = if (com.bipss.app.SimulationMode.isActive(context)) {
            AppText.text(R.string.copy_b96546997330, "Simulated band - not real data.")
        } else if (connected) {
            AppText.text(R.string.copy_acd39b45e01c, "Band connected. Readings are being recorded.")
        } else {
            AppText.text(R.string.copy_13bd1c9bd30e, "Band not connected. Waiting to reconnect.")
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

    /** Refreshes the band-connection notice through the permission-safe path. */
    fun updateServiceNotification(context: Context, connected: Boolean) {
        safeNotify(context, NotificationIds.SERVICE, buildServiceNotification(context, connected))
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
        // Alerts computed from simulated data must not look like real ones.
        if (id != NotificationIds.SERVICE && com.bipss.app.SimulationMode.isActive(context)) {
            Log.i(TAG, "Notification $id suppressed in simulation mode")
            return
        }
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
    private fun deepLink(
        context: Context,
        destination: com.bipss.app.navigation.DeepLink.Target,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY)
            putExtra(com.bipss.app.navigation.DeepLink.EXTRA_NAVIGATE_TO, destination.key)
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
