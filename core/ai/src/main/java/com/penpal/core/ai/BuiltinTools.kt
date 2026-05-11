package com.penpal.core.ai

import android.util.Log
import com.penpal.core.data.StackDao

class SearchKnowledgeTool(
    private val vectorStore: VectorStoreRepository
) : Tool {
    override val name = "search_knowledge"
    override val description = "Search the knowledge base for relevant information based on a query. Use this when the user asks questions that might be answered by previously uploaded documents or notes."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = "string",
                description = "The search query to find relevant information",
                required = true
            ),
            ToolParameter(
                name = "max_results",
                type = "integer",
                description = "Maximum number of results to return",
                required = false,
                default = 5
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val query = context.arguments["query"] as? String
        if (query.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: query")
        }

        val maxResults = (context.arguments["max_results"] as? Number)?.toInt() ?: 5

        return try {
            val chunks = vectorStore.similaritySearch(query, maxResults)
            if (chunks.isEmpty()) {
                return ToolResult.Success("No relevant information found in knowledge base.")
            }

            val results = chunks.mapIndexed { index, chunk ->
                "[${index + 1}] ${chunk.text.take(500)}"
            }.joinToString("\n\n")

            ToolResult.Success("Found ${chunks.size} relevant result(s):\n\n$results")
        } catch (e: Exception) {
            Log.e("SearchKnowledgeTool", "Search failed: ${e.message}", e)
            ToolResult.Error("Search failed: ${e.message}")
        }
    }
}

class ReadStackTool(
    private val stackDao: StackDao
) : Tool {
    override val name = "read_stack"
    override val description = "Read the content of a notebook/stack. Returns the title and all blocks (text, images, equations) in the notebook."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = listOf(
            ToolParameter(
                name = "stack_id",
                type = "string",
                description = "The ID of the notebook to read",
                required = true
            )
        )
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        val stackId = context.arguments["stack_id"] as? String
        if (stackId.isNullOrBlank()) {
            return ToolResult.Error("Missing required parameter: stack_id")
        }

        return try {
            val entity = stackDao.getStack(stackId)
            if (entity == null) {
                return ToolResult.Error("Notebook not found: $stackId")
            }

            val blocksJson = entity.blocksJson
            val title = entity.title

            ToolResult.Success("""
                |Notebook: $title
                |ID: $stackId
                |Blocks: $blocksJson
            """.trimMargin())
        } catch (e: Exception) {
            Log.e("ReadStackTool", "Read failed: ${e.message}", e)
            ToolResult.Error("Failed to read notebook: ${e.message}")
        }
    }
}

class GetConversationHistoryTool : Tool {
    override val name = "get_conversation_history"
    override val description = "Get the full conversation history for context. Returns all previous messages in the current conversation."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = emptyList()
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        if (context.conversationHistory.isEmpty()) {
            return ToolResult.Success("No conversation history available.")
        }

        val history = context.conversationHistory.joinToString("\n\n") { msg ->
            val role = when (msg.role) {
                com.penpal.core.ai.MessageRole.USER -> "User"
                com.penpal.core.ai.MessageRole.ASSISTANT -> "Assistant"
            }
            "[$role]: ${msg.content.take(500)}"
        }

        return ToolResult.Success("Conversation history:\n\n$history")
    }
}

class ListAttachedStacksTool : Tool {
    override val name = "list_attached_stacks"
    override val description = "List all notebooks that are currently attached to the conversation. Use this to see what reference materials are available."
    override val schema = ToolSchema(
        name = name,
        description = description,
        parameters = emptyList()
    )

    override suspend fun execute(context: ToolExecutionContext): ToolResult {
        if (context.attachedStackIds.isEmpty()) {
            return ToolResult.Success("No notebooks are currently attached to this conversation.")
        }

        val stacks = context.attachedStackIds.mapIndexed { index, id ->
            "[${index + 1}] Notebook ID: $id"
        }.joinToString("\n")

        return ToolResult.Success("Attached notebooks:\n\n$stacks")
    }
}

class ToolRegistryBuilder {
    private val registry = ToolRegistry()

    fun withSearchKnowledge(vectorStore: VectorStoreRepository): ToolRegistryBuilder {
        registry.register(SearchKnowledgeTool(vectorStore))
        return this
    }

    fun withStackReader(stackDao: StackDao): ToolRegistryBuilder {
        registry.register(ReadStackTool(stackDao))
        return this
    }

    fun withConversationHistory(): ToolRegistryBuilder {
        registry.register(GetConversationHistoryTool())
        return this
    }

    fun withListAttachedStacks(): ToolRegistryBuilder {
        registry.register(ListAttachedStacksTool())
        return this
    }

    fun withWebSearch(vectorStore: VectorStoreRepository): ToolRegistryBuilder {
        registry.register(WebSearchTool())
        registry.register(FetchUrlContentTool())
        registry.register(StoreWebContentTool(vectorStore))
        registry.register(ListStoredSourcesTool(vectorStore))
        registry.register(DeleteStoredSourceTool(vectorStore))
        return this
    }

    fun withTool(tool: Tool): ToolRegistryBuilder {
        registry.register(tool)
        return this
    }

    fun build(): ToolRegistry = registry
}
