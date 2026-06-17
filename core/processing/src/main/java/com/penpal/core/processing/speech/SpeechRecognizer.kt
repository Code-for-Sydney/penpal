package com.penpal.core.processing.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Android SpeechRecognizer wrapper for real-time streaming transcription of short clips.
 *
 * Uses the device's built-in speech recognition (usually Google Speech Services).
 * Best for short utterances (< 1 minute) due to Android SpeechRecognizer limitations.
 *
 * For longer audio files, use WhisperTranscriber instead.
 */
class StreamingSpeechRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "StreamingSpeechRecognizer"
        private const val DEFAULT_TIMEOUT_MS = 30000L // 30 seconds max for short clips
    }

    private var speechRecognizer: SpeechRecognizer? = null

    /**
     * Check if speech recognition is available on this device.
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Stream transcription results in real-time.
     * Emits partial results as they become available, then final result.
     *
     * @param timeoutMs Maximum time to listen (default 30s)
     * @return Flow of transcription results
     */
    fun streamTranscription(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Flow<SpeechResult> = flow {
        if (!isAvailable()) {
            emit(SpeechResult.Error("Speech recognition not available on this device"))
            return@flow
        }

        val channel = Channel<SpeechResult>(Channel.UNLIMITED)

        withContext(Dispatchers.Main) {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "Ready for speech")
                        channel.trySend(SpeechResult.Ready)
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Beginning of speech")
                        channel.trySend(SpeechResult.Started)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Audio level changed - can be used for UI visualization
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "End of speech")
                        channel.trySend(SpeechResult.Ended)
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                            else -> "Unknown error: $error"
                        }
                        Log.e(TAG, "Speech recognition error: $message")
                        channel.trySend(SpeechResult.Error(message))
                        channel.close()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            Log.d(TAG, "Final result: $text")
                            channel.trySend(SpeechResult.Final(text))
                        } else {
                            channel.trySend(SpeechResult.Error("No recognition results"))
                        }
                        channel.close()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            Log.d(TAG, "Partial result: $text")
                            channel.trySend(SpeechResult.Partial(text))
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                }

                startListening(intent)
            }
        }

        // Collect results with timeout
        try {
            withTimeoutOrNull(timeoutMs) {
                for (result in channel) {
                    emit(result)
                    if (result is SpeechResult.Final || result is SpeechResult.Error) {
                        break
                    }
                }
            } ?: emit(SpeechResult.Error("Speech recognition timed out"))
        } finally {
            withContext(Dispatchers.Main) {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }

    /**
     * One-shot transcription of short audio.
     * Convenience method that returns only the final result.
     */
    suspend fun transcribe(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        var finalText = ""
        streamTranscription(timeoutMs).collect { result ->
            when (result) {
                is SpeechResult.Final -> finalText = result.text
                is SpeechResult.Error -> if (finalText.isEmpty()) finalText = "[Error: ${result.message}]"
                else -> {} // Ignore other states
            }
        }
        return finalText
    }

    /**
     * Stop listening immediately.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}

/**
 * Result states from speech recognition.
 */
sealed class SpeechResult {
    object Ready : SpeechResult()
    object Started : SpeechResult()
    object Ended : SpeechResult()
    data class Partial(val text: String) : SpeechResult()
    data class Final(val text: String) : SpeechResult()
    data class Error(val message: String) : SpeechResult()
}
