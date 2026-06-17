package com.penpal.core.ai.embedding

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class WordPieceTokenizer internal constructor(
    private val vocab: Map<String, Int>,
    private val unkToken: String = "[UNK]",
    private val maxInputCharsPerWord: Int = 100
) {
    private val unkId = vocab[unkToken] ?: 1

    companion object {
        private const val TAG = "WordPieceTokenizer"
        private const val DEFAULT_VOCAB_PATH = "tokenizer/vocab.txt"

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

        fun fallback(): WordPieceTokenizer {
            val vocab = mutableMapOf<String, Int>()
            vocab["[PAD]"] = 0
            vocab["[UNK]"] = 1
            vocab["[CLS]"] = 2
            vocab["[SEP]"] = 3
            vocab["[MASK]"] = 4

            var nextId = 5
            "abcdefghijklmnopqrstuvwxyz0123456789 .,!?;:\\-'\"()[]{}@/#$%&*+={}<>|~`".forEach { c ->
                vocab[c.toString()] = nextId++
            }

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

        return when {
            tokens.size > maxLength -> tokens.subList(0, maxLength)
            else -> tokens
        }
    }

    private fun tokenizeWord(word: String): List<Int> {
        val tokens = mutableListOf<Int>()
        var remaining = word

        while (remaining.isNotEmpty()) {
            var longestMatch = ""
            var longestId = unkId

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
                tokens.add(unkId)
                remaining = remaining.drop(1)
                continue
            }

            tokens.add(longestId)
            remaining = remaining.substring(longestMatch.length)

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

    fun decode(tokenIds: List<Int>): String {
        val idToToken = vocab.entries.associate { it.value to it.key }
        return tokenIds.map { idToToken[it] ?: "[UNK]" }
            .filter { it != "[PAD]" && it != "[CLS]" && it != "[SEP]" }
            .joinToString(" ")
            .replace(" ##", "")
    }
}