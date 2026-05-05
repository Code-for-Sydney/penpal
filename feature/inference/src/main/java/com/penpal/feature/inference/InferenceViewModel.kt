package com.penpal.feature.inference

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.ai.DownloadProgress
import com.penpal.core.ai.InferenceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InferenceUiState(
    val isReady: Boolean = false,
    val isProcessing: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0,
    val modelStatus: ModelStatus = ModelStatus.NOT_LOADED,
    val modelName: String = "Gemma 4 E2B-IT",
    val modelVariant: String = "google/gemma-4-e2b-it",
    val modelSize: String = "~2.6 GB",
    val contextLength: String = "32K tokens",
    val quantization: String = "INT4",
    val serverUrl: String = "http://localhost:8000",
    val isServerConnected: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null
)

enum class ModelStatus {
    NOT_LOADED,
    LOADING,
    DOWNLOADING,
    READY,
    ERROR
}

sealed class InferenceEvent {
    data object LoadModel : InferenceEvent()
    data object UnloadModel : InferenceEvent()
    data object DismissError : InferenceEvent()
    data object DismissStatus : InferenceEvent()
    data object CheckModelStatus : InferenceEvent()
}

class InferenceViewModel(
    application: Application,
    private val inferenceBridge: InferenceBridge
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(InferenceUiState())
    val uiState: StateFlow<InferenceUiState> = _uiState.asStateFlow()

    init {
        // Observe ready state
        viewModelScope.launch {
            inferenceBridge.isReady.collect { ready ->
                _uiState.update {
                    it.copy(
                        isReady = ready,
                        modelStatus = when {
                            ready -> ModelStatus.READY
                            it.modelStatus == ModelStatus.DOWNLOADING -> ModelStatus.DOWNLOADING
                            it.modelStatus == ModelStatus.LOADING -> ModelStatus.LOADING
                            else -> ModelStatus.NOT_LOADED
                        }
                    )
                }
            }
        }

        // Observe processing state
        viewModelScope.launch {
            inferenceBridge.isProcessing.collect { processing ->
                _uiState.update { it.copy(isProcessing = processing) }
            }
        }

        // Observe downloading state
        viewModelScope.launch {
            inferenceBridge.isDownloading.collect { downloading ->
                _uiState.update {
                    it.copy(
                        isDownloading = downloading,
                        modelStatus = if (downloading) ModelStatus.DOWNLOADING else it.modelStatus
                    )
                }
            }
        }

        // Observe download progress
        viewModelScope.launch {
            inferenceBridge.downloadProgress.collect { progress ->
                _uiState.update { it.copy(downloadProgress = progress.percentage) }
            }
        }

        // Check initial model status
        checkModelStatus()
    }

    fun onEvent(event: InferenceEvent) {
        when (event) {
            is InferenceEvent.LoadModel -> loadModel()
            is InferenceEvent.UnloadModel -> unloadModel()
            is InferenceEvent.DismissError -> _uiState.update { it.copy(error = null) }
            is InferenceEvent.DismissStatus -> _uiState.update { it.copy(statusMessage = null) }
            is InferenceEvent.CheckModelStatus -> checkModelStatus()
        }
    }

    private fun checkModelStatus() {
        viewModelScope.launch {
            val isDownloaded = inferenceBridge.isModelDownloaded()
            if (isDownloaded) {
                _uiState.update { it.copy(isReady = true, modelStatus = ModelStatus.READY) }
            }
        }
    }

    private fun loadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelStatus.DOWNLOADING, isDownloading = true) }

            inferenceBridge.downloadModel(
                context = getApplication(),
                modelName = _uiState.value.modelVariant,
                coroutineScope = viewModelScope,
                onProgress = { downloaded, total ->
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    _uiState.update {
                        it.copy(
                            downloadProgress = progress,
                            statusMessage = "Downloading... $progress%"
                        )
                    }
                },
                onDone = {
                    _uiState.update {
                        it.copy(
                            modelStatus = ModelStatus.READY,
                            isReady = true,
                            isDownloading = false,
                            downloadProgress = 100,
                            statusMessage = "Model ready!"
                        )
                    }
                },
                onError = { error ->
                    _uiState.update {
                        it.copy(
                            modelStatus = ModelStatus.ERROR,
                            isDownloading = false,
                            error = error,
                            statusMessage = null
                        )
                    }
                }
            )
        }
    }

    private fun unloadModel() {
        inferenceBridge.release()
        _uiState.update {
            it.copy(
                modelStatus = ModelStatus.NOT_LOADED,
                isReady = false,
                isDownloading = false,
                downloadProgress = 0
            )
        }
    }
}