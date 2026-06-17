package com.penpal.core.ai.inference.model

import android.graphics.Bitmap

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    LOADING,
    READY,
    ERROR
}

data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val percentage: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
}

data class InferenceConfig(
    val temperature: Float = 0.3f,
    val topK: Int = 16,
    val topP: Float = 0.95f,
    val maxTokens: Int = 4096,
    val prompt: String = "Analyze the handwriting in this image."
)

data class DetectedItem(
    val text: String,
    val boxYmin: Float,
    val boxXmin: Float,
    val boxYmax: Float,
    val boxXmax: Float
)