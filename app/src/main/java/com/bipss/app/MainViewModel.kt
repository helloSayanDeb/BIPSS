package com.bipss.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isLoading:        Boolean = true,
    val startDestination: String  = Routes.ONBOARDING_WELCOME
)

class MainViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferencesRepository.hasCompletedOnboarding.collect { done ->
                _uiState.update {
                    it.copy(
                        isLoading        = false,
                        startDestination = if (done) Routes.DASHBOARD_TODAY
                                           else      Routes.ONBOARDING_WELCOME
                    )
                }
            }
        }
    }
}
