package com.penpal.core.ai

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * WordPiece tokenizer implementation compatible with BERT/MiniLM models.
 *
 * Loads vocabulary from a file (assets or filesystem) and performs subword tokenization.
 * Expected vocab format: one token per line, with [PAD], [UNK], [CLS], [SEP] special tokens.
 *
 * For production use, bundle the real vocab.txt from sentence-transformers/all-MiniLM-L6-v2
 * (available at https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt)
 */
class WordPieceTokenizer internal constructor(
    private val vocab: Map<String, Int>,
    private val unkToken: String = "[UNK]",
    private val maxInputCharsPerWord: Int = 100
) {
    private val unkId = vocab[unkToken] ?: 1

    companion object {
        private const val TAG = "WordPieceTokenizer"
        private const val DEFAULT_VOCAB_PATH = "tokenizer/vocab.txt"

        /**
         * Create tokenizer from assets.
         * Place vocab.txt in app/src/main/assets/tokenizer/vocab.txt
         */
        fun fromAssets(context: Context, path: String = DEFAULT_VOCAB_PATH): WordPieceTokenizer? {
            return try {
                val vocab = mutableMapOf<String, Int>()
                context.assets.open(path).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                        lines.forEachIndexed { index, token ->
                            vocab[token.trim()] = index
                        }
                    }
                }
                Log.i(TAG, "Loaded vocabulary with ${vocab.size} tokens from assets/$path")
                WordPieceTokenizer(vocab)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load vocabulary from assets/$path: ${e.message}")
                null
            }
        }

        /**
         * Create tokenizer from file.
         */
        fun fromFile(vocabFile: File): WordPieceTokenizer? {
            return try {
                val vocab = mutableMapOf<String, Int>()
                vocabFile.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, token ->
                        vocab[token.trim()] = index
                    }
                }
                Log.i(TAG, "Loaded vocabulary with ${vocab.size} tokens from ${vocabFile.absolutePath}")
                WordPieceTokenizer(vocab)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load vocabulary from file: ${e.message}")
                null
            }
        }

        /**
         * Create a minimal fallback tokenizer with basic tokens.
         * This won't produce good embeddings but allows the app to function.
         */
        fun fallback(): WordPieceTokenizer {
            val vocab = mutableMapOf<String, Int>()
            vocab["[PAD]"] = 0
            vocab["[UNK]"] = 1
            vocab["[CLS]"] = 2
            vocab["[SEP]"] = 3
            vocab["[MASK]"] = 4

            // Basic characters and common subwords
            var nextId = 5
            "abcdefghijklmnopqrstuvwxyz0123456789 .,!?;:\\-'\"()[]{}@/#$%&*+={}<>|~`".forEach { c ->
                vocab[c.toString()] = nextId++
            }

            // Common subwords
            val commonSubwords = listOf(
                "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
                "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
                "##s", "##e", "##d", "##ing", "##ly", "##er", "##tion", "##est",
                "##n", "##t", "##r", "##a", "##o", "##i", "##l", "##m"
            )
            commonSubwords.forEach { vocab[it] = nextId++ }

            Log.i(TAG, "Created fallback vocabulary with ${vocab.size} tokens")
            return WordPieceTokenizer(vocab)
        }
    }

    val vocabSize: Int get() = vocab.size

    /**
     * Tokenize text into WordPiece token IDs.
     * Returns list including [CLS] at start and [SEP] at end.
     */
    fun tokenize(text: String, maxLength: Int = 512): List<Int> {
        val tokens = mutableListOf(vocab["[CLS]"] ?: 2)

        val words = text.lowercase()
            .replace(Regex("[^a-z0-9 .,!?;:\\-'\"()[]{}@/#\$%&*+={}<>|~`]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it.length <= maxInputCharsPerWord }

        for (word in words) {
            val wordTokens = tokenizeWord(word)
            tokens.addAll(wordTokens)
        }

        tokens.add(vocab["[SEP]"] ?: 3)

        // Truncate or pad to maxLength
        return when {
            tokens.size > maxLength -> tokens.subList(0, maxLength)
            else -> tokens
        }
    }

    /**
     * Tokenize a single word using WordPiece algorithm.
     */
    private fun tokenizeWord(word: String): List<Int> {
        val tokens = mutableListOf<Int>()
        var remaining = word

        while (remaining.isNotEmpty()) {
            var longestMatch = ""
            var longestId = unkId

            // Try all prefixes from longest to shortest
            for (i in remaining.length downTo 1) {
                val prefix = remaining.substring(0, i)
                val id = vocab[prefix]
                if (id != null) {
                    longestMatch = prefix
                    longestId = id
                    break
                }
            }

            if (longestMatch.isEmpty()) {
                // No match found - add ##prefixes
                tokens.add(unkId)
                remaining = remaining.drop(1)
                continue
            }

            tokens.add(longestId)
            remaining = remaining.substring(longestMatch.length)

            // Add ## prefix for subword continuation
            if (remaining.isNotEmpty()) {
                var foundSubword = false
                for (i in remaining.length downTo 1) {
                    val subword = "##${remaining.substring(0, i)}"
                    val id = vocab[subword]
                    if (id != null) {
                        tokens.add(id)
                        remaining = remaining.substring(i)
                        foundSubword = true
                        break
                    }
                }
                if (!foundSubword && remaining.isNotEmpty()) {
                    tokens.add(unkId)
                    remaining = remaining.drop(1)
                }
            }
        }

        return tokens
    }

    /**
     * Convert token IDs back to approximate text (for debugging).
     */
    fun decode(tokenIds: List<Int>): String {
        val idToToken = vocab.entries.associate { it.value to it.key }
        return tokenIds.map { idToToken[it] ?: "[UNK]" }
            .filter { it != "[PAD]" && it != "[CLS]" && it != "[SEP]" }
            .joinToString(" ")
            .replace(" ##", "")
    }
}
