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
 * Following the pattern from Google AI Edge Gallery:
 * - Engine is initialized once and shared across the app
 * - Downloads models to app's cache directory
 * - Supports CPU, GPU, and NPU backends
 */
class LmEngineManager(private val context: Context) {

    private val mutex = Mutex()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var engine: Engine? = null

    /**
     * Get or create the shared Engine instance.
     * The engine is loaded lazily with the model at modelPath.
     *
     * @param modelPath Path to the .litertlm model file
     * @param backend Backend to use (CPU, GPU, or NPU)
     * @param forceReload If true, close existing engine and reload
     */
    suspend fun getEngine(
        modelPath: String,
        backend: Backend = Backend.CPU(),
        forceReload: Boolean = false
    ): Engine? = mutex.withLock {
        if (forceReload) {
            releaseEngine()
        }

        if (engine == null && !_isLoading.value) {
            _isLoading.value = true
            _error.value = null

            try {
                withContext(Dispatchers.IO) {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        // Use cache dir to improve 2nd load time
                        cacheDir = context.cacheDir.absolutePath
                    )

                    engine = Engine(config).apply {
                        Log.d(TAG, "Initializing LiteRT-LM Engine with model: $modelPath")
                        initialize()
                        Log.d(TAG, "Engine initialized successfully")
                    }
                }
                _isInitialized.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine", e)
                _error.value = e.message ?: "Failed to initialize engine"
                _isInitialized.value = false
            } finally {
                _isLoading.value = false
            }
        }

        engine
    }

    /**
     * Get the current engine instance without initializing.
     */
    fun getCurrentEngine(): Engine? = engine

    /**
     * Release the engine and free resources.
     */
    suspend fun releaseEngine() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                engine?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing engine", e)
            }
            engine = null
            _isInitialized.value = false
            Log.d(TAG, "Engine released")
        }
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