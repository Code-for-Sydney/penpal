package com.penpal.core.ai.model

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile

data class GgufConversionResult(
    val success: Boolean,
    val outputPath: String? = null,
    val errorMessage: String? = null,
    val modelInfo: GgufModelInfo? = null
)

data class GgufModelInfo(
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val fileSizeDisplay: String,
    val architecture: String?,
    val quantization: String?
)

sealed class ConversionState {
    data object Idle : ConversionState()
    data class Scanning(val progress: Int) : ConversionState()
    data class Converting(val modelName: String, val progress: Float) : ConversionState()
    data class Success(val outputPath: String, val modelName: String) : ConversionState()
    data class Error(val message: String, val modelName: String?) : ConversionState()
}

object GgufConverter {

    private const val GGUF_MAGIC = 0x46554746 // "GGUF" in little-endian

    fun scanForGgufFiles(context: Context): List<GgufModelInfo> {
        val searchDirs = listOf(
            context.getExternalFilesDir(null),
            context.filesDir,
            File(context.filesDir, "models"),
            File(context.filesDir, "downloads"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/sdcard/Download"),
            File("/sdcard")
        ).filterNotNull()

        val results = mutableListOf<GgufModelInfo>()

        for (dir in searchDirs) {
            if (!dir.exists()) continue

            dir.listFiles()?.forEach { file ->
                if (file.isFile && isGgufFile(file)) {
                    results.add(parseGgufInfo(file))
                }
            }
        }

        return results.distinctBy { it.filePath }.sortedByDescending { it.fileSizeBytes }
    }

    fun scanForGgufFilesFlow(context: Context): Flow<List<GgufModelInfo>> = flow {
        emit(scanForGgufFiles(context))
    }.flowOn(Dispatchers.IO)

    fun isGgufFile(file: File): Boolean {
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return false
        if (file.length() < 1_000_000L) return false

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = raf.readInt()
                magic == GGUF_MAGIC
            }
        } catch (e: Exception) {
            false
        }
    }

    fun parseGgufInfo(file: File): GgufModelInfo {
        var architecture: String? = null
        var quantization: String? = null

        try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = raf.readInt()
                if (magic != GGUF_MAGIC) return@use

                raf.readInt() // version

                val tensorCount = raf.readLong()
                val metadataKvCount = raf.readLong()

                for (i in 0 until minOf(metadataKvCount, 100)) {
                    val keyLen = raf.readInt()
                    if (keyLen <= 0 || keyLen > 1000) break

                    val keyBytes = ByteArray(keyLen)
                    raf.readFully(keyBytes)
                    val key = String(keyBytes, Charsets.UTF_8)

                    val valueType = raf.readInt()

                    when (valueType) {
                        8 -> { // string
                            val strLen = raf.readInt()
                            if (strLen in 1..10000) {
                                val strBytes = ByteArray(strLen)
                                raf.readFully(strBytes)
                                val value = String(strBytes, Charsets.UTF_8)
                                when {
                                    key.contains("general.architecture", ignoreCase = true) -> architecture = value
                                    key.contains("quantization", ignoreCase = true) -> quantization = value
                                }
                            }
                        }
                        4 -> raf.readLong() // uint64
                        5 -> raf.readInt()  // bool
                        6 -> { // float32
                            val bytes = ByteArray(4)
                            raf.readFully(bytes)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Unable to parse metadata, that's ok
        }

        val sizeDisplay = formatFileSize(file.length())

        return GgufModelInfo(
            fileName = file.nameWithoutExtension,
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            fileSizeDisplay = sizeDisplay,
            architecture = architecture,
            quantization = quantization
        )
    }

    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }

    fun getPythonScriptPath(): String {
        return "scripts/convert_gguf_to_litert.py"
    }

    fun getConversionInstructions(): String {
        return """
            |GGUF to LiteRT Conversion Instructions
            |
            |1. Copy the GGUF file to your computer (or access via ADB)
            |2. Run the conversion script:
            |   python3 scripts/convert_gguf_to_litert.py --input model.gguf --output model.litertlm
            |
            |Requirements:
            |- Python 3.10+
            |- transformers library
            |- gguf-parser or llama.cpp
            |
            |For more details, see: docs/GGUF_CONVERSION.md
        """.trimMargin()
    }

    fun generateAdbCommand(inputPath: String, outputPath: String): String {
        return "adb shell python3 /sdcard/convert.py --input $inputPath --output $outputPath"
    }
}