package com.penpal.feature.process

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.data.ExtractionJobDao
import com.penpal.core.data.ExtractionJobEntity
import com.penpal.core.processing.NetworkMonitor
import com.penpal.core.processing.WorkerLauncher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProcessUiState(
    val jobs: List<ExtractionJobEntity> = emptyList(),
    val isProcessing: Boolean = false,
    val selectedSourceType: SourceType = SourceType.PDF,
    val inputText: String = "",
    val error: String? = null,
    val isOnline: Boolean = true,
    val cachedChunkCount: Int = 0
)

enum class SourceType { PDF, AUDIO, IMAGE, URL, CODE }

sealed class ProcessEvent {
    data class UpdateInput(val text: String) : ProcessEvent()
    data class SelectSourceType(val type: SourceType) : ProcessEvent()
    data object EnqueueJob : ProcessEvent()
    data class CancelJob(val jobId: String) : ProcessEvent()
    data object DismissError : ProcessEvent()
    data object OfflineWarningShown : ProcessEvent()
}

class ProcessViewModel(
    private val extractionJobDao: ExtractionJobDao,
    private val workerLauncher: WorkerLauncher,
    private val networkMonitor: NetworkMonitor,
    private val getCachedChunkCount: suspend () -> Int
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProcessUiState())
    val uiState: StateFlow<ProcessUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            extractionJobDao.getAllJobs().collect { jobs ->
                _uiState.update { it.copy(jobs = jobs) }
            }
        }

        viewModelScope.launch {
            networkMonitor.isOnline.collect { isOnline ->
                _uiState.update { it.copy(isOnline = isOnline) }
            }
        }

        viewModelScope.launch {
            val count = getCachedChunkCount()
            _uiState.update { it.copy(cachedChunkCount = count) }
        }
    }

    fun refreshCachedChunkCount() {
        viewModelScope.launch {
            val count = getCachedChunkCount()
            _uiState.update { it.copy(cachedChunkCount = count) }
        }
    }

    fun onEvent(event: ProcessEvent) {
        when (event) {
            is ProcessEvent.UpdateInput -> {
                _uiState.update { it.copy(inputText = event.text) }
            }
            is ProcessEvent.SelectSourceType -> {
                _uiState.update { it.copy(selectedSourceType = event.type) }
            }
            is ProcessEvent.EnqueueJob -> enqueueJob()
            is ProcessEvent.CancelJob -> cancelJob(event.jobId)
            is ProcessEvent.DismissError -> {
                _uiState.update { it.copy(error = null) }
            }
            is ProcessEvent.OfflineWarningShown -> {
                // User acknowledged offline warning, proceed with queuing
                enqueueJobInternal()
            }
        }
    }

    private fun enqueueJob() {
        val input = _uiState.value.inputText.trim()
        if (input.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a URL or file path") }
            return
        }

        // Check if this source requires network
        val requiresNetwork = when (_uiState.value.selectedSourceType) {
            SourceType.URL -> true
            SourceType.PDF, SourceType.AUDIO, SourceType.IMAGE, SourceType.CODE -> {
                // Check if it looks like a URL
                input.startsWith("http://") || input.startsWith("https://")
            }
        }

        if (requiresNetwork && !_uiState.value.isOnline) {
            _uiState.update { it.copy(error = "This source requires network connection. Please connect to WiFi or cellular.")}
            return
        }

        enqueueJobInternal()
    }

    private fun enqueueJobInternal() {
        val input = _uiState.value.inputText.trim()

        val (mimeType, rule) = when (_uiState.value.selectedSourceType) {
            SourceType.PDF -> "pdf" to "FULL_TEXT"
            SourceType.AUDIO -> "audio" to "TRANSCRIPT"
            SourceType.IMAGE -> "image" to "IMAGE_OCR"
            SourceType.URL -> "url" to "URL_CONTENT"
            SourceType.CODE -> "code" to "CODE"
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isProcessing = true, inputText = "") }
                workerLauncher.enqueue(input, mimeType, rule)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to enqueue job: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    private fun cancelJob(jobId: String) {
        viewModelScope.launch {
            extractionJobDao.delete(jobId)
        }
    }
}