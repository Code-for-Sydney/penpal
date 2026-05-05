package com.penpal.core.processing

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.penpal.core.data.ExtractionJobDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtractionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val extractionJobDao = com.penpal.core.data.PenpalDatabase.getInstance(context).extractionJobDao()
    private val notificationHelper = NotificationHelper(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()

        setProgress(workDataOf(KEY_PROGRESS to 0))
        notificationHelper.showProgressNotification(jobId, "Loading...", 0)

        val job = extractionJobDao.getJob(jobId) ?: return@withContext Result.failure()

        try {
            extractionJobDao.updateStatus(jobId, "RUNNING")
            setProgress(workDataOf(KEY_PROGRESS to 10))
            notificationHelper.showProgressNotification(jobId, job.sourceUri, 10)

            val uri = Uri.parse(job.sourceUri)
            setProgress(workDataOf(KEY_PROGRESS to 50))
            notificationHelper.showProgressNotification(jobId, job.sourceUri, 50)

            val chunks = listOf(
                com.penpal.core.ai.RawChunk(
                    jobId + "_0",
                    job.sourceUri,
                    "Parsed content placeholder",
                    0
                )
            )

            setProgress(workDataOf(KEY_PROGRESS to 70))
            notificationHelper.showProgressNotification(jobId, job.sourceUri, 70)

            if (chunks.isNotEmpty()) {
                val insertProgress = 70 + (30 * chunks.size / 100).coerceAtMost(30)
                setProgress(workDataOf(KEY_PROGRESS to insertProgress))
                notificationHelper.showProgressNotification(jobId, job.sourceUri, insertProgress)
            }

            extractionJobDao.updateStatus(jobId, "DONE")
            extractionJobDao.updateProgress(jobId, 100)

            // Show completion notification
            notificationHelper.showCompletionNotification(jobId, job.sourceUri)

            Result.success(workDataOf(KEY_JOB_ID to jobId))

        } catch (e: Exception) {
            extractionJobDao.updateStatus(jobId, "FAILED")
            // Show failure notification
            notificationHelper.showFailureNotification(jobId, job.sourceUri, e.message ?: "Unknown error")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
    }
}