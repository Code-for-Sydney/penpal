package com.drawapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Clean audio recorder that:
 * - Records audio at 16kHz mono 16-bit PCM
 * - Saves to WAV files
 * - Provides amplitude callbacks for UI
 * - Has clear logging for debugging
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16

        // Recording directory name
        const val RECORDINGS_DIR = "recordings"
    }

    // AudioRecord buffer size (calculated once for efficiency)
    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    ).let {
        if (it <= 0) {
            Log.e(TAG, "Invalid buffer size: $it, using 4096")
            4096
        } else {
            Log.d(TAG, "AudioRecord buffer size: $it")
            it
        }
    }.coerceAtLeast(4096)

    // Recording state
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // File output
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var totalBytesWritten = 0L

    // Callbacks
    var onAmplitudeUpdate: ((Float) -> Unit)? = null
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((File?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Main thread handler for callbacks
    private val mainHandler = Handler(Looper.getMainLooper())

    // Recording state - exposed via property
    private var recordingActive = false

    /**
     * Check if currently recording
     */
    val isRecording: Boolean
        get() = recordingActive

    /**
     * Check if microphone permission is granted
     */
    fun hasPermission(): Boolean {
        val granted = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "hasPermission: $granted")
        return granted
    }

    /**
     * Start recording audio to a file
     * @param fileName Name of the output WAV file (without extension)
     * @return true if recording started successfully
     */
    fun startRecording(fileName: String): Boolean {
        Log.d(TAG, "startRecording called with fileName: $fileName")

        if (!hasPermission()) {
            Log.e(TAG, "Microphone permission not granted")
            mainHandler.post { onError?.invoke("Microphone permission not granted") }
            return false
        }

        if (recordingActive) {
            Log.w(TAG, "Already recording, stopping first")
            stopRecording()
        }

        try {
            // Create output file
            val recordingsDir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
            outputFile = File(recordingsDir, "$fileName.wav")
            Log.d(TAG, "Output file: ${outputFile?.absolutePath}")

            // Create output stream and write WAV header placeholder
            outputStream = FileOutputStream(outputFile)
            totalBytesWritten = 0

            // Write placeholder WAV header (will be updated when recording stops)
            writeWavHeader(outputStream!!, 0)

            // Initialize AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                mainHandler.post { onError?.invoke("Failed to initialize audio recorder") }
                cleanup()
                return false
            }

            Log.d(TAG, "AudioRecord initialized successfully")

            // Start recording
            recordingActive = true
            audioRecord?.startRecording()
            Log.d(TAG, "Recording started")

            // Start recording thread
            recordingThread = Thread { recordingLoop() }
            recordingThread?.start()

            mainHandler.post { onRecordingStarted?.invoke() }
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            mainHandler.post { onError?.invoke("Microphone access denied") }
            cleanup()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during start: ${e.message}", e)
            mainHandler.post { onError?.invoke("Recording failed: ${e.message}") }
            cleanup()
            return false
        }
    }

    /**
     * Main recording loop - reads audio data and writes to file
     */
    private fun recordingLoop() {
        Log.d(TAG, "Recording loop started")
        val buffer = ShortArray(bufferSize / 2)
        val byteBuffer = ByteArray(buffer.size * 2)

        while (recordingActive) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (readCount > 0) {
                // Calculate RMS amplitude for UI feedback
                val rms = calculateRms(buffer, readCount)
                val rmsDb = amplitudeToDb(rms)

                mainHandler.post {
                    onAmplitudeUpdate?.invoke(rmsDb)
                    Log.v(TAG, "Audio level - RMS: ${rms.toInt()}, dB: ${rmsDb.toInt()}")
                }

                // Convert shorts to bytes and write to file
                for (i in 0 until readCount) {
                    val sample = buffer[i].toInt()
                    byteBuffer[i * 2] = (sample and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = (sample shr 8).toByte()
                }

                try {
                    outputStream?.write(byteBuffer, 0, readCount * 2)
                    totalBytesWritten += readCount * 2
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to file: ${e.message}")
                    mainHandler.post { onError?.invoke("Failed to write audio data") }
                    break
                }
            } else if (readCount < 0) {
                Log.e(TAG, "AudioRecord read returned error: $readCount")
                break
            }
        }

        Log.d(TAG, "Recording loop ended")
    }

    /**
     * Stop recording and finalize the WAV file
     * @return The recorded file, or null if recording failed
     */
    fun stopRecording(): File? {
        Log.d(TAG, "stopRecording called, isRecording=$recordingActive")

        if (!recordingActive) {
            Log.w(TAG, "Not recording, nothing to stop")
            return null
        }

        recordingActive = false

        // Stop recording thread
        recordingThread?.join(1000)
        recordingThread = null

        // Stop and release AudioRecord
        try {
            audioRecord?.stop()
            Log.d(TAG, "AudioRecord stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord?.release()
        audioRecord = null

        // Close file and update WAV header
        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null
            Log.d(TAG, "File stream closed, total bytes: $totalBytesWritten")

            // Update WAV header with actual file size
            outputFile?.let { file ->
                updateWavHeader(file, totalBytesWritten.toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file: ${e.message}")
        }

        val resultFile = outputFile
        outputFile = null

        mainHandler.post { onRecordingStopped?.invoke(resultFile) }

        Log.d(TAG, "Recording stopped, file: ${resultFile?.absolutePath}")
        return resultFile
    }

    /**
     * Cancel recording and delete the output file
     */
    fun cancelRecording() {
        Log.d(TAG, "cancelRecording called")
        recordingActive = false
        recordingThread?.join(500)
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) { }
        audioRecord?.release()
        audioRecord = null

        try {
            outputStream?.close()
        } catch (e: Exception) { }
        outputStream = null

        // Delete the incomplete file
        outputFile?.delete()
        outputFile = null

        Log.d(TAG, "Recording cancelled and file deleted")
    }

    /**
     * Get list of all recording files
     */
    fun getRecordings(): List<File> {
        val recordingsDir = File(context.filesDir, RECORDINGS_DIR)
        return recordingsDir.listFiles { file -> file.extension == "wav" }?.toList() ?: emptyList()
    }

    /**
     * Delete a recording file
     */
    fun deleteRecording(file: File): Boolean {
        return file.delete()
    }

    /**
     * Get duration of a WAV file in milliseconds
     */
    fun getDurationMs(file: File): Long {
        return try {
            val fileSize = file.length() - 44 // Subtract WAV header size
            val bytesPerSecond = SAMPLE_RATE * 2 // 16-bit mono
            (fileSize * 1000 / bytesPerSecond)
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanup() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) { }
        audioRecord?.release()
        audioRecord = null
        try {
            outputStream?.close()
        } catch (e: Exception) { }
        outputStream = null
    }

    /**
     * Calculate RMS (Root Mean Square) amplitude
     */
    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    /**
     * Convert amplitude to decibels
     * RMS amplitude from AudioRecord typically ranges from ~10 (silence) to ~10000 (loud speech)
     */
    private fun amplitudeToDb(amplitude: Float): Float {
        return if (amplitude > 0) {
            // Convert RMS amplitude to dB using standard formula
            // dB = 20 * log10(amplitude / reference)
            // Using reference of 1.0 (normalized to max possible sample value 32767)
            val db = 20.0 * log10((amplitude / 32768.0).toDouble())
            db.toFloat().coerceIn(-60f, 0f)
        } else {
            -60f
        }
    }

    /**
     * Write WAV file header
     */
    private fun writeWavHeader(outputStream: FileOutputStream, dataSize: Int) {
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * 2 // 16-bit mono

        // RIFF header
        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(totalSize))
        outputStream.write("WAVE".toByteArray())

        // fmt chunk
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16)) // Subchunk1Size (16 for PCM)
        outputStream.write(shortToByteArray(1)) // AudioFormat (1 = PCM)
        outputStream.write(shortToByteArray(1)) // NumChannels (1 = mono)
        outputStream.write(intToByteArray(SAMPLE_RATE)) // SampleRate
        outputStream.write(intToByteArray(byteRate)) // ByteRate
        outputStream.write(shortToByteArray(2)) // BlockAlign (2 bytes per sample)
        outputStream.write(shortToByteArray(BITS_PER_SAMPLE)) // BitsPerSample

        // data chunk
        outputStream.write("data".toByteArray())
        outputStream.write(intToByteArray(dataSize))
    }

    /**
     * Update WAV header with actual data size
     */
    private fun updateWavHeader(file: File, dataSize: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                // Update RIFF chunk size (offset 4)
                val totalSize = dataSize + 36
                raf.seek(4)
                raf.write(intToByteArray(totalSize))

                // Update data chunk size (offset 40)
                raf.seek(40)
                raf.write(intToByteArray(dataSize))
            }
            Log.d(TAG, "WAV header updated with data size: $dataSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header: ${e.message}")
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }
}