package com.penpal.core.processing

import android.content.Context
import android.net.Uri
import com.penpal.core.ai.RawChunk
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class PdfDocumentParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.close()
            listOf(RawChunk(UUID.randomUUID().toString(), uri.toString(), "PDF content placeholder", 0))
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class AudioParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.close()
            listOf(RawChunk(UUID.randomUUID().toString(), uri.toString(), "Audio placeholder", 0))
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class ImageParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            inputStream?.close()
            listOf(RawChunk(UUID.randomUUID().toString(), uri.toString(), "Image OCR placeholder", 0))
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class UrlParser(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> {
        return try {
            val request = Request.Builder().url(uri.toString()).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val text = body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                response.close()
                listOf(RawChunk(UUID.randomUUID().toString(), uri.toString(), text, 0))
            } else {
                response.close()
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class CodeParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.readText() ?: ""
            inputStream?.close()
            val classes = Regex("class\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            val funcs = Regex("fun\\s+(\\w+)").findAll(content).map { it.groupValues[1] }.toList()
            listOf(RawChunk(UUID.randomUUID().toString(), uri.toString(), "classes=${classes.joinToString()}, functions=${funcs.joinToString()}", 0))
        } catch (e: Exception) {
            emptyList()
        }
    }
}