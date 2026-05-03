package com.drawapp

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LlmInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlmInferenceEngine"

        const val DEFAULT_MAX_TOKENS = 4096
        const val DEFAULT_TOP_K = 64
        const val DEFAULT_TOP_P = 0.95f
        const val DEFAULT_TEMPERATURE = 1.0f
    }

    enum class Accelerator {
        CPU, GPU, NPU, TPU
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var modelPath: String? = null
    private var isInitialized = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    interface InferenceCallback {
        fun onPartialResult(text: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    data class Config(
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
        val topK: Int = DEFAULT_TOP_K,
        val topP: Float = DEFAULT_TOP_P,
        val temperature: Float = DEFAULT_TEMPERATURE,
        val accelerator: Accelerator = Accelerator.GPU,
        val systemInstruction: String? = null,
        val enableConversationConstrainedDecoding: Boolean = false
    )

    fun isReady(): Boolean = isInitialized && engine != null && conversation != null

    fun getModelPath(): String? = modelPath

    @OptIn(ExperimentalApi::class)
    fun initialize(
        modelPath: String,
        config: Config = Config(),
        callback: (String) -> Unit
    ) {
        Log.d(TAG, "Initializing engine with model: $modelPath")

        if (isInitialized) {
            cleanUp { doInitialize(modelPath, config, callback) }
        } else {
            doInitialize(modelPath, config, callback)
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun doInitialize(
        modelPath: String,
        config: Config,
        callback: (String) -> Unit
    ) {
        tryWithBackendFallback(modelPath, config) { success, error ->
            if (success) {
                callback("")
            } else {
                callback(error)
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun tryWithBackendFallback(
        modelPath: String,
        config: Config,
        callback: (Boolean, String) -> Unit
    ) {
        val backends = when (config.accelerator) {
            Accelerator.CPU -> listOf(Triple("CPU", Backend.CPU(), null))
            Accelerator.GPU -> listOf(
                Triple("GPU", Backend.GPU(), null),
                Triple("CPU", Backend.CPU(), null)
            )
            Accelerator.NPU, Accelerator.TPU -> listOf(
                Triple("NPU", Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir), null)
            )
        }
        
        for ((backendName, backend, _) in backends) {
            try {
                this.modelPath = modelPath
                Log.d(TAG, "Trying $backendName backend...")
                
                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = config.maxTokens,
                    cacheDir = context.getExternalFilesDir(null)?.absolutePath
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                ExperimentalFlags.enableConversationConstrainedDecoding =
                    config.enableConversationConstrainedDecoding

                val samplerConfig = if (config.accelerator == Accelerator.NPU || config.accelerator == Accelerator.TPU) {
                    null
                } else {
                    SamplerConfig(
                        topK = config.topK,
                        topP = config.topP.toDouble(),
                        temperature = config.temperature.toDouble()
                    )
                }

                val systemInstruction = config.systemInstruction?.let {
                    Contents.of(Content.Text(it))
                }

                conversation = engine!!.createConversation(
                    ConversationConfig(
                        samplerConfig = samplerConfig,
                        systemInstruction = systemInstruction
                    )
                )

                ExperimentalFlags.enableConversationConstrainedDecoding = false
                isInitialized = true

                Log.d(TAG, "Engine initialized with $backendName backend")
                callback(true, "")
                return
            } catch (e: Exception) {
                Log.e(TAG, "$backendName failed: ${e.message}")
                if (backendName == backends.last().first) {
                    Log.e(TAG, "All backends failed")
                    isInitialized = false
                    callback(false, "Initialization failed: ${e.message}")
                    return
                }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initializeSuspend(modelPath: String, config: Config = Config()): Boolean =
        suspendCancellableCoroutine { continuation ->
            initialize(modelPath, config) { error ->
                if (continuation.isActive) {
                    if (error.isEmpty()) {
                        continuation.resume(true)
                    } else {
                        continuation.resumeWithException(Exception(error))
                    }
                }
            }
        }

    fun runInference(input: String, callback: InferenceCallback) {
        if (!isInitialized) {
            callback.onError("Engine not initialized")
            return
        }

        val conv = conversation
        if (conv == null) {
            callback.onError("Conversation not available")
            return
        }

        scope.launch {
            try {
                val contents = Contents.of(Content.Text(input))

                conv.sendMessageAsync(
                    contents,
                    object : MessageCallback {
                        private val fullResponse = StringBuilder()

                        override fun onMessage(message: Message) {
                            val text = message.toString()
                            fullResponse.append(text)
                            callback.onPartialResult(fullResponse.toString())
                        }

                        override fun onDone() {
                            callback.onComplete(fullResponse.toString())
                        }

                        override fun onError(throwable: Throwable) {
                            Log.e(TAG, "Inference error: ${throwable.message}")
                            callback.onError(throwable.message ?: "Unknown error")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Inference failed: ${e.message}", e)
                callback.onError(e.message ?: "Inference failed")
            }
        }
    }

    suspend fun runInferenceSuspend(input: String): String =
        suspendCancellableCoroutine { continuation ->
            runInference(input, object : InferenceCallback {
                override fun onPartialResult(text: String) {}
                override fun onComplete(fullText: String) {
                    if (continuation.isActive) continuation.resume(fullText)
                }
                override fun onError(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(Exception(error))
                }
            })
        }

    fun runInference(
        input: String,
        onPartialResult: (String) -> Unit = {},
        onComplete: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        runInference(input, object : InferenceCallback {
            override fun onPartialResult(text: String) { onPartialResult(text) }
            override fun onComplete(fullText: String) { onComplete(fullText) }
            override fun onError(error: String) { onError(error) }
        })
    }

    fun resetConversation() {
        val conv = conversation ?: return
        try {
            conv.close()
            if (engine != null) {
                conversation = engine!!.createConversation(ConversationConfig())
                Log.d(TAG, "Conversation reset")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset conversation: ${e.message}")
        }
    }

    fun stopInference() {
        conversation?.cancelProcess()
        Log.d(TAG, "Inference cancelled")
    }

    fun cleanUp(callback: () -> Unit = {}) {
        Log.d(TAG, "Cleaning up engine...")
        conversation?.let {
            try { it.close() } catch (e: Exception) { Log.e(TAG, "Failed to close conversation", e) }
        }
        conversation = null
        engine?.let {
            try { it.close() } catch (e: Exception) { Log.e(TAG, "Failed to close engine", e) }
        }
        engine = null
        isInitialized = false
        modelPath = null
        Log.d(TAG, "Engine cleaned up")
        callback()
    }

    fun cleanUpSync() {
        var completed = false
        cleanUp { completed = true }
        while (!completed) { Thread.sleep(10) }
    }
}