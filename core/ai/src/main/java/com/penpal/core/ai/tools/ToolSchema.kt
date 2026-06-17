package com.penpal.core.ai.tools

data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val default: Any? = null
)

fun Tool.toSchemaJson(): String {
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

    return """
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

private val gson = com.google.gson.Gson()