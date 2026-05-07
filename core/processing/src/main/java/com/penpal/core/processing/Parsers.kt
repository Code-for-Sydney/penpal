package com.penpal.core.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.penpal.core.ai.RawChunk
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "DocumentParsers"
private const val CHUNK_SIZE = 1000
private const val CHUNK_OVERLAP = 200

/**
 * Factory for creating appropriate DocumentParser based on mime type
 */
class ParserFactory(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    fun createParser(mimeType: String): DocumentParser {
        return when (mimeType.lowercase()) {
            "pdf" -> PdfDocumentParser(context)
            "image" -> ImageParser(context)
            "audio" -> AudioParser(context)
            "url" -> UrlParser(context, okHttpClient)
            "code" -> CodeParser(context)
            else -> throw IllegalArgumentException("Unsupported mime type: $mimeType")
        }
    }
}

class PdfDocumentParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    val text = stripper.getText(document)
                    chunkText(text, uri.toString())
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PDF: ${uri}", e)
            emptyList()
        }
    }
}

class ImageParser(
    private val context: Context
) : DocumentParser {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> = withContext(Dispatchers.IO) {
        try {
            val bitmap = loadBitmap(uri)
                ?: return@withContext emptyList()

            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.processImageSuspending(image)

            val text = visionText.text
            if (text.isBlank()) {
                listOf(RawChunk(
                    UUID.randomUUID().toString(),
                    uri.toString(),
                    "[No text detected in image]",
                    0
                ))
            } else {
                chunkText(text, uri.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse image: ${uri}", e)
            emptyList()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap: $uri", e)
            null
        }
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.processImageSuspending(
        image: InputImage
    ) = suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { result ->
                continuation.resume(result)
            }
            .addOnFailureListener { error ->
                continuation.resumeWithException(error)
            }
    }
}

class AudioParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Read file metadata
                val fileSize = inputStream.available()
                val fileName = uri.lastPathSegment ?: "unknown"

                // Try to transcribe using available transcriber
                val transcriber = CompositeTranscriber(context)
                val transcription = if (transcriber.isAvailable()) {
                    try {
                        // Copy to temp file for transcription
                        val tempFile = File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.wav")
                        inputStream.copyTo(tempFile.outputStream())
                        val result = transcriber.transcribe(tempFile)
                        tempFile.delete()
                        if (result.success) result.text else null
                    } catch (e: Exception) {
                        Log.w(TAG, "Transcription failed for $fileName: ${e.message}")
                        null
                    }
                } else null

                val content = if (transcription != null) {
                    "[Audio file: $fileName, Size: ${fileSize / 1024}KB]\n\nTranscription:\n$transcription"
                } else {
                    "[Audio file: $fileName, Size: ${fileSize / 1024}KB]\n" +
                    "Audio transcription requires a speech-to-text model. " +
                    "Use Android SpeechRecognizer for short clips (via mic button in chat) " +
                    "or download Whisper ONNX model for file transcription."
                }

                listOf(RawChunk(
                    UUID.randomUUID().toString(),
                    uri.toString(),
                    content,
                    0
                ))
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse audio: ${uri}", e)
            emptyList()
        }
    }
}

class UrlParser(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(uri.toString()).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val doc = Jsoup.parse(body, uri.toString())

                    // Extract meaningful text
                    val title = doc.title()
                    doc.select("script, style, nav, footer, header, aside, .advertisement, .ads").remove()

                    val article = doc.select("article").firstOrNull()
                    val main = doc.select("main").firstOrNull()
                    val content = article ?: main ?: doc.body()

                    val text = buildString {
                        if (title.isNotBlank()) {
                            appendLine("Title: $title")
                            appendLine()
                        }
                        appendLine(content.text().trim())
                    }

                    chunkText(text, uri.toString())
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse URL: ${uri}", e)
            emptyList()
        }
    }
}

class CodeParser(
    private val context: Context
) : DocumentParser {
    override suspend fun parse(uri: Uri, rule: String): List<RawChunk> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val content = inputStream.bufferedReader().readText()
                val fileName = uri.lastPathSegment ?: "unknown"

                // Detect language from extension
                val language = detectLanguage(fileName)

                // Chunk by top-level constructs
                val chunks = chunkCode(content, language, uri.toString())
                if (chunks.isEmpty()) {
                    // Fallback: simple text chunking
                    chunkText(content, uri.toString())
                } else {
                    chunks
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse code: ${uri}", e)
            emptyList()
        }
    }

    private fun detectLanguage(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "go" -> "go"
            "rs" -> "rust"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "swift" -> "swift"
            else -> "unknown"
        }
    }

    private fun chunkCode(content: String, language: String, sourceId: String): List<RawChunk> {
        val chunks = mutableListOf<RawChunk>()
        val lines = content.lines()
        val patterns = getLanguagePatterns(language)

        var currentChunk = StringBuilder()
        var chunkIndex = 0
        var inMultilineComment = false

        for (line in lines) {
            val trimmed = line.trim()

            // Track multiline comments
            if (patterns?.multiLineCommentStart != null) {
                if (patterns.multiLineCommentStart != null && trimmed.contains(patterns.multiLineCommentStart)) inMultilineComment = true
                if (patterns.multiLineCommentEnd != null && trimmed.contains(patterns.multiLineCommentEnd)) {
                    inMultilineComment = false
                    continue
                }
            }
            if (inMultilineComment) continue

            // Skip single-line comments and empty lines at chunk boundaries
            val isComment = patterns?.singleLineComment != null && trimmed.startsWith(patterns.singleLineComment)
            val isEmpty = trimmed.isEmpty()

            // Check if this line starts a new top-level construct
            val isNewConstruct = patterns?.let { p ->
                p.classPattern.matches(trimmed) ||
                p.functionPattern.matches(trimmed) ||
                p.importPattern.matches(trimmed)
            } ?: false

            if (isNewConstruct && currentChunk.isNotEmpty() && currentChunk.length > CHUNK_SIZE) {
                chunks.add(RawChunk(
                    UUID.randomUUID().toString(),
                    sourceId,
                    currentChunk.toString().trim(),
                    chunkIndex++
                ))
                currentChunk = StringBuilder()
            }

            if (!isComment || currentChunk.isNotEmpty()) {
                currentChunk.appendLine(line)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(RawChunk(
                UUID.randomUUID().toString(),
                sourceId,
                currentChunk.toString().trim(),
                chunkIndex
            ))
        }

        return chunks
    }

    private data class LanguagePatterns(
        val classPattern: Regex,
        val functionPattern: Regex,
        val importPattern: Regex,
        val singleLineComment: String? = null,
        val multiLineCommentStart: String? = null,
        val multiLineCommentEnd: String? = null
    )

    private fun getLanguagePatterns(language: String): LanguagePatterns? {
        return when (language) {
            "kotlin", "java" -> LanguagePatterns(
                classPattern = Regex("^(public|private|protected|internal|open|abstract|sealed|data|class|interface|object|enum)\\s"),
                functionPattern = Regex("^(public|private|protected|internal|open|abstract|override|fun|def|static|final)\\s"),
                importPattern = Regex("^import\\s"),
                singleLineComment = "//",
                multiLineCommentStart = "/*",
                multiLineCommentEnd = "*/"
            )
            "python" -> LanguagePatterns(
                classPattern = Regex("^class\\s"),
                functionPattern = Regex("^(def|async\\s+def)\\s"),
                importPattern = Regex("^(import|from)\\s"),
                singleLineComment = "#",
                multiLineCommentStart = "\"\"\"",
                multiLineCommentEnd = "\"\"\""
            )
            "javascript", "typescript" -> LanguagePatterns(
                classPattern = Regex("^(class|export\\s+class|const|let|var)\\s"),
                functionPattern = Regex("^(function|const|let|var|export|async)\\s.*[=\\(]"),
                importPattern = Regex("^(import|export)\\s"),
                singleLineComment = "//",
                multiLineCommentStart = "/*",
                multiLineCommentEnd = "*/"
            )
            "go" -> LanguagePatterns(
                classPattern = Regex("^type\\s"),
                functionPattern = Regex("^func\\s"),
                importPattern = Regex("^import\\s"),
                singleLineComment = "//",
                multiLineCommentStart = "/*",
                multiLineCommentEnd = "*/"
            )
            "rust" -> LanguagePatterns(
                classPattern = Regex("^(struct|enum|trait|impl|type)\\s"),
                functionPattern = Regex("^(fn|async\\s+fn|pub\\s+fn)\\s"),
                importPattern = Regex("^use\\s"),
                singleLineComment = "//",
                multiLineCommentStart = "/*",
                multiLineCommentEnd = "*/"
            )
            else -> null
        }
    }
}

/**
 * Chunk text into overlapping segments for embedding and retrieval
 */
fun chunkText(text: String, sourceId: String, chunkSize: Int = CHUNK_SIZE, overlap: Int = CHUNK_OVERLAP): List<RawChunk> {
    if (text.length <= chunkSize) {
        return listOf(RawChunk(UUID.randomUUID().toString(), sourceId, text.trim(), 0))
    }

    val chunks = mutableListOf<RawChunk>()
    var start = 0
    var index = 0

    while (start < text.length) {
        val end = (start + chunkSize).coerceAtMost(text.length)
            val chunkText = if (end < text.length) {
                // Try to break at a sentence or newline
                val breakPoint = findBreakPoint(text, start, end, chunkSize)
                text.substring(start, breakPoint)
            } else {
                text.substring(start, end)
            }

        chunks.add(RawChunk(
            UUID.randomUUID().toString(),
            sourceId,
            chunkText.trim(),
            index++
        ))

        start += chunkText.length - overlap
        if (start >= text.length - overlap) break
    }

    return chunks
}

private fun findBreakPoint(text: String, start: Int, preferredEnd: Int, chunkSize: Int): Int {
    // Look for sentence endings or newlines near the preferred end
    val searchRange = (preferredEnd.coerceAtMost(text.length - 1) downTo start + chunkSize / 2)
    for (i in searchRange) {
        if (text[i] == '\n' || text[i] == '.' && i + 1 < text.length && text[i + 1].isWhitespace()) {
            return i + 1
        }
    }
    return preferredEnd
}
