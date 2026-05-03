package com.drawapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class InferenceEngineManager(private val context: Context) {

    companion object {
        private const val TAG = "InferenceEngineMgr"
        private const val PREFS_NAME = "inference_engine_prefs"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_ACCELERATOR = "accelerator"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P = "top_p"

        @Volatile
        private var instance: InferenceEngineManager? = null

        fun getInstance(context: Context): InferenceEngineManager {
            return instance ?: synchronized(this) {
                instance ?: InferenceEngineManager(context.applicationContext).also { instance = it }
            }
        }
    }

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
        fun onComplete(localPath: String)
        fun onError(message: String)
    }

    interface DownloadModelCallback {
        fun onDownloadStarted()
        fun onProgress(progress: Int, downloadedBytes: Long, totalBytes: Long)
        fun onDownloadComplete(localPath: String)
        fun onError(error: String)
    }

    data class ModelInfo(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val localPath: String,
        val isDownloaded: Boolean
    )

    data class EngineState(
        val isReady: Boolean,
        val modelPath: String?,
        val accelerator: LlmInferenceEngine.Accelerator,
        val config: LlmInferenceEngine.Config
    )

    private val engine = LlmInferenceEngine(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var currentState = EngineState(
        isReady = false,
        modelPath = prefs.getString(KEY_MODEL_PATH, null),
        accelerator = LlmInferenceEngine.Accelerator.GPU,
        config = LlmInferenceEngine.Config()
    )

    private val listeners = mutableListOf<StateListener>()

    interface StateListener {
        fun onStateChanged(state: EngineState)
    }

    fun addStateListener(listener: StateListener) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: StateListener) {
        listeners.remove(listener)
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            listeners.forEach { it.onStateChanged(currentState) }
        }
    }

    private fun updateState(update: (EngineState) -> EngineState) {
        currentState = update(currentState)
        notifyStateChanged()
    }

    fun getState(): EngineState = currentState

    fun isReady(): Boolean = engine.isReady()

    fun initialize(callback: (Boolean, String) -> Unit) {
        val modelPath = currentState.modelPath
        if (modelPath == null) {
            callback(false, "No model path configured")
            return
        }

        if (!File(modelPath).exists()) {
            callback(false, "Model file not found at: $modelPath")
            return
        }

        engine.initialize(modelPath, currentState.config) { error ->
            val success = error.isEmpty()
            if (success) {
                updateState { it.copy(isReady = true) }
            } else {
                updateState { it.copy(isReady = false) }
            }
            callback(success, error)
        }
    }

    suspend fun initializeSuspend(): Boolean {
        return withContext(Dispatchers.IO) {
            val modelPath = currentState.modelPath ?: return@withContext false
            if (!File(modelPath).exists()) return@withContext false

            try {
                engine.initializeSuspend(modelPath, currentState.config)
                updateState { it.copy(isReady = true) }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                updateState { it.copy(isReady = false) }
                false
            }
        }
    }

    fun runInference(input: String, callback: LlmInferenceEngine.InferenceCallback) {
        if (!engine.isReady()) {
            callback.onError("Engine not ready")
            return
        }
        engine.runInference(input, callback)
    }

    suspend fun runInferenceSuspend(input: String): String {
        if (!engine.isReady()) throw IllegalStateException("Engine not ready")
        return engine.runInferenceSuspend(input)
    }

    fun stopInference() {
        engine.stopInference()
    }

    fun resetConversation() {
        engine.resetConversation()
    }

    fun cleanUp() {
        engine.cleanUp()
        updateState { it.copy(isReady = false) }
    }

    fun setModelPath(path: String) {
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
        updateState { it.copy(modelPath = path, isReady = false) }
    }

    fun setAccelerator(accelerator: LlmInferenceEngine.Accelerator) {
        prefs.edit().putString(KEY_ACCELERATOR, accelerator.name).apply()
        updateState {
            it.copy(
                accelerator = accelerator,
                config = it.config.copy(accelerator = accelerator),
                isReady = false
            )
        }
    }

    fun setConfig(config: LlmInferenceEngine.Config) {
        prefs.edit().apply {
            putInt(KEY_MAX_TOKENS, config.maxTokens)
            putFloat(KEY_TEMPERATURE, config.temperature)
            putInt(KEY_TOP_K, config.topK)
            putFloat(KEY_TOP_P, config.topP)
            apply()
        }
        updateState { it.copy(config = config, isReady = false) }
    }

    fun downloadModel(
        modelUrl: String,
        fileName: String,
        sizeBytes: Long,
        callback: DownloadModelCallback
    ) {
        val baseDir = context.getExternalFilesDir(null) ?: return
        val modelDir = File(baseDir, "models")
        if (!modelDir.exists()) modelDir.mkdirs()

        val localFile = File(modelDir, fileName)

        callback.onDownloadStarted()

        scope.launch {
            try {
                val url = java.net.URL(modelUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 600000
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    mainHandler.post { callback.onError("Download failed: HTTP $responseCode") }
                    return@launch
                }

                val totalBytes = if (sizeBytes > 0) sizeBytes else connection.contentLengthLong
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    localFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                mainHandler.post { callback.onProgress(progress, downloadedBytes, totalBytes) }
                            }
                        }
                    }
                }

                connection.disconnect()

                setModelPath(localFile.absolutePath)
                mainHandler.post { callback.onDownloadComplete(localFile.absolutePath) }
                Log.d(TAG, "Model downloaded to: ${localFile.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                mainHandler.post { callback.onError(e.message ?: "Download failed") }
            }
        }
    }

    fun getLocalModelPath(): String? {
        val baseDir = context.getExternalFilesDir(null) ?: return null
        val modelDir = File(baseDir, "models")
        return modelDir.absolutePath
    }

    fun listDownloadedModels(): List<String> {
        val baseDir = context.getExternalFilesDir(null) ?: return emptyList()
        val modelDir = File(baseDir, "models")
        if (!modelDir.exists()) return emptyList()

        return modelDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".bin") || it.name.endsWith(".safetensors")) }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    fun deleteModel(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted && currentState.modelPath == path) {
                setModelPath("")
            }
            deleted
        } else false
    }

    fun getModelSize(path: String): Long {
        return File(path).length()
    }
}