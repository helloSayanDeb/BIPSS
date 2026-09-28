package com.bipss.app.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.SensorRepository
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.domain.recovery.RecoveryBreakdown
import com.bipss.app.domain.recovery.RecoveryCalculator
import com.bipss.app.domain.recovery.RecoveryMetrics
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecoveryDetailViewModel(
    private val sensorRepo: SensorRepository,
    private val prefsRepo: UserPreferencesRepository
) : ViewModel() {

    data class UiState(
        val breakdown: RecoveryBreakdown? = null,
        val heartRate: Int? = null,
        val prvRmssd: Int? = null,
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        observeSensors()
        refresh()
    }

    private fun observeSensors() {
        viewModelScope.launch {
            sensorRepo.sensorEvents.collect { reading ->
                _uiState.update {
                    it.copy(
                        heartRate = reading.heartRate ?: it.heartRate,
                        prvRmssd = reading.bandPrvRmssdMs ?: it.prvRmssd
                    )
                }
                refresh()
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val baseStats = sensorRepo.baselineStatsDao.getByType(MetricTypes.HR)
            val baselineDays = prefsRepo.daysOfData()
            val s = _uiState.value

            val metrics = RecoveryMetrics(
                restingHr = s.heartRate,
                baselineHr = baseStats?.mean,
                prvRmssd = s.prvRmssd,
                baselineDays = baselineDays
            )
            
            _uiState.update { 
                it.copy(
                    breakdown = RecoveryCalculator.compute(metrics),
                    isLoading = false
                ) 
            }
        }
    }
}
