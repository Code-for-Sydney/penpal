package com.penpal.core.ai.inference

import android.content.Context
import android.graphics.Bitmap
import com.penpal.core.ai.messaging.MessagePart
import com.penpal.core.ai.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceBridge {
    val isReady: StateFlow<Boolean>
    val isProcessing: StateFlow<Boolean>
    val isDownloading: StateFlow<Boolean>
    val isUnloading: StateFlow<Boolean>
    val downloadProgress: StateFlow<com.penpal.core.ai.inference.model.DownloadProgress>
    val modelStatus: StateFlow<com.penpal.core.ai.inference.model.ModelStatus>

    fun initialize(
        context: Context,
        modelName: String = "google/gemma-4-e2b-it",
        backend: String? = null,
        onDone: (String) -> Unit
    )

    suspend fun isModelDownloaded(): Boolean

    fun downloadModel(
        context: Context,
        modelName: String,
        coroutineScope: CoroutineScope,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    )

    fun downloadModel(listener: DownloadProgressListener)

    fun deleteModel()

    fun runInference(
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    )

    fun runInferenceWithImage(
        input: String,
        image: Bitmap,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    )

    fun runInferenceFlow(input: String): Flow<String>

    fun runInferenceFlowParts(input: String): Flow<List<MessagePart>>

    fun runInferenceWithImageFlow(input: String, image: Bitmap): Flow<String>

    fun runInferenceWithImageFlowParts(input: String, image: Bitmap): Flow<List<MessagePart>>

    fun runInferenceWithAudio(
        input: String,
        audioData: FloatArray,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    )

    fun runInferenceWithAudioFlow(input: String, audioData: FloatArray): Flow<String>

    fun runInferenceWithAudioFlowParts(input: String, audioData: FloatArray): Flow<List<MessagePart>>

    fun resetConversation()

    fun stopInference()

    fun release()

    fun unloadModel()

    suspend fun listAvailableModels(context: Context): List<ModelManager.ModelInfo>

    fun loadModel(
        context: Context,
        modelPath: String,
        backend: String? = null,
        onDone: (String) -> Unit
    )

    fun deleteModel(modelPath: String)

    interface DownloadProgressListener {
        fun onProgress(progress: Float)
        fun onComplete()
        fun onError(message: String)
    }
}

typealias DownloadProgress = com.penpal.core.ai.inference.model.DownloadProgress
typealias ModelStatus = com.penpal.core.ai.inference.model.ModelStatus
typealias InferenceConfig = com.penpal.core.ai.inference.model.InferenceConfig
typealias DetectedItem = com.penpal.core.ai.inference.model.DetectedItem