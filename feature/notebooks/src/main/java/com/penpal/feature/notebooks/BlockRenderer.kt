package com.penpal.feature.notebooks

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders LaTeX expressions using MathJax via WebView.
 * Isolated in its own composable to prevent WebView conflicts.
 */
@Composable
fun LatexView(
    expression: String,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val htmlContent = remember(expression) {
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
                    padding: 12px;
                    background-color: transparent;
                    color: #FFFFFF;
                    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                    box-sizing: border-box;
                }
                .math-container {
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    width: 100%;
                }
            </style>
        </head>
        <body>
            <div class="math-container">
                <p>$$expression$$</p>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    domStorageEnabled = false
                }
                loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceColor)
            .heightIn(min = 60.dp),
        update = { webView ->
            if (webView.contentDescription != expression) {
                webView.contentDescription = expression
                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
            }
        }
    )
}

/**
 * Simple text block for markdown content.
 * Uses a minimal approach without heavy markdown parsing.
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier
) {
    // For now, just render as plain text
    // Can be extended with proper markdown parsing later
    Text(
        text = content.ifEmpty { "Tap to edit..." },
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (content.isEmpty()) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (content.isEmpty()) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    )
}

/**
 * Placeholder for image block
 */
@Composable
fun ImageBlockView(
    caption: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = if (caption.isNotEmpty()) caption else "Tap to add image",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Placeholder for drawing block
 */
@Composable
fun DrawingBlockView(
    pathData: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = "Drawing canvas",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Embed preview block
 */
@Composable
fun EmbedBlockView(
    preview: String,
    type: EmbedType,
    modifier: Modifier = Modifier
) {
    val typeLabel = when (type) {
        EmbedType.LINK -> "Link"
        EmbedType.AUDIO -> "Audio"
        EmbedType.VIDEO -> "Video"
        EmbedType.FILE -> "File"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text = "[$typeLabel]",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = preview.ifEmpty { "Tap to add embed" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}