package com.penpal.core.processing.speech

import android.content.Context
import android.net.Uri
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

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
        const val N_MELS = 80
        const val N_FFT = 400
        const val HOP_LENGTH = 160
        const val SAMPLE_RATE = 16000
        const val CHUNK_LENGTH = 30 // seconds
        const val MAX_TOKENS = 448

        // Whisper tokenizer special tokens
        const val SOT_TOKEN = 50258 // <|startoftranscript|>
        const val TRANSCRIBE_TOKEN = 50359 // <|transcribe|>
        const val EN_TOKEN = 50259 // <|en|>
        const val NOTIMESTAMPS_TOKEN = 50363 // <|notimestamps|>
        const val EOT_TOKEN = 50256 // <|endoftext|>

        fun modelFile(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "models")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, MODEL_FILE_NAME)
        }
    }

    private val modelFile = modelFile(context)
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    override fun isAvailable(): Boolean {
        if (!modelFile.exists()) return false
        initSession()
        return ortSession != null
    }

    private fun initSession() {
        if (ortSession != null) return
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(2)
            sessionOptions.setInterOpNumThreads(2)
            ortSession = ortEnvironment?.createSession(modelFile.absolutePath, sessionOptions)
            Log.i(TAG, "ONNX session initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ONNX session", e)
        }
    }

    override suspend fun transcribe(audioFile: File): TranscriptionResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext TranscriptionResult(
                text = "",
                success = false,
                error = "Whisper ONNX model not found or failed to initialize at ${modelFile.absolutePath}. " +
                        "Download whisper-tiny.onnx and place it in the models directory."
            )
        }

        try {
            Log.d(TAG, "Starting transcription for ${audioFile.absolutePath}")

            // Decode audio to PCM float array
            val audioData = decodeAudioFile(audioFile)
                ?: return@withContext TranscriptionResult(
                    text = "",
                    success = false,
                    error = "Failed to decode audio file"
                )

            // Compute mel spectrogram
            val melSpectrogram = computeMelSpectrogram(audioData)

            // Run ONNX inference with greedy decoding
            val transcription = runInference(melSpectrogram)

            TranscriptionResult(
                text = transcription,
                success = true,
                language = "en"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription failed", e)
            TranscriptionResult(
                text = "",
                success = false,
                error = e.message ?: "Unknown error during transcription"
            )
        }
    }

    /**
     * Decodes various audio formats to 16kHz mono float PCM
     */
    private fun decodeAudioFile(audioFile: File): FloatArray? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(audioFile.absolutePath)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()

            if (durationMs > 300000) { // 5 minutes max
                Log.w(TAG, "Audio too long (${durationMs}ms), limiting to 5 minutes")
            }

            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(audioFile.absolutePath)

            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    trackIndex = i
                    break
                }
            }

            if (trackIndex < 0) {
                Log.e(TAG, "No audio track found")
                return null
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)

            val decoder = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = android.media.MediaCodec.BufferInfo()
            val allSamples = mutableListOf<Float>()
            var isEOS = false
            val maxSamples = SAMPLE_RATE * CHUNK_LENGTH

            while (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outIndex = decoder.dequeueOutputBuffer(info, 10000)
                while (outIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)

                    val pcmData = ShortArray(info.size / 2)
                    buffer.asShortBuffer().get(pcmData)

                    // Convert to float and downmix to mono
                    for (i in 0 until pcmData.size step channelCount) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            if (i + c < pcmData.size) {
                                sum += pcmData[i + c].toFloat() / 32768f
                            }
                        }
                        allSamples.add(sum / channelCount)

                        if (allSamples.size >= maxSamples) {
                            isEOS = true
                            break
                        }
                    }

                    decoder.releaseOutputBuffer(outIndex, false)
                    if (isEOS) break
                    outIndex = decoder.dequeueOutputBuffer(info, 0)
                }
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            var result = allSamples.toFloatArray()

            // Resample if needed
            if (sampleRate != SAMPLE_RATE) {
                val ratio = sampleRate.toDouble() / SAMPLE_RATE.toDouble()
                val newSize = (result.size / ratio).toInt().coerceAtMost(maxSamples)
                val resampled = FloatArray(newSize)
                for (i in 0 until newSize) {
                    val srcPos = i * ratio
                    val srcIdx = srcPos.toInt()
                    val frac = (srcPos - srcIdx).toFloat()
                    val s0 = result.getOrElse(srcIdx) { 0f }
                    val s1 = result.getOrElse(srcIdx + 1) { s0 }
                    resampled[i] = s0 * (1f - frac) + s1 * frac
                }
                result = resampled
            }

            Log.d(TAG, "Decoded ${result.size} samples at ${SAMPLE_RATE}Hz")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode audio file", e)
            null
        }
    }

    /**
     * Computes mel spectrogram from audio data
     */
    private fun computeMelSpectrogram(audioData: FloatArray): FloatArray {
        // Pad or truncate to exactly 30 seconds
        val targetLength = SAMPLE_RATE * CHUNK_LENGTH
        val paddedAudio = when {
            audioData.size > targetLength -> audioData.copyOfRange(0, targetLength)
            audioData.size < targetLength -> FloatArray(targetLength) { i ->
                if (i < audioData.size) audioData[i] else 0f
            }
            else -> audioData
        }

        val numFrames = 1 + (targetLength - N_FFT) / HOP_LENGTH
        val melSpectrogram = FloatArray(N_MELS * numFrames)

        // Compute STFT magnitude
        val window = FloatArray(N_FFT) { i ->
            (0.5 - 0.5 * kotlin.math.cos(2 * kotlin.math.PI * i / (N_FFT - 1))).toFloat()
        }

        // Precompute mel filterbank (simplified triangular filters)
        val melFilters = createMelFilterbank()

        for (frame in 0 until numFrames) {
            val start = frame * HOP_LENGTH
            val frameData = FloatArray(N_FFT) { i ->
                if (start + i < paddedAudio.size) paddedAudio[start + i] * window[i] else 0f
            }

            // Compute FFT magnitude (simplified - using basic DFT for small size)
            val magnitudes = FloatArray(N_FFT / 2 + 1)
            for (k in 0 until N_FFT / 2 + 1) {
                var real = 0f
                var imag = 0f
                for (n in 0 until N_FFT) {
                    val angle = -2f * kotlin.math.PI.toFloat() * k * n / N_FFT
                    real += frameData[n] * kotlin.math.cos(angle)
                    imag += frameData[n] * kotlin.math.sin(angle)
                }
                magnitudes[k] = kotlin.math.sqrt(real * real + imag * imag)
            }

            // Apply mel filterbank
            for (melBin in 0 until N_MELS) {
                var sum = 0f
                for (freqBin in 0 until N_FFT / 2 + 1) {
                    sum += magnitudes[freqBin] * melFilters[melBin * (N_FFT / 2 + 1) + freqBin]
                }
                melSpectrogram[melBin * numFrames + frame] = kotlin.math.ln(sum + 1e-10f)
            }
        }

        return melSpectrogram
    }

    private fun createMelFilterbank(): FloatArray {
        val numFreqBins = N_FFT / 2 + 1
        val filters = FloatArray(N_MELS * numFreqBins)

        // Simplified mel filterbank creation
        val fMin = 0f
        val fMax = SAMPLE_RATE / 2f
        val melMin = 2595f * kotlin.math.log10(1 + fMin / 700f)
        val melMax = 2595f * kotlin.math.log10(1 + fMax / 700f)

        for (i in 0 until N_MELS) {
            val melCenter = melMin + (melMax - melMin) * (i + 1) / (N_MELS + 1)
            val freqCenter = 700f * (10.0.pow((melCenter / 2595f).toDouble()).toFloat() - 1f)
            val binCenter = (freqCenter / (SAMPLE_RATE / 2f) * (numFreqBins - 1)).toInt()

            val melLeft = melMin + (melMax - melMin) * i / (N_MELS + 1)
            val freqLeft = 700f * (10.0.pow((melLeft / 2595f).toDouble()).toFloat() - 1f)
            val binLeft = (freqLeft / (SAMPLE_RATE / 2f) * (numFreqBins - 1)).toInt()

            val melRight = melMin + (melMax - melMin) * (i + 2) / (N_MELS + 1)
            val freqRight = 700f * (10.0.pow((melRight / 2595f).toDouble()).toFloat() - 1f)
            val binRight = (freqRight / (SAMPLE_RATE / 2f) * (numFreqBins - 1)).toInt()

            for (j in binLeft until binCenter) {
                if (j in 0 until numFreqBins) {
                    filters[i * numFreqBins + j] = (j - binLeft).toFloat() / (binCenter - binLeft).coerceAtLeast(1)
                }
            }
            for (j in binCenter until binRight) {
                if (j in 0 until numFreqBins) {
                    filters[i * numFreqBins + j] = (binRight - j).toFloat() / (binRight - binCenter).coerceAtLeast(1)
                }
            }
        }

        return filters
    }

    /**
     * Runs ONNX inference with greedy decoding
     */
    private fun runInference(melSpectrogram: FloatArray): String {
        val session = ortSession ?: throw IllegalStateException("ONNX session not initialized")

        // Create input tensor
        val inputShape = longArrayOf(1, N_MELS.toLong(), melSpectrogram.size / N_MELS.toLong())
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            java.nio.FloatBuffer.wrap(melSpectrogram),
            inputShape
        )

        // Initial decoder input: start of transcript + language + task + no timestamps
        var decoderTokens = intArrayOf(SOT_TOKEN, EN_TOKEN, TRANSCRIBE_TOKEN, NOTIMESTAMPS_TOKEN)
        val generatedTokens = mutableListOf<Int>()

        try {
            for (step in 0 until MAX_TOKENS) {
                // Run encoder + decoder
                val decoderShape = longArrayOf(1, decoderTokens.size.toLong())
                val decoderTensor = OnnxTensor.createTensor(
                    ortEnvironment,
                    java.nio.IntBuffer.wrap(decoderTokens),
                    decoderShape
                )

                val inputs = mapOf(
                    "input_features" to inputTensor,
                    "decoder_input_ids" to decoderTensor
                )

                val results = session.run(inputs)
                val outputTensor = results.get(0) as OnnxTensor
                val logits = outputTensor.value as Array<Array<FloatArray>>

                // Greedy decoding - take last token's logits
                val lastLogits = logits[0][logits[0].size - 1]
                val nextToken = lastLogits.withIndex().maxByOrNull { it.value }?.index ?: EOT_TOKEN

                decoderTensor.close()
                results.close()

                if (nextToken == EOT_TOKEN) break

                generatedTokens.add(nextToken)
                decoderTokens = decoderTokens.copyOf(decoderTokens.size + 1)
                decoderTokens[decoderTokens.size - 1] = nextToken
            }
        } finally {
            inputTensor.close()
        }

        // Decode tokens to text (simplified - in production use proper tokenizer)
        return decodeTokens(generatedTokens)
    }

    /**
     * Simple token decoding (Whisper tokens map roughly to byte pairs)
     */
    private fun decodeTokens(tokens: List<Int>): String {
        if (tokens.isEmpty()) return ""

        // Basic byte-fallback decoding for Whisper tokens
        val bytes = mutableListOf<Byte>()
        for (token in tokens) {
            when {
                token < 256 -> bytes.add(token.toByte())
                token < 50256 -> {
                    // Common tokens - map to common characters/words
                    bytes.addAll(token.toString().toByteArray().toList())
                }
            }
        }

        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            bytes.map { it.toInt().toChar() }.joinToString("")
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
