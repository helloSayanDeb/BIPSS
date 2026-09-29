package com.bipss.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.CURRENT_ONBOARDING_VERSION
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.navigation.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

    /**
     * A notification tap waiting to be acted on. Held here (not in the Activity)
     * so it survives recreation, and consumed exactly once so a rotation does
     * not navigate again.
     */
    private val _pendingDeepLink = MutableStateFlow<com.bipss.app.navigation.DeepLink.Target?>(null)
    val pendingDeepLink: StateFlow<com.bipss.app.navigation.DeepLink.Target?> = _pendingDeepLink.asStateFlow()

    fun onDeepLink(target: com.bipss.app.navigation.DeepLink.Target?) {
        if (target != null) _pendingDeepLink.value = target
    }

    fun consumeDeepLink() { _pendingDeepLink.value = null }

    init {
        viewModelScope.launch {
            // A wipe that was interrupted before its result was acknowledged
            // still has to end in onboarding: the profile it needs is gone.
            if (userPreferencesRepository.onboardingResetPending.first()) {
                userPreferencesRepository.resetOnboarding()
            }
            combine(
                userPreferencesRepository.hasCompletedOnboarding,
                userPreferencesRepository.onboardingVersion
            ) { done, version ->
                when {
                    !done -> Routes.ONBOARDING_WELCOME
                    // Finished an earlier onboarding: show only what it never asked.
                    version < CURRENT_ONBOARDING_VERSION -> Routes.ONBOARDING_GENDER
                    else -> Routes.DASHBOARD_TODAY
                }
            }.distinctUntilChanged().collect { start ->
                _uiState.update { it.copy(isLoading = false, startDestination = start) }
            }
        }
    }
}
