package com.penpal.core.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Interface for audio transcription using Whisper or similar models.
 *
 * Implementations:
 * - [AndroidSpeechTranscriber]: Uses Android SpeechRecognizer (good for short clips)
 * - [WhisperOnnxTranscriber]: Uses ONNX Whisper model (ideal for longer files, offline)
 *
 * To use Whisper, download the ONNX model and place it in the models directory.
 */
interface WhisperTranscriber {
    /**
     * Transcribe an audio file.
     * @param audioFile WAV or other audio file
     * @return Transcription result
     */
    suspend fun transcribe(audioFile: File): TranscriptionResult

    /**
     * Check if this transcriber is available/ready.
     */
    fun isAvailable(): Boolean

    /**
     * Stream transcription progress (for long files).
     */
    fun streamTranscribe(audioFile: File): Flow<TranscriptionProgress> = flow {
        emit(TranscriptionProgress.Loading)
        val result = transcribe(audioFile)
        emit(TranscriptionProgress.Complete(result))
    }
}

/**
 * Transcription result from Whisper/SpeechRecognizer.
 */
data class TranscriptionResult(
    val text: String,
    val success: Boolean,
    val error: String? = null,
    val language: String? = null,
    val confidence: Float? = null
)

/**
 * Progress states for streaming transcription.
 */
sealed class TranscriptionProgress {
    object Loading : TranscriptionProgress()
    data class Partial(val text: String, val progressPercent: Int) : TranscriptionProgress()
    data class Complete(val result: TranscriptionResult) : TranscriptionProgress()
}

/**
 * Android SpeechRecognizer-based transcriber.
 * Works offline if the device has offline speech recognition packs installed.
 * Best for short clips (< 1 minute).
 */
class AndroidSpeechTranscriber(private val context: Context) : WhisperTranscriber {

    companion object {
        private const val TAG = "AndroidSpeechTranscriber"
    }

    private val streamingRecognizer = StreamingSpeechRecognizer(context)

    override fun isAvailable(): Boolean = streamingRecognizer.isAvailable()

    override suspend fun transcribe(audioFile: File): TranscriptionResult = withContext(Dispatchers.IO) {
        if (!streamingRecognizer.isAvailable()) {
            return@withContext TranscriptionResult(
                text = "",
                success = false,
                error = "Speech recognition not available"
            )
        }

        try {
            // For file-based transcription with Android SpeechRecognizer,
            // we would need to play the audio and capture it, which is complex.
            // Instead, use the streaming recognizer for live input.
            // For files, return a message suggesting Whisper.
            Log.w(TAG, "Android SpeechRecognizer is designed for live audio, not files. " +
                    "Use streamTranscription() for live input, or integrate Whisper for file transcription.")

            TranscriptionResult(
                text = "",
                success = false,
                error = "Use streamTranscription() for live audio, or integrate Whisper ONNX for file transcription"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            TranscriptionResult(
                text = "",
                success = false,
                error = e.message
            )
        }
    }

    /**
     * Stream live transcription from microphone.
     */
    fun streamLiveTranscription(): Flow<SpeechResult> {
        return streamingRecognizer.streamTranscription(timeoutMs = 60000)
    }

    fun destroy() {
        streamingRecognizer.destroy()
    }
}

/**
 * Whisper ONNX transcriber placeholder.
 *
 * To use this:
 * 1. Download Whisper ONNX model (e.g., whisper-tiny.onnx or whisper-base.onnx)
 * 2. Place in Android/data/<pkg>/files/models/
 * 3. Implement ONNX Runtime inference using the model
 *
 * The model can be obtained from:
 * - https://huggingface.co/openai/whisper-tiny (convert to ONNX using optimum)
 * - https://github.com/openai/whisper (use whisper-export tools)
 */
class WhisperOnnxTranscriber(private val context: Context) : WhisperTranscriber {

    companion object {
        private const val TAG = "WhisperOnnxTranscriber"
        const val MODEL_FILE_NAME = "whisper-tiny.onnx"

        fun modelFile(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "models")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, MODEL_FILE_NAME)
        }
    }

    private val modelFile = modelFile(context)

    override fun isAvailable(): Boolean {
        return modelFile.exists()
    }

    override suspend fun transcribe(audioFile: File): TranscriptionResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext TranscriptionResult(
                text = "",
                success = false,
                error = "Whisper ONNX model not found at ${modelFile.absolutePath}. " +
                        "Download whisper-tiny.onnx and place it in the models directory."
            )
        }

        try {
            // TODO: Implement ONNX Runtime inference for Whisper
            // This requires:
            // 1. Loading the ONNX model
            // 2. Preprocessing audio to mel spectrogram
            // 3. Running inference
            // 4. Decoding tokens to text

            Log.w(TAG, "Whisper ONNX inference not yet implemented. " +
                    "Model found at ${modelFile.absolutePath} but inference logic needs to be added.")

            TranscriptionResult(
                text = "",
                success = false,
                error = "Whisper ONNX inference not yet implemented. Model file found but processing logic is incomplete."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription failed", e)
            TranscriptionResult(
                text = "",
                success = false,
                error = e.message
            )
        }
    }
}

/**
 * Composite transcriber that tries Whisper first, then falls back to Android SpeechRecognizer.
 */
class CompositeTranscriber(context: Context) : WhisperTranscriber {
    private val whisper = WhisperOnnxTranscriber(context)
    private val androidSpeech = AndroidSpeechTranscriber(context)

    override fun isAvailable(): Boolean = whisper.isAvailable() || androidSpeech.isAvailable()

    override suspend fun transcribe(audioFile: File): TranscriptionResult {
        return if (whisper.isAvailable()) {
            whisper.transcribe(audioFile)
        } else {
            androidSpeech.transcribe(audioFile)
        }
    }

    fun destroy() {
        androidSpeech.destroy()
    }
}
