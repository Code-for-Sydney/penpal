package com.penpal.core.ai

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manager to handle model downloads using WorkManager.
 */
class ModelDownloadManager(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun downloadModel(modelName: String) {
        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_NAME to modelName))
            .addTag(TAG_DOWNLOAD)
            .build()

        workManager.enqueueUniqueWork(
            "download_$modelName",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    fun getDownloadProgress(modelName: String): Flow<Int?> {
        return workManager.getWorkInfosForUniqueWorkLiveData("download_$modelName")
            .asFlow()
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull()
                if (workInfo?.state == WorkInfo.State.RUNNING) {
                    workInfo.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                } else if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                    100
                } else {
                    null
                }
            }
    }

    fun cancelDownload(modelName: String) {
        workManager.cancelUniqueWork("download_$modelName")
    }

    companion object {
        private const val TAG_DOWNLOAD = "model_download"
    }
}
