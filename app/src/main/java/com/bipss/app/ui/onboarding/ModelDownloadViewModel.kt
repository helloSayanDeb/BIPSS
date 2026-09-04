package com.bipss.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ai.ChatbotEngine
import com.bipss.app.ai.DownloadProgress
import com.bipss.app.ai.ModelDownloadManager
import com.bipss.app.data.UserPreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ModelDownloadUiState(
    val progress: DownloadProgress = DownloadProgress.Idle
)

class ModelDownloadViewModel(
    private val modelDownloadManager: ModelDownloadManager,
    private val chatbotEngine:        ChatbotEngine,
    private val prefsRepo:            UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelDownloadUiState())
    val uiState: StateFlow<ModelDownloadUiState> = _uiState.asStateFlow()

    init {
        // Check if model is already installed
        viewModelScope.launch {
            val installed = prefsRepo.installedModelVersion.first()
            if (installed != null) {
                _uiState.update { it.copy(progress = DownloadProgress.AlreadyUpToDate) }
            }
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            modelDownloadManager.downloadModelIfNeeded().collect { progress ->
                _uiState.update { it.copy(progress = progress) }
                if (progress is DownloadProgress.Success) {
                    // Initialize chatbot engine with downloaded model
                    val path = prefsRepo.installedModelFilePath.first()
                    if (path != null) chatbotEngine.initialize(path)
                }
            }
        }
    }

    fun completeOnboarding(navigate: () -> Unit) {
        viewModelScope.launch {
            prefsRepo.setOnboardingComplete()
            navigate()
        }
    }
}
