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
/**
 * Wraps LiteRT-LM (Gemma) for handwriting recognition.
 * Shared singleton instance to keep the engine alive across activities.
 */
class HandwritingRecognizer private constructor(private val context: Context) {

    private var engine: Engine? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isReady = MutableStateFlow(false)
    val isReadyFlow: StateFlow<Boolean> = _isReady.asStateFlow()

    var isReady: Boolean
        get() = _isReady.value
        private set(value) { _isReady.value = value }

    private var isInitializing = false
    private val loadCallbacks = mutableListOf<Pair<() -> Unit, (String) -> Unit>>()

    var isRecognizing = false
        private set

    companion object {
        @Volatile
        private var instance: HandwritingRecognizer? = null

        fun getInstance(context: Context): HandwritingRecognizer {
            return instance ?: synchronized(this) {
                instance ?: HandwritingRecognizer(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Initial initialization of the engine.
     * Looks for an existing model and loads it if found.
     */
    fun initialize() {
        if (isReady) return
        val path = ModelManager.findExistingModel(context)
        if (path != null) {
            load(path, {}, {})
        }
    }

    fun load(
        modelPath: String,
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        synchronized(this) {
            if (isReady && engine != null) {
                onReady()
                return
            }
            
            loadCallbacks.add(onReady to onError)
            if (isInitializing) return
            isInitializing = true
        }
        
        isReady = false
        ioScope.launch {
            try {
                // Release any previously loaded engine
                engine?.close()
                engine = null

                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    maxNumImages = 1
                )

                val newEngine = Engine(config)
                newEngine.initialize()
                
                engine = newEngine
                isReady = true
                
                val callbacks = synchronized(this@HandwritingRecognizer) {
                    isInitializing = false
                    val c = loadCallbacks.toList()
                    loadCallbacks.clear()
                    c
                }
                withContext(Dispatchers.Main) { 
                    callbacks.forEach { it.first() } 
                }
            } catch (e: Exception) {
                val fullError = e.stackTraceToString()
                val callbacks = synchronized(this@HandwritingRecognizer) {
                    isInitializing = false
                    val c = loadCallbacks.toList()
                    loadCallbacks.clear()
                    c
                }
                withContext(Dispatchers.Main) {
                    callbacks.forEach { it.second("Model load failed: ${e.message}\n$fullError") }
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

    /** Only call this when the application is actually shutting down if necessary. */
    fun close() {
        // Typically not needed for a singleton in Android unless you want to force release memory
        engine?.close()
        engine = null
        isReady = false
    }
}

