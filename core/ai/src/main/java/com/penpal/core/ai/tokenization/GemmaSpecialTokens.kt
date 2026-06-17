package com.penpal.core.ai.tokenization

object GemmaSpecialTokens {
    val TURN_TOKENS = setOf(
        "<|turn>",
        "<turn|>",
        "<|turn>model",
        "<|turn>user",
        "<|turn>system"
    )

    val TOOL_TOKENS = setOf(
        "<|tool>",
        "<tool|>",
        "<|tool_call>",
        "<tool_call|>",
        "<|tool_response>",
        "<tool_response|>"
    )

    val THINKING_TOKENS = setOf(
        "<|think|>",
        "<|channel>",
        "<channel|>"
    )

    val MEDIA_TOKENS = setOf(
        "<|image>",
        "<image|>",
        "<|audio>",
        "<audio|>",
        "<|image|>",
        "<|audio|>"
    )

    const val STRING_DELIMITER = "<|\"|>"

    val SEQUENCE_TOKENS = setOf(
        "<bos>",
        "<eos>",
        "<|endoftext|>",
        "<|im_start|>",
        "<|im_end|>"
    )

    val ALL_USER_FACING: Set<String> = TURN_TOKENS +
            TOOL_TOKENS +
            THINKING_TOKENS +
            MEDIA_TOKENS +
            SEQUENCE_TOKENS +
            STRING_DELIMITER

    fun formatPrompt(
        messages: List<Pair<String, String>>,
        systemInstruction: String? = null
    ): String = buildString {
        systemInstruction?.let {
            appendLine("<|turn>system")
            appendLine(it)
            appendLine("<turn|>")
        }
        messages.forEach { (role, content) ->
            appendLine("<|turn>$role")
            appendLine(content)
            appendLine("<turn|>")
        }
        append("<|turn>model")
    }
}