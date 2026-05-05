package com.penpal.feature.notebooks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A graph canvas for visualizing and editing node-based relationships.
 * Supports:
 * - Drag nodes to reposition
 * - Pan with two fingers
 * - Pinch to zoom
 * - Double-tap to add new node
 * - Long-press for context menu
 * - Edge creation between nodes
 */
@Composable
fun GraphNodeCanvas(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    selectedNodeId: String?,
    isAddingEdge: Boolean,
    edgeStartNodeId: String?,
    onNodePositionChanged: (String, Float, Float) -> Unit,
    onNodeDragEnded: (GraphNode) -> Unit,
    onNodeSelected: (String?) -> Unit,
    onNodeDoubleTap: (Float, Float) -> Unit,
    onNodeLongPress: (String, Offset) -> Unit,
    onEdgeStart: (String) -> Unit,
    onEdgeComplete: (String) -> Unit,
    onCanvasTap: (Offset) -> Unit,
    onCanvasPan: (Offset) -> Unit,
    onCanvasScale: (Float) -> Unit,
    modifier: Modifier = Modifier,
    canvasOffset: Offset = Offset.Zero,
    canvasScale: Float = 1f
) {
    var viewportOffset by remember { mutableStateOf(canvasOffset) }
    var viewportScale by remember { mutableStateOf(canvasScale) }
    var draggedNodeId by remember { mutableStateOf<String?>(null) }
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }

    // Sync external offset/scale changes
    LaunchedEffect(canvasOffset, canvasScale) {
        viewportOffset = canvasOffset
        viewportScale = canvasScale
    }

    val nodeRadius = 40f
    val nodeColors = mapOf(
        NodeType.DEFAULT to Color(0xFF6366F1),
        NodeType.CONCEPT to Color(0xFF10B981),
        NodeType.TOOL to Color(0xFFF59E0B),
        NodeType.DATA to Color(0xFF3B82F6),
        NodeType.STARRED to Color(0xFFEF4444)
    )

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    viewportScale = (viewportScale * zoom).coerceIn(0.25f, 4f)
                    viewportOffset = Offset(
                        viewportOffset.x + pan.x,
                        viewportOffset.y + pan.y
                    )
                    onCanvasPan(viewportOffset)
                    onCanvasScale(viewportScale)
                }
            }
            .pointerInput(isAddingEdge, edgeStartNodeId) {
                if (isAddingEdge && edgeStartNodeId != null) {
                    detectTapGestures { offset ->
                        // Find if we tapped a node
                        val canvasPos = screenToCanvas(offset, viewportOffset, viewportScale, size.width.toFloat(), size.height.toFloat())
                        val tappedNode = nodes.find { node ->
                            val dx = canvasPos.x - node.posX
                            val dy = canvasPos.y - node.posY
                            (dx * dx + dy * dy) <= (nodeRadius * nodeRadius)
                        }
                        if (tappedNode != null && tappedNode.id != edgeStartNodeId) {
                            onEdgeComplete(tappedNode.id)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val canvasPos = screenToCanvas(offset, viewportOffset, viewportScale, size.width.toFloat(), size.height.toFloat())
                        onNodeDoubleTap(canvasPos.x, canvasPos.y)
                    },
                    onLongPress = { offset ->
                        val canvasPos = screenToCanvas(offset, viewportOffset, viewportScale, size.width.toFloat(), size.height.toFloat())
                        val tappedNode = nodes.find { node ->
                            val dx = canvasPos.x - node.posX
                            val dy = canvasPos.y - node.posY
                            (dx * dx + dy * dy) <= (nodeRadius * nodeRadius)
                        }
                        if (tappedNode != null) {
                            onNodeLongPress(tappedNode.id, offset)
                        }
                    },
                    onTap = { offset ->
                        val canvasPos = screenToCanvas(offset, viewportOffset, viewportScale, size.width.toFloat(), size.height.toFloat())
                        val tappedNode = nodes.find { node ->
                            val dx = canvasPos.x - node.posX
                            val dy = canvasPos.y - node.posY
                            (dx * dx + dy * dy) <= (nodeRadius * nodeRadius)
                        }
                        if (tappedNode != null) {
                            onNodeSelected(tappedNode.id)
                        } else {
                            onNodeSelected(null)
                            onCanvasTap(offset)
                        }
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nodes, selectedNodeId) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val canvasPos = screenToCanvas(offset, viewportOffset, viewportScale, size.width.toFloat(), size.height.toFloat())
                            val hitNode = nodes.find { node ->
                                val dx = canvasPos.x - node.posX
                                val dy = canvasPos.y - node.posY
                                (dx * dx + dy * dy) <= (nodeRadius * nodeRadius)
                            }
                            draggedNodeId = hitNode?.id
                            dragStartPos = canvasPos
                        },
                        onDrag = { change, _ ->
                            draggedNodeId?.let { nodeId ->
                                val canvasPos = screenToCanvas(change.position, viewportOffset, viewportScale, size.width.toFloat(), size.height.toFloat())
                                onNodePositionChanged(nodeId, canvasPos.x, canvasPos.y)
                            }
                        },
                        onDragEnd = {
                            draggedNodeId?.let { nodeId ->
                                nodes.find { it.id == nodeId }?.let { node ->
                                    onNodeDragEnded(node)
                                }
                            }
                            draggedNodeId = null
                        }
                    )
                }
        ) {
            // Apply canvas transform
            val centerX = size.width / 2
            val centerY = size.height / 2
            val offsetX = viewportOffset.x + centerX * (1 - viewportScale)
            val offsetY = viewportOffset.y + centerY * (1 - viewportScale)

            // Draw grid (subtle) - grid is drawn in canvas space without scale
            drawGrid(offsetX, offsetY, viewportScale)

            // Draw edges and nodes with scale transform
            // We use canvas-native drawing with manual scale applied to coordinates

            // Draw edges first (behind nodes)
            edges.forEach { edge ->
                val fromNode = nodes.find { it.id == edge.fromNodeId }
                val toNode = nodes.find { it.id == edge.toNodeId }
                if (fromNode != null && toNode != null) {
                    drawEdge(fromNode, toNode, edge, selectedNodeId, viewportScale, offsetX, offsetY)
                }
            }

            // Draw edge being created
            if (isAddingEdge && edgeStartNodeId != null) {
                val startNode = nodes.find { it.id == edgeStartNodeId }
                if (startNode != null) {
                    drawPendingEdge(startNode, offsetX, offsetY, viewportScale, size.width, size.height)
                }
            }

            // Draw nodes
            nodes.forEach { node ->
                drawNode(
                    node = node,
                    isSelected = node.id == selectedNodeId,
                    isDragging = node.id == draggedNodeId,
                    isAddingEdge = isAddingEdge,
                    edgeStartId = edgeStartNodeId,
                    scale = viewportScale,
                    offsetX = offsetX,
                    offsetY = offsetY
                )
            }
        }
    }
}

/**
 * Draws a subtle grid pattern
 */
private fun DrawScope.drawGrid(offsetX: Float, offsetY: Float, scale: Float) {
    val gridColor = Color(0xFF2A2A4E)
    val gridSpacing = 100f * scale

    val startX = (-5000f * scale + offsetX).toInt()
    val endX = (5000f * scale + offsetX).toInt()
    val startY = (-5000f * scale + offsetY).toInt()
    val endY = (5000f * scale + offsetY).toInt()

    for (x in startX..endX step gridSpacing.toInt()) {
        drawLine(
            color = gridColor.copy(alpha = 0.3f),
            start = Offset(x.toFloat(), startY.toFloat()),
            end = Offset(x.toFloat(), endY.toFloat()),
            strokeWidth = 1f
        )
    }
    for (y in startY..endY step gridSpacing.toInt()) {
        drawLine(
            color = gridColor.copy(alpha = 0.3f),
            start = Offset(startX.toFloat(), y.toFloat()),
            end = Offset(endX.toFloat(), y.toFloat()),
            strokeWidth = 1f
        )
    }
}

/**
 * Draws an edge between two nodes
 */
private fun DrawScope.drawEdge(
    from: GraphNode,
    to: GraphNode,
    edge: GraphEdge,
    selectedNodeId: String?,
    scale: Float,
    offsetX: Float,
    offsetY: Float
) {
    val isHighlighted = selectedNodeId == from.id || selectedNodeId == to.id

    val edgeColor = when {
        edge.type == EdgeType.HIGHLIGHTED -> Color(0xFF6366F1)
        isHighlighted -> Color(0xFF6366F1).copy(alpha = 0.8f)
        edge.type == EdgeType.LABELLED -> Color(0xFF10B981)
        else -> Color(0xFF4B5563)
    }

    val strokeWidth = if (isHighlighted) 3f / scale else 2f / scale

    // Draw curved path
    val path = Path().apply {
        val startX = from.posX
        val startY = from.posY
        val endX = to.posX
        val endY = to.posY

        val midX = (startX + endX) / 2
        val midY = (startY + endY) / 2

        // Add slight curve based on distance
        val dx = endX - startX
        val dy = endY - startY
        val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val curvature = (dist * 0.1f).coerceAtMost(50f)

        // Perpendicular offset
        val perpX = -dy / dist * curvature
        val perpY = dx / dist * curvature

        moveTo(startX, startY)
        quadraticBezierTo(
            midX + perpX * 0.3f,
            midY + perpY * 0.3f,
            endX,
            endY
        )
    }

    drawPath(
        path = path,
        color = edgeColor,
        style = Stroke(width = strokeWidth)
    )

    // Draw arrow head
    drawArrowHead(
        from = Offset(from.posX, from.posY),
        to = Offset(to.posX, to.posY),
        color = edgeColor,
        strokeWidth = strokeWidth
    )

    // Draw label if present
    if (edge.label.isNotEmpty()) {
        val midX = (from.posX + to.posX) / 2
        val midY = (from.posY + to.posY) / 2
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 12f / scale
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawText(edge.label, midX, midY - 10f / scale, paint)
        }
    }
}

/**
 * Draws arrow head at end of edge
 */
private fun DrawScope.drawArrowHead(
    from: Offset,
    to: Offset,
    color: Color,
    strokeWidth: Float
) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val arrowLength = 15f
    val arrowAngle = Math.PI / 6

    val x1 = to.x - arrowLength * cos(angle - arrowAngle).toFloat()
    val y1 = to.y - arrowLength * sin(angle - arrowAngle).toFloat()
    val x2 = to.x - arrowLength * cos(angle + arrowAngle).toFloat()
    val y2 = to.y - arrowLength * sin(angle + arrowAngle).toFloat()

    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(x1, y1)
        moveTo(to.x, to.y)
        lineTo(x2, y2)
    }

    drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
}

/**
 * Draws a pending edge while user is creating one
 */
private fun DrawScope.drawPendingEdge(
    startNode: GraphNode,
    offsetX: Float,
    offsetY: Float,
    scale: Float,
    canvasWidth: Float,
    canvasHeight: Float
) {
    // Draw from the start node towards center for visual feedback
    drawLine(
        color = Color(0xFF6366F1).copy(alpha = 0.5f),
        start = Offset(startNode.posX, startNode.posY),
        end = Offset(startNode.posX + 100f, startNode.posY + 100f),
        strokeWidth = 2f
    )
}

/**
 * Draws a single node
 */
private fun DrawScope.drawNode(
    node: GraphNode,
    isSelected: Boolean,
    isDragging: Boolean,
    isAddingEdge: Boolean,
    edgeStartId: String?,
    scale: Float,
    offsetX: Float,
    offsetY: Float
) {
    val radius = 40f

    // Apply scale and offset to position
    val scaledX = node.posX
    val scaledY = node.posY

    val nodeColor = when {
        isSelected -> Color(0xFF818CF8)
        isDragging -> Color(0xFFA5B4FC)
        else -> when (node.type) {
            NodeType.DEFAULT -> Color(0xFF6366F1)
            NodeType.CONCEPT -> Color(0xFF10B981)
            NodeType.TOOL -> Color(0xFFF59E0B)
            NodeType.DATA -> Color(0xFF3B82F6)
            NodeType.STARRED -> Color(0xFFEF4444)
        }
    }

    val shadowColor = if (isSelected || isDragging) {
        nodeColor.copy(alpha = 0.5f)
    } else {
        Color.Black.copy(alpha = 0.3f)
    }

    // Draw shadow
    if (isSelected || isDragging) {
        drawCircle(
            color = shadowColor,
            radius = radius + 8f / scale,
            center = Offset(scaledX + 4f / scale, scaledY + 4f / scale)
        )
    }

    // Draw node circle
    drawCircle(
        color = nodeColor,
        radius = radius,
        center = Offset(scaledX, scaledY)
    )

    // Draw selection ring
    if (isSelected) {
        drawCircle(
            color = Color.White,
            radius = radius + 4f / scale,
            center = Offset(scaledX, scaledY),
            style = Stroke(width = 2f / scale)
        )
    }

    // Draw edge creation indicator
    if (isAddingEdge && edgeStartId != null && edgeStartId != node.id) {
        drawCircle(
            color = Color(0xFF10B981).copy(alpha = 0.3f),
            radius = radius + 10f / scale,
            center = Offset(scaledX, scaledY),
            style = Stroke(width = 3f / scale)
        )
    }

    // Draw star indicator for starred nodes
    if (node.type == NodeType.STARRED) {
        drawCircle(
            color = Color.White,
            radius = 8f / scale,
            center = Offset(scaledX, scaledY - radius - 15f / scale)
        )
    }

    // Draw label
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 14f / scale
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val textOffset = textHeight / 2 - fontMetrics.descent

        val label = if (node.label.length > 12) {
            node.label.take(10) + "…"
        } else {
            node.label
        }

        drawText(
            label,
            scaledX,
            scaledY + radius + 20f / scale + textOffset,
            paint
        )
    }
}

/**
 * Converts screen coordinates to canvas coordinates
 */
private fun screenToCanvas(
    screenPos: Offset,
    offset: Offset,
    scale: Float,
    canvasWidth: Float,
    canvasHeight: Float
): Offset {
    val centerX = canvasWidth / 2
    val centerY = canvasHeight / 2

    return Offset(
        (screenPos.x - centerX - offset.x) / scale,
        (screenPos.y - centerY - offset.y) / scale
    )
}
