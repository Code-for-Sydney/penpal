package com.penpal.core.ai

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for LLM inference using ML Kit GenAI API with LiteRT-LM.
 * Based on the Google AI Edge Gallery implementation pattern.
 */
interface InferenceBridge {
    val isReady: StateFlow<Boolean>
    val isProcessing: StateFlow<Boolean>
    val isDownloading: StateFlow<Boolean>
    val downloadProgress: StateFlow<DownloadProgress>
    val modelStatus: StateFlow<ModelStatus>

    /**
     * Initialize the model for inference.
     * @param context Application context
     * @param modelName The model name (e.g., "google/gemma-4-e2b-it")
     * @param onDone Callback with result message
     */
    fun initialize(
        context: Context,
        modelName: String = "google/gemma-4-e2b-it",
        onDone: (String) -> Unit
    )

    /**
     * Check if the model is downloaded and ready.
     */
    suspend fun isModelDownloaded(): Boolean

    /**
     * Download the model if not present.
     */
    fun downloadModel(
        context: Context,
        modelName: String,
        coroutineScope: CoroutineScope,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Download the model with a listener interface.
     */
    fun downloadModel(listener: DownloadProgressListener)

    /**
     * Delete the downloaded model.
     */
    fun deleteModel()

    /**
     * Run inference with text input.
     * @param input The text prompt
     * @param resultListener Callback for partial and final results
     * @param cleanUpListener Callback for cleanup
     * @param onError Error callback
     */
    fun runInference(
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Run inference with text and image input.
     */
    fun runInferenceWithImage(
        input: String,
        image: Bitmap,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Reset conversation history.
     */
    fun resetConversation()

    /**
     * Stop ongoing inference.
     */
    fun stopInference()

    /**
     * Release model resources.
     */
    fun release()

    /**
     * Download progress listener interface.
     */
    interface DownloadProgressListener {
        fun onProgress(progress: Float)
        fun onComplete()
        fun onError(message: String)
    }
}

/**
 * Model download/installation status.
 */
enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR
}

/**
 * Download progress data class.
 */
data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val percentage: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
}

/**
 * Configuration for inference parameters.
 */
data class InferenceConfig(
    val temperature: Float = 0.3f,
    val topK: Int = 16,
    val topP: Float = 0.95f,
    val maxTokens: Int = 4096,
    val prompt: String = "Analyze the handwriting in this image."
)

/**
 * Detected item from image analysis.
 */
data class DetectedItem(
    val text: String,
    val boxYmin: Float,
    val boxXmin: Float,
    val boxYmax: Float,
    val boxXmax: Float
)