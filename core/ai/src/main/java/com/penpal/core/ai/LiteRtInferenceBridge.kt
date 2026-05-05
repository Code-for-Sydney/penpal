package com.penpal.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM implementation of InferenceBridge using ML Kit GenAI API.
 * 
 * NOTE: Currently delegating to OllamaInferenceBridge as LiteRT-LM dependencies
 * are not publicly available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtInferenceBridge(context: Context) : InferenceBridge {

    private val ollamaBridge = OllamaInferenceBridge(context)

    override val isReady: StateFlow<Boolean> = ollamaBridge.isReady
    override val isProcessing: StateFlow<Boolean> = ollamaBridge.isProcessing
    override val isDownloading: StateFlow<Boolean> = ollamaBridge.isDownloading
    override val downloadProgress: StateFlow<DownloadProgress> = ollamaBridge.downloadProgress
    override val modelStatus: StateFlow<ModelStatus> = ollamaBridge.modelStatus

    override fun initialize(
        context: Context,
        modelName: String,
        onDone: (String) -> Unit
    ) {
        ollamaBridge.initialize(context, modelName, onDone)
    }

    override suspend fun isModelDownloaded(): Boolean {
        return ollamaBridge.isModelDownloaded()
    }

    override fun downloadModel(
        context: Context,
        modelName: String,
        coroutineScope: CoroutineScope,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        ollamaBridge.downloadModel(context, modelName, coroutineScope, onProgress, onDone, onError)
    }

    override fun downloadModel(listener: InferenceBridge.DownloadProgressListener) {
        ollamaBridge.downloadModel(listener)
    }

    override fun deleteModel() {
        ollamaBridge.deleteModel()
    }

    override fun runInference(
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    ) {
        ollamaBridge.runInference(input, resultListener, cleanUpListener, onError)
    }

    override fun runInferenceWithImage(
        input: String,
        image: Bitmap,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (String) -> Unit
    ) {
        ollamaBridge.runInferenceWithImage(input, image, resultListener, cleanUpListener, onError)
    }

    override fun resetConversation() {
        ollamaBridge.resetConversation()
    }

    override fun stopInference() {
        ollamaBridge.stopInference()
    }

    override fun release() {
        ollamaBridge.release()
    }

    companion object {
        private const val TAG = "LiteRtInferenceBridge"
    }
}