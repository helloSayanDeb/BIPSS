package com.bipss.app.ui.hydration

import com.bipss.app.localization.AppText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.WaterRepository
import com.bipss.app.data.db.entity.WaterLogEntity
import com.bipss.app.domain.WaterDay
import com.bipss.app.domain.waterHistory
import com.bipss.app.domain.time.LocalDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HydrationUi(
    val today: Long = LocalDay.epochDay(System.currentTimeMillis()),
    val entries: List<WaterLogEntity> = emptyList(),
    val history: List<WaterDay> = emptyList(),
    val goalMl: Int? = null,
    val loaded: Boolean = false,
    val suggestedGoal: Boolean = false
) {
    val totalMl: Int get() = entries.sumOf { it.amountMl }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HydrationViewModel(
    private val repo: WaterRepository,
    private val prefs: UserPreferencesRepository,
    private val appScope: CoroutineScope
) : ViewModel() {
    val ui = LocalDay.midnightTicker().flatMapLatest { today ->
        combine(repo.entriesBetween(LocalDay.startOfDayMs(today - 6), LocalDay.startOfDayMs(today + 1)), prefs.waterGoalMl, prefs.userProfile) { entries, goal, profile ->
            val suggested = profile?.let { com.bipss.app.domain.DailyWellness.waterStartingGoal(it.weightKg, it.age) }
            HydrationUi(today, entries.filter { LocalDay.epochDay(it.timestampMs) == today },
                waterHistory(entries, today), goal ?: suggested, loaded = true, suggestedGoal = goal == null && suggested != null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HydrationUi())

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun add() = save { repo.log(250) }
    fun subtract() = save { repo.subtractToday() }
    fun remove(id: Long) = save { repo.remove(id) }
    fun setGoal(amountMl: Int) = save { prefs.setWaterGoalMl(amountMl) }

    // Finish a tap even when the user immediately leaves the screen.
    private fun save(block: suspend () -> Unit) {
        appScope.launch {
            _error.value = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _error.value = AppText.label("Couldn't save that change. Please try again.") }
        }
    }
}
