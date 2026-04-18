package com.drawapp

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.VisionModelOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.core.BaseOptions
import kotlinx.coroutines.*

/**
 * Wraps MediaPipe LLM Inference with a multimodal Gemma model.
 * Call [load] once, then [recognize] for each canvas bitmap.
 */
class HandwritingRecognizer(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isReady = false
        private set

    /** Load (or reload) the model from [modelPath] (.task file on device). */
    fun load(
        modelPath: String,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        isReady = false
        ioScope.launch {
            try {
                // Release any previously loaded model
                llmInference?.close()
                llmInference = null

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(4096)
                    .setMaxNumImages(1)
                    .setVisionModelOptions(
                        VisionModelOptions.builder().build()
                    )
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                isReady = true
                withContext(Dispatchers.Main) { onReady() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val fullError = e.stackTraceToString()
                    onError("Model load failed: ${e.message}\n$fullError")
                }
            }
        }
    }

    /**
     * Run multimodal inference on [bitmap] (the full canvas).
     * [onPartialResult] is called for each streamed token.
     * [onDone] is called when generation completes.
     */
    fun recognize(
        bitmap: Bitmap,
        onPartialResult: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val inference = llmInference ?: run {
            onError("Model not loaded")
            return
        }

        ioScope.launch {
            try {
                // Build a session for multimodal input
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(1)
                    .setTemperature(0.1f)
                    .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                    .build()
                val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)

                // Convert Android Bitmap → MediaPipe MPImage
                val mpImage = BitmapImageBuilder(bitmap).build()

                // Add image and prompt chunks to the session
                session.addQueryChunk("<start_of_turn>user\n")
                session.addImage(mpImage)
                session.addQueryChunk(
                    "\nAnalyze the handwriting in this image. " +
                    "What word, letter, number, or text is drawn? " +
                    "Reply with ONLY the recognized text." +
                    "<end_of_turn>\n<start_of_turn>model\n"
                )

                // Stream tokens back on Main thread
                session.generateResponseAsync { partial, done ->
                    CoroutineScope(Dispatchers.Main).launch {
                        if (partial.isNotBlank()) onPartialResult(partial)
                        if (done) {
                            session.close()
                            onDone()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Recognition failed: ${e.message}")
                }
            }
        }
    }

    fun close() {
        ioScope.cancel()
        llmInference?.close()
        llmInference = null
        isReady = false
    }
}
