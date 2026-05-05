package com.penpal.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ollama implementation of InferenceBridge.
 * Delegates work to Ollama REST API and ModelDownloadManager.
 */
class OllamaInferenceBridge(
    context: Context,
    private val apiService: OllamaApiService = OllamaApiService(),
    private val downloadManager: ModelDownloadManager = ModelDownloadManager(context)
) : InferenceBridge {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    override val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    override val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    override val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _modelStatus = MutableStateFlow(ModelStatus.NOT_DOWNLOADED)
    override val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private var inferenceJob: Job? = null
    private var currentModel = "llama3.2:latest"

    override fun initialize(
        context: Context,
        modelName: String,
        onDone: (String) -> Unit
    ) {
        currentModel = modelName
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloaded = isModelDownloaded()
                _isReady.value = downloaded
                _modelStatus.value = if (downloaded) ModelStatus.DOWNLOADED else ModelStatus.NOT_DOWNLOADED
                onDone(if (downloaded) "Model ready: $modelName" else "Model needs download: $modelName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Ollama bridge", e)
                onDone("Error: ${e.message}")
            }
        }
    }

    override suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        try {
            val models = apiService.listModels()
            val found = models.any { it.name == currentModel || it.model == currentModel }
            found
        } catch (e: Exception) {
            Log.e(TAG, "Error checking downloaded models", e)
            false
        }
    }

    override fun downloadModel(
        context: Context,
        modelName: String,
        coroutineScope: CoroutineScope,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        currentModel = modelName
        _isDownloading.value = true
        _modelStatus.value = ModelStatus.DOWNLOADING
        
        downloadManager.downloadModel(modelName)
        
        coroutineScope.launch {
            downloadManager.getDownloadProgress(modelName).collect { progress ->
                if (progress != null) {
                    _downloadProgress.value = DownloadProgress(
                        downloadedBytes = progress.toLong(),
                        totalBytes = 100L
                    )
                    onProgress(progress.toLong(), 100L)
                    
                    if (progress >= 100) {
                        _isDownloading.value = false
                        _isReady.value = true
                        _modelStatus.value = ModelStatus.DOWNLOADED
                        onDone()
                    }
                }
            }
        }
    }

    override fun downloadModel(listener: InferenceBridge.DownloadProgressListener) {
        _isDownloading.value = true
        _modelStatus.value = ModelStatus.DOWNLOADING
        
        downloadManager.downloadModel(currentModel)

        CoroutineScope(Dispatchers.IO).launch {
            downloadManager.getDownloadProgress(currentModel).collect { progress ->
                if (progress != null) {
                    listener.onProgress(progress / 100f)
                    if (progress >= 100) {
                        _isDownloading.value = false
                        _isReady.value = true
                        _modelStatus.value = ModelStatus.DOWNLOADED
                        listener.onComplete()
                    }
                }
            }
        }
    }

    override fun deleteModel() {
        _isReady.value = false
        _modelStatus.value = ModelStatus.NOT_DOWNLOADED
        _downloadProgress.value = DownloadProgress()
        downloadManager.cancelDownload(currentModel)
    }

    override fun runInference(
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    ) {
        _isProcessing.value = true
        inferenceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                var fullResponse = ""
                apiService.generateStream(currentModel, input).collect { response ->
                    fullResponse += response.response
                    resultListener(fullResponse, response.done)
                }
                _isProcessing.value = false
                cleanUpListener()
            } catch (e: Exception) {
                _isProcessing.value = false
                onError(e.message ?: "Inference failed")
            }
        }
    }

    override fun runInferenceWithImage(
        input: String,
        image: Bitmap,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    ) {
        runInference(input, resultListener, cleanUpListener, onError)
    }

    override fun resetConversation() {
        inferenceJob?.cancel()
    }

    override fun stopInference() {
        inferenceJob?.cancel()
        _isProcessing.value = false
    }

    override fun release() {
        stopInference()
        _isReady.value = false
    }

    companion object {
        private const val TAG = "OllamaInferenceBridge"
    }
}
