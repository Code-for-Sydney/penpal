package com.drawapp

import android.app.Application

class PenpalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the shared recognizer instance on startup
        // This will start loading the model if it exists
        HandwritingRecognizer.getInstance(this).initialize()
    }
}
