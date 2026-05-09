package com.penpal.core.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.penpal.core.data.ExtractionJobDao
import com.penpal.core.data.ExtractionJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class WorkerLauncher(
    private val context: Context,
    private val extractionJobDao: ExtractionJobDao
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun enqueue(uri: String, mimeType: String, rule: String, agentPrompt: String? = null): String {
        return withContext(Dispatchers.IO) {
            val jobId = UUID.randomUUID().toString()
            val job = ExtractionJobEntity(
                id = jobId,
                sourceUri = uri,
                mimeType = mimeType,
                rule = rule,
                status = "QUEUED",
                workerId = null,
                progress = 0
            )
            extractionJobDao.insert(job)

            val inputData = workDataOf(
                ExtractionWorker.KEY_JOB_ID to jobId,
                ExtractionWorker.KEY_AGENT_PROMPT to (agentPrompt ?: "")
            )
            val workRequest = OneTimeWorkRequestBuilder<ExtractionWorker>()
                .setInputData(inputData)
                .build()

            workManager.enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, workRequest)
            jobId
        }
    }

    fun observeJobs(): Flow<List<ExtractionJobEntity>> {
        return extractionJobDao.getAllJobs()
    }
}