package com.bipss.app.widget

import android.content.Context
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.WaterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps the widget in step with drinks logged inside the app, and rolls it over at midnight. */
class WaterWidgetUpdater(
    private val context: Context,
    private val water: WaterRepository,
    private val prefs: UserPreferencesRepository,
    private val scope: CoroutineScope
) {
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(water.todayTotals, prefs.waterGoalMl) { totals, goal -> totals.totalMl to goal }
                .distinctUntilChanged()
                .collect { WaterWidgetProvider.refresh(context) }
        }
    }
}
