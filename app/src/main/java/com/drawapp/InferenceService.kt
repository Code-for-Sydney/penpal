package com.drawapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class InferenceService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "InferenceService"

        @Volatile
        private var instance: InferenceService? = null

        fun getInstance(context: Context): InferenceService {
            return instance ?: synchronized(this) {
                instance ?: InferenceService(context.applicationContext).also { instance = it }
            }
        }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    data class Config(
        val temperature: Float = 0.7f,
        val topK: Int = 64,
        val topP: Float = 0.95f,
        val maxTokens: Int = 4096,
        val useGpu: Boolean = true
    )

    fun initialize(modelPath: String, config: Config = Config(), callback: (Boolean, String) -> Unit) {
        scope.launch {
            // Try GPU first, then CPU
            val success = tryInitializeWithFallback(modelPath, config)
            callback(success.first, success.second)
        }
    }

    private suspend fun tryInitializeWithFallback(modelPath: String, config: Config): Pair<Boolean, String> {
        val backends = if (config.useGpu) {
            listOf(
                Triple("GPU", Backend.GPU(), Backend.GPU()),
                Triple("CPU", Backend.CPU(), Backend.CPU())
            )
        } else {
            listOf(Triple("CPU", Backend.CPU(), Backend.CPU()))
        }
        
        for ((backendName, backend, visionBackend) in backends) {
            try {
                Log.d(TAG, "Trying $backendName backend...")
                engine?.close()
                conversation?.close()

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = visionBackend,
                    audioBackend = Backend.CPU(),
                    maxNumImages = 1,
                    maxNumTokens = config.maxTokens
                )

                engine = Engine(engineConfig)
                engine!!.initialize()

                val samplerConfig = SamplerConfig(
                    topK = config.topK,
                    topP = config.topP.toDouble(),
                    temperature = config.temperature.toDouble()
                )

                conversation = engine!!.createConversation(
                    ConversationConfig(samplerConfig = samplerConfig)
                )

                _isReady.value = true
                Log.d(TAG, "Engine initialized with $backendName backend")
                return Pair(true, "")
            } catch (e: Exception) {
                Log.e(TAG, "$backendName failed: ${e.message}")
                if (backendName == backends.last().first) {
                    Log.e(TAG, "All backends failed")
                    _isReady.value = false
                    return Pair(false, e.message ?: "Initialization failed")
                }
            }
        }
        return Pair(false, "Unknown error")
    }

    fun isReady(): Boolean = _isReady.value

    fun close() {
        scope.launch {
            conversation?.close()
            engine?.close()
            engine = null
            conversation = null
            _isReady.value = false
        }
    }

    fun resetConversation() {
        val currentEngine = engine ?: return
        scope.launch {
            conversation?.close()
            conversation = currentEngine.createConversation(
                ConversationConfig(samplerConfig = SamplerConfig(
                    topK = 64,
                    topP = 0.95,
                    temperature = 0.7
                ))
            )
        }
    }

    fun stopInference() {
        conversation?.cancelProcess()
    }

    @OptIn(ExperimentalApi::class)
    suspend fun runInference(prompt: String): String {
        val conv = conversation ?: throw Exception("Not initialized")
        return suspendCoroutine { cont ->
            val content = Contents.of(Content.Text(prompt))
            conv.sendMessageAsync(content, object : MessageCallback {
                private var full = ""
                override fun onMessage(message: Message) {
                    full += message.toString()
                }
                override fun onDone() {
                    cont.resume(full)
                }
                override fun onError(throwable: Throwable) {
                    cont.resumeWithException(Exception(throwable.message))
                }
            })
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String? = null): String {
        val conv = conversation ?: throw Exception("Not initialized")
        val transcriptionPrompt = prompt ?: "Transcribe this audio accurately. Only output the transcription text."
        return suspendCoroutine { cont ->
            val content = Contents.of(Content.AudioBytes(audioData), Content.Text(transcriptionPrompt))
            conv.sendMessageAsync(content, object : MessageCallback {
                private var full = ""
                override fun onMessage(message: Message) {
                    full += message.toString()
                }
                override fun onDone() {
                    cont.resume(full)
                }
                override fun onError(throwable: Throwable) {
                    cont.resumeWithException(Exception(throwable.message))
                }
            })
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun recognizeHandwriting(bitmap: Bitmap, prompt: String? = null): String {
        val conv = conversation ?: throw Exception("Not initialized")
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val imageBytes = stream.toByteArray()
        val systemPrompt = prompt ?: "Analyze the handwriting in this image. What is written? Reply with ONLY the recognized text."
        
        return suspendCoroutine { cont ->
            val content = Contents.of(
                Content.ImageBytes(imageBytes),
                Content.Text(systemPrompt)
            )
            conv.sendMessageAsync(content, object : MessageCallback {
                private var full = ""
                override fun onMessage(message: Message) {
                    full += message.toString()
                }
                override fun onDone() {
                    cont.resume(full)
                }
                override fun onError(throwable: Throwable) {
                    cont.resumeWithException(Exception(throwable.message))
                }
            })
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun understandImage(bitmap: Bitmap, question: String): String {
        val conv = conversation ?: throw Exception("Not initialized")
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val imageBytes = stream.toByteArray()
        
        return suspendCoroutine { cont ->
            val content = Contents.of(
                Content.ImageBytes(imageBytes),
                Content.Text(question)
            )
            conv.sendMessageAsync(content, object : MessageCallback {
                private var full = ""
                override fun onMessage(message: Message) {
                    full += message.toString()
                }
                override fun onDone() {
                    cont.resume(full)
                }
                override fun onError(throwable: Throwable) {
                    cont.resumeWithException(Exception(throwable.message))
                }
            })
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun batchTranscribeAudio(
        audioFiles: List<File>,
        prompt: String? = null,
        maxConcurrency: Int = 4
    ): GemmaTranscriber.TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Batch transcribing ${audioFiles.size} files")

        if (audioFiles.isEmpty()) {
            return@withContext GemmaTranscriber.TranscriptionResult(false, "", "No files provided")
        }

        if (!_isReady.value) {
            return@withContext GemmaTranscriber.TranscriptionResult(false, "", "Engine not initialized")
        }

        val semaphore = Semaphore(maxConcurrency)
        val results = mutableListOf<GemmaTranscriber.ChunkTranscription>()

        audioFiles.forEachIndexed { index, file ->
            semaphore.acquire()
            try {
                val audioData = file.readBytes()
                val text = transcribeAudio(audioData, prompt)
                results.add(GemmaTranscriber.ChunkTranscription(index, 0, 0, text, true))
            } catch (e: Exception) {
                Log.e(TAG, "Transcription $index failed: ${e.message}")
                results.add(GemmaTranscriber.ChunkTranscription(index, 0, 0, "[Error: ${e.message}]", false))
            } finally {
                semaphore.release()
            }
        }

        val hasError = results.any { !it.success }
        val fullText = results.sortedBy { it.chunkIndex }.joinToString(" ") {
            if (it.transcription.startsWith("[Error")) "" else it.transcription
        }

        GemmaTranscriber.TranscriptionResult(!hasError, fullText.trim(), if (hasError) "Some transcriptions failed" else null, results)
    }

    @OptIn(ExperimentalApi::class)
    suspend fun transcribeAudioWithVAD(
        audioFile: File,
        prompt: String? = null,
        maxConcurrency: Int = 4
    ): GemmaTranscriber.TranscriptionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "VAD transcription of ${audioFile.absolutePath}")

        if (!_isReady.value) {
            return@withContext GemmaTranscriber.TranscriptionResult(false, "", "Engine not initialized")
        }

        val chunker = AudioChunker()
        val chunkingResult = chunker.chunkAudioFile(audioFile)

        if (chunkingResult.chunks.isEmpty()) {
            return@withContext GemmaTranscriber.TranscriptionResult(true, "", null)
        }

        Log.d(TAG, "Created ${chunkingResult.chunks.size} chunks for parallel processing")

        val semaphore = Semaphore(maxConcurrency)
        val results = mutableListOf<GemmaTranscriber.ChunkTranscription>()

        chunkingResult.chunks.forEachIndexed { index, chunk ->
            semaphore.acquire()
            try {
                val audioData = chunk.file.readBytes()
                val text = transcribeAudio(audioData, prompt)
                results.add(GemmaTranscriber.ChunkTranscription(
                    chunkIndex = index,
                    startTimeMs = chunk.startTimeMs,
                    endTimeMs = chunk.endTimeMs,
                    transcription = text,
                    success = true
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Chunk $index failed: ${e.message}")
                results.add(GemmaTranscriber.ChunkTranscription(
                    chunkIndex = index,
                    startTimeMs = chunk.startTimeMs,
                    endTimeMs = chunk.endTimeMs,
                    transcription = "[Error: ${e.message}]",
                    success = false
                ))
            } finally {
                semaphore.release()
            }
        }

        val hasError = results.any { !it.success }
        val fullText = results.sortedBy { it.chunkIndex }.joinToString(" ") {
            if (it.transcription.startsWith("[Error")) "" else it.transcription
        }

        GemmaTranscriber.TranscriptionResult(!hasError, fullText.trim(), if (hasError) "Some chunks failed" else null, results)
    }

    suspend fun processYouTube(url: String, onProgress: (Float) -> Unit): GemmaTranscriber.TranscriptionResult {
        Log.d(TAG, "Processing YouTube: $url")
        
        return try {
            val tempDir = File(context.cacheDir, "youtube_temp").apply { mkdirs() }
            val audioFile = downloadYouTubeAudio(url, tempDir)
            
            if (audioFile == null) {
                return GemmaTranscriber.TranscriptionResult(false, "", "Failed to download YouTube audio")
            }

            val transcriptionResult = transcribeAudioWithVAD(audioFile)
            audioFile.delete()
            tempDir.delete()

            transcriptionResult
        } catch (e: Exception) {
            Log.e(TAG, "YouTube processing failed: ${e.message}")
            GemmaTranscriber.TranscriptionResult(false, "", e.message)
        }
    }

    private fun downloadYouTubeAudio(url: String, outputDir: File): File? {
        return try {
            val videoId = extractYouTubeVideoId(url) ?: return null
            
            val outputFile = File(outputDir, "video_${System.currentTimeMillis()}.wav")
            
            val downloadUrl = "https://yewtu.be/api/videos/$videoId/download"
            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 600000

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP ${conn.responseCode}")
                return null
            }

            conn.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            null
        }
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("youtube\\.com/watch\\?v=([^&]+)"),
            Regex("youtu\\.be/([^?]+)"),
            Regex("youtube\\.com/embed/([^?]+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun cleanup() {
        close()
        scope.cancel()
    }
}