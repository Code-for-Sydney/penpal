package com.drawapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WebSearchCapture {

    private val gson = Gson()
    private const val PREFS_NAME = "web_search_captures"
    private const val KEY_CAPTURES = "search_captures"

    data class CapturedSearch(
        val id: String,
        val timestamp: Long,
        val query: String,
        val transcriptionContext: String,
        val results: List<SearchResultItem>,
        val sourceFile: String? = null
    )

    data class SearchResultItem(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String
    )

    fun captureSearch(
        context: Context,
        query: String,
        transcriptionContext: String,
        results: List<GemmaServerClient.SearchResult>,
        sourceFile: String? = null
    ): CapturedSearch {
        val captured = CapturedSearch(
            id = generateId(),
            timestamp = System.currentTimeMillis(),
            query = query,
            transcriptionContext = transcriptionContext,
            results = results.map { SearchResultItem(it.title, it.url, it.snippet, it.source) },
            sourceFile = sourceFile
        )

        val captures = getCaptures(context).toMutableList()
        captures.add(0, captured)

        saveCaptures(context, captures)

        return captured
    }

    fun getCaptures(context: Context): List<CapturedSearch> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CAPTURES, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<CapturedSearch>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteCapture(context: Context, captureId: String) {
        val captures = getCaptures(context).toMutableList()
        captures.removeAll { it.id == captureId }
        saveCaptures(context, captures)
    }

    fun clearAllCaptures(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CAPTURES)
            .apply()
    }

    private fun saveCaptures(context: Context, captures: List<CapturedSearch>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CAPTURES, gson.toJson(captures)).apply()
    }

    fun exportToJson(context: Context, capture: CapturedSearch): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "websearch_${capture.id}_$timestamp.json"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename)
        file.writeText(gson.toJson(capture, CapturedSearch::class.java))
        return file
    }

    fun exportToMarkdown(capture: CapturedSearch): String {
        val sb = StringBuilder()
        sb.appendLine("# Web Search Capture")
        sb.appendLine()
        sb.appendLine("**Date:** ${formatTimestamp(capture.timestamp)}")
        sb.appendLine("**Query:** ${capture.query}")
        sb.appendLine()
        sb.appendLine("## Transcription Context")
        sb.appendLine(capture.transcriptionContext)
        sb.appendLine()
        sb.appendLine("## Results")
        sb.appendLine()

        capture.results.forEachIndexed { index, result ->
            sb.appendLine("${index + 1}. **${result.title}**")
            sb.appendLine("   - Source: ${result.source}")
            sb.appendLine("   - URL: ${result.url}")
            sb.appendLine("   - ${result.snippet}")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun exportToText(capture: CapturedSearch): String {
        val sb = StringBuilder()
        sb.appendLine("WEB SEARCH RESULTS")
        sb.appendLine("==================")
        sb.appendLine("Date: ${formatTimestamp(capture.timestamp)}")
        sb.appendLine("Query: ${capture.query}")
        sb.appendLine()
        sb.appendLine("CONTEXT:")
        sb.appendLine(capture.transcriptionContext)
        sb.appendLine()
        sb.appendLine("LINKS:")
        capture.results.forEachIndexed { index, result ->
            sb.appendLine("${index + 1}. ${result.title}")
            sb.appendLine("   ${result.url}")
            sb.appendLine("   ${result.snippet}")
            sb.appendLine()
        }

        return sb.toString()
    }

    fun shareAsText(capture: CapturedSearch): Intent {
        val text = buildString {
            appendLine("Web Search: ${capture.query}")
            appendLine()
            capture.results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.title}")
                appendLine("   ${result.url}")
            }
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Web Search: ${capture.query}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    fun shareAsMarkdown(capture: CapturedSearch): Intent {
        val markdown = exportToMarkdown(capture)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "Web Search: ${capture.query}")
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
    }

    private fun generateId(): String {
        return java.util.UUID.randomUUID().toString().take(8)
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(timestamp))
    }
}