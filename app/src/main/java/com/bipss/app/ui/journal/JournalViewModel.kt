package com.bipss.app.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.R
import com.bipss.app.data.JournalRepository
import com.bipss.app.domain.journal.Habit
import com.bipss.app.domain.journal.HabitEffect
import com.bipss.app.domain.time.LocalDay
import com.bipss.app.localization.AppText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(private val repo: JournalRepository) : ViewModel() {
    private val today = LocalDay.epochDay(System.currentTimeMillis())

    /** 0 is today, 1 is yesterday. */
    val daysBack = MutableStateFlow(0)

    val answers: StateFlow<Map<Habit, Boolean>> = daysBack.flatMapLatest { repo.answers(today - it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val effects: StateFlow<List<HabitEffect>> = repo.effects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(back: Int) { daysBack.value = back }

    /** Tapping the answer already given takes it back. */
    fun answer(habit: Habit, yes: Boolean) {
        viewModelScope.launch {
            val current = answers.value[habit]
            repo.set(today - daysBack.value, habit, if (current == yes) null else yes)
        }
    }
}

fun questionText(h: Habit): String = when (h) {
    Habit.CAFFEINE -> AppText.text(R.string.copy_391136a66bb7, "Caffeine after 2 pm?")
    Habit.ALCOHOL -> AppText.text(R.string.copy_e69ede86fafb, "Alcohol?")
    Habit.LATE_MEAL -> AppText.text(R.string.copy_2cd64ad7bd0f, "A meal within 3 hours of bed?")
    Habit.SCREENS -> AppText.text(R.string.copy_90e4b62339a1, "Screens in bed?")
    Habit.STRESS -> AppText.text(R.string.copy_24dd1d2a6ffb, "A stressful day?")
    Habit.MEDITATION -> AppText.text(R.string.copy_c2259646bb68, "Meditation or slow breathing?")
}

fun habitName(h: Habit): String = when (h) {
    Habit.CAFFEINE -> AppText.text(R.string.copy_a7bd90afd475, "Late caffeine")
    Habit.ALCOHOL -> AppText.text(R.string.copy_670a802ebaec, "Alcohol")
    Habit.LATE_MEAL -> AppText.text(R.string.copy_74924b1e2c55, "Late meals")
    Habit.SCREENS -> AppText.text(R.string.copy_bb55848b0245, "Screens in bed")
    Habit.STRESS -> AppText.text(R.string.copy_2a3abee29252, "Stressful days")
    Habit.MEDITATION -> AppText.text(R.string.copy_a6d94ceab2c8, "Mindful minutes")
}
