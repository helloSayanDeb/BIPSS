package com.bipss.app.ui.activity

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.*
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ActivityUi(val today: Long = LocalDay.epochDay(System.currentTimeMillis()),
    val data: DailyActivityData = DailyActivityData(), val profile: UserProfile? = null) {
    val todaySteps: Int? get() = data.bandSteps[today]
    val weekMinutes: Int get() = data.activities.filter { LocalDay.epochDay(it.atMs) in (today - 6)..today }.sumOf { it.minutes }
}
class ActivityViewModel(private val repo: DailyActivityRepository, private val prefs: UserPreferencesRepository, private val appScope: kotlinx.coroutines.CoroutineScope) : ViewModel() {
    val ui = combine(repo.data, prefs.userProfile, LocalDay.midnightTicker()) { data, profile, today ->
        ActivityUi(today, data, profile)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActivityUi())
    val error = MutableStateFlow<String?>(null)
    private fun save(block: suspend () -> Unit) { appScope.launch {
        try { block(); error.value = null } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { error.value = AppText.text(R.string.copy_d0440ada2268, "Couldn't save that change. Please try again.") }
    } }
    fun goal(value: Int) = save { repo.setGoal(value) }
    fun log(name: String, minutes: Int, note: String) = save { repo.logActivity(name, minutes, note) }
    fun delete(id: String) = save { repo.deleteActivity(id) }
    fun tracking(enabled: Boolean, then: () -> Unit) = save { repo.setTracking(enabled); then() }
    fun sleepTimes(bed: Int, wake: Int, onSaved: () -> Unit = {}) = save { repo.setSleepTimes(bed, wake); onSaved() }
    fun nutrition(enabled: Boolean) = save { repo.enableNutrition(enabled) }
    fun food(name: String, kcal: Int?, protein: Int?) = save { repo.logFood(name, kcal, protein) }
    fun deleteFood(id: String) = save { repo.deleteFood(id) }
    fun startWorkout(name: String) = save { repo.startWorkout(name) }
    fun finishWorkout() = save { repo.finishWorkout() }
    fun cancelWorkout() = save { repo.cancelWorkout() }
}
