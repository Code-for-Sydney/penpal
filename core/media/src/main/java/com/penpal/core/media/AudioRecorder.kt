package com.penpal.core.media

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
import kotlin.math.sqrt

class AudioRecorder(private val context: Context) {

    companion object {
        const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        const val RECORDINGS_DIR = "recordings"
    }

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
    ).let {
        if (it <= 0) {
            Log.e(TAG, "Invalid buffer size: $it, using 4096")
            4096
        } else it
    }.coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var outputStream: FileOutputStream? = null
    private var totalBytesWritten = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordingActive = false

    var onAmplitudeUpdate: ((Float) -> Unit)? = null
    var onPcmBuffer: ((ShortArray, Int) -> Unit)? = null
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((File?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isRecording: Boolean
        get() = recordingActive

    fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording(fileName: String): Boolean {
        if (!hasPermission()) {
            mainHandler.post { onError?.invoke("Microphone permission not granted") }
            return false
        }

        if (recordingActive) stopRecording()

        try {
            val recordingsDir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
            outputFile = File(recordingsDir, "$fileName.wav")
            outputStream = FileOutputStream(outputFile)
            totalBytesWritten = 0
            writeWavHeader(outputStream!!, 0)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                mainHandler.post { onError?.invoke("Failed to initialize audio recorder") }
                cleanup()
                return false
            }

            recordingActive = true
            audioRecord?.startRecording()

            recordingThread = Thread { recordingLoop() }
            recordingThread?.start()

            mainHandler.post { onRecordingStarted?.invoke() }
            return true
        } catch (e: SecurityException) {
            mainHandler.post { onError?.invoke("Microphone access denied") }
            cleanup()
            return false
        } catch (e: Exception) {
            mainHandler.post { onError?.invoke("Recording failed: ${e.message}") }
            cleanup()
            return false
        }
    }

    private fun recordingLoop() {
        val buffer = ShortArray(bufferSize / 2)
        val byteBuffer = ByteArray(buffer.size * 2)

        while (recordingActive) {
            val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (readCount > 0) {
                val rms = calculateRms(buffer, readCount)
                val rmsDb = amplitudeToDb(rms)

                mainHandler.post { onAmplitudeUpdate?.invoke(rmsDb) }
                onPcmBuffer?.invoke(buffer, readCount)

                for (i in 0 until readCount) {
                    val sample = buffer[i].toInt()
                    byteBuffer[i * 2] = (sample and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = (sample shr 8).toByte()
                }

                try {
                    outputStream?.write(byteBuffer, 0, readCount * 2)
                    totalBytesWritten += readCount * 2
                } catch (e: Exception) {
                    mainHandler.post { onError?.invoke("Failed to write audio data") }
                    break
                }
            } else if (readCount < 0) {
                break
            }
        }
    }

    fun stopRecording(): File? {
        if (!recordingActive) return null

        recordingActive = false
        recordingThread?.join(1000)
        recordingThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        try {
            outputStream?.flush()
            outputStream?.close()
            outputStream = null

            outputFile?.let { file ->
                updateWavHeader(file, totalBytesWritten.toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file: ${e.message}")
        }

        val resultFile = outputFile
        outputFile = null
        mainHandler.post { onRecordingStopped?.invoke(resultFile) }
        return resultFile
    }

    fun cancelRecording() {
        recordingActive = false
        recordingThread?.join(500)
        recordingThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null

        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null

        outputFile?.delete()
        outputFile = null
    }

    fun getRecordings(): List<File> {
        val recordingsDir = File(context.filesDir, RECORDINGS_DIR)
        return recordingsDir.listFiles { file -> file.extension == "wav" }?.toList() ?: emptyList()
    }

    fun deleteRecording(file: File): Boolean = file.delete()

    fun getDurationMs(file: File): Long {
        return try {
            val fileSize = file.length() - 44
            val bytesPerSecond = SAMPLE_RATE * 2
            (fileSize * 1000 / bytesPerSecond)
        } catch (_: Exception) { 0L }
    }

    private fun cleanup() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        try { outputStream?.close() } catch (_: Exception) {}
        outputStream = null
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length).toFloat()
    }

    private fun amplitudeToDb(amplitude: Float): Float {
        return if (amplitude > 0) {
            (20.0 * log10((amplitude / 32768.0).toDouble())).toFloat().coerceIn(-60f, 0f)
        } else -60f
    }

    private fun writeWavHeader(outputStream: FileOutputStream, dataSize: Int) {
        val totalSize = dataSize + 36
        val byteRate = SAMPLE_RATE * 2

        outputStream.write("RIFF".toByteArray())
        outputStream.write(intToByteArray(totalSize))
        outputStream.write("WAVE".toByteArray())
        outputStream.write("fmt ".toByteArray())
        outputStream.write(intToByteArray(16))
        outputStream.write(shortToByteArray(1))
        outputStream.write(shortToByteArray(1))
        outputStream.write(intToByteArray(SAMPLE_RATE))
        outputStream.write(intToByteArray(byteRate))
        outputStream.write(shortToByteArray(2))
        outputStream.write(shortToByteArray(BITS_PER_SAMPLE))
        outputStream.write("data".toByteArray())
        outputStream.write(intToByteArray(dataSize))
    }

    private fun updateWavHeader(file: File, dataSize: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val totalSize = dataSize + 36
                raf.seek(4)
                raf.write(intToByteArray(totalSize))
                raf.seek(40)
                raf.write(intToByteArray(dataSize))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WAV header: ${e.message}")
        }
    }

    private fun intToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToByteArray(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )
}
