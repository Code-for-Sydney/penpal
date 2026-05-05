package com.penpal.core.processing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.penpal.core.processing.R

/**
 * Helper class for managing extraction job notifications.
 * Creates notification channels and builds notification instances
 * for background extraction work.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Creates the notification channel required for Android O+ devices.
     * Should be called during application startup.
     */
    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Document Extraction",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress when extracting documents for RAG indexing"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)

        // Channel for completion/failure notifications (higher priority)
        val completionChannel = NotificationChannel(
            COMPLETION_CHANNEL_ID,
            "Extraction Results",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when document extraction completes or fails"
        }
        notificationManager.createNotificationChannel(completionChannel)
    }

    /**
     * Shows a progress notification for a running extraction job.
     * @param jobId Unique identifier for the job
     * @param sourceUri The source being processed (for display)
     * @param progress Current progress percentage (0-100)
     */
    fun showProgressNotification(jobId: String, sourceUri: String, progress: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Extracting document")
            .setContentText(getSourceDisplayName(sourceUri))
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

        notificationManager.notify(jobId.hashCode(), notification)
    }

    /**
     * Shows a completion notification when a job finishes successfully.
     * @param jobId Unique identifier for the job
     * @param sourceUri The source that was processed
     */
    fun showCompletionNotification(jobId: String, sourceUri: String) {
        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setContentTitle("Extraction complete")
            .setContentText("${getSourceDisplayName(sourceUri)} has been indexed")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        notificationManager.notify(jobId.hashCode(), notification)
    }

    /**
     * Shows a failure notification when a job fails.
     * @param jobId Unique identifier for the job
     * @param sourceUri The source that failed to process
     * @param errorMessage The error that occurred
     */
    fun showFailureNotification(jobId: String, sourceUri: String, errorMessage: String) {
        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setContentTitle("Extraction failed")
            .setContentText("${getSourceDisplayName(sourceUri)}: $errorMessage")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(jobId.hashCode(), notification)
    }

    /**
     * Cancels any notification for a specific job.
     * @param jobId Unique identifier for the job
     */
    fun cancelNotification(jobId: String) {
        notificationManager.cancel(jobId.hashCode())
    }

    /**
     * Extracts a readable display name from a URI or URL.
     */
    private fun getSourceDisplayName(sourceUri: String): String {
        return when {
            sourceUri.startsWith("http://") || sourceUri.startsWith("https://") -> {
                try {
                    val url = java.net.URL(sourceUri)
                    url.host + url.path
                } catch (e: Exception) {
                    sourceUri.takeLast(30)
                }
            }
            sourceUri.contains("/") -> {
                sourceUri.substringAfterLast("/").take(30)
            }
            else -> sourceUri.take(30)
        }
    }

    companion object {
        const val CHANNEL_ID = "extraction_progress"
        const val COMPLETION_CHANNEL_ID = "extraction_results"
    }
}
