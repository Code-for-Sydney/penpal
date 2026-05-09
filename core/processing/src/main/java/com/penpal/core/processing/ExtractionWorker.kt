package com.penpal.core.processing

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.penpal.core.ai.VectorStoreRepository
import com.penpal.core.data.ExtractionJobDao
import com.penpal.core.data.PenpalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class ExtractionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val extractionJobDao: ExtractionJobDao = PenpalDatabase.getInstance(context).extractionJobDao()
    private val notificationHelper = NotificationHelper(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val agentPrompt = inputData.getString(KEY_AGENT_PROMPT)?.takeIf { it.isNotBlank() }

        setProgress(workDataOf(KEY_PROGRESS to 0))
        notificationHelper.showProgressNotification(jobId, "Loading...", 0)

        val job = extractionJobDao.getJob(jobId) ?: return@withContext Result.failure()

        try {
            extractionJobDao.updateStatus(jobId, "RUNNING")
            setProgress(workDataOf(KEY_PROGRESS to 10))
            notificationHelper.showProgressNotification(jobId, job.sourceUri, 10)

            val uri = Uri.parse(job.sourceUri)
            setProgress(workDataOf(KEY_PROGRESS to 30))
            notificationHelper.showProgressNotification(jobId, job.sourceUri, 30)

            // Parse the document using the appropriate parser
            val parserFactory = createParserFactory()
            val parser = parserFactory.createParser(job.mimeType)
            val chunks = parser.parse(uri, job.rule)

            setProgress(workDataOf(KEY_PROGRESS to 60))
            notificationHelper.showProgressNotification(jobId, job.sourceUri, 60)

            if (chunks.isNotEmpty()) {
                // Persist chunks to vector store for RAG
                val vectorStore = getVectorStore()
                vectorStore.embed(chunks)

                setProgress(workDataOf(KEY_PROGRESS to 90))
                notificationHelper.showProgressNotification(jobId, job.sourceUri, 90)
            }

            extractionJobDao.updateStatus(jobId, "DONE")
            extractionJobDao.updateProgress(jobId, 100)

            notificationHelper.showCompletionNotification(jobId, job.sourceUri)

            Result.success(workDataOf(KEY_JOB_ID to jobId))

        } catch (e: Exception) {
            extractionJobDao.updateStatus(jobId, "FAILED")
            notificationHelper.showFailureNotification(jobId, job.sourceUri, e.message ?: "Unknown error")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    private fun createParserFactory(): ParserFactory {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return ParserFactory(applicationContext, okHttpClient)
    }

    private fun getVectorStore(): VectorStoreRepository {
        return com.penpal.core.ai.VectorStoreProvider.instance
            ?: throw IllegalStateException("VectorStoreProvider not initialized")
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_AGENT_PROMPT = "agent_prompt"
    }
}
