package com.penpal.core.ai.inference.implementation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import com.penpal.core.ai.inference.InferenceBridge
import com.penpal.core.ai.inference.model.DownloadProgress
import com.penpal.core.ai.inference.model.ModelStatus
import com.penpal.core.ai.messaging.ContentMode
import com.penpal.core.ai.messaging.FilteredChunkWithTransitions
import com.penpal.core.ai.messaging.MessagePart
import com.penpal.core.ai.messaging.MessagePartAggregator
import com.penpal.core.ai.model.ModelManager
import com.penpal.core.ai.tokenization.StreamingTokenFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

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

    private val _isUnloading = MutableStateFlow(false)
    override val isUnloading: StateFlow<Boolean> = _isUnloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    override val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _modelStatus = MutableStateFlow(ModelStatus.NOT_DOWNLOADED)
    override val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    private var currentModelPath: String? = null

    override fun initialize(
        context: Context,
        modelName: String,
        backend: String?,
        onDone: (String) -> Unit
    ) {
        if (_isReady.value && engine != null) {
            onDone("Model already loaded")
            return
        }
        scope.launch {
            try {
                val modelPath = findModelFile(modelName)
                if (modelPath != null && modelExists(modelPath)) {
                    ModelManager.saveModelPath(context, modelPath)
                    val success = initializeEngine(modelPath, backend)
                    if (success) {
                        _modelStatus.value = ModelStatus.READY
                        _isReady.value = true
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
        val managerFile = ModelManager.modelFile(context)
        if (managerFile.exists() && managerFile.length() > 1_000_000L) {
            return managerFile.absolutePath
        }
        val existing = ModelManager.findExistingModel(context)
        if (existing != null) return existing

        val candidates = listOf(
            File(context.getExternalFilesDir(null), "$modelName.litertlm"),
            File(context.filesDir, "models/$modelName.litertlm"),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "$modelName.litertlm"),
            File("/sdcard/Download/$modelName.litertlm"),
            File("/sdcard/$modelName.litertlm")
        )

        for (file in candidates) {
            if (file.exists() && file.length() > 1_000_000L) {
                return file.absolutePath
            }
        }

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

    private suspend fun initializeEngine(modelPath: String, preferredBackend: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val backends = when (preferredBackend?.uppercase()) {
                    "GPU" -> listOf(Triple("GPU", Backend.GPU(), Backend.GPU()))
                    "CPU" -> listOf(Triple("CPU", Backend.CPU(), Backend.CPU()))
                    else -> listOf(
                        Triple("GPU", Backend.GPU(), Backend.GPU()),
                        Triple("CPU", Backend.CPU(), Backend.CPU())
                    )
                }

                for ((backendName, backend, visionBackend) in backends) {
                    try {
                        Log.d(TAG, "Trying $backendName backend...")
                        conversation?.close()
                        try { engine?.close() } catch (_: IllegalStateException) { }
                        engine = null

                        val engineConfig = EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            visionBackend = visionBackend,
                            maxNumTokens = 8192
                        )
                        engine = Engine(engineConfig)
                        engine!!.initialize()

                        conversation = engine!!.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                            )
                        )

                        currentModelPath = modelPath
                        _isReady.value = true
                        Log.d(TAG, "Engine initialized with $backendName backend")
                        return@withContext true
                    } catch (e: Exception) {
                        Log.e(TAG, "$backendName backend failed: ${e.message}")
                        if (backendName == "CPU" || preferredBackend != null) throw e
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
        if (currentModelPath != null && modelExists(currentModelPath!!)) return true
        return ModelManager.findExistingModel(context) != null
    }

    override fun downloadModel(context: Context, modelName: String, coroutineScope: CoroutineScope, onProgress: (downloaded: Long, total: Long) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
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
                                ModelManager.DownloadState.RUNNING, ModelManager.DownloadState.PAUSED -> {
                                    _downloadProgress.value = DownloadProgress(status.bytesDownloaded, status.totalBytes)
                                    onProgress(status.bytesDownloaded, status.totalBytes)
                                    delay(500)
                                }
                                ModelManager.DownloadState.DONE -> {
                                    _downloadProgress.value = DownloadProgress(status.totalBytes, status.totalBytes)
                                    onProgress(status.totalBytes, status.totalBytes)
                                    _isDownloading.value = false
                                    _modelStatus.value = ModelStatus.DOWNLOADED
                                    val modelPath = ModelManager.modelFile(context).absolutePath
                                    ModelManager.saveModelPath(context, modelPath)
                                    if (initializeEngine(modelPath)) currentModelPath = modelPath
                                    onDone()
                                    return@launch
                                }
                                ModelManager.DownloadState.FAILED -> {
                                    _isDownloading.value = false
                                    _modelStatus.value = ModelStatus.ERROR
                                    onError("Download failed (reason: ${status.reason})")
                                    return@launch
                                }
                                else -> delay(500)
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

                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
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
                                            ModelManager.DownloadState.RUNNING, ModelManager.DownloadState.PAUSED -> {
                                                _downloadProgress.value = DownloadProgress(status.bytesDownloaded, status.totalBytes)
                                                listener.onProgress(status.progressPercent / 100f)
                                                delay(500)
                                            }
                                            ModelManager.DownloadState.DONE -> {
                                                _downloadProgress.value = DownloadProgress(status.totalBytes, status.totalBytes)
                                                listener.onProgress(1f)
                                                _isDownloading.value = false
                                                _modelStatus.value = ModelStatus.DOWNLOADED
                                                val modelPath = ModelManager.modelFile(context).absolutePath
                                                ModelManager.saveModelPath(context, modelPath)
                                                if (initializeEngine(modelPath)) currentModelPath = modelPath
                                                listener.onComplete()
                                                continuation.resume(Unit)
                                                return@launch
                                            }
                                            ModelManager.DownloadState.FAILED -> {
                                                _isDownloading.value = false
                                                _modelStatus.value = ModelStatus.ERROR
                                                listener.onError("Download failed (reason: ${status.reason})")
                                                continuation.resume(Unit)
                                                return@launch
                                            }
                                            else -> delay(500)
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
            try { File(path).delete() } catch (e: Exception) { Log.e(TAG, "Failed to delete model", e) }
        }
        ModelManager.clearModelPath(context)
        currentModelPath = null
        _isReady.value = false
        _modelStatus.value = ModelStatus.NOT_DOWNLOADED
        _downloadProgress.value = DownloadProgress()
        Log.d(TAG, "Model deleted")
    }

    override suspend fun listAvailableModels(context: Context): List<ModelManager.ModelInfo> = ModelManager.listAvailableModels(context)

    override fun loadModel(context: Context, modelPath: String, backend: String?, onDone: (String) -> Unit) {
        scope.launch {
            try {
                if (!modelExists(modelPath)) {
                    onDone("Model file not found: $modelPath")
                    return@launch
                }
                // Guard against reloading if already loaded with same model
                if (_isReady.value && engine != null && currentModelPath == modelPath) {
                    onDone("Model already loaded")
                    return@launch
                }
                _modelStatus.value = ModelStatus.LOADING
                val file = File(modelPath)
                ModelManager.trackModel(context, ModelManager.ModelInfo(
                    name = file.nameWithoutExtension, path = modelPath, sizeBytes = file.length(), lastUsed = System.currentTimeMillis()
                ))
                val success = initializeEngine(modelPath, backend)
                if (success) {
                    _modelStatus.value = ModelStatus.READY
                    _isReady.value = true
                    onDone("Model loaded: ${file.name}")
                } else {
                    _modelStatus.value = ModelStatus.ERROR
                    onDone("Failed to initialize model")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load model failed", e)
                _modelStatus.value = ModelStatus.ERROR
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
    override fun runInference(input: String, resultListener: (partialResult: String, done: Boolean) -> Unit, cleanUpListener: () -> Unit, onError: (String) -> Unit) {
        if (!_isReady.value || engine == null) {
            onError("Model not ready. Please load the model first.")
            return
        }
        _isProcessing.value = true
        val completed = AtomicBoolean(false)

        scope.launch {
            val timeoutJob = launch {
                delay(120_000)
                if (completed.compareAndSet(false, true)) {
                    Log.w(TAG, "Inference timed out after 120s")
                    conversation?.cancelProcess()
                    _isProcessing.value = false
                    onError("Inference timed out. The model may be stuck or the device may not have enough memory.")
                }
            }

            try {
                resetConversationForInference()
                val conv = conversation!!
                val content = Contents.of(Content.Text(input))
                Log.d(TAG, "Sending message with ${input.length} chars")
                val filter = StreamingTokenFilter()

                conv.sendMessageAsync(content, object : MessageCallback {
                    private var full = ""
                    private var lastMode = ContentMode.REGULAR

                    override fun onMessage(message: Message) {
                        val text = extractTextFromMessage(message)
                        val result = filter.append(text)
                        if (result.mode != lastMode) lastMode = result.mode
                        full += result.text
                        if (result.text.isNotEmpty()) resultListener(full, false)
                    }

                    override fun onDone() {
                        if (completed.compareAndSet(false, true)) {
                            val result = filter.flush()
                            full += result.text
                            if (full.isEmpty()) Log.w(TAG, "onDone: received empty response from model")
                            else Log.d(TAG, "onDone: finalLength=${full.length}")
                            resultListener(full, true)
                            _isProcessing.value = false
                            cleanUpListener()
                            timeoutJob.cancel()
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        if (completed.compareAndSet(false, true)) {
                            Log.e(TAG, "onError: ${throwable.message}", throwable)
                            _isProcessing.value = false
                            onError(throwable.message ?: "Inference error")
                            timeoutJob.cancel()
                        }
                    }
                })
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    Log.e(TAG, "Inference exception: ${e.message}", e)
                    _isProcessing.value = false
                    onError("Inference error: ${e.message}")
                }
            } finally {
                timeoutJob.cancel()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithImage(input: String, image: Bitmap, resultListener: (partialResult: String, done: Boolean) -> Unit, cleanUpListener: () -> Unit, onError: (String) -> Unit) {
        if (!_isReady.value || engine == null) {
            onError("Model not ready. Please load the model first.")
            return
        }
        _isProcessing.value = true
        val completed = AtomicBoolean(false)

        scope.launch {
            val timeoutJob = launch {
                delay(120_000)
                if (completed.compareAndSet(false, true)) {
                    Log.w(TAG, "Image inference timed out after 120s")
                    conversation?.cancelProcess()
                    _isProcessing.value = false
                    onError("Inference timed out. The model may be stuck or the device may not have enough memory.")
                }
            }

            try {
                resetConversationForInference()
                val stream = java.io.ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val imageBytes = stream.toByteArray()

                val conv = conversation!!
                val content = Contents.of(Content.ImageBytes(imageBytes), Content.Text(input))
                Log.d(TAG, "Sending image message with ${input.length} chars")
                val filter = StreamingTokenFilter()

                conv.sendMessageAsync(content, object : MessageCallback {
                    private var full = ""
                    private var lastMode = ContentMode.REGULAR

                    override fun onMessage(message: Message) {
                        val text = extractTextFromMessage(message)
                        val result = filter.append(text)
                        if (result.mode != lastMode) lastMode = result.mode
                        full += result.text
                        if (result.text.isNotEmpty()) resultListener(full, false)
                    }

                    override fun onDone() {
                        if (completed.compareAndSet(false, true)) {
                            val result = filter.flush()
                            full += result.text
                            if (full.isEmpty()) Log.w(TAG, "onDone: received empty response from model")
                            else Log.d(TAG, "onDone: finalLength=${full.length}")
                            resultListener(full, true)
                            _isProcessing.value = false
                            cleanUpListener()
                            timeoutJob.cancel()
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        if (completed.compareAndSet(false, true)) {
                            Log.e(TAG, "onError: ${throwable.message}", throwable)
                            _isProcessing.value = false
                            onError(throwable.message ?: "Inference error")
                            timeoutJob.cancel()
                        }
                    }
                })
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    Log.e(TAG, "Image inference exception: ${e.message}", e)
                    _isProcessing.value = false
                    onError("Inference error: ${e.message}")
                }
            } finally {
                timeoutJob.cancel()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun runInferenceFlow(input: String): Flow<String> = flow {
        if (!_isReady.value || engine == null) throw IllegalStateException("Model not ready. Please load the model first.")
        _isProcessing.value = true
        val filter = StreamingTokenFilter()
        var accumulated = ""

        try {
            resetConversationForInference()
            val conv = conversation!!
            val content = Contents.of(Content.Text(input))
            Log.d(TAG, "Sending message via Flow with ${input.length} chars")

            withTimeout(120_000) {
                conv.sendMessageAsync(content)
                    .catch { e -> Log.e(TAG, "Flow error: ${e.message}", e); throw e }
                    .collect { message ->
                        val text = extractTextFromMessage(message)
                        if (text.isNotEmpty()) {
                            val result = filter.append(text)
                            if (result.text.isNotEmpty()) {
                                accumulated += result.text
                                emit(accumulated)
                            }
                        }
                    }
            }
            val result = filter.flush()
            if (result.text.isNotEmpty()) {
                accumulated += result.text
                emit(accumulated)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Inference timed out after 120s")
            conversation?.cancelProcess()
            throw IllegalStateException("Inference timed out. The model may be stuck or the device may not have enough memory.")
        } finally {
            _isProcessing.value = false
            filter.clear()
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithImageFlow(input: String, image: Bitmap): Flow<String> = flow {
        if (!_isReady.value || engine == null) throw IllegalStateException("Model not ready. Please load the model first.")
        _isProcessing.value = true
        val filter = StreamingTokenFilter()
        var accumulated = ""

        try {
            resetConversationForInference()
            val stream = java.io.ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val imageBytes = stream.toByteArray()

            val conv = conversation!!
            val content = Contents.of(Content.ImageBytes(imageBytes), Content.Text(input))
            Log.d(TAG, "Sending image message via Flow with ${input.length} chars")

            withTimeout(120_000) {
                conv.sendMessageAsync(content)
                    .catch { e -> Log.e(TAG, "Image Flow error: ${e.message}", e); throw e }
                    .collect { message ->
                        val text = extractTextFromMessage(message)
                        if (text.isNotEmpty()) {
                            val result = filter.append(text)
                            if (result.text.isNotEmpty()) {
                                accumulated += result.text
                                emit(accumulated)
                            }
                        }
                    }
            }
            val result = filter.flush()
            if (result.text.isNotEmpty()) {
                accumulated += result.text
                emit(accumulated)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Image inference timed out after 120s")
            conversation?.cancelProcess()
            throw IllegalStateException("Inference timed out. The model may be stuck or the device may not have enough memory.")
        } finally {
            _isProcessing.value = false
            filter.clear()
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    override fun runInferenceFlowParts(input: String): Flow<List<MessagePart>> = flow {
        Log.d(TAG, ">>> runInferenceFlowParts CALLED with input length: ${input.length}")
        if (!_isReady.value || engine == null) throw IllegalStateException("Model not ready. Please load the model first.")

        _isProcessing.value = true
        val filter = StreamingTokenFilter()
        val aggregator = MessagePartAggregator()

        try {
            resetConversationForInference()
            val conv = conversation!!
            val content = Contents.of(Content.Text(input))

            withTimeout(120_000) {
                conv.sendMessageAsync(content)
                    .catch { e -> Log.e(TAG, "FlowParts error: ${e.message}", e); throw e }
                    .collect { message ->
                        val text = extractTextFromMessage(message)
                        if (text.isNotEmpty()) {
                            val result = filter.appendWithTransitions(text)
                            if (result.text.isNotEmpty() || result.transitions.isNotEmpty()) {
                                val parts = aggregator.processChunk(result)
                                emit(parts)
                            }
                        }
                    }
            }
            val result = filter.flush()
            if (result.text.isNotEmpty()) {
                val parts = aggregator.processChunk(FilteredChunkWithTransitions(result.text, result.mode, emptyList()))
                emit(parts)
            }
            emit(aggregator.finalize())
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Inference timed out after 120s")
            conversation?.cancelProcess()
            throw IllegalStateException("Inference timed out. The model may be stuck or the device may not have enough memory.")
        } finally {
            _isProcessing.value = false
            filter.clear()
            aggregator.reset()
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithImageFlowParts(input: String, image: Bitmap): Flow<List<MessagePart>> = flow {
        if (!_isReady.value || engine == null) throw IllegalStateException("Model not ready. Please load the model first.")

        _isProcessing.value = true
        val filter = StreamingTokenFilter()
        val aggregator = MessagePartAggregator()

        try {
            resetConversationForInference()
            val stream = java.io.ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val imageBytes = stream.toByteArray()

            val conv = conversation!!
            val content = Contents.of(Content.ImageBytes(imageBytes), Content.Text(input))
            Log.d(TAG, "Sending image message via FlowParts with ${input.length} chars")

            withTimeout(120_000) {
                conv.sendMessageAsync(content)
                    .catch { e -> Log.e(TAG, "Image FlowParts error: ${e.message}", e); throw e }
                    .collect { message ->
                        val text = extractTextFromMessage(message)
                        if (text.isNotEmpty()) {
                            val result = filter.appendWithTransitions(text)
                            if (result.text.isNotEmpty() || result.transitions.isNotEmpty()) {
                                emit(aggregator.processChunk(result))
                            }
                        }
                    }
            }
            val result = filter.flush()
            if (result.text.isNotEmpty()) {
                emit(aggregator.processChunk(FilteredChunkWithTransitions(result.text, result.mode, emptyList())))
            }
            emit(aggregator.finalize())
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Image inference timed out after 120s")
            conversation?.cancelProcess()
            throw IllegalStateException("Inference timed out. The model may be stuck or the device may not have enough memory.")
        } finally {
            _isProcessing.value = false
            filter.clear()
            aggregator.reset()
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithAudio(input: String, audioData: FloatArray, resultListener: (partialResult: String, done: Boolean) -> Unit, cleanUpListener: () -> Unit, onError: (String) -> Unit) {
        if (!_isReady.value || engine == null) {
            onError("Model not ready. Please load the model first.")
            return
        }
        _isProcessing.value = true
        val completed = AtomicBoolean(false)

        scope.launch {
            val timeoutJob = launch {
                delay(120_000)
                if (completed.compareAndSet(false, true)) {
                    Log.w(TAG, "Audio inference timed out after 120s")
                    conversation?.cancelProcess()
                    _isProcessing.value = false
                    onError("Inference timed out. The model may be stuck or the device may not have enough memory.")
                }
            }

            try {
                resetConversationForInference()
                val conv = conversation!!
                val audioBytes = floatArrayToLittleEndianBytes(audioData)
                val content = Contents.of(Content.AudioBytes(audioBytes), Content.Text(input))
                val filter = StreamingTokenFilter()

                conv.sendMessageAsync(content, object : MessageCallback {
                    private var full = ""

                    override fun onMessage(message: Message) {
                        val text = extractTextFromMessage(message)
                        val result = filter.append(text)
                        full += result.text
                        if (result.text.isNotEmpty()) resultListener(full, false)
                    }

                    override fun onDone() {
                        if (completed.compareAndSet(false, true)) {
                            val result = filter.flush()
                            full += result.text
                            resultListener(full, true)
                            _isProcessing.value = false
                            cleanUpListener()
                            timeoutJob.cancel()
                        }
                    }

                    override fun onError(throwable: Throwable) {
                        if (completed.compareAndSet(false, true)) {
                            Log.e(TAG, "Audio onError: ${throwable.message}", throwable)
                            _isProcessing.value = false
                            onError(throwable.message ?: "Inference error")
                            timeoutJob.cancel()
                        }
                    }
                })
            } catch (e: Exception) {
                if (completed.compareAndSet(false, true)) {
                    Log.e(TAG, "Audio inference exception: ${e.message}", e)
                    _isProcessing.value = false
                    onError("Inference error: ${e.message}")
                }
            } finally {
                timeoutJob.cancel()
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithAudioFlow(input: String, audioData: FloatArray): Flow<String> = flow {
        if (!_isReady.value || engine == null) throw IllegalStateException("Model not ready. Please load the model first.")
        _isProcessing.value = true
        val filter = StreamingTokenFilter()
        var accumulated = ""

        try {
            resetConversationForInference()
            val conv = conversation!!
            val audioBytes = floatArrayToLittleEndianBytes(audioData)
            val content = Contents.of(Content.AudioBytes(audioBytes), Content.Text(input))

            withTimeout(120_000) {
                conv.sendMessageAsync(content)
                    .catch { e -> Log.e(TAG, "Audio Flow error: ${e.message}", e); throw e }
                    .collect { message ->
                        val text = extractTextFromMessage(message)
                        if (text.isNotEmpty()) {
                            val result = filter.append(text)
                            if (result.text.isNotEmpty()) {
                                accumulated += result.text
                                emit(accumulated)
                            }
                        }
                    }
            }
            val result = filter.flush()
            if (result.text.isNotEmpty()) {
                accumulated += result.text
                emit(accumulated)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Audio inference timed out after 120s")
            conversation?.cancelProcess()
            throw IllegalStateException("Inference timed out. The model may be stuck or the device may not have enough memory.")
        } finally {
            _isProcessing.value = false
            filter.clear()
        }
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalApi::class)
    override fun runInferenceWithAudioFlowParts(input: String, audioData: FloatArray): Flow<List<MessagePart>> = flow {
        if (!_isReady.value || engine == null) throw IllegalStateException("Model not ready. Please load the model first.")
        _isProcessing.value = true
        val filter = StreamingTokenFilter()
        val aggregator = MessagePartAggregator()

        try {
            resetConversationForInference()
            val conv = conversation!!
            val audioBytes = floatArrayToLittleEndianBytes(audioData)
            val content = Contents.of(Content.AudioBytes(audioBytes), Content.Text(input))

            withTimeout(120_000) {
                conv.sendMessageAsync(content)
                    .catch { e -> Log.e(TAG, "Audio FlowParts error: ${e.message}", e); throw e }
                    .collect { message ->
                        val text = extractTextFromMessage(message)
                        if (text.isNotEmpty()) {
                            val result = filter.appendWithTransitions(text)
                            if (result.text.isNotEmpty() || result.transitions.isNotEmpty()) {
                                emit(aggregator.processChunk(result))
                            }
                        }
                    }
            }
            val result = filter.flush()
            if (result.text.isNotEmpty()) {
                emit(aggregator.processChunk(FilteredChunkWithTransitions(result.text, result.mode, emptyList())))
            }
            emit(aggregator.finalize())
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Audio inference timed out after 120s")
            conversation?.cancelProcess()
            throw IllegalStateException("Inference timed out. The model may be stuck or the device may not have enough memory.")
        } finally {
            _isProcessing.value = false
            filter.clear()
            aggregator.reset()
        }
    }.flowOn(Dispatchers.IO)

    override fun resetConversation() {
        val currentEngine = engine ?: return
        scope.launch {
            conversation?.close()
            conversation = currentEngine.createConversation(
                ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7))
            )
            Log.d(TAG, "Conversation reset")
        }
    }

    /**
     * Reset conversation for stateless inference.
     * The prompt already contains full conversation history,
     * so we clear the conversation state to avoid duplicate history.
     */
    private fun resetConversationForInference() {
        val currentEngine = engine ?: throw IllegalStateException("Engine not initialized")
        conversation?.close()
        conversation = currentEngine.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7))
        )
    }

    override fun stopInference() {
        conversation?.cancelProcess()
        _isProcessing.value = false
        Log.d(TAG, "Inference stopped")
    }

    override fun unloadModel() {
        _isUnloading.value = true
        scope.launch {
            try {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null
                currentModelPath = null
                _isReady.value = false
                _isProcessing.value = false
                _isDownloading.value = false
                _downloadProgress.value = DownloadProgress()
                _modelStatus.value = ModelStatus.DOWNLOADED
                Log.d(TAG, "Model unloaded - resources released, file still on disk")
            } finally {
                _isUnloading.value = false
            }
        }
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

    private fun floatArrayToLittleEndianBytes(floatArray: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floatArray.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (f in floatArray) buffer.putFloat(f)
        return buffer.array()
    }

    private fun extractTextFromMessage(message: Message): String {
        return try {
            val contents = message.javaClass.getMethod("getContents").invoke(message)
            if (contents != null) {
                // Contents.getContents() returns List<Content>
                val contentList = contents.javaClass.getMethod("getContents").invoke(contents) as? List<*>
                if (contentList != null) {
                    val result = contentList.mapNotNull { item ->
                        when {
                            item == null -> null
                            item is String -> item
                            else -> {
                                // Try to get text from Content item
                                // Check if it's a Content.Text by looking for getText method
                                try {
                                    item.javaClass.getMethod("getText").invoke(item) as? String
                                } catch (_: Exception) {
                                    // Not a text content, skip
                                    null
                                }
                            }
                        }
                    }.joinToString("")
                    result
                } else {
                    contents.toString().takeIf { it != "null" } ?: ""
                }
            } else ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract text from message: ${e.message}")
            ""
        }
    }

    companion object {
        private const val TAG = "LiteRtInferenceBridge"
    }
}