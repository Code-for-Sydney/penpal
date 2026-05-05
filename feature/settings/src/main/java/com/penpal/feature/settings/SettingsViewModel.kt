package com.penpal.feature.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.ai.ModelManager
import com.penpal.core.ai.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings UI State
 */
data class SettingsUiState(
    val modelName: String = "gemma-4-E2B-it",
    val modelFileName: String = "gemma-4-E2B-it.litertlm",
    val modelStatus: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val downloadProgressText: String = "",
    val isDownloading: Boolean = false,
    val isLoading: Boolean = false,
    val inferenceMode: InferenceMode = InferenceMode.ON_DEVICE,
    val maxTokens: Int = 4096,
    val temperature: Float = 0.7f,
    val showDeleteConfirmation: Boolean = false,
    val showDownloadDialog: Boolean = false,
    val appVersion: String = "1.0.0",
    val error: String? = null,
    val message: String? = null,
    val availableModels: List<ModelManager.ModelInfo> = emptyList(),
    val isLoadingModels: Boolean = false
)

enum class InferenceMode {
    ON_DEVICE,
    CLOUD,
    HYBRID
}

/**
 * Settings ViewModel with LiteRT-LM and ModelManager integration
 */
class SettingsViewModel(
    private val application: Application,
    private val inferenceBridge: InferenceBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            // Check if model already exists
            val existingModel = ModelManager.findExistingModel(application)
            if (existingModel != null) {
                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.DOWNLOADED,
                        isLoading = false
                    )
                }
                // Initialize the model
                initializeModel()
            } else {
                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.NOT_DOWNLOADED,
                        isLoading = false
                    )
                }
            }

            // Observe model status changes
            inferenceBridge.modelStatus.collect { status ->
                _uiState.update { it.copy(modelStatus = status) }
            }
        }
    }

    private fun initializeModel() {
        viewModelScope.launch {
            inferenceBridge.initialize(application, _uiState.value.modelName) { result ->
                _uiState.update { it.copy(message = result) }
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.DownloadModel -> startDownload()
            is SettingsEvent.DeleteModel -> deleteModel()
            is SettingsEvent.ToggleInferenceMode -> toggleInferenceMode()
            is SettingsEvent.UpdateMaxTokens -> updateMaxTokens(event.value)
            is SettingsEvent.UpdateTemperature -> updateTemperature(event.value)
            is SettingsEvent.ShowDeleteConfirmation -> showDeleteConfirmation()
            is SettingsEvent.DismissDeleteConfirmation -> dismissDeleteConfirmation()
            is SettingsEvent.DismissError -> dismissError()
            is SettingsEvent.ShowDownloadDialog -> showDownloadDialog()
            is SettingsEvent.HideDownloadDialog -> hideDownloadDialog()
            is SettingsEvent.StartHfDownload -> startHfDownload(event.token)
            is SettingsEvent.StartKaggleDownload -> startKaggleDownload(event.username, event.apiKey)
            is SettingsEvent.RefreshModelList -> refreshModelList()
            is SettingsEvent.SelectModel -> selectModel(event.modelPath)
            is SettingsEvent.DeleteSpecificModel -> deleteSpecificModel(event.modelPath)
        }
    }

    private fun showDownloadDialog() {
        _uiState.update { it.copy(showDownloadDialog = true) }
    }

    private fun hideDownloadDialog() {
        _uiState.update { it.copy(showDownloadDialog = false) }
    }

    private fun startDownload() {
        // Show the download dialog for token input
        showDownloadDialog()
    }

    private fun startHfDownload(token: String) {
        hideDownloadDialog()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    downloadProgressText = "Connecting to HuggingFace..."
                )
            }

            try {
                ModelManager.startDownloadHFAsync(
                    context = application,
                    hfToken = token,
                    onSuccess = { downloadId ->
                        // Start polling for progress
                        pollDownloadProgress(downloadId)
                    },
                    onError = { error ->
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                error = error
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        error = e.message ?: "Download failed"
                    )
                }
            }
        }
    }

    private fun startKaggleDownload(username: String, apiKey: String) {
        hideDownloadDialog()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    downloadProgressText = "Connecting to Kaggle..."
                )
            }

            try {
                ModelManager.startDownloadKaggleAsync(
                    context = application,
                    username = username,
                    apiKey = apiKey,
                    onSuccess = { downloadId ->
                        pollDownloadProgress(downloadId)
                    },
                    onError = { error ->
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                error = error
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isDownloading = false,
                        error = e.message ?: "Download failed"
                    )
                }
            }
        }
    }

    private fun pollDownloadProgress(downloadId: Long) {
        viewModelScope.launch {
            var lastProgress = 0
            while (_uiState.value.isDownloading) {
                val status = ModelManager.queryDownload(application, downloadId)

when (status.state) {
                    ModelManager.DownloadState.RUNNING -> {
                        val progress = status.progressPercent
                        if (progress != lastProgress) {
                            _uiState.update {
                                it.copy(
                                    downloadProgress = progress / 100f,
                                    downloadProgressText = status.progressDisplay
                                )
                            }
                            lastProgress = progress
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                    ModelManager.DownloadState.PAUSED -> {
                        val progress = status.progressPercent
                        if (progress != lastProgress) {
                            _uiState.update {
                                it.copy(
                                    downloadProgress = progress / 100f,
                                    downloadProgressText = status.progressDisplay
                                )
                            }
                            lastProgress = progress
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                    ModelManager.DownloadState.DONE -> {
                        // Model downloaded successfully
                        val modelPath = ModelManager.modelFile(application).absolutePath
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = 1f,
                                downloadProgressText = "Download complete!",
                                modelStatus = ModelStatus.DOWNLOADED,
                                message = "Model downloaded. Initializing..."
                            )
                        }
                        // Initialize the model
                        initializeModel()
                        return@launch
                    }
                    ModelManager.DownloadState.FAILED -> {
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                error = "Download failed. Check your connection and try again.",
                                modelStatus = ModelStatus.ERROR
                            )
                        }
                        return@launch
                    }
                    else -> {
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
        }
    }

    private fun deleteModel() {
        viewModelScope.launch {
            try {
                // Delete the model file
                val modelFile = ModelManager.modelFile(application)
                if (modelFile.exists()) {
                    modelFile.delete()
                }
                ModelManager.clearModelPath(application)

                inferenceBridge.deleteModel()

                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.NOT_DOWNLOADED,
                        showDeleteConfirmation = false,
                        message = "Model deleted"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Failed to delete model")
                }
            }
        }
    }

    private fun toggleInferenceMode() {
        _uiState.update { state ->
            val nextMode = when (state.inferenceMode) {
                InferenceMode.ON_DEVICE -> InferenceMode.CLOUD
                InferenceMode.CLOUD -> InferenceMode.HYBRID
                InferenceMode.HYBRID -> InferenceMode.ON_DEVICE
            }
            state.copy(inferenceMode = nextMode)
        }
    }

    private fun updateMaxTokens(value: Int) {
        _uiState.update { it.copy(maxTokens = value.coerceIn(256, 8192)) }
    }

    private fun updateTemperature(value: Float) {
        _uiState.update { it.copy(temperature = value.coerceIn(0f, 2f)) }
    }

    private fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    private fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    private fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun refreshModelList() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true) }
            try {
                val models = inferenceBridge.listAvailableModels(application)
                _uiState.update { it.copy(availableModels = models, isLoadingModels = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingModels = false, error = "Failed to list models: ${e.message}") }
            }
        }
    }

    private fun selectModel(modelPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "Loading model...") }
            inferenceBridge.loadModel(
                context = application,
                modelPath = modelPath,
                onDone = { message ->
                    val success = message.startsWith("Model loaded")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            modelStatus = if (success) ModelStatus.DOWNLOADED else ModelStatus.ERROR,
                            message = message,
                            error = if (success) null else message
                        )
                    }
                    if (success) refreshModelList()
                }
            )
        }
    }

    private fun deleteSpecificModel(modelPath: String) {
        viewModelScope.launch {
            inferenceBridge.deleteModel(modelPath)
            refreshModelList()
            _uiState.update {
                it.copy(message = "Model deleted")
            }
        }
    }
}

/**
 * Settings Events
 */
sealed class SettingsEvent {
    data object DownloadModel : SettingsEvent()
    data object DeleteModel : SettingsEvent()
    data object ToggleInferenceMode : SettingsEvent()
    data class UpdateMaxTokens(val value: Int) : SettingsEvent()
    data class UpdateTemperature(val value: Float) : SettingsEvent()
    data object ShowDeleteConfirmation : SettingsEvent()
    data object DismissDeleteConfirmation : SettingsEvent()
    data object DismissError : SettingsEvent()
    data object ShowDownloadDialog : SettingsEvent()
    data object HideDownloadDialog : SettingsEvent()
    data class StartHfDownload(val token: String) : SettingsEvent()
    data class StartKaggleDownload(val username: String, val apiKey: String) : SettingsEvent()
    data object RefreshModelList : SettingsEvent()
    data class SelectModel(val modelPath: String) : SettingsEvent()
    data class DeleteSpecificModel(val modelPath: String) : SettingsEvent()
}