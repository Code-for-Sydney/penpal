package com.penpal.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * LiteRT-LM implementation of InferenceBridge using Google AI Edge LiteRT-LM API.
 *
 * Based on the InferenceService pattern from main branch:
 * - Uses Engine for model management
 * - Uses Conversation for chat sessions
 * - Supports GPU/CPU backends with fallback
 * - Thread-safe initialization
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtInferenceBridge(private val context: Context) : InferenceBridge {

    private var engine: com.google.ai.edge.litertlm.Engine? = null
    private var conversation: Conversation? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    private var currentModelPath: String? = null

    override fun initialize(
        context: Context,
        modelName: String,
        onDone: (String) -> Unit
    ) {
        scope.launch {
            try {
                val modelPath = findModelFile(modelName)
                if (modelPath != null && modelExists(modelPath)) {
                    // Persist the discovered path so it's reused on next launch
                    ModelManager.saveModelPath(context, modelPath)
                    val success = initializeEngine(modelPath)
                    if (success) {
                        _modelStatus.value = ModelStatus.DOWNLOADED
                        onDone("Model loaded: $modelName")
                    } else {
                        onDone("Failed to initialize model")
                    }
                } else {
                    _modelStatus.value = ModelStatus.NOT_DOWNLOADED
                    onDone("Model not found: $modelName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                onDone("Error: ${e.message}")
            }
        }
    }

    private suspend fun findModelFile(modelName: String): String? {
        // 1. Check ModelManager's tracked file (the known filename from downloads)
        val managerFile = ModelManager.modelFile(context)
        if (managerFile.exists() && managerFile.length() > 1_000_000L) {
            return managerFile.absolutePath
        }

        // 2. Check ModelManager's persisted path + scan common locations
        val existing = ModelManager.findExistingModel(context)
        if (existing != null) return existing

        // 3. Check modelName-based paths
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "$modelName.litertlm"),
            File(context.filesDir, "models/$modelName.litertlm"),
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "$modelName.litertlm"),
            File("/sdcard/Download/$modelName.litertlm"),
            File("/sdcard/$modelName.litertlm")
        )

        for (file in candidates) {
            if (file.exists() && file.length() > 1_000_000L) {
                return file.absolutePath
            }
        }

        // 4. Scan for ANY .litertlm file in common directories (manual downloads)
        val scanDirs = listOf(
            context.getExternalFilesDir(null),
            context.filesDir,
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            File("/sdcard/Download"),
            File("/sdcard")
        )
        for (dir in scanDirs) {
            dir?.listFiles { f -> f.extension == "litertlm" || f.name.endsWith(".litertlm") }
                ?.firstOrNull { it.length() > 1_000_000L }
                ?.let { return it.absolutePath }
        }

        return null
    }

    private fun modelExists(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.length() > 1_000_000L
    }

    private suspend fun initializeEngine(modelPath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Pixel 8 Pro Tensor G3: GPU backend uses Adreno GPU
                // which provides excellent acceleration for LLM inference.
                // CPU is the fallback if GPU init fails.
                val backends = listOf(
                    Triple("GPU", Backend.GPU(), Backend.GPU()),
                    Triple("CPU", Backend.CPU(), Backend.CPU())
                )

                for ((backendName, backend, visionBackend) in backends) {
                    try {
                        Log.d(TAG, "Trying $backendName backend...")

                        // Close existing engine
                        conversation?.close()
                        engine?.close()

                        val engineConfig = EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            visionBackend = visionBackend,
                            audioBackend = Backend.CPU(),
                            maxNumImages = 1,
                            maxNumTokens = 4096
                        )

                        engine = Engine(engineConfig)
                        engine!!.initialize()

                        conversation = engine!!.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(
                                    topK = 64,
                                    topP = 0.95,
                                    temperature = 0.7
                                )
                            )
                        )

                        currentModelPath = modelPath
                        _isReady.value = true
                        Log.d(TAG, "Engine initialized with $backendName backend")
                        return@withContext true

                    } catch (e: Exception) {
                        Log.e(TAG, "$backendName backend failed: ${e.message}")
                        if (backendName == "CPU") {
                            throw e
                        }
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine", e)
                _isReady.value = false
                false
            }
        }
    }

    override suspend fun isModelDownloaded(): Boolean {
        // 1. Check in-memory path
        if (currentModelPath != null && modelExists(currentModelPath!!)) return true
        // 2. Check persisted path + scan common locations (supports manual downloads)
        return ModelManager.findExistingModel(context) != null
    }

    override fun downloadModel(
        context: Context,
        modelName: String,
        coroutineScope: CoroutineScope,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        _isDownloading.value = true
        _modelStatus.value = ModelStatus.DOWNLOADING

        val hfToken = ModelManager.savedHfToken(context)
        val authHeader = if (hfToken.isNotBlank()) "Bearer $hfToken" else null

        ModelManager.startDownloadAsync(
            context = context,
            url = ModelManager.MODEL_DOWNLOAD_URL_HF,
            authHeader = authHeader,
            onSuccess = { downloadId ->
                coroutineScope.launch {
                    try {
                        while (true) {
                            val status = ModelManager.queryDownload(context, downloadId)
                            when (status.state) {
                                ModelManager.DownloadState.RUNNING,
                                ModelManager.DownloadState.PAUSED -> {
                                    _downloadProgress.value = DownloadProgress(
                                        downloadedBytes = status.bytesDownloaded,
                                        totalBytes = status.totalBytes
                                    )
                                    onProgress(status.bytesDownloaded, status.totalBytes)
                                    delay(500)
                                }
                                ModelManager.DownloadState.DONE -> {
                                    _downloadProgress.value = DownloadProgress(
                                        downloadedBytes = status.totalBytes,
                                        totalBytes = status.totalBytes
                                    )
                                    onProgress(status.totalBytes, status.totalBytes)
                                    _isDownloading.value = false
                                    _modelStatus.value = ModelStatus.DOWNLOADED
                                    val modelPath = ModelManager.modelFile(context).absolutePath
                                    ModelManager.saveModelPath(context, modelPath)
                                    if (initializeEngine(modelPath)) {
                                        currentModelPath = modelPath
                                    }
                                    onDone()
                                    return@launch
                                }
                                ModelManager.DownloadState.FAILED -> {
                                    _isDownloading.value = false
                                    _modelStatus.value = ModelStatus.ERROR
                                    onError("Download failed (reason: ${status.reason})")
                                    return@launch
                                }
                                else -> {
                                    delay(500)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        _isDownloading.value = false
                        _modelStatus.value = ModelStatus.ERROR
                        onError("Download error: ${e.message}")
                    }
                }
            },
            onError = { error ->
                _isDownloading.value = false
                _modelStatus.value = ModelStatus.ERROR
                onError(error)
            }
        )
    }

    override fun downloadModel(listener: InferenceBridge.DownloadProgressListener) {
        scope.launch {
            try {
                val context = this@LiteRtInferenceBridge.context
                val hfToken = ModelManager.savedHfToken(context)
                val authHeader = if (hfToken.isNotBlank()) "Bearer $hfToken" else null

                _isDownloading.value = true
                _modelStatus.value = ModelStatus.DOWNLOADING

                suspendCoroutine<Unit> { continuation ->
                    ModelManager.startDownloadAsync(
                        context = context,
                        url = ModelManager.MODEL_DOWNLOAD_URL_HF,
                        authHeader = authHeader,
                        onSuccess = { downloadId ->
                            scope.launch {
                                try {
                                    while (true) {
                                        val status = ModelManager.queryDownload(context, downloadId)
                                        when (status.state) {
                                            ModelManager.DownloadState.RUNNING,
                                            ModelManager.DownloadState.PAUSED -> {
                                                _downloadProgress.value = DownloadProgress(
                                                    downloadedBytes = status.bytesDownloaded,
                                                    totalBytes = status.totalBytes
                                                )
                                                listener.onProgress(status.progressPercent / 100f)
                                                delay(500)
                                            }
                                            ModelManager.DownloadState.DONE -> {
                                                _downloadProgress.value = DownloadProgress(
                                                    downloadedBytes = status.totalBytes,
                                                    totalBytes = status.totalBytes
                                                )
                                                listener.onProgress(1f)
                                                _isDownloading.value = false
                                                _modelStatus.value = ModelStatus.DOWNLOADED
                                                val modelPath = ModelManager.modelFile(context).absolutePath
                                                ModelManager.saveModelPath(context, modelPath)
                                                if (initializeEngine(modelPath)) {
                                                    currentModelPath = modelPath
                                                }
                                                listener.onComplete()
                                                continuation.resume(Unit)
                                                return@launch
                                            }
                                            ModelManager.DownloadState.FAILED -> {
                                                _isDownloading.value = false
                                                _modelStatus.value = ModelStatus.ERROR
                                                val msg = "Download failed (reason: ${status.reason})"
                                                listener.onError(msg)
                                                continuation.resume(Unit)
                                                return@launch
                                            }
                                            else -> {
                                                delay(500)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    _isDownloading.value = false
                                    _modelStatus.value = ModelStatus.ERROR
                                    listener.onError("Download error: ${e.message}")
                                    continuation.resume(Unit)
                                }
                            }
                        },
                        onError = { error ->
                            _isDownloading.value = false
                            _modelStatus.value = ModelStatus.ERROR
                            listener.onError(error)
                            continuation.resume(Unit)
                        }
                    )
                }
            } catch (e: Exception) {
                _isDownloading.value = false
                _modelStatus.value = ModelStatus.ERROR
                listener.onError(e.message ?: "Download failed")
            }
        }
    }

    override fun deleteModel() {
        currentModelPath?.let { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete model", e)
            }
        }
        ModelManager.clearModelPath(context)
        currentModelPath = null
        _isReady.value = false
        _modelStatus.value = ModelStatus.NOT_DOWNLOADED
        _downloadProgress.value = DownloadProgress()
        Log.d(TAG, "Model deleted")
    }

    override suspend fun listAvailableModels(context: Context): List<ModelManager.ModelInfo> {
        return ModelManager.listAvailableModels(context)
    }

    override fun loadModel(
        context: Context,
        modelPath: String,
        onDone: (String) -> Unit
    ) {
        scope.launch {
            try {
                if (!modelExists(modelPath)) {
                    onDone("Model file not found: $modelPath")
                    return@launch
                }

                // Track this model as recently used
                val file = File(modelPath)
                ModelManager.trackModel(
                    context,
                    ModelManager.ModelInfo(
                        name = file.nameWithoutExtension,
                        path = modelPath,
                        sizeBytes = file.length(),
                        lastUsed = System.currentTimeMillis()
                    )
                )

                val success = initializeEngine(modelPath)
                if (success) {
                    _modelStatus.value = ModelStatus.DOWNLOADED
                    onDone("Model loaded: ${file.name}")
                } else {
                    onDone("Failed to initialize model")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load model failed", e)
                onDone("Error: ${e.message}")
            }
        }
    }

    override fun deleteModel(modelPath: String) {
        val wasCurrent = currentModelPath == modelPath
        if (wasCurrent) {
            scope.launch {
                conversation?.close()
                engine?.close()
                engine = null
                conversation = null
                currentModelPath = null
                _isReady.value = false
                _isProcessing.value = false
            }
        }
        ModelManager.untrackModel(context, modelPath, deleteFile = true)
        Log.d(TAG, "Model deleted: $modelPath")
    }

    @OptIn(ExperimentalApi::class)
    override fun runInference(
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!_isReady.value || conversation == null) {
            onError("Model not ready. Please load the model first.")
            return
        }

        _isProcessing.value = true

        scope.launch {
            try {
                val conv = conversation!!
                val content = Contents.of(Content.Text(input))

                conv.sendMessageAsync(content, object : MessageCallback {
                    private var full = ""

                    override fun onMessage(message: Message) {
                        full += message.toString()
                        resultListener(full, false)
                    }

                    override fun onDone() {
                        resultListener(full, true)
                        _isProcessing.value = false
                        cleanUpListener()
                    }

                    override fun onError(throwable: Throwable) {
                        _isProcessing.value = false
                        onError(throwable.message ?: "Inference error")
                    }
                })
            } catch (e: Exception) {
                _isProcessing.value = false
                onError("Inference error: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithImage(
        input: String,
        image: Bitmap,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!_isReady.value || conversation == null) {
            onError("Model not ready. Please load the model first.")
            return
        }

        _isProcessing.value = true

        scope.launch {
            try {
                val stream = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val imageBytes = stream.toByteArray()

                val conv = conversation!!
                val content = Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.Text(input)
                )

                conv.sendMessageAsync(content, object : MessageCallback {
                    private var full = ""

                    override fun onMessage(message: Message) {
                        full += message.toString()
                        resultListener(full, false)
                    }

                    override fun onDone() {
                        resultListener(full, true)
                        _isProcessing.value = false
                        cleanUpListener()
                    }

                    override fun onError(throwable: Throwable) {
                        _isProcessing.value = false
                        onError(throwable.message ?: "Inference error")
                    }
                })
            } catch (e: Exception) {
                _isProcessing.value = false
                onError("Inference error: ${e.message}")
            }
        }
    }

    override fun resetConversation() {
        val currentEngine = engine ?: return
        scope.launch {
            conversation?.close()
            conversation = currentEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = 64,
                        topP = 0.95,
                        temperature = 0.7
                    )
                )
            )
            Log.d(TAG, "Conversation reset")
        }
    }

    override fun stopInference() {
        conversation?.cancelProcess()
        _isProcessing.value = false
        Log.d(TAG, "Inference stopped")
    }

    override fun release() {
        scope.launch {
            conversation?.close()
            engine?.close()
            engine = null
            conversation = null
            currentModelPath = null
            _isReady.value = false
            _isProcessing.value = false
            _isDownloading.value = false
            _downloadProgress.value = DownloadProgress()
            Log.d(TAG, "Model released")
        }
    }

    companion object {
        private const val TAG = "LiteRtInferenceBridge"
    }
}