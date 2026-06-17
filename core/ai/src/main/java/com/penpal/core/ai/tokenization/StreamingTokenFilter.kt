package com.penpal.core.ai.tokenization

import com.penpal.core.ai.messaging.ContentMode
import com.penpal.core.ai.messaging.FilteredChunk
import com.penpal.core.ai.messaging.FilteredChunkWithTransitions

class StreamingTokenFilter(
    specialTokens: Set<String> = GemmaSpecialTokens.ALL_USER_FACING
) {
    private val trie = TokenTrie(specialTokens)
    private val buffer = StringBuilder()

    private var currentMode = ContentMode.REGULAR
    val mode: ContentMode get() = currentMode

    private var hasEmittedContent = false
    private var lastEmittedChar: Char? = null
    private var needsSpaceSeparator = false
    private val pendingTransitions = mutableListOf<com.penpal.core.ai.messaging.ModeTransitionEvent>()

    fun append(chunk: String): FilteredChunk {
        val result = appendWithTransitions(chunk)
        return FilteredChunk(result.text, result.mode)
    }

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
                        com.penpal.core.ai.messaging.ModeTransitionEvent(
                            fromMode = previousMode,
                            toMode = currentMode,
                            textEmittedBeforeTransition = emitted.toString()
                        )
                    )
                }

                pos += match.length

                while (pos < buffer.length && buffer[pos].isWhitespace()) {
                    pos++
                }

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

    fun flush(): FilteredChunk {
        if (buffer.isEmpty()) {
            val mode = currentMode
            resetState()
            return FilteredChunk("", mode)
        }

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

    private fun shouldAddSpace(nextChar: Char): Boolean {
        if (!nextChar.isLetterOrDigit()) return false
        val last = lastEmittedChar ?: return false
        if (last.isWhitespace()) return false
        if (last == '_' || last == '$') return false
        return true
    }

    private fun updateMode(token: String) {
        currentMode = when {
            token in GemmaSpecialTokens.THINKING_TOKENS -> when {
                // Closing thinking tokens switch back to REGULAR
                token.endsWith("|>") || token.startsWith("<channel|") -> ContentMode.REGULAR
                else -> ContentMode.THINKING
            }
            token in GemmaSpecialTokens.MEDIA_TOKENS -> when {
                token.contains("image", ignoreCase = true) -> ContentMode.IMAGE
                token.contains("audio", ignoreCase = true) -> ContentMode.AUDIO
                else -> ContentMode.REGULAR
            }
            token in GemmaSpecialTokens.TOOL_TOKENS -> when {
                // Closing tool tokens switch back to REGULAR
                token.endsWith("|>") -> ContentMode.REGULAR
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