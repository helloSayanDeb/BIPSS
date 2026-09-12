package com.bipss.app.ui.strain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.MetricTypes
import com.bipss.app.data.SensorRepository
import com.bipss.app.domain.strain.StrainBreakdown
import com.bipss.app.domain.strain.StrainCalculator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class StrainDetailViewModel(
    private val sensorRepo: SensorRepository
) : ViewModel() {

    data class UiState(
        val breakdown: StrainBreakdown? = null,
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val startOfToday = c.timeInMillis
            val endOfToday = startOfToday + 86400000L

            val stats = sensorRepo.minuteAggregateDao.getStats(
                MetricTypes.ACCEL_MAG,
                startOfToday / 60000L,
                endOfToday / 60000L
            )
            
            _uiState.update { 
                it.copy(
                    breakdown = StrainCalculator.compute(stats),
                    isLoading = false
                ) 
            }
        }
    }
}
