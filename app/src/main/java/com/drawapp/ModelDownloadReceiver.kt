package com.drawapp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the system DOWNLOAD_COMPLETE broadcast.
 * Checks if it's our model download, then tells MainActivity to load it.
 */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val savedId = ModelManager.savedDownloadId(context)

        if (completedId != savedId || completedId == -1L) return

        val status = ModelManager.queryDownload(context, completedId)

        if (status.state == ModelManager.DownloadState.DONE) {
            val modelPath = ModelManager.modelFile(context).absolutePath
            ModelManager.saveModelPath(context, modelPath)
            
            // Trigger global engine initialization
            HandwritingRecognizer.getInstance(context).load(modelPath)

            // Notify any running activity via a local broadcast
            val notify = Intent(ACTION_MODEL_READY).putExtra(EXTRA_MODEL_PATH, modelPath)
            context.sendBroadcast(notify)
        }
    }

    companion object {
        const val ACTION_MODEL_READY = "com.drawapp.MODEL_READY"
        const val EXTRA_MODEL_PATH   = "model_path"
    }
}
