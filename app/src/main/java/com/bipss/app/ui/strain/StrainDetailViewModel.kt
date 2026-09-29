package com.bipss.app.ui.strain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.ScoringRepository
import com.bipss.app.data.StrainOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StrainDetailViewModel(
    private val scoring: ScoringRepository
) : ViewModel() {

    data class UiState(
        val outcome: StrainOutcome? = null,
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = UiState(outcome = scoring.strainForDay(), isLoading = false)
        }
    }
}
