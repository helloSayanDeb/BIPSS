package com.bipss.app.ui.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.ScoringRepository
import com.bipss.app.domain.recovery.RecoveryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Last night's recovery, as [ScoringRepository] computed it. Deliberately not
 * driven by live readings: recovery describes a night, and a heart rate taken
 * while walking to the kitchen says nothing about it.
 */
class RecoveryDetailViewModel(
    private val scoring: ScoringRepository
) : ViewModel() {

    data class UiState(
        val result: RecoveryResult? = null,
        val dayN: Int = 0,
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = UiState(result = scoring.latestRecovery(), dayN = scoring.dayN(), isLoading = false)
        }
    }
}
