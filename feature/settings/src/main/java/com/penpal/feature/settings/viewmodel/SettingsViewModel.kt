package com.penpal.feature.settings.viewmodel

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.ai.inference.InferenceBridge
import com.penpal.core.ai.inference.model.ModelStatus
import com.penpal.core.ai.model.GgufConverter
import com.penpal.core.ai.model.GgufModelInfo
import com.penpal.core.ai.model.ConversionState
import com.penpal.core.ai.model.ModelManager
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
    val isLoadingModels: Boolean = false,
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val inactivityTimeoutMinutes: Int = 10,
    val defaultSystemPrompt: String = "",
    val conversionState: ConversionState = ConversionState.Idle,
    val detectedGgufFiles: List<GgufModelInfo> = emptyList(),
    val showConversionDialog: Boolean = false
)

enum class InferenceMode {
    ON_DEVICE,
    CLOUD,
    HYBRID
}

enum class BackendPreference {
    GPU,
    CPU,
    AUTO
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

            // Load inactivity timeout setting
            val prefs = application.getSharedPreferences("penpal_app_prefs", MODE_PRIVATE)
            val timeoutMinutes = prefs.getInt("inactivity_timeout_minutes", 10)
            val defaultSystemPrompt = prefs.getString("default_system_prompt", "") ?: ""
            _uiState.update {
                it.copy(
                    inactivityTimeoutMinutes = timeoutMinutes,
                    defaultSystemPrompt = defaultSystemPrompt
                )
            }

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
            val backend = when (_uiState.value.backendPreference) {
                BackendPreference.GPU -> "GPU"
                BackendPreference.CPU -> "CPU"
                BackendPreference.AUTO -> null
            }
            inferenceBridge.initialize(application, _uiState.value.modelName, backend) { result ->
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
            is SettingsEvent.UpdateBackendPreference -> updateBackendPreference(event.preference)
            is SettingsEvent.UpdateInactivityTimeout -> updateInactivityTimeout(event.minutes)
            is SettingsEvent.UpdateDefaultSystemPrompt -> updateDefaultSystemPrompt(event.prompt)
            is SettingsEvent.ScanForGgufFiles -> scanForGgufFiles()
            is SettingsEvent.ShowConversionDialog -> showConversionDialog()
            is SettingsEvent.HideConversionDialog -> hideConversionDialog()
            is SettingsEvent.SelectGgufFile -> selectGgufFile(event.filePath)
        }
    }

    private fun updateBackendPreference(preference: BackendPreference) {
        _uiState.update { it.copy(backendPreference = preference) }
    }

    private fun updateInactivityTimeout(minutes: Int) {
        application.getSharedPreferences("penpal_app_prefs", MODE_PRIVATE)
            .edit()
            .putInt("inactivity_timeout_minutes", minutes)
            .apply()
        _uiState.update { it.copy(inactivityTimeoutMinutes = minutes) }
    }

    private fun updateDefaultSystemPrompt(prompt: String) {
        application.getSharedPreferences("penpal_app_prefs", MODE_PRIVATE)
            .edit()
            .putString("default_system_prompt", prompt)
            .apply()
        _uiState.update { it.copy(defaultSystemPrompt = prompt) }
    }

    private fun scanForGgufFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(conversionState = ConversionState.Scanning(0)) }
            try {
                val files = GgufConverter.scanForGgufFiles(application)
                _uiState.update {
                    it.copy(
                        conversionState = ConversionState.Idle,
                        detectedGgufFiles = files,
                        showConversionDialog = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        conversionState = ConversionState.Error(e.message ?: "Scan failed", null)
                    )
                }
            }
        }
    }

    private fun showConversionDialog() {
        scanForGgufFiles()
    }

    private fun hideConversionDialog() {
        _uiState.update {
            it.copy(
                showConversionDialog = false,
                conversionState = ConversionState.Idle
            )
        }
    }

    private fun selectGgufFile(filePath: String) {
        viewModelScope.launch {
            val file = java.io.File(filePath)
            if (file.exists()) {
                val info = GgufConverter.parseGgufInfo(file)
                _uiState.update {
                    it.copy(
                        conversionState = ConversionState.Success(filePath, info.fileName),
                        message = "GGUF file detected: ${info.fileName}\n\n" +
                                "To convert this model to LiteRT format:\n\n" +
                                "1. Copy the file to your computer\n" +
                                "2. Run: python3 scripts/convert_gguf_to_litert.py --input ${file.name} --output ${info.fileName}.litertlm\n\n" +
                                "Or use OllamaInferenceBridge to run GGUF models directly."
                    )
                }
            }
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
            val backend = when (_uiState.value.backendPreference) {
                BackendPreference.GPU -> "GPU"
                BackendPreference.CPU -> "CPU"
                BackendPreference.AUTO -> null
            }
            inferenceBridge.loadModel(
                context = application,
                modelPath = modelPath,
                backend = backend,
                onDone = { message ->
                    val success = message.startsWith("Model loaded")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            modelStatus = if (success) ModelStatus.READY else ModelStatus.ERROR,
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
    data class UpdateBackendPreference(val preference: BackendPreference) : SettingsEvent()
    data class UpdateInactivityTimeout(val minutes: Int) : SettingsEvent()
    data class UpdateDefaultSystemPrompt(val prompt: String) : SettingsEvent()
    data object ScanForGgufFiles : SettingsEvent()
    data object ShowConversionDialog : SettingsEvent()
    data object HideConversionDialog : SettingsEvent()
    data class SelectGgufFile(val filePath: String) : SettingsEvent()
}