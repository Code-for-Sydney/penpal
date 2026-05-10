package com.penpal.feature.chat

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.viewinterop.AndroidView

private val syntaxColors = SyntaxColors()

private data class SyntaxColors(
    val keyword: Color = Color(0xFFCC7832),
    val string: Color = Color(0xFF6A8759),
    val number: Color = Color(0xFF6897BB),
    val comment: Color = Color(0xFF808080),
    val function: Color = Color(0xFFFFC66D),
    val type: Color = Color(0xFF5799C7),
    val operator: Color = Color(0xFFA9B7C6),
    val variable: Color = Color(0xFFA9B7C6),
    val punctuation: Color = Color(0xFFA9B7C6),
    val annotation: Color = Color(0xFFBBB529),
    val property: Color = Color(0xFF9876AA),
    val tag: Color = Color(0xFF5799C7),
    val attribute: Color = Color(0xFF80FF00),
    val default: Color = Color(0xFFA9B7C6)
)

private data class Token(
    val text: String,
    val type: TokenType
)

private enum class TokenType {
    KEYWORD, STRING, NUMBER, COMMENT, FUNCTION, TYPE,
    OPERATOR, VARIABLE, PUNCTUATION, ANNOTATION, PROPERTY,
    TAG, ATTRIBUTE, PLAIN
}

private val keywordPatterns = mapOf(
    "python" to listOf("def", "class", "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "import", "from", "as", "return", "yield", "raise", "pass", "break", "continue", "and", "or", "not", "in", "is", "None", "True", "False", "lambda", "async", "await", "self", "print", "range", "len", "str", "int", "float", "list", "dict", "set", "tuple", "input", "open", "isinstance", "hasattr", "getattr", "setattr"),
    "kotlin" to listOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return", "break", "continue", "throw", "try", "catch", "finally", "import", "package", "as", "is", "in", "out", "suspend", "inline", "private", "public", "protected", "internal", "open", "override", "abstract", "sealed", "data", "enum", "companion", "object", "this", "super", "null", "true", "false", "println", "print", "listOf", "mapOf", "setOf", "arrayOf", "mutableListOf", "mutableMapOf", "mutableSetOf"),
    "java" to listOf("public", "private", "protected", "class", "interface", "enum", "extends", "implements", "new", "this", "super", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "throw", "try", "catch", "finally", "import", "package", "static", "final", "abstract", "synchronized", "volatile", "transient", "native", "void", "int", "long", "short", "byte", "float", "double", "char", "boolean", "String", "true", "false", "null", "instanceof", "assert"),
    "javascript" to listOf("function", "const", "let", "var", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "throw", "try", "catch", "finally", "class", "extends", "new", "this", "super", "import", "export", "from", "as", "async", "await", "yield", "true", "false", "null", "undefined", "typeof", "instanceof", "in", "of", "console", "log", "Promise", "Array", "Object", "String", "Number", "Boolean"),
    "typescript" to listOf("function", "const", "let", "var", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "throw", "try", "catch", "finally", "class", "extends", "new", "this", "super", "import", "export", "from", "as", "async", "await", "yield", "true", "false", "null", "undefined", "typeof", "instanceof", "in", "of", "interface", "type", "enum", "namespace", "module", "declare", "abstract", "readonly", "private", "public", "protected", "implements", "keyof", "infer", "extends", "never", "unknown", "any"),
    "go" to listOf("func", "var", "const", "type", "struct", "interface", "map", "chan", "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "return", "go", "defer", "select", "package", "import", "nil", "true", "false", "make", "new", "len", "cap", "append", "copy", "delete", "panic", "recover", "print", "println", "fmt"),
    "rust" to listOf("fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "type", "where", "if", "else", "match", "for", "while", "loop", "break", "continue", "return", "use", "mod", "pub", "crate", "self", "super", "as", "in", "ref", "move", "async", "await", "dyn", "true", "false", "Some", "None", "Ok", "Err", "println", "vec", "String", "Option", "Result", "Box"),
    "c" to listOf("int", "long", "short", "char", "float", "double", "void", "signed", "unsigned", "const", "static", "extern", "register", "volatile", "auto", "struct", "union", "enum", "typedef", "sizeof", "if", "else", "switch", "case", "default", "for", "while", "do", "break", "continue", "return", "goto", "NULL", "true", "false"),
    "cpp" to listOf("int", "long", "short", "char", "float", "double", "void", "bool", "signed", "unsigned", "const", "static", "extern", "register", "volatile", "auto", "struct", "union", "enum", "class", "public", "private", "protected", "virtual", "override", "final", "template", "typename", "namespace", "using", "new", "delete", "this", "nullptr", "true", "false", "if", "else", "switch", "case", "default", "for", "while", "do", "break", "continue", "return", "throw", "try", "catch", "cout", "cin", "endl", "std", "string", "vector", "map", "set"),
    "swift" to listOf("func", "var", "let", "class", "struct", "enum", "protocol", "extension", "if", "else", "guard", "switch", "case", "default", "for", "while", "repeat", "break", "continue", "return", "throw", "try", "catch", "import", "public", "private", "internal", "fileprivate", "open", "static", "final", "override", "lazy", "weak", "unowned", "mutating", "nonmutating", "init", "deinit", "self", "Self", "super", "nil", "true", "false", "as", "is", "in", "where", "Type", "Print"),
    "sql" to listOf("SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "ADD", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AND", "OR", "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "AS", "DISTINCT", "COUNT", "SUM", "AVG", "MAX", "MIN", "UNION", "ALL", "EXISTS", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "INDEX", "VIEW", "TRIGGER", "CASE", "WHEN", "THEN", "ELSE", "END"),
    "bash" to listOf("if", "then", "else", "elif", "fi", "for", "do", "done", "while", "until", "case", "esac", "function", "return", "exit", "break", "continue", "export", "source", "alias", "unalias", "cd", "pwd", "ls", "echo", "printf", "read", "test", "true", "false", "local", "declare", "typeset", "readonly", "shift", "set", "unset", "eval", "exec"),
    "json" to listOf("true", "false", "null"),
    "yaml" to listOf("true", "false", "null", "yes", "no", "on", "off"),
    "html" to listOf("html", "head", "body", "div", "span", "p", "a", "img", "table", "tr", "td", "th", "ul", "ol", "li", "form", "input", "button", "select", "option", "textarea", "label", "script", "style", "link", "meta", "title", "header", "footer", "nav", "section", "article", "aside", "main", "h1", "h2", "h3", "h4", "h5", "h6"),
    "css" to listOf("color", "background", "border", "margin", "padding", "width", "height", "display", "position", "top", "left", "right", "bottom", "font", "text", "align", "justify", "transform", "transition", "animation", "flex", "grid", "important", "auto", "none", "block", "inline", "relative", "absolute", "fixed", "static")
)

private val typePatterns = mapOf(
    "python" to listOf("int", "str", "float", "bool", "list", "dict", "tuple", "set", "bytes", "object", "type", "Exception", "Callable", "Optional", "Union", "List", "Dict", "Tuple", "Set"),
    "kotlin" to listOf("Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String", "Unit", "Any", "Nothing", "List", "Map", "Set", "Array", "MutableList", "MutableMap", "MutableSet"),
    "java" to listOf("Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character", "String", "Object", "Class", "System", "Math", "Arrays", "Collections", "List", "Map", "Set"),
    "javascript" to listOf("Array", "Object", "String", "Number", "Boolean", "Date", "RegExp", "Error", "Map", "Set", "Promise", "Symbol", "BigInt"),
    "typescript" to listOf("Array", "Object", "String", "Number", "Boolean", "Date", "RegExp", "Error", "Map", "Set", "Promise", "Symbol", "BigInt", "Partial", "Required", "Readonly", "Record", "Pick", "Omit", "Exclude", "Extract", "NonNullable"),
    "go" to listOf("int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64", "float32", "float64", "complex64", "complex128", "byte", "rune", "string", "bool", "error"),
    "rust" to listOf("i8", "i16", "i32", "i64", "i128", "u8", "u16", "u32", "u64", "u128", "f32", "f64", "bool", "char", "str", "String", "Vec", "Option", "Result", "Box", "Rc", "Arc"),
    "swift" to listOf("Int", "Int8", "Int16", "Int32", "Int64", "UInt", "UInt8", "UInt16", "UInt32", "UInt64", "Float", "Double", "Bool", "Character", "String", "Array", "Dictionary", "Set", "Optional", "Any", "AnyObject")
)

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
                is MarkdownElement.LatexBlock -> {
                    LatexBlock(
                        expression = element.expression,
                        isBlock = element.isBlock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
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
    val scrollState = rememberScrollState()
    val normalizedLang = language?.lowercase()?.trim() ?: ""
    val highlightedCode = highlightCode(code, normalizedLang)

    Column(
        modifier = modifier
            .background(
                Color(0xFF2B2B2B),
                RoundedCornerShape(8.dp)
            )
    ) {
        if (language != null) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF808080),
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = if (language != null) 0.dp else 8.dp)
        ) {
            Text(
                text = highlightedCode,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                color = Color(0xFFA9B7C6)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LatexBlock(
    expression: String,
    isBlock: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val escapedExpression = remember(expression) {
        expression
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    val isBalanced = remember(expression) {
        var depth = 0
        for (c in expression) {
            when (c) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth < 0) return@remember false
        }
        depth == 0
    }

    val htmlContent = remember(escapedExpression, isBlock) {
        val displayType = if (isBlock) "display" else "inline"
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
            <script src="https://polyfill.io/v3/polyfill.min.js?features=es6"></script>
            <script id="MathJax-script" src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>
            <script>
                MathJax = {
                    tex: {
                        inlineMath: [['$', '$'], ['\\(', '\\)']],
                        displayMath: [['$$', '$$'], ['\\[', '\\]']],
                        processEscapes: true
                    },
                    options: {
                        skipHtmlTags: ['script', 'noscript', 'style', 'textarea', 'pre']
                    },
                    startup: {
                        pageReady: () => { return MathJax.startup.defaultPageReady(); }
                    }
                };
            </script>
            <style>
                body {
                    margin: 0;
                    padding: 8px;
                    background-color: transparent;
                    color: #A9B7C6;
                    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: ${if (isBlock) "center" else "flex-start"};
                    min-height: 50px;
                    box-sizing: border-box;
                }
                .math-tex {
                    font-size: ${if (isBlock) "18px" else "14px"};
                    color: #A9B7C6;
                }
            </style>
        </head>
        <body>
            <div class="math-tex">$$$escapedExpression$$</div>
        </body>
        </html>
        """.trimIndent()
    }

    if (isBalanced) {
        var webViewRef: WebView? = null

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    webViewClient = WebViewClient()
                    settings.apply {
                        javaScriptEnabled = true
                        builtInZoomControls = true
                        displayZoomControls = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        domStorageEnabled = false
                        setSupportZoom(true)
                    }
                    setOnLongClickListener {
                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setText(expression)
                        Toast.makeText(ctx, "Copied TeX command", Toast.LENGTH_SHORT).show()
                        true
                    }
                    webViewRef = this
                    loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                }
            },
            modifier = modifier
                .background(Color(0xFF2B2B2B), RoundedCornerShape(8.dp))
                .heightIn(min = if (isBlock) 60.dp else 30.dp)
        )
    } else {
        Text(
            text = expression,
            modifier = modifier
                .background(Color(0xFF2B2B2B), RoundedCornerShape(8.dp))
                .padding(8.dp),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (isBlock) 18.sp else 14.sp
            ),
            color = Color(0xFFA9B7C6)
        )
    }
}

private fun highlightCode(code: String, language: String): AnnotatedString {
    return buildAnnotatedString {
        val keywords = keywordPatterns[language] ?: emptyList()
        val types = typePatterns[language] ?: emptyList()

        val lines = code.split("\n")
        for ((lineIndex, line) in lines.withIndex()) {
            if (lineIndex > 0) {
                append("\n")
            }

            tokenizeLine(line, language, keywords, types)
        }
    }
}

private fun AnnotatedString.Builder.tokenizeLine(
    line: String,
    language: String,
    keywords: List<String>,
    types: List<String>
) {
    val trimmed = line.trimStart()
    val indent = line.length - trimmed.length

    repeat(indent) { append(" ") }

    if (trimmed.startsWith("//") || trimmed.startsWith("#") && language != "python" && language != "yaml" && language != "python" || trimmed.startsWith("/*") || trimmed.startsWith("<!--")) {
        withStyle(SpanStyle(color = syntaxColors.comment)) {
            append(trimmed)
        }
        return
    }

    if (trimmed.startsWith("#") && (language == "python" || language == "bash" || language == "yaml" || language == "")) {
        withStyle(SpanStyle(color = syntaxColors.comment)) {
            append(trimmed)
        }
        return
    }

    var i = 0
    while (i < trimmed.length) {
        val remaining = trimmed.substring(i)

        when {
            remaining.startsWith("\"") || remaining.startsWith("'") -> {
                val (str, end) = extractString(trimmed, i)
                withStyle(SpanStyle(color = syntaxColors.string)) {
                    append(str)
                }
                i = end
            }
            remaining.startsWith("`") -> {
                val end = trimmed.indexOf("`", i + 1).let { if (it == -1) trimmed.length else it + 1 }
                withStyle(SpanStyle(color = syntaxColors.string)) {
                    append(trimmed.substring(i, end))
                }
                i = end
            }
            remaining.startsWith("@") && language in listOf("java", "kotlin", "python") -> {
                val end = trimmed.indexOf(" ").let { if (it == -1 || it == i) trimmed.length else i + (trimmed.substring(i).indexOf(" ").takeIf { it > 0 } ?: trimmed.length - i) }
                val annotation = trimmed.substring(i, minOf(end, trimmed.length))
                withStyle(SpanStyle(color = syntaxColors.annotation)) {
                    append(annotation)
                }
                i = minOf(end, trimmed.length)
            }
            remaining.startsWith("<") && (language == "html" || language == "xml") -> {
                val tagEnd = trimmed.indexOf(">", i).let { if (it == -1) trimmed.length else it + 1 }
                val tagText = trimmed.substring(i, tagEnd)

                if (tagText.contains(" ") || tagText.contains("=")) {
                    val tagNameMatch = Regex("</?([a-zA-Z][a-zA-Z0-9]*)").find(tagText)
                    val tagName = tagNameMatch?.groupValues?.get(1) ?: tagText

                    val start = if (tagText.startsWith("</")) 2 else 1
                    val nameEnd = start + tagName.length

                    withStyle(SpanStyle(color = syntaxColors.tag)) {
                        append(tagText.substring(0, nameEnd))
                    }
                    if (nameEnd < tagText.length) {
                        val attrs = tagText.substring(nameEnd)
                        val attrPattern = Regex("([a-zA-Z-]+)=[\"']")
                        var lastEnd = 0
                        attrPattern.findAll(attrs).forEach { match ->
                            append(attrs.substring(lastEnd, match.range.first))
                            withStyle(SpanStyle(color = syntaxColors.attribute)) {
                                append(match.value)
                            }
                            lastEnd = match.range.last + 1
                        }
                        if (lastEnd < attrs.length) {
                            withStyle(SpanStyle(color = syntaxColors.property)) {
                                append(attrs.substring(lastEnd))
                            }
                        }
                    }
                    if (tagText.endsWith("/>")) {
                        append("/")
                    }
                } else {
                    withStyle(SpanStyle(color = syntaxColors.tag)) {
                        append(tagText)
                    }
                }
                i = tagEnd
            }
            remaining.matches(Regex("^\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFdDlL]?")) -> {
                val numEnd = remaining.indexOfAny(charArrayOf(' ', '\t', ';', ',', ')', ']')).let { if (it == -1) remaining.length else it }
                withStyle(SpanStyle(color = syntaxColors.number)) {
                    append(trimmed.substring(i, i + numEnd))
                }
                i += numEnd
            }
            remaining.startsWith("//") || remaining.startsWith("#") -> {
                withStyle(SpanStyle(color = syntaxColors.comment)) {
                    append(remaining)
                }
                return
            }
            else -> {
                val wordEnd = remaining.indexOfAny(charArrayOf(' ', '\t', '(', ')', '[', ']', '{', '}', ';', ',', '=', '+', '-', '*', '/', '<', '>', '&', '|', '!', ':', '"', '\'', '`')).let { if (it == -1) remaining.length else it }
                val word = if (wordEnd > 0) remaining.substring(0, wordEnd) else remaining[0].toString()
                val consumed = if (wordEnd > 0) wordEnd else 1

                val nextChar = trimmed.getOrNull(i + consumed)
                val isFunctionCall = nextChar == '('

                val tokenType = when {
                    word in keywords -> TokenType.KEYWORD
                    word in types -> TokenType.TYPE
                    isFunctionCall && word.isNotEmpty() -> TokenType.FUNCTION
                    word.matches(Regex("^[A-Z][a-zA-Z0-9_]*$")) && language in listOf("java", "kotlin", "swift", "c", "cpp") -> TokenType.TYPE
                    else -> TokenType.PLAIN
                }

                val color = when (tokenType) {
                    TokenType.KEYWORD -> syntaxColors.keyword
                    TokenType.TYPE -> syntaxColors.type
                    TokenType.FUNCTION -> syntaxColors.function
                    else -> syntaxColors.default
                }

                withStyle(SpanStyle(color = color)) {
                    append(word)
                }
                i += consumed
            }
        }
    }
}

private fun extractString(text: String, start: Int): Pair<String, Int> {
    val quote = text[start]
    var i = start + 1
    while (i < text.length) {
        if (text[i] == quote && (i == start + 1 || text[i - 1] != '\\')) {
            return Pair(text.substring(start, i + 1), i + 1)
        }
        i++
    }
    return Pair(text.substring(start), text.length)
}

private fun parseMarkdown(text: String): ParsedMarkdown {
    val elements = mutableListOf<MarkdownElement>()
    val lines = text.split("\n")

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        when {
            // LaTeX block $$...$$
            line.trimStart().startsWith("$$") -> {
                val latexContent = StringBuilder()
                val lineWithoutDollar = line.trimStart().removeSurrounding("$$").trim()
                if (lineWithoutDollar.isNotEmpty()) {
                    latexContent.append(lineWithoutDollar)
                }
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("$$")) {
                    if (latexContent.isNotEmpty()) latexContent.append("\n")
                    latexContent.append(lines[i])
                    i++
                }
                elements.add(MarkdownElement.LatexBlock(latexContent.toString().trim(), isBlock = true))
                i++
            }
            line.startsWith("```") -> {
                val language = line.substring(3).trim().takeIf { it.isNotEmpty() }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                elements.add(MarkdownElement.CodeBlock(codeLines.joinToString("\n"), language))
                i++
            }
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                val headerText = line.substring(level).trim()
                elements.add(MarkdownElement.Header(headerText, level))
                i++
            }
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

private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var pos = 0

        while (pos < text.length) {
            when {
                // Inline LaTeX $...$
                text.startsWith("$", pos) && !text.startsWith("$$", pos) -> {
                    val end = text.indexOf("$", pos + 1)
                    if (end != -1 && end > pos + 1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontStyle = FontStyle.Italic,
                            color = Color(0xFFFFC66D)
                        )) {
                            append(text.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else {
                        append("$")
                        pos += 1
                    }
                }
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
                text.startsWith("`", pos) && !text.startsWith("``", pos) -> {
                    val end = text.indexOf("`", pos + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.LightGray.copy(alpha = 0.3f)
                        )) {
                            append(text.substring(pos + 1, end))
                        }
                        pos = end + 1
                    } else {
                        append("`")
                        pos += 1
                    }
                }
                text.startsWith("[", pos) -> {
                    val closeBracket = text.indexOf("]", pos)
                    val openParen = text.indexOf("(", closeBracket)
                    val closeParen = text.indexOf(")", openParen)
                    if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                        val linkText = text.substring(pos + 1, closeBracket)
                        val url = text.substring(openParen + 1, closeParen)
                        withStyle(SpanStyle(
                            color = Color.Blue,
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
    data class LatexBlock(val expression: String, val isBlock: Boolean) : MarkdownElement()
    data class Header(val text: String, val level: Int) : MarkdownElement()
    data class BulletList(val items: List<String>) : MarkdownElement()
    data class NumberedList(val items: List<String>) : MarkdownElement()
}