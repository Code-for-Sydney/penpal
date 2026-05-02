package com.drawapp

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PenpalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox
        PDFBoxResourceLoader.init(this)
        
        // Initialize the shared recognizer instance on startup
        // This will start loading the model if it exists
        HandwritingRecognizer.getInstance(this).initialize()
    }
}
