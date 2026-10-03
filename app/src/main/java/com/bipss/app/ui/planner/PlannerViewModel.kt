package com.bipss.app.ui.planner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.DailyActivityRepository
import com.bipss.app.data.SleepPlanRepository
import com.bipss.app.data.SleepPlanState
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.worker.BedtimeReminderWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlannerViewModel(
    private val plans: SleepPlanRepository,
    private val daily: DailyActivityRepository,
    private val prefs: UserPreferencesRepository,
    private val context: Context
) : ViewModel() {
    val state: StateFlow<SleepPlanState?> = plans.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val reminder: StateFlow<Boolean> = prefs.bedtimeReminderEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setReminder(on: Boolean) {
        viewModelScope.launch {
            prefs.setBedtimeReminderEnabled(on)
            if (on) BedtimeReminderWorker.reschedule(context) else BedtimeReminderWorker.cancel(context)
        }
    }

    /** Saves the wake time; the bedtime the Home rhythm cards use follows the new plan. */
    fun setWakeTime(wakeMin: Int) {
        viewModelScope.launch {
            val current = plans.current()
            val bed = com.bipss.app.domain.sleep.SleepPlanner
                .plan(current.nights, current.strainUsed, wakeMin).bedtimeMin
            daily.setSleepTimes(bed, wakeMin)
            if (prefs.bedtimeReminderEnabled.first()) BedtimeReminderWorker.reschedule(context)
        }
    }
}
