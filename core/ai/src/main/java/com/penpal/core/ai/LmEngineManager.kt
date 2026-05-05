package com.penpal.core.ai

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

/**
 * Manages the LiteRT-LM Engine lifecycle.
 *
 * Based on the pattern from InferenceService.kt (main branch):
 * - Engine is initialized with model path and backend configuration
 * - Supports GPU, CPU backends with fallback
 * - Thread-safe initialization using Mutex
 */
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

    /**
     * Configuration for the engine.
     */
    data class Config(
        val temperature: Float = 0.7f,
        val topK: Int = 64,
        val topP: Float = 0.95f,
        val maxTokens: Int = 4096,
        val useGpu: Boolean = true
    )

    /**
     * Initialize the engine with the model at modelPath.
     *
     * @param modelPath Path to the .litertlm model file
     * @param config Configuration for the engine
     * @param forceReload If true, close existing engine and reload
     * @return true if initialization succeeded
     */
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
                    // Check if model file exists
                    val modelFile = File(modelPath)
                    if (!modelFile.exists()) {
                        throw IllegalStateException("Model file not found: $modelPath")
                    }

                    Log.d(TAG, "Initializing LiteRT-LM Engine with model: $modelPath")

                    // Pixel 8 Pro Tensor G3: GPU backend uses Adreno GPU
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

                            // Close existing engine before creating new one
                            engine?.close()

                            val engineConfig = EngineConfig(
                                modelPath = modelPath,
                                backend = backend,
                                visionBackend = visionBackend,
                                audioBackend = Backend.CPU(),
                                maxNumImages = 1,
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

    /**
     * Get the current engine instance.
     */
    fun getEngine(): Engine? = engine

    /**
     * Get the current model path.
     */
    fun getCurrentModelPath(): String? = _modelPath.value

    /**
     * Release the engine and free resources.
     */
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

    /**
     * Check if a model file exists at the given path.
     */
    fun isModelAvailable(modelPath: String): Boolean {
        return File(modelPath).exists()
    }

    /**
     * Get the default model directory in app's files directory.
     */
    fun getModelDirectory(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Build a model path for a given model name.
     */
    fun getModelPath(modelName: String): String {
        return File(getModelDirectory(), "$modelName.litertlm").absolutePath
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _error.value = null
    }

    companion object {
        private const val TAG = "LmEngineManager"
        private const val MODEL_DIR = "models"
    }
}