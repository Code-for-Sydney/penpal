package com.penpal.core.ai.inference.implementation

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LmEngineManager(private val context: Context) {
    private val mutex = Mutex()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _modelPath = MutableStateFlow<String?>(null)
    val modelPath: StateFlow<String?> = _modelPath.asStateFlow()

    private var engine: Engine? = null

    data class Config(
        val temperature: Float = 0.7f,
        val topK: Int = 64,
        val topP: Float = 0.95f,
        val maxTokens: Int = 4096,
        val useGpu: Boolean = true
    )

    suspend fun getEngine(
        modelPath: String,
        config: Config = Config(),
        forceReload: Boolean = false
    ): Boolean = mutex.withLock {
        if (forceReload) {
            releaseEngineInternal()
        }

        if (engine == null && !_isLoading.value) {
            _isLoading.value = true
            _error.value = null

            try {
                withContext(Dispatchers.IO) {
                    val modelFile = File(modelPath)
                    if (!modelFile.exists()) {
                        throw IllegalStateException("Model file not found: $modelPath")
                    }

                    Log.d(TAG, "Initializing LiteRT-LM Engine with model: $modelPath")

                    val backends = if (config.useGpu) {
                        listOf(
                            Triple("GPU", Backend.GPU(), Backend.GPU()),
                            Triple("CPU", Backend.CPU(), Backend.CPU())
                        )
                    } else {
                        listOf(Triple("CPU", Backend.CPU(), Backend.CPU()))
                    }

                    var success = false
                    for ((backendName, backend, visionBackend) in backends) {
                        try {
                            Log.d(TAG, "Trying $backendName backend...")

                            engine?.close()

                            val engineConfig = EngineConfig(
                                modelPath = modelPath,
                                backend = backend,
                                visionBackend = visionBackend,
                                maxNumTokens = config.maxTokens
                            )

                            engine = Engine(engineConfig)
                            engine!!.initialize()

                            Log.d(TAG, "Engine initialized with $backendName backend")
                            success = true
                            break

                        } catch (e: Exception) {
                            Log.e(TAG, "$backendName backend failed: ${e.message}")
                            if (backendName == backends.last().first) {
                                throw e
                            }
                        }
                    }

                    if (!success) {
                        throw IllegalStateException("All backends failed")
                    }
                }

                _modelPath.value = modelPath
                _isInitialized.value = true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine", e)
                _error.value = e.message ?: "Failed to initialize engine"
                _isInitialized.value = false
            } finally {
                _isLoading.value = false
            }
        }

        engine != null
    }

    fun getEngine(): Engine? = engine

    fun getCurrentModelPath(): String? = _modelPath.value

    suspend fun releaseEngine() = mutex.withLock {
        releaseEngineInternal()
    }

    private fun releaseEngineInternal() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine", e)
        }
        engine = null
        _modelPath.value = null
        _isInitialized.value = false
        Log.d(TAG, "Engine released")
    }

    fun isModelAvailable(modelPath: String): Boolean {
        return File(modelPath).exists()
    }

    fun getModelDirectory(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getModelPath(modelName: String): String {
        return File(getModelDirectory(), "$modelName.litertlm").absolutePath
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "LmEngineManager"
        private const val MODEL_DIR = "models"
    }
}