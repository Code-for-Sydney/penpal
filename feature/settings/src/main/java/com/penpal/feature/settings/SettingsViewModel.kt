package com.penpal.feature.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.ai.ModelStatus
import com.penpal.core.ai.OllamaApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings UI State
 */
data class SettingsUiState(
    val modelName: String = "llama3.2:latest",
    val availableModels: List<String> = listOf("llama3.2:latest", "qwen2.5:3b", "gemma2:2b"),
    val modelStatus: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val downloadProgress: Float = 0f,
    val inferenceMode: InferenceMode = InferenceMode.ON_DEVICE,
    val maxTokens: Int = 8192,
    val temperature: Float = 0.7f,
    val isDownloading: Boolean = false,
    val isSimulated: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val appVersion: String = "1.0.0",
    val error: String? = null
)

enum class InferenceMode {
    ON_DEVICE,
    CLOUD,
    HYBRID
}

/**
 * Settings ViewModel
 */
class SettingsViewModel(
    private val application: Application,
    private val inferenceBridge: InferenceBridge
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    private val ollamaApi = OllamaApiService()

    init {
        loadSettings()
        refreshAvailableModels()
        // Observe model status changes
        viewModelScope.launch {
            inferenceBridge.modelStatus.collect { status ->
                _uiState.update { it.copy(modelStatus = status) }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val modelStatus = inferenceBridge.modelStatus.value
            _uiState.update { it.copy(modelStatus = modelStatus) }
        }
    }

    private fun refreshAvailableModels() {
        viewModelScope.launch {
            try {
                val models = ollamaApi.listModels()
                if (models.isNotEmpty()) {
                    _uiState.update { it.copy(availableModels = models.map { m -> m.name }) }
                }
            } catch (e: Exception) {
                // Keep defaults if API fails
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.DownloadModel -> downloadModel()
            is SettingsEvent.DeleteModel -> deleteModel()
            is SettingsEvent.SelectModel -> selectModel(event.name)
            is SettingsEvent.ToggleInferenceMode -> toggleInferenceMode()
            is SettingsEvent.UpdateMaxTokens -> updateMaxTokens(event.value)
            is SettingsEvent.UpdateTemperature -> updateTemperature(event.value)
            is SettingsEvent.ShowDeleteConfirmation -> showDeleteConfirmation()
            is SettingsEvent.DismissDeleteConfirmation -> dismissDeleteConfirmation()
            is SettingsEvent.DismissError -> dismissError()
        }
    }

    private fun selectModel(name: String) {
        _uiState.update { it.copy(modelName = name) }
        viewModelScope.launch {
            inferenceBridge.initialize(application, name) { result ->
                // Initialized
            }
        }
    }

    private fun downloadModel() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f
                )
            }

            try {
                inferenceBridge.downloadModel(object : InferenceBridge.DownloadProgressListener {
                    override fun onProgress(progress: Float) {
                        _uiState.update { it.copy(downloadProgress = progress) }
                    }

                    override fun onComplete() {
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                downloadProgress = 1f,
                                modelStatus = ModelStatus.DOWNLOADED
                            )
                        }
                    }

                    override fun onError(message: String) {
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                error = message
                            )
                        }
                    }
                })
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

    private fun deleteModel() {
        viewModelScope.launch {
            try {
                inferenceBridge.deleteModel()
                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.NOT_DOWNLOADED,
                        showDeleteConfirmation = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
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
}

/**
 * Settings Events
 */
sealed class SettingsEvent {
    data object DownloadModel : SettingsEvent()
    data object DeleteModel : SettingsEvent()
    data class SelectModel(val name: String) : SettingsEvent()
    data object ToggleInferenceMode : SettingsEvent()
    data class UpdateMaxTokens(val value: Int) : SettingsEvent()
    data class UpdateTemperature(val value: Float) : SettingsEvent()
    data object ShowDeleteConfirmation : SettingsEvent()
    data object DismissDeleteConfirmation : SettingsEvent()
    data object DismissError : SettingsEvent()
}