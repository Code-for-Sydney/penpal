package com.penpal.core.ai

/**
 * All Gemma 4 control/special tokens that should be filtered from user-facing text.
 *
 * These tokens are reserved in the Gemma tokenizer and used for:
 * - Dialogue turns: <|turn>, <turn|>
 * - Tool use: <|tool>, <tool|>, <|tool_call>, <tool_call|>, <|tool_response>, <tool_response|>
 * - Thinking: <|think|>, <|channel>, <channel|>
 * - Media: <|image>, <image|>, <|audio>, <audio|>
 * - String delimiter: <|"|>
 * - Sequence markers: <bos>, <eos>
 */
object GemmaSpecialTokens {

    /** Tokens that mark the beginning and end of dialogue turns. */
    val TURN_TOKENS = setOf(
        "<|turn>",
        "<turn|>",
        "<|turn>model",
        "<|turn>user",
        "<|turn>system"
    )

    /** Tokens used for tool/function calling lifecycle. */
    val TOOL_TOKENS = setOf(
        "<|tool>",
        "<tool|>",
        "<|tool_call>",
        "<tool_call|>",
        "<|tool_response>",
        "<tool_response|>"
    )

    /** Tokens used for thinking/reasoning mode. */
    val THINKING_TOKENS = setOf(
        "<|think|>",
        "<|channel>",
        "<channel|>",
        "thought" // Often follows <|channel> in thinking mode
    )

    /** Tokens used for multimodal content (image/audio embeddings). */
    val MEDIA_TOKENS = setOf(
        "<|image>",
        "<image|>",
        "<|audio>",
        "<audio|>",
        "<|image|>",
        "<|audio|>"
    )

    /** Special string delimiter used inside structured data blocks. */
    const val STRING_DELIMITER = "<|\">|>"

    /** Sequence boundary tokens. */
    val SEQUENCE_TOKENS = setOf(
        "<bos>",
        "<eos>",
        "<|endoftext|>",
        "<|im_start|>",
        "<|im_end|>"
    )

    /** Complete set of all special tokens to filter from user-facing output. */
    val ALL_USER_FACING: Set<String> = TURN_TOKENS +
            TOOL_TOKENS +
            THINKING_TOKENS +
            MEDIA_TOKENS +
            SEQUENCE_TOKENS +
            STRING_DELIMITER

    /**
     * Format a simple user prompt into the Gemma 4 chat template.
     * LiteRT-LM handles most formatting internally, but this is useful
     * for reference or manual prompt construction.
     */
    fun formatPrompt(
        messages: List<Pair<String, String>>, // role to content
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
