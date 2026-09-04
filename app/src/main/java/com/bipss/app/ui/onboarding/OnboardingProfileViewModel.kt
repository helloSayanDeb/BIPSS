package com.bipss.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val nameError:   String? = null,
    val ageError:    String? = null,
    val heightError: String? = null,
    val weightError: String? = null,
    val isSaving:    Boolean = false
) {
    val isFormValid: Boolean
        get() = name.isNotBlank() && nameError == null &&
                ageError == null && heightError == null && weightError == null &&
                age.isNotBlank() && height.isNotBlank() && weight.isNotBlank()
}

class OnboardingProfileViewModel(
    private val repository:         UserPreferencesRepository,
    private val healthConnectRepo:  HealthConnectRepository
) : ViewModel() {

    /** Whether the Health Connect permission request should be launched (see the screen). */
    val healthConnectAvailable: Boolean get() = healthConnectRepo.isAvailable()
    fun healthConnectPermissionContract() = healthConnectRepo.permissionRequestContract()
    val healthConnectPermissions: Set<String> get() = HealthConnectRepository.PERMISSIONS

    private val _uiState = MutableStateFlow(OnboardingProfileUiState())
    val uiState: StateFlow<OnboardingProfileUiState> = _uiState.asStateFlow()

    fun loadExistingProfile() {
        viewModelScope.launch {
            val profile = repository.userProfile.first() ?: return@launch
            _uiState.update {
                it.copy(
                    name   = profile.name,
                    age    = profile.age.toString(),
                    height = profile.heightCm.toInt().toString(),
                    weight = profile.weightKg.toString()
                )
            }
        }
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

    fun onContinue(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            val profile = UserProfile(
                name     = state.name.trim(),
                age      = state.age.toInt(),
                heightCm = state.height.toFloat(),
                weightKg = state.weight.toFloat()
            )
            repository.saveUserProfile(profile)
            healthConnectRepo.writeProfile(profile.heightCm, profile.weightKg)
            _uiState.update { it.copy(isSaving = false) }
            onSuccess()
        }
    }

    fun onSaveEdited(onSuccess: () -> Unit) {
        onContinue(onSuccess)  // same logic; navigation target differs (handled in screen)
    }
}
