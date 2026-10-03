package com.bipss.app.ui.onboarding

import com.bipss.app.localization.AppText
import com.bipss.app.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.ai.ChatbotEngine
import com.bipss.app.ai.DownloadProgress
import com.bipss.app.ai.ModelDownloadManager
import com.bipss.app.ai.isModelDownloadConfigured
import com.bipss.app.data.UserPreferencesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ModelDownloadUiState(
    /** False in a build with no model manifest configured: there is nothing to download. */
    val configured: Boolean = isModelDownloadConfigured,
    val progress: DownloadProgress = DownloadProgress.Idle,
    val checking: Boolean = false,
    /** From the real manifest, fetched only after the user asked. Null until then. */
    val manifestSizeMb: Int? = null,
    val manifestCheckFailed: Boolean = false,
)

/**
 * Step 4. Nothing touches the network until the user taps: the first tap
 * reads the manifest so the real download size can be shown, the second
 * starts the download.
 */
class ModelDownloadViewModel(
    private val modelDownloadManager: ModelDownloadManager,
    private val chatbotEngine:        ChatbotEngine,
    private val prefsRepo:            UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelDownloadUiState())
    val uiState: StateFlow<ModelDownloadUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            // Local only: a model installed earlier (e.g. before a catch-up) is just shown as ready.
            if (chatbotEngine.isModelAvailable) {
                _uiState.update { it.copy(progress = DownloadProgress.AlreadyUpToDate) }
            }
        }
    }

    fun checkSize() {
        if (_uiState.value.checking) return
        viewModelScope.launch {
            _uiState.update { it.copy(checking = true, manifestCheckFailed = false) }
            val manifest = modelDownloadManager.fetchManifestPublic()
            _uiState.update {
                it.copy(
                    checking = false,
                    manifestSizeMb = manifest?.let { m -> (m.sizeBytes / 1_048_576L).toInt() },
                    manifestCheckFailed = manifest == null
                )
            }
        }
    }

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            modelDownloadManager.downloadModelIfNeeded().collect { progress ->
                if (progress is DownloadProgress.Success || progress is DownloadProgress.AlreadyUpToDate) {
                    _uiState.update { it.copy(progress = DownloadProgress.Preparing) }
                    val path = prefsRepo.installedModelFilePath.first()
                    if (path != null) chatbotEngine.initialize(path)
                    _uiState.update { it.copy(progress = if (chatbotEngine.isModelAvailable) DownloadProgress.Success
                        else DownloadProgress.Failure(AppText.text(R.string.copy_2ad6b0c5231f, "This download couldn't run on this phone. You can still use Coach."))) }
                } else _uiState.update { it.copy(progress = progress) }
            }
        }
    }

    /**
     * Stops a download in progress. The partial file is kept, and a later
     * download from Settings resumes from it.
     */
    fun stopDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.update { it.copy(progress = DownloadProgress.Idle) }
    }
}
