package com.penpal.core.ai

import android.util.Log

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
        Log.d("ToolRegistry", "Registered tool: ${tool.name}")
    }

    fun unregister(name: String) {
        tools.remove(name)
        Log.d("ToolRegistry", "Unregistered tool: $name")
    }

    fun get(name: String): Tool? = tools[name]

    fun getAll(): List<Tool> = tools.values.toList()

    fun has(name: String): Boolean = tools.containsKey(name)

    fun getToolSchemas(): List<ToolSchema> = tools.values.map { it.schema }

    fun getToolsJson(): String {
        val schemas = getToolSchemas()
        if (schemas.isEmpty()) {
            return "[]"
        }
        val schemasJson = schemas.joinToString(",\n") { schema ->
            val paramsJson = schema.parameters.joinToString(",\n") { param ->
                val defaultJson = param.default?.let { ", \"default\": ${gson.toJson(it)}" } ?: ""
                """
                |            {
                |                "name": "${param.name}",
                |                "type": "${param.type}",
                |                "description": "${param.description}",
                |                "required": ${param.required}$defaultJson
                |            }
                """.trimMargin()
            }
            """
            |{
            |    "name": "${schema.name}",
            |    "description": "${schema.description}",
            |    "parameters": {
            |        "type": "object",
            |        "properties": {
            |$paramsJson
            |        },
            |        "required": [${schema.parameters.filter { it.required }.joinToString { "\"${it.name}\"" }}]
            |    }
            |}
            """.trimMargin()
        }
        return "[\n$schemasJson\n]"
    }

    suspend fun execute(
        name: String,
        context: ToolExecutionContext
    ): ToolResult {
        val tool = tools[name]
        if (tool == null) {
            Log.e("ToolRegistry", "Tool not found: $name")
            return ToolResult.Error("Tool '$name' not found")
        }

        Log.d("ToolRegistry", "Executing tool: $name with args: ${context.arguments}")
        return try {
            tool.execute(context)
        } catch (e: Exception) {
            Log.e("ToolRegistry", "Tool execution error: ${e.message}", e)
            ToolResult.Error(e.message ?: "Unknown error")
        }
    }

    companion object {
        private val gson = com.google.gson.Gson()
    }
}
