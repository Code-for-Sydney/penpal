package com.drawapp

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream

/**
 * Wraps LiteRT-LM (Gemma) for handwriting recognition.
 * Call [load] once, then [recognize] for each canvas bitmap.
 */
class HandwritingRecognizer(private val context: Context) {

    private var engine: Engine? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isReady = false
        private set

    var isRecognizing = false
        private set

    /** Load (or reload) the model from [modelPath] (.litertlm file on device). */
    fun load(
        modelPath: String,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        isReady = false
        ioScope.launch {
            try {
                // Release any previously loaded engine
                engine?.close()
                engine = null

                val config = EngineConfig(
                    modelPath = modelPath,
                    // Specify backends for both LLM and Vision
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    maxNumImages = 1
                )

                // Use the Engine constructor (create method is not available in this version)
                val newEngine = Engine(config)
                newEngine.initialize()
                
                engine = newEngine
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
        val currentEngine = engine ?: run {
            onError("Model not loaded")
            return
        }

        if (isRecognizing) {
            onError("Another recognition is already in progress")
            return
        }

        ioScope.launch {
            var conversation: Conversation? = null
            try {
                isRecognizing = true
                conversation = currentEngine.createConversation()

                // Resize bitmap to a standard resolution (e.g., 448x448) for the multimodal model
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 448, 448, true)

                // Convert Android Bitmap -> Encoded Bytes (JPEG) for LiteRT-LM
                val stream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val imageBytes = stream.toByteArray()
                
                // Clean up scaled bitmap
                if (scaledBitmap != bitmap) scaledBitmap.recycle()

                // Construct multimodal message
                val prompt = "Analyze the handwriting in this image. What word, letter, number, or text is drawn? Reply with ONLY the recognized text."
                val content = Contents.of(
                    Content.ImageBytes(imageBytes),
                    Content.Text(prompt)
                )

                // Stream tokens back on Main thread
                conversation.sendMessageAsync(content).collect { partial ->
                    val text = partial.toString()
                    withContext(Dispatchers.Main) {
                        if (text.isNotBlank()) onPartialResult(text)
                    }
                }

                withContext(Dispatchers.Main) {
                    onDone()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Recognition failed: ${e.message}")
                }
            } finally {
                conversation?.close()
                isRecognizing = false
            }
        }
    }

    fun close() {
        ioScope.cancel()
        engine?.close()
        engine = null
        isReady = false
    }
}
