package com.penpal.feature.notebooks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A touch-based drawing canvas for sketching and note-taking.
 * Supports:
 * - Freehand drawing with variable stroke widths
 * - Color selection
 * - Eraser mode
 * - Clear canvas
 * - Undo/redo
 */
@Composable
fun DrawingCanvas(
    pathData: String,
    onPathDataChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color.Black,
    strokeWidth: Float = 4f,
    backgroundColor: Color = Color.White
) {
    // Drawing state
    var paths by remember { mutableStateOf<List<DrawingPath>>(emptyList()) }
    var currentPath by remember { mutableStateOf<DrawingPath?>(null) }
    var selectedColor by remember { mutableStateOf(strokeColor) }
    var selectedWidth by remember { mutableFloatStateOf(strokeWidth) }
    var isErasing by remember { mutableStateOf(false) }
    var showToolbar by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(0f to 0f) }

    // Parse existing path data
    LaunchedEffect(pathData) {
        if (pathData.isNotEmpty()) {
            paths = parsePathData(pathData)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        // Drawing canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPath = DrawingPath(
                                color = if (isErasing) Color.Transparent else selectedColor,
                                strokeWidth = if (isErasing) selectedWidth * 3 else selectedWidth,
                                isEraser = isErasing,
                                points = mutableListOf(PathPoint(offset.x, offset.y))
                            )
                        },
                        onDrag = { change, _ ->
                            currentPath?.let { path ->
                                val newPath = path.withAddedPoint(PathPoint(change.position.x, change.position.y))
                                currentPath = newPath
                            }
                        },
                        onDragEnd = {
                            currentPath?.let { completed ->
                                paths = paths + completed
                            }
                            currentPath = null
                        }
                    )
                }
        ) {
            canvasSize = size.width to size.height

            // Draw all completed paths
            paths.forEach { drawingPath ->
                drawPath(drawingPath)
            }

            // Draw current path being drawn
            currentPath?.let { drawingPath ->
                drawPath(drawingPath)
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Toolbar (appears on tap)
        // ──────────────────────────────────────────────────────────────

        if (showToolbar) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Undo
                    IconButton(
                        onClick = {
                            if (paths.isNotEmpty()) {
                                paths = paths.dropLast(1)
                                onPathDataChanged(serializePaths(paths))
                            }
                        },
                        enabled = paths.isNotEmpty(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Undo,
                            contentDescription = "Undo",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Redo (placeholder - could track undone paths)
                    IconButton(
                        onClick = { /* TODO: redo */ },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Redo,
                            contentDescription = "Redo",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .padding(horizontal = 4.dp)
                    )

                    // Eraser
                    IconButton(
                        onClick = { isErasing = !isErasing },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (isErasing) Icons.Default.Delete else Icons.Default.Circle,
                            contentDescription = "Eraser",
                            tint = if (isErasing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Clear
                    IconButton(
                        onClick = {
                            paths = emptyList()
                            onPathDataChanged("")
                        },
                        enabled = paths.isNotEmpty(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Color palette
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        Color.Black to "Black",
                        Color.Gray to "Gray",
                        Color.Red to "Red",
                        Color(0xFFFF6B00) to "Orange",
                        Color.Blue to "Blue",
                        Color(0xFF10B981) to "Green",
                        Color(0xFF8B5CF6) to "Purple",
                        Color(0xFFEC4899) to "Pink"
                    ).forEach { (color, name) ->
                        IconButton(
                            onClick = {
                                selectedColor = color
                                isErasing = false
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (selectedColor == color && !isErasing) 28.dp else 24.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(color)
                                    .then(
                                        if (selectedColor == color && !isErasing) {
                                            Modifier.background(
                                                color = Color.White,
                                                shape = RoundedCornerShape(50)
                                            )
                                        } else Modifier
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Tap to show toolbar (simple approach - tap anywhere to toggle)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        val event = awaitPointerEvent()
                        // Just detect tap to show toolbar
                        showToolbar = true
                    }
                }
        )

        // Hide toolbar after delay
        LaunchedEffect(showToolbar) {
            if (showToolbar) {
                kotlinx.coroutines.delay(5000)
                showToolbar = false
            }
        }

        // Save on path change
        LaunchedEffect(paths) {
            onPathDataChanged(serializePaths(paths))
        }
    }
}

/**
 * Represents a drawing path with color, width, and points
 */
private data class DrawingPath(
    val color: Color,
    val strokeWidth: Float,
    val isEraser: Boolean = false,
    val points: MutableList<PathPoint> = mutableListOf()
)

/**
 * A point in the path
 */
private data class PathPoint(
    val x: Float,
    val y: Float
)

/**
 * Creates a copy of DrawingPath with updated points
 */
private fun DrawingPath.withAddedPoint(point: PathPoint): DrawingPath {
    val newPoints = mutableListOf<PathPoint>()
    newPoints.addAll(this.points)
    newPoints.add(point)
    return this.copy(points = newPoints)
}

/**
 * Draws a DrawingPath on the canvas
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPath(path: DrawingPath) {
    if (path.points.size < 2) return

    val stroke = Stroke(
        width = path.strokeWidth,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round
    )

    for (i in 0 until path.points.size - 1) {
        val p1 = path.points[i]
        val p2 = path.points[i + 1]

        val lineColor = if (path.isEraser) Color.White else path.color
        drawLine(
            color = lineColor,
            start = Offset(p1.x, p1.y),
            end = Offset(p2.x, p2.y),
            strokeWidth = path.strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Serialize paths to a simple string format
 */
private fun serializePaths(paths: List<DrawingPath>): String {
    return paths.joinToString(";") { path ->
        val pointsStr = path.points.joinToString(",") { "${it.x.toInt()},${it.y.toInt()}" }
        val colorHex = path.color.toArgb().toString(16)
        val eraseFlag = if (path.isEraser) "1" else "0"
        "$eraseFlag:$colorHex:${path.strokeWidth.toInt()}:$pointsStr"
    }
}

/**
 * Deserialize paths from string format
 */
private fun parsePathData(data: String): List<DrawingPath> {
    if (data.isEmpty()) return emptyList()

    return try {
        data.split(";").mapNotNull { pathStr ->
            val parts = pathStr.split(":")
            if (parts.size < 4) return@mapNotNull null

            val isEraser = parts[0] == "1"
            val color = Color(parts[1].toLong(16).toULong())
            val strokeWidth = parts[2].toFloat()
            val points = parts[3].split(",").chunked(2) { chunk ->
                PathPoint(chunk[0].toFloat(), chunk[1].toFloat())
            }

            if (points.size >= 2) {
                DrawingPath(
                    color = color,
                    strokeWidth = strokeWidth,
                    isEraser = isEraser,
                    points = points.toMutableList()
                )
            } else null
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Color extension to convert to argb
 */
private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
