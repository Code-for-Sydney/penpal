package com.penpal.core.ai.model

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelDownloadManager(private val context: Context) {
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val activeDownloads = mutableMapOf<String, Job>()

    fun downloadModel(modelName: String) {
        activeDownloads[modelName]?.cancel()
        activeDownloads[modelName] = CoroutineScope(Dispatchers.IO).launch {
            for (progress in 0..100 step 5) {
                _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                    put(modelName, progress)
                }
                kotlinx.coroutines.delay(500)
                if (!_downloadProgress.value.containsKey(modelName)) break
            }
        }
    }

    fun cancelDownload(modelName: String) {
        activeDownloads[modelName]?.cancel()
        activeDownloads.remove(modelName)
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            remove(modelName)
        }
    }

    fun getDownloadProgress(modelName: String): Flow<Int?> = flow {
        while (true) {
            emit(_downloadProgress.value[modelName])
            kotlinx.coroutines.delay(200)
        }
    }

    suspend fun listAvailableModels(): List<ModelManager.ModelInfo> = withContext(Dispatchers.IO) {
        ModelManager.listAvailableModels(context)
    }
}