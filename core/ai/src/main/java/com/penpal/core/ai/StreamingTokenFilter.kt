package com.penpal.core.ai

/**
 * Content mode for streamed text segments.
 * Used to track what type of content is being generated for potential UI styling.
 */
enum class ContentMode {
    /** Normal assistant response text. */
    REGULAR,
    /** Thinking/reasoning content (inside think tags). */
    THINKING,
    /** Image description or analysis content. */
    IMAGE,
    /** Audio transcription or analysis content. */
    AUDIO,
    /** Tool/function call content. */
    TOOL_CALL,
    /** Tool/function response content. */
    TOOL_RESPONSE,
    /** System instruction content. */
    SYSTEM
}

/**
 * Result of filtering a text chunk, containing the cleaned text and current content mode.
 */
data class FilteredChunk(
    /** Clean text with special tokens removed. */
    val text: String,
    /** Current content mode based on special tokens encountered. */
    val mode: ContentMode
)

/**
 * Streaming token filter that removes special tokens from a text stream.
 *
 * Uses a trie (prefix tree) to efficiently match special tokens character-by-character.
 * Tracks content mode (thinking, image, audio, etc.) based on encountered tokens.
 *
 * Token removal rules:
 * - Special tokens are completely removed from output
 * - Partial tokens at chunk boundaries are buffered until resolved
 * - When a token appears at the start of text, following whitespace is stripped
 * - When a token appears inline after content, whitespace after the token is
 *   preserved. A space is only inserted if the token sits between two words
 *   with no whitespace between them
 * - This preserves the model's intended whitespace and line breaks while
 *   ensuring text flows naturally when tokens are inline
 *
 * Examples:
 * ```
 * "Hello<|turn>model"          → "Hello"
 * "<|turn>model\nHello"        → "Hello"
 * "Hello<|turn>model\nWorld"   → "Hello\nWorld"
 * "Hello<|turn>modelWorld"     → "Hello World"
 * "Hello<|turn>model World"    → "Hello World"
 * ```
 */
class StreamingTokenFilter(
    specialTokens: Set<String> = GemmaSpecialTokens.ALL_USER_FACING
) {
    private val trie = TokenTrie(specialTokens)
    private val buffer = StringBuilder()

    private var currentMode = ContentMode.REGULAR

    /** Current content mode based on the last special token encountered. */
    val mode: ContentMode get() = currentMode

    /** Tracks whether any non-whitespace content has been emitted. */
    private var hasEmittedContent = false

    /** The last character that was emitted (including whitespace). */
    private var lastEmittedChar: Char? = null

    /** Set when a token is removed between two words (no surrounding whitespace). */
    private var needsSpaceSeparator = false

    /** Tracks mode transitions that occurred during the current append call. */
    private val pendingTransitions = mutableListOf<ModeTransitionEvent>()

    /**
     * Appends a new chunk of text and returns the safe prefix that can be emitted.
     *
     * @param chunk New text chunk from the model
     * @return FilteredChunk containing cleaned text and current mode
     */
    fun append(chunk: String): FilteredChunk {
        val result = appendWithTransitions(chunk)
        return FilteredChunk(result.text, result.mode)
    }

    /**
     * Appends a new chunk and returns cleaned text along with any mode transitions.
     *
     * This is the preferred method when building structured MessageParts, as it
     * allows the caller to know exactly when the model switches between thinking,
     * tool calling, and regular text modes.
     *
     * @param chunk New text chunk from the model
     * @return FilteredChunkWithTransitions containing cleaned text, current mode, and transitions
     */
    fun appendWithTransitions(chunk: String): FilteredChunkWithTransitions {
        if (chunk.isEmpty()) {
            return FilteredChunkWithTransitions("", currentMode, emptyList())
        }
        pendingTransitions.clear()
        buffer.append(chunk)

        val emitted = StringBuilder()
        var pos = 0

        while (pos < buffer.length) {
            val match = trie.findLongestMatch(buffer, pos)
            if (match != null) {
                val previousMode = currentMode
                updateMode(match)

                if (currentMode != previousMode) {
                    pendingTransitions.add(
                        ModeTransitionEvent(
                            fromMode = previousMode,
                            toMode = currentMode,
                            textEmittedBeforeTransition = emitted.toString()
                        )
                    )
                }

                pos += match.length

                // After stripping a special token, skip following whitespace.
                // This whitespace is part of the token formatting, not content.
                while (pos < buffer.length && buffer[pos].isWhitespace()) {
                    pos++
                }

                // If we had content before this token, we may need a space before
                // the next word. Only add space if the next character is a word
                // character (letter/digit) and the last emitted char was also a
                // word char or punctuation. Don't add space before punctuation,
                // symbols, or whitespace.
                if (hasEmittedContent) {
                    if (pos < buffer.length && shouldAddSpace(buffer[pos])) {
                        needsSpaceSeparator = true
                    }
                }

                continue
            }

            val partial = trie.findPartialMatchAtEnd(buffer, pos)
            if (partial != null) {
                break
            }

            val char = buffer[pos]

            if (needsSpaceSeparator) {
                // Only add space if next char is a word character and last char
                // was not whitespace or symbol
                if (!char.isWhitespace() && shouldAddSpace(char)) {
                    emitted.append(' ')
                }
                needsSpaceSeparator = false
            }

            emitted.append(char)
            lastEmittedChar = char
            if (!char.isWhitespace()) {
                hasEmittedContent = true
            }
            pos++
        }

        if (pos > 0) {
            buffer.delete(0, pos)
        }

        return FilteredChunkWithTransitions(
            text = emitted.toString(),
            mode = currentMode,
            transitions = pendingTransitions.toList()
        )
    }

    /**
     * Flushes any remaining buffered text, removing special tokens.
     * Call this when the stream is complete.
     */
    fun flush(): FilteredChunk {
        if (buffer.isEmpty()) {
            val mode = currentMode
            resetState()
            return FilteredChunk("", mode)
        }

        // Try to remove any complete special tokens from remaining buffer
        var text = buffer.toString()
        var changed = true
        while (changed) {
            changed = false
            for (token in trie.allTokens) {
                if (text.contains(token)) {
                    text = text.replace(token, "")
                    changed = true
                }
            }
        }

        // If we need a space separator, check if remaining text starts with
        // a character that should have a space before it
        if (needsSpaceSeparator && text.isNotEmpty()) {
            if (shouldAddSpace(text[0])) {
                text = " $text"
            }
        }

        buffer.clear()
        val mode = currentMode
        resetState()
        return FilteredChunk(text, mode)
    }

    /** Clear the internal buffer and reset all state. */
    fun clear() {
        buffer.clear()
        resetState()
    }

    private fun resetState() {
        currentMode = ContentMode.REGULAR
        hasEmittedContent = false
        lastEmittedChar = null
        needsSpaceSeparator = false
    }

    /**
     * Determines whether a space should be added before [nextChar] based on
     * the last emitted character.
     *
     * Rules:
     * - Add space before word chars (letters/digits) if last char was also a
     *   word char or punctuation (but not whitespace or symbols like _)
     * - Never add space before punctuation, symbols, or whitespace
     */
    private fun shouldAddSpace(nextChar: Char): Boolean {
        if (!nextChar.isLetterOrDigit()) return false
        val last = lastEmittedChar ?: return false
        if (last.isWhitespace()) return false
        // Don't add space after symbols like _ or $
        if (last == '_' || last == '$') return false
        return true
    }

    private fun updateMode(token: String) {
        currentMode = when {
            token in GemmaSpecialTokens.THINKING_TOKENS -> ContentMode.THINKING
            token in GemmaSpecialTokens.MEDIA_TOKENS -> when {
                token.contains("image", ignoreCase = true) -> ContentMode.IMAGE
                token.contains("audio", ignoreCase = true) -> ContentMode.AUDIO
                else -> ContentMode.REGULAR
            }
            token in GemmaSpecialTokens.TOOL_TOKENS -> when {
                token.contains("tool_call", ignoreCase = true) -> ContentMode.TOOL_CALL
                token.contains("tool_response", ignoreCase = true) -> ContentMode.TOOL_RESPONSE
                else -> ContentMode.REGULAR
            }
            token in GemmaSpecialTokens.TURN_TOKENS -> when {
                token.contains("system", ignoreCase = true) -> ContentMode.SYSTEM
                else -> ContentMode.REGULAR
            }
            token in GemmaSpecialTokens.SEQUENCE_TOKENS -> ContentMode.REGULAR
            else -> currentMode
        }
    }
}

/**
 * Trie (prefix tree) for efficient special token matching.
 */
private class TokenTrie(tokens: Set<String>) {
    private val root = TrieNode()
    val allTokens = tokens.toList()

    init {
        tokens.forEach { insert(it) }
    }

    private fun insert(token: String) {
        var node = root
        for (char in token) {
            node = node.children.getOrPut(char) { TrieNode() }
        }
        node.isEndOfToken = true
        node.token = token
    }

    /**
     * Finds the longest complete special token match starting at [start] in [text].
     * Returns the matched token string, or null if no complete token is found.
     */
    fun findLongestMatch(text: CharSequence, start: Int): String? {
        var node = root
        var lastMatch: String? = null

        for (i in start until text.length) {
            node = node.children[text[i]] ?: break
            if (node.isEndOfToken) {
                lastMatch = node.token
            }
        }

        return lastMatch
    }

    /**
     * Checks if there's a partial match starting at [start] that reaches
     * the end of [text] but is NOT a complete token.
     *
     * Returns the partial match string if found, null otherwise.
     */
    fun findPartialMatchAtEnd(text: CharSequence, start: Int): String? {
        if (start >= text.length) return null

        var node = root
        var lastPartialLength = 0

        for (i in start until text.length) {
            val char = text[i]
            if (char !in node.children) break
            node = node.children[char]!!
            lastPartialLength = i - start + 1
        }

        // Check if this is a valid partial match:
        // 1. We matched at least one character
        // 2. The match reaches the end of the text
        // 3. The node has children (can be extended to a full token) OR is not a complete token
        if (lastPartialLength > 0 && start + lastPartialLength == text.length) {
            if (!node.isEndOfToken || node.children.isNotEmpty()) {
                return text.substring(start, start + lastPartialLength)
            }
        }

        return null
    }
}

private class TrieNode {
    val children = mutableMapOf<Char, TrieNode>()
    var isEndOfToken = false
    var token: String = ""
}
