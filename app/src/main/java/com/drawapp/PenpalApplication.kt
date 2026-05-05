package com.drawapp

import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.penpal.core.ai.InferenceBridge
import com.penpal.core.ai.LiteRtInferenceBridge
import com.penpal.core.ai.MiniLmEmbedder
import com.penpal.core.ai.VectorStoreRepositoryImpl
import com.penpal.core.processing.NotificationHelper
import com.penpal.core.processing.WorkerLauncher
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PenpalApplication : Application() {

    lateinit var gemmaServer: GemmaServerClient
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    private val TAG = "PenpalApp"

    override fun onCreate() {
        super.onCreate()

        // Initialize notification channels for extraction jobs
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannel()

        PDFBoxResourceLoader.init(this)

        gemmaServer = GemmaServerClient(this)

        Log.d(TAG, "Pre-loading model in background...")
        ioScope.launch {
            val recognizer = HandwritingRecognizer.getInstance(this@PenpalApplication)
            if (!recognizer.isReady) {
                val existingModel = ModelManager.findExistingModel(this@PenpalApplication)
                if (existingModel != null) {
                    recognizer.load(existingModel, HandwritingRecognizer.InferenceConfig(), onReady = {
                        Log.d(TAG, "Model pre-loaded successfully")
                    }, onError = { error ->
                        Log.e(TAG, "Model pre-load failed: $error")
                    })
                }
            } else {
                Log.d(TAG, "Model already ready")
            }
        }
    }

    val vectorStore: VectorStoreRepositoryImpl by lazy {
        val database = com.penpal.core.data.PenpalDatabase.getInstance(this)
        VectorStoreRepositoryImpl(database.chunkDao(), MiniLmEmbedder(), gson)
    }

    val workerLauncher: WorkerLauncher by lazy {
        val database = com.penpal.core.data.PenpalDatabase.getInstance(this)
        WorkerLauncher(this, database.extractionJobDao())
    }

    val inferenceBridge: InferenceBridge by lazy {
        LiteRtInferenceBridge(this)
    }

    val gson: Gson by lazy { Gson() }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
