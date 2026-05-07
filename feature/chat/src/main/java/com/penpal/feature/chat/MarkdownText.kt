package com.penpal.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight markdown renderer for streaming chat text.
 * 
 * Supports:
 * - Code blocks (```...```) with background and monospace font
 * - Inline code (`...`) with background
 * - Bold (**...**)
 * - Italic (*...*)
 * - Headers (# ...)
 * - Bullet lists (- ...)
 * - Links ([text](url))
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium
) {
    val parsed = parseMarkdown(text)
    
    Column(modifier = modifier) {
        parsed.elements.forEach { element ->
            when (element) {
                is MarkdownElement.Text -> {
                    Text(
                        text = element.content,
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is MarkdownElement.CodeBlock -> {
                    CodeBlock(
                        code = element.code,
                        language = element.language,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
                is MarkdownElement.Header -> {
                    Text(
                        text = element.text,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = when (element.level) {
                                1 -> 20.sp
                                2 -> 18.sp
                                else -> 16.sp
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MarkdownElement.BulletList -> {
                    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
                        element.items.forEach { item ->
                            Text(
                                text = "\u2022 ${item}",
                                style = style,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                is MarkdownElement.NumberedList -> {
                    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
                        element.items.forEachIndexed { index, item ->
                            Text(
                                text = "${index + 1}. ${item}",
                                style = style,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        language?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                ),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Parse markdown text into structured elements.
 * This is a simplified parser for streaming text.
 */
private fun parseMarkdown(text: String): ParsedMarkdown {
    val elements = mutableListOf<MarkdownElement>()
    val lines = text.split("\n")
    
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        
        when {
            // Code block
            line.startsWith("```") -> {
                val language = line.substring(3).trim().takeIf { it.isNotEmpty() }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                elements.add(MarkdownElement.CodeBlock(codeLines.joinToString("\n"), language))
                i++ // skip closing ```
            }
            // Header
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                val headerText = line.substring(level).trim()
                elements.add(MarkdownElement.Header(headerText, level))
                i++
            }
            // Bullet list
            line.trimStart().startsWith("-") || line.trimStart().startsWith("*") -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val currentLine = lines[i]
                    val trimmed = currentLine.trimStart()
                    if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                        items.add(trimmed.substring(1).trim())
                        i++
                    } else if (currentLine.isBlank() && items.isNotEmpty()) {
                        i++
                        break
                    } else {
                        break
                    }
                }
                if (items.isNotEmpty()) {
                    elements.add(MarkdownElement.BulletList(items))
                }
            }
            // Numbered list
            line.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val currentLine = lines[i]
                    val trimmed = currentLine.trimStart()
                    val match = Regex("^\\d+\\.\\s(.*)").find(trimmed)
                    if (match != null) {
                        items.add(match.groupValues[1])
                        i++
                    } else if (currentLine.isBlank() && items.isNotEmpty()) {
                        i++
                        break
                    } else {
                        break
                    }
                }
                if (items.isNotEmpty()) {
                    elements.add(MarkdownElement.NumberedList(items))
                }
            }
            // Regular text with inline formatting
            else -> {
                if (line.isNotBlank()) {
                    elements.add(MarkdownElement.Text(parseInlineMarkdown(line)))
                }
                i++
            }
        }
    }
    
    return ParsedMarkdown(elements)
}

/**
 * Parse inline markdown: bold, italic, inline code, links.
 */
private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var pos = 0
        
        while (pos < text.length) {
            when {
                // Bold **text**
                text.startsWith("**", pos) -> {
                    val end = text.indexOf("**", pos + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(pos + 2, end))
                        }
                        pos = end + 2
                    } else {
                        append("**")
                        pos += 2
                    }
                }
                // Italic *text* (but not **)
                text.startsWith("*", pos) && !text.startsWith("**", pos) -> {
                    val end = text.indexOf("*", pos + 1)
                    if (end != -1 && !text.startsWith("**", end)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else {
                        append("*")
                        pos += 1
                    }
                }
                // Inline code `text`
                text.startsWith("`", pos) && !text.startsWith("``", pos) -> {
                    val end = text.indexOf("`", pos + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = androidx.compose.ui.graphics.Color.LightGray.copy(alpha = 0.3f)
                        )) {
                            append(text.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else {
                        append("`")
                        pos += 1
                    }
                }
                // Link [text](url)
                text.startsWith("[", pos) -> {
                    val closeBracket = text.indexOf("]", pos)
                    val openParen = text.indexOf("(", closeBracket)
                    val closeParen = text.indexOf(")", openParen)
                    if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                        val linkText = text.substring(pos + 1, closeBracket)
                        val url = text.substring(openParen + 1, closeParen)
                        withStyle(SpanStyle(
                            color = androidx.compose.ui.graphics.Color.Blue,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        pos = closeParen + 1
                    } else {
                        append("[")
                        pos += 1
                    }
                }
                else -> {
                    append(text[pos])
                    pos++
                }
            }
        }
    }
}

private data class ParsedMarkdown(val elements: List<MarkdownElement>)

private sealed class MarkdownElement {
    data class Text(val content: AnnotatedString) : MarkdownElement()
    data class CodeBlock(val code: String, val language: String?) : MarkdownElement()
    data class Header(val text: String, val level: Int) : MarkdownElement()
    data class BulletList(val items: List<String>) : MarkdownElement()
    data class NumberedList(val items: List<String>) : MarkdownElement()
}
