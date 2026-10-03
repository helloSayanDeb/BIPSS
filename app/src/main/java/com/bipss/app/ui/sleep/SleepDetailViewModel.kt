package com.bipss.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.SleepRepository
import com.bipss.app.data.db.entity.SleepSessionEntity
import com.bipss.app.domain.sleep.DesaturationScreening
import com.bipss.app.domain.sleep.NightScreening
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the sleep screen, including the overnight oxygen-dip count.
 *
 * [summary] is null until [DesaturationScreening.NIGHTS_REQUIRED] nights exist. That
 * is the point: the screen shows the machinery and the individual nights from
 * night one, and withholds the aggregate statement until the data can support
 * it, rather than issuing a confident verdict off two nights.
 */
class SleepDetailViewModel(
    private val sleepRepo: SleepRepository,
    private val exporter: com.bipss.app.export.DataExporter
) : ViewModel() {

    data class UiState(
        val sessions:      List<SleepSessionEntity> = emptyList(),
        val screenings:    List<NightScreening> = emptyList(),
        val selectedIndex: Int = 0,
        val summary:       String? = null,
        val lastNightScore: Int? = null,
        val isLoading:     Boolean = true
    ) {
        val nightsCollected: Int get() = screenings.size
        val nightsRequired:  Int get() = DesaturationScreening.NIGHTS_REQUIRED
        val progress: Float get() = DesaturationScreening.progress(nightsCollected)
        val selected: NightScreening? get() = screenings.getOrNull(selectedIndex)
        val hasAnyNight: Boolean get() = screenings.isNotEmpty()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val screenings = sleepRepo.nightlyScreenings()
            _uiState.update {
                it.copy(
                    sessions       = sleepRepo.sessionsNewestLast(),
                    screenings     = screenings,
                    // Open on the most recent night, which is what anyone
                    // checking in the morning actually wants to see.
                    selectedIndex  = (screenings.size - 1).coerceAtLeast(0),
                    summary        = DesaturationScreening.screeningSummary(screenings),
                    lastNightScore = sleepRepo.lastNightScore(),
                    isLoading      = false
                )
            }
        }
    }

    fun selectPrevious() = _uiState.update {
        it.copy(selectedIndex = (it.selectedIndex - 1).coerceAtLeast(0))
    }

    fun selectNext() = _uiState.update {
        it.copy(selectedIndex = (it.selectedIndex + 1).coerceAtMost(it.screenings.lastIndex.coerceAtLeast(0)))
    }

    /** Night-by-night PDF a user can hand to a clinician, as a share sheet. */
    suspend fun physicianReportShare(): android.content.Intent =
        exporter.shareIntent(exporter.breathingRecordPdf(), "application/pdf")
}
