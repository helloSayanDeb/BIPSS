package com.bipss.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bipss.app.R
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.WaterRepository
import com.bipss.app.domain.DailyWellness
import com.bipss.app.localization.AppText
import com.bipss.app.navigation.DeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The home-screen water log: today's total against the goal, and buttons to
 * add a drink or take one back without opening the app. A button press is a
 * broadcast to this provider, which writes to the same water log the app
 * reads, then redraws every copy of the widget.
 */
class WaterWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch { try { render(context) } finally { pending.finish() } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ADD -> act(context) { it.log(intent.getIntExtra(EXTRA_ML, 250)) }
            ACTION_UNDO -> act(context) { it.subtractToday() }
            else -> super.onReceive(context, intent)
        }
    }

    private fun act(context: Context, block: suspend (WaterRepository) -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { block(GlobalContext.get().get<WaterRepository>()) }
                render(context)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_ADD = "com.bipss.app.widget.WATER_ADD"
        const val ACTION_UNDO = "com.bipss.app.widget.WATER_UNDO"
        const val EXTRA_ML = "ml"
        private val AMOUNTS = intArrayOf(150, 250, 500)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every copy of the widget from the current water log. */
        fun refresh(context: Context) {
            scope.launch { render(context) }
        }

        private fun broadcast(context: Context, action: String, ml: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, ml + if (action == ACTION_UNDO) 10_000 else 0,
                Intent(context, WaterWidgetProvider::class.java).setAction(action).putExtra(EXTRA_ML, ml),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 1,
                Intent().setClassName(context.packageName, "com.bipss.app.MainActivity")
                    .putExtra(DeepLink.EXTRA_NAVIGATE_TO, DeepLink.Target.HYDRATION.key)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        private suspend fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WaterWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val koin = GlobalContext.get()
            val totalMl = runCatching { koin.get<WaterRepository>().todayTotals.first().totalMl }.getOrDefault(0)
            val prefs = koin.get<UserPreferencesRepository>()
            val goal = prefs.waterGoalMl.first()
                ?: prefs.userProfile.first()?.let { DailyWellness.waterStartingGoal(it.weightKg, it.age) }

            val views = RemoteViews(context.packageName, R.layout.widget_water)
            views.setTextViewText(R.id.widget_title, AppText.text(R.string.copy_7ca7dea90680, "Water"))
            views.setTextViewText(R.id.widget_total, if (goal != null)
                AppText.text(R.string.copy_db896ea3ada1, "%1\$s / %2\$s mL", totalMl, goal)
            else AppText.text(R.string.copy_e52749128c70, "%1\$s mL", totalMl))
            views.setProgressBar(R.id.widget_progress, 100, if (goal != null && goal > 0) (totalMl * 100 / goal).coerceIn(0, 100) else 0, false)
            val buttons = intArrayOf(R.id.widget_add_small, R.id.widget_add_medium, R.id.widget_add_large)
            AMOUNTS.forEachIndexed { i, ml ->
                views.setTextViewText(buttons[i], "+$ml")
                views.setContentDescription(buttons[i], AppText.text(R.string.copy_d43fdce41454, "Add %1\$s millilitres", ml))
                views.setOnClickPendingIntent(buttons[i], broadcast(context, ACTION_ADD, ml))
            }
            views.setTextViewText(R.id.widget_undo, "−250")
            views.setContentDescription(R.id.widget_undo, AppText.text(R.string.copy_f8e9cdf007a8, "Take back the last 250 millilitres"))
            views.setOnClickPendingIntent(R.id.widget_undo, broadcast(context, ACTION_UNDO, 250))
            views.setOnClickPendingIntent(R.id.widget_header, openApp(context))
            manager.updateAppWidget(ids, views)
        }
    }
}
