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
    val progress: DownloadProgress = DownloadProgress.Idle,
    /** Fetched from the real manifest, not a guess. Null while unknown/unreachable. */
    val manifestSizeMb: Int? = null,
    val manifestCheckFailed: Boolean = false,
)

class ModelDownloadViewModel(
    private val modelDownloadManager: ModelDownloadManager,
    private val chatbotEngine:        ChatbotEngine,
    private val prefsRepo:            UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelDownloadUiState())
    val uiState: StateFlow<ModelDownloadUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val installed = prefsRepo.installedModelVersion.first()
            if (installed != null) {
                _uiState.update { it.copy(progress = DownloadProgress.AlreadyUpToDate) }
            } else {
                // Ask the manifest what it actually weighs before showing any
                // number — no placeholder size is shown if this fails (e.g.
                // MODEL_MANIFEST_URL not yet pointed at a real file).
                val manifest = modelDownloadManager.fetchManifestPublic()
                _uiState.update {
                    it.copy(
                        manifestSizeMb = manifest?.let { m -> (m.sizeBytes / 1_048_576L).toInt() },
                        manifestCheckFailed = manifest == null
                    )
                }
            }
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            modelDownloadManager.downloadModelIfNeeded().collect { progress ->
                _uiState.update { it.copy(progress = progress) }
                if (progress is DownloadProgress.Success) {
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
