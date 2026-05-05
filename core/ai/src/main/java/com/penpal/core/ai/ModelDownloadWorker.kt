package com.penpal.core.ai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.collect

/**
 * WorkManager worker to download Ollama models in the background.
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: return Result.failure()
        val apiService = OllamaApiService() // In a real app, use Hilt to inject

        return try {
            apiService.pullModel(modelName).collect { response ->
                if (response.status == "success") {
                    // Handled by completion
                } else if (response.total != null && response.completed != null) {
                    val progress = (response.completed.toFloat() / response.total.toFloat() * 100).toInt()
                    setProgress(workDataOf(KEY_PROGRESS to progress))
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    companion object {
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
    }
}
