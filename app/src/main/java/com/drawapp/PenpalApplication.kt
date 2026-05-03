package com.drawapp

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch

class PenpalApplication : Application() {
    
    lateinit var gemmaServer: GemmaServerClient
        private set
    
    private val TAG = "PenpalApp"
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize PDFBox
        PDFBoxResourceLoader.init(this)
        
        // Initialize shared Gemma server client
        gemmaServer = GemmaServerClient(this)
        
        // Pre-load the model in background on app start (not blocking)
        // This happens before user opens a notebook so it's ready when needed
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
    
    // Separate scope for background initialization
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
}
