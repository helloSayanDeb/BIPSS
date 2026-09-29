package com.bipss.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.Gender
import com.bipss.app.data.UserPreferencesRepository
import com.bipss.app.data.UserProfile
import com.bipss.app.data.repository.HealthConnectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingProfileUiState(
    val name:        String  = "",
    val age:         String  = "",
    val height:      String  = "",
    val weight:      String  = "",
    val gender:      Gender? = null,
    val nameError:   String? = null,
    val ageError:    String? = null,
    val heightError: String? = null,
    val weightError: String? = null,
    val isSaving:    Boolean = false,
    val healthConnectGranted: Boolean = false
) {
    val isFormValid: Boolean
        get() = name.isNotBlank() && nameError == null &&
                ageError == null && heightError == null && weightError == null &&
                age.isNotBlank() && height.isNotBlank() && weight.isNotBlank() &&
                gender != null
}

class OnboardingProfileViewModel(
    private val repository:         UserPreferencesRepository,
    private val healthConnectRepo:  HealthConnectRepository
) : ViewModel() {

    /** Whether Health Connect is on this phone at all (the edit screen offers it only then). */
    val healthConnectAvailable: Boolean get() = healthConnectRepo.isAvailable()
    fun healthConnectPermissionContract() = healthConnectRepo.permissionRequestContract()
    val healthConnectPermissions: Set<String> get() = HealthConnectRepository.PERMISSIONS

    private val _uiState = MutableStateFlow(OnboardingProfileUiState())
    val uiState: StateFlow<OnboardingProfileUiState> = _uiState.asStateFlow()

    fun loadExistingProfile() {
        viewModelScope.launch {
            refreshHealthConnectNow()
            val profile = repository.userProfile.first() ?: return@launch
            _uiState.update {
                it.copy(
                    name   = profile.name,
                    age    = profile.age.toString(),
                    height = profile.heightCm.toInt().toString(),
                    weight = profile.weightKg.toString(),
                    gender = profile.gender
                )
            }
        }
    }

    fun refreshHealthConnect() { viewModelScope.launch { refreshHealthConnectNow() } }

    private suspend fun refreshHealthConnectNow() {
        val granted = healthConnectAvailable && runCatching { healthConnectRepo.hasAllPermissions() }.getOrDefault(false)
        _uiState.update { it.copy(healthConnectGranted = granted) }
    }

    fun onNameChange(v: String) {
        _uiState.update {
            it.copy(name = v, nameError = if (v.isBlank()) "Name cannot be empty" else null)
        }
    }

    fun onAgeChange(v: String) {
        val age = v.toIntOrNull()
        _uiState.update {
            it.copy(
                age      = v,
                ageError = when {
                    v.isBlank()          -> "Required"
                    age == null          -> "Must be a number"
                    age !in 1..120       -> "Age must be 1–120"
                    else                 -> null
                }
            )
        }
    }

    fun onHeightChange(v: String) {
        val h = v.toFloatOrNull()
        _uiState.update {
            it.copy(
                height      = v,
                heightError = when {
                    v.isBlank()          -> "Required"
                    h == null            -> "Must be a number"
                    h !in 50f..250f      -> "Height must be 50–250 cm"
                    else                 -> null
                }
            )
        }
    }

    fun onWeightChange(v: String) {
        val w = v.toFloatOrNull()
        _uiState.update {
            it.copy(
                weight      = v,
                weightError = when {
                    v.isBlank()          -> "Required"
                    w == null            -> "Must be a number"
                    w !in 20f..300f      -> "Weight must be 20–300 kg"
                    else                 -> null
                }
            )
        }
    }

    fun onGenderChange(g: Gender) { _uiState.update { it.copy(gender = g) } }

    /** Saves the profile, then [onSuccess] navigates (onward in setup, or back in edit mode). */
    fun onSave(onSuccess: () -> Unit) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            val profile = UserProfile(
                name     = state.name.trim(),
                age      = state.age.toInt(),
                heightCm = state.height.toFloat(),
                weightKg = state.weight.toFloat(),
                gender   = state.gender
            )
            repository.saveUserProfile(profile)
            // No-op unless the user allowed Health Connect (from the edit screen).
            healthConnectRepo.writeProfile(profile.heightCm, profile.weightKg)
            _uiState.update { it.copy(isSaving = false) }
            onSuccess()
        }
    }
}
