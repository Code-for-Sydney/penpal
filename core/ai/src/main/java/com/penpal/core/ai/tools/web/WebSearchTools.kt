package com.penpal.core.ai.tools.web

import android.util.Log
import com.penpal.core.ai.tools.Tool
import com.penpal.core.ai.tools.ToolExecutionContext
import com.penpal.core.ai.tools.ToolParameter
import com.penpal.core.ai.tools.ToolResult
import com.penpal.core.ai.tools.ToolSchema
import com.penpal.core.ai.vectorstore.VectorStoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "WebSearchTools"
private const val CHUNK_SIZE = 1000
private const val CHUNK_OVERLAP = 200

class WebSearchTool : Tool {
    override val name = "web_search"
    override val description = "Search the web for links/URLs related to a query. Returns a list of relevant URLs with titles and snippets."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = "string",
                description = "The search query",
                required = true
            ),
            ToolParameter(
                name = "max_results",
                type = "integer",
                description = "Maximum number of results to return",
                required = false,
                default = 10
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val query = context.arguments["query"] as? String
        if (query.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: query")
        }

        val maxResults = (context.arguments["max_results"] as? Number)?.toInt() ?: 10

        return try {
            val results = performSearch(query, maxResults)
            if (results.isEmpty()) {
                return ToolResult.Success("No search results found for: $query")
            }

            val formatted = results.mapIndexed { index, result ->
                "[${index + 1}] ${result.title}\n${result.url}\n${result.snippet}"
            }.joinToString("\n\n")

            ToolResult.Success("Search results for \"$query\":\n\n$formatted")
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            ToolResult.Error("Search failed: ${e.message}")
        }
    }

    private suspend fun performSearch(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=$encodedQuery")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()

                val results = mutableListOf<SearchResult>()
                OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val doc = Jsoup.parse(body)

                            doc.select(".result").forEach { element ->
                                if (results.size >= maxResults) return@forEach

                                val link = element.select(".result__a").firstOrNull()
                                val title = link?.text() ?: ""
                                val url = link?.attr("href") ?: ""

                                val snippet = element.select(".result__snippet").firstOrNull()?.text() ?: ""

                                if (url.isNotBlank() && url.startsWith("http")) {
                                    results.add(SearchResult(title, url, snippet))
                                }
                            }
                        }
                    }

                results
            } catch (e: Exception) {
                Log.w(TAG, "Search failed: ${e.message}")
                emptyList()
            }
        }
}

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

class FetchUrlContentTool : Tool {
    override val name = "fetch_url_content"
    override val description = "Fetch and extract text content from a URL. Useful for reading web pages, articles, or documentation."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = "string",
                description = "The URL to fetch content from",
                required = true
            ),
            ToolParameter(
                name = "extract_images",
                type = "boolean",
                description = "Whether to extract image descriptions",
                required = false,
                default = false
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val url = context.arguments["url"] as? String
        if (url.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: url")
        }

        val extractImages = context.arguments["extract_images"] as? Boolean ?: false

        return try {
            val content = fetchUrlContent(url, extractImages)
            if (content.isEmpty()) {
                return ToolResult.Error("Failed to extract content from: $url")
            }

            ToolResult.Success(content)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: ${e.message}", e)
            ToolResult.Error("Failed to fetch URL: ${e.message}")
        }
    }

    private suspend fun fetchUrlContent(url: String, extractImages: Boolean): String =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()

                OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            return@withContext ""
                        }

                        val body = response.body?.string() ?: ""
                        val doc = Jsoup.parse(body, url)

                        val title = doc.title()
                        doc.select("script, style, nav, footer, header, aside, .advertisement, .ads, noscript").remove()

                        val article = doc.select("article").firstOrNull()
                        val main = doc.select("main").firstOrNull()
                        val content = article ?: main ?: doc.body()

                        val text = buildString {
                            if (title.isNotBlank()) {
                                appendLine("Title: $title")
                                appendLine()
                            }
                            appendLine("URL: $url")
                            appendLine()
                            appendLine(content.text().trim())
                        }

                        text
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch URL: $url", e)
                ""
            }
        }
}

class StoreWebContentTool(
    private val vectorStore: VectorStoreRepository
) : Tool {
    override val name = "store_web_content"
    override val description = "Store fetched web content into the knowledge base for future retrieval. Call fetch_url_content first to get the content."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = "string",
                description = "The source URL of the content",
                required = true
            ),
            ToolParameter(
                name = "content",
                type = "string",
                description = "The text content to store",
                required = true
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val url = context.arguments["url"] as? String
        val content = context.arguments["content"] as? String

        if (url.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: url")
        }
        if (content.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: content")
        }

        return try {
            val chunks = chunkText(content, url)
            vectorStore.embed(chunks)

            ToolResult.Success("Successfully stored ${chunks.size} chunk(s) from: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Store failed: ${e.message}", e)
            ToolResult.Error("Failed to store content: ${e.message}")
        }
    }

    private fun chunkText(text: String, sourceId: String): List<RawChunk> {
        if (text.length <= CHUNK_SIZE) {
            return listOf(RawChunk(UUID.randomUUID().toString(), sourceId, text, 0))
        }

        val chunks = mutableListOf<RawChunk>()
        var position = 0

        while (position < text.length) {
            val end = minOf(position + CHUNK_SIZE, text.length)
            var chunkEnd = end

            if (end < text.length) {
                val lastSentence = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), end - 1)
                if (lastSentence > position) {
                    chunkEnd = lastSentence + 1
                }
            }

            val chunkText = text.substring(position, chunkEnd).trim()
            if (chunkText.isNotBlank()) {
                chunks.add(RawChunk(UUID.randomUUID().toString(), sourceId, chunkText, chunks.size))
            }

            position = chunkEnd - CHUNK_OVERLAP
            if (position <= chunks.lastOrNull()?.let { text.indexOf(it.text) + it.text.length } ?: 0) {
                position = chunkEnd
            }
            if (position >= text.length) break
        }

        return chunks
    }
}

class ListStoredSourcesTool(
    private val vectorStore: VectorStoreRepository
) : Tool {
    override val name = "list_stored_sources"
    override val description = "List all web content sources stored in the knowledge base. Use this to see what URLs have been saved."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "include_content",
                type = "boolean",
                description = "Whether to include a content preview for each source",
                required = false,
                default = false
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val includeContent = context.arguments["include_content"] as? Boolean ?: false

        return try {
            val sourceIds = vectorStore.getAllSourceIds()
            if (sourceIds.isEmpty()) {
                return ToolResult.Success("No web content has been stored yet. Use web_search to find URLs, then fetch and store them.")
            }

            val sources = sourceIds.mapIndexed { index, sourceId ->
                val chunks = vectorStore.getChunksForSource(sourceId)
                val preview = if (includeContent) {
                    "\nPreview: ${chunks.firstOrNull()?.text?.take(200)}..."
                } else ""

                val chunkCount = chunks.size
                "[${index + 1}] $sourceId ($chunkCount chunks)$preview"
            }.joinToString("\n\n")

            ToolResult.Success("Stored sources:\n\n$sources")
        } catch (e: Exception) {
            Log.e(TAG, "List failed: ${e.message}", e)
            ToolResult.Error("Failed to list sources: ${e.message}")
        }
    }
}

class DeleteStoredSourceTool(
    private val vectorStore: VectorStoreRepository
) : Tool {
    override val name = "delete_stored_source"
    override val description = "Delete all stored content from a specific URL/source. Use list_stored_sources first to see available sources."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = "string",
                description = "The URL/source to delete",
                required = true
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val url = context.arguments["url"] as? String
        if (url.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: url")
        }

        return try {
            val chunks = vectorStore.getChunksForSource(url)
            if (chunks.isEmpty()) {
                return ToolResult.Error("No stored content found for: $url")
            }

            vectorStore.deleteChunksForSource(url)
            ToolResult.Success("Deleted ${chunks.size} chunk(s) from: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}", e)
            ToolResult.Error("Failed to delete source: ${e.message}")
        }
    }
}

class ToolRegistryWebSearchBuilder(private val vectorStore: VectorStoreRepository) {
    private val registry = com.penpal.core.ai.tools.ToolRegistry()

    fun build(): com.penpal.core.ai.tools.ToolRegistry {
        registry.register(WebSearchTool())
        registry.register(FetchUrlContentTool())
        registry.register(StoreWebContentTool(vectorStore))
        registry.register(ListStoredSourcesTool(vectorStore))
        registry.register(DeleteStoredSourceTool(vectorStore))
        return registry
    }
}