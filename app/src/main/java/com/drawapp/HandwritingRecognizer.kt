package com.drawapp

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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class HandwritingRecognizer private constructor(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isReady = MutableStateFlow(false)
    val isReadyFlow: StateFlow<Boolean> = _isReady.asStateFlow()

    var isReady: Boolean
        get() = _isReady.value
        private set(value) { _isReady.value = value }

    private var isInitializing = false
    private val loadCallbacks = mutableListOf<Pair<() -> Unit, (String) -> Unit>>()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessingFlow: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessageFlow: StateFlow<String?> = _errorMessage.asStateFlow()

    sealed class AnyRequest {
        data class ImageOnly(val request: InferenceRequest) : AnyRequest()
        data class WithAudio(val request: InferenceRequestWithAudio) : AnyRequest()
    }

    private val anyRequestChannel = Channel<AnyRequest>(Channel.UNLIMITED)

    companion object {
        private const val TAG = "HandwritingRecognizer"
        
        @Volatile
        private var instance: HandwritingRecognizer? = null

        fun getInstance(context: Context): HandwritingRecognizer {
            return instance ?: synchronized(this) {
                instance ?: HandwritingRecognizer(context.applicationContext).also { instance = it }
            }
        }
    }

    data class InferenceConfig(
        val temperature: Float = 0.3f,
        val topK: Int = 16,
        val topP: Float = 0.95f,
        val prompt: String = "Analyze the handwriting in this image. What word, letter, number, or text is drawn? Detect symbols like stars (*) or asterisks as well. Reply with ONLY the recognized text."
    ) {
        companion object {
            val DEFAULT = InferenceConfig()
        }
    }

    data class InferenceRequest(
        val bitmap: Bitmap,
        val config: InferenceConfig = InferenceConfig(),
        val isBackground: Boolean = false,
        val onPartialResult: (String) -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit
    )

    data class InferenceRequestWithAudio(
        val bitmap: Bitmap,
        val audioData: ByteArray,
        val config: InferenceConfig,
        val onPartialResult: (String) -> Unit,
        val onDone: () -> Unit,
        val onError: (String) -> Unit
    )

    init {
        ioScope.launch {
            for (request in anyRequestChannel) {
                when (request) {
                    is AnyRequest.ImageOnly -> runInference(request.request)
                    is AnyRequest.WithAudio -> runInferenceWithAudio(request.request)
                }
            }
        }
    }

    fun initialize() {
        if (isReady) return
        val path = ModelManager.findExistingModel(context)
        if (path != null) {
            load(path, InferenceConfig(), {}, {})
        }
    }

    @OptIn(ExperimentalApi::class)
    fun load(
        modelPath: String,
        config: InferenceConfig = InferenceConfig(),
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        synchronized(this) {
            if (isReady && engine != null) {
                onReady()
                return
            }
            
            loadCallbacks.add(onReady to onError)
            if (isInitializing) return
            isInitializing = true
        }
        
        isReady = false
        _errorMessage.value = null
        
        ioScope.launch {
            val (success, error) = tryWithBackendFallback(modelPath, config)
            withContext(Dispatchers.Main) {
                if (success) {
                    loadCallbacks.forEach { it.first() }
                } else {
                    _errorMessage.value = error
                    loadCallbacks.forEach { it.second(error) }
                }
                loadCallbacks.clear()
                isInitializing = false
            }
        }
    }

    private suspend fun tryWithBackendFallback(
        modelPath: String,
        config: InferenceConfig
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val backends = listOf(
            Triple("GPU", Backend.GPU(), Backend.GPU()),
            Triple("CPU", Backend.CPU(), Backend.CPU())
        )
        
        for ((backendName, backend, visionBackend) in backends) {
            try {
                Log.d(TAG, "Trying backend: $backendName")
                engine?.close()
                conversation?.close()
                engine = null
                conversation = null
                
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = visionBackend,
                    audioBackend = Backend.CPU(),
                    maxNumImages = 1
                )
                
                val newEngine = Engine(engineConfig)
                Log.d(TAG, "Initializing Engine with $backendName...")
                newEngine.initialize()
                
                val samplerConfig = SamplerConfig(
                    topK = config.topK,
                    topP = config.topP.toDouble(),
                    temperature = config.temperature.toDouble()
                )
                
                val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
                val newConversation = newEngine.createConversation(conversationConfig)
                
                engine = newEngine
                conversation = newConversation
                isReady = true
                Log.d(TAG, "Model ready with $backendName backend")
                return@withContext Pair(true, "")
            } catch (e: Exception) {
                Log.e(TAG, "$backendName backend failed: ${e.message}")
                if (backendName == "CPU") {
                    return@withContext Pair(false, e.message ?: "Unknown error")
                }
            }
        }
        Pair(false, "No backends available")
    }

    fun recognize(
        bitmap: Bitmap,
        config: InferenceConfig = InferenceConfig(),
        isBackground: Boolean = false,
        onPartialResult: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val request = InferenceRequest(bitmap, config, isBackground, onPartialResult, onDone, onError)
        ioScope.launch {
            anyRequestChannel.send(AnyRequest.ImageOnly(request))
        }
    }

    @OptIn(ExperimentalApi::class)
    fun recognizeWithAudio(
        bitmap: Bitmap,
        audioData: ByteArray,
        config: InferenceConfig = InferenceConfig(),
        onPartialResult: (String) -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val request = InferenceRequestWithAudio(bitmap, audioData, config, onPartialResult, onDone, onError)
        ioScope.launch {
            anyRequestChannel.send(AnyRequest.WithAudio(request))
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String? = null): String = suspendCancellableCoroutine { cont ->
        val currentConversation = conversation ?: run {
            cont.resumeWithException(Exception("Model not loaded"))
            return@suspendCancellableCoroutine
        }

        val transcriptionPrompt = prompt ?: "Transcribe this audio accurately. If you cannot hear clearly, say \"Inaudible\". Only output the transcription."

        val content = Contents.of(Content.AudioBytes(audioData), Content.Text(transcriptionPrompt))

        currentConversation.sendMessageAsync(
            content,
            object : MessageCallback {
                private var accumulated = ""

                override fun onMessage(message: Message) {
                    val text = message.toString()
                    accumulated += text
                }

                override fun onDone() {
                    cont.resume(accumulated, onCancellation = null)
                }

                override fun onError(throwable: Throwable) {
                    cont.resumeWithException(Exception(throwable.message ?: "Transcription failed"))
                }
            }
        )
    }

    private suspend fun runInference(request: InferenceRequest) {
        val currentConversation = conversation ?: run {
            withContext(Dispatchers.Main) {
                request.onError("Model not loaded")
            }
            return
        }

        val isCancelled = AtomicBoolean(false)

        try {
            if (!request.isBackground) {
                _isProcessing.value = true
            }
            
            withTimeout(30000L) {
                val stream = ByteArrayOutputStream()
                request.bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val imageBytes = stream.toByteArray()

                val content = Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.Text(request.config.prompt)
                )

                suspendCancellableCoroutine { continuation ->
                    currentConversation.sendMessageAsync(
                        content,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val text = message.toString()
                                if (text.isNotBlank()) request.onPartialResult(text)
                            }

                            override fun onDone() {
                                if (!isCancelled.get()) {
                                    request.onDone()
                                    continuation.resume(Unit, onCancellation = null)
                                }
                            }

                            override fun onError(throwable: Throwable) {
                                if (!isCancelled.get()) {
                                    if (throwable is CancellationException) {
                                        request.onError("Recognition cancelled")
                                    } else {
                                        request.onError("Recognition failed: ${throwable.message}")
                                    }
                                    continuation.resume(Unit, onCancellation = null)
                                }
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (!isCancelled.get()) {
                withContext(Dispatchers.Main) {
                    if (e is TimeoutCancellationException) {
                        request.onError("Recognition timed out")
                    } else {
                        request.onError("Recognition failed: ${e.message}")
                    }
                }
            }
        } finally {
            if (!request.isBackground) {
                _isProcessing.value = false
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun runInferenceWithAudio(request: InferenceRequestWithAudio) {
        val currentConversation = conversation ?: run {
            withContext(Dispatchers.Main) {
                request.onError("Model not loaded")
            }
            return
        }

        try {
            _isProcessing.value = true
            
            withTimeout(30000L) {
                val stream = ByteArrayOutputStream()
                request.bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val imageBytes = stream.toByteArray()

                val content = Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.AudioBytes(request.audioData),
                    Content.Text(request.config.prompt)
                )

                suspendCancellableCoroutine { continuation ->
                    currentConversation.sendMessageAsync(
                        content,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val text = message.toString()
                                if (text.isNotBlank()) request.onPartialResult(text)
                            }

                            override fun onDone() {
                                request.onDone()
                                continuation.resume(Unit, onCancellation = null)
                            }

                            override fun onError(throwable: Throwable) {
                                if (throwable is CancellationException) {
                                    request.onError("Recognition cancelled")
                                } else {
                                    request.onError("Recognition failed: ${throwable.message}")
                                }
                                continuation.resume(Unit, onCancellation = null)
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (e is TimeoutCancellationException) {
                    request.onError("Recognition timed out")
                } else {
                    request.onError("Recognition failed: ${e.message}")
                }
            }
        } finally {
            _isProcessing.value = false
        }
    }

    @OptIn(ExperimentalApi::class)
    fun resetConversation(config: InferenceConfig = InferenceConfig()) {
        val currentEngine = engine ?: return
        val currentConversation = conversation ?: return
        
        ioScope.launch {
            try {
                Log.d(TAG, "Resetting conversation")
                currentConversation.close()
                
                val samplerConfig = SamplerConfig(
                    topK = config.topK,
                    topP = config.topP.toDouble(),
                    temperature = config.temperature.toDouble()
                )
                
                val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
                conversation = currentEngine.createConversation(conversationConfig)
                Log.d(TAG, "Conversation reset complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset conversation: ${e.message}")
            }
        }
    }

    fun close() {
        ioScope.launch {
            try {
                conversation?.close()
                engine?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing resources: ${e.message}")
            } finally {
                conversation = null
                engine = null
                isReady = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
