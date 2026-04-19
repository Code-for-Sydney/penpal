package com.drawapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Background color
    var canvasBackgroundColor: Int = Color.parseColor("#FDFCF5")

    // --- Lined Paper Paints ---
    private val linePaint = Paint().apply {
        color = Color.parseColor("#D3D3D3") // Light Grey
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val marginPaint = Paint().apply {
        color = Color.parseColor("#FFCDD2") // Soft Red
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    init {
        setBackgroundColor(canvasBackgroundColor)
    }

    // --- Data classes ---
    data class Stroke(
        val path: Path,
        val paint: Paint,
        val bounds: RectF,
        val commands: List<PathCommand> = emptyList(),
        val isEraserStroke: Boolean = false
    )

    // --- State ---
    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()

    private var currentPath = Path()
    private var currentCommands = mutableListOf<PathCommand>()
    private var lastX = 0f
    private var lastY = 0f

    // --- Current brush settings ---
    var brushColor: Int = Color.WHITE
        set(value) { field = value; updateCurrentPaint() }

    var brushSize: Float = 12f
        set(value) { field = value; updateCurrentPaint() }

    var isEraser: Boolean = false
        set(value) { field = value; updateCurrentPaint() }

    var brushOpacity: Int = 255
        set(value) { field = value; updateCurrentPaint() }

    /** Called once each time the user lifts their finger after a stroke. */
    var onStrokeCompleted: (() -> Unit)? = null

    // --- Debug ---
    private var debugBoundingBox: RectF? = null
    private val debugPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
    }


    private var currentPaint = buildPaint()

    // Original background image if loaded from file
    private var initialBitmap: Bitmap? = null

    // Touch tolerance for smoothness
    private val TOUCH_TOLERANCE = 4f

    // ══════════════════════════════════════════════════════════════════════
    // Infinite canvas: Matrix-based view transformation
    // ══════════════════════════════════════════════════════════════════════

    /** Maps canvas-space → screen-space */
    private val viewMatrix = Matrix()
    /** Maps screen-space → canvas-space (inverse of viewMatrix) */
    private val inverseMatrix = Matrix()

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    // Zoom limits
    private val MIN_ZOOM = 0.25f
    private val MAX_ZOOM = 4.0f

    // Multi-touch state
    private var isDrawing = false
    private var isPanning = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // Pan tracking
    private var lastPanX = 0f
    private var lastPanY = 0f

    // Scale gesture detector
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(MIN_ZOOM, MAX_ZOOM)

                // Scale around the focal point (pinch center)
                val focusX = detector.focusX
                val focusY = detector.focusY
                val scaleChange = scaleFactor / oldScale

                // Adjust translation so the focal point stays fixed
                translateX = focusX - scaleChange * (focusX - translateX)
                translateY = focusY - scaleChange * (focusY - translateY)

                updateMatrix()
                invalidate()
                return true
            }
        }
    )

    private fun updateMatrix() {
        viewMatrix.reset()
        viewMatrix.postScale(scaleFactor, scaleFactor)
        viewMatrix.postTranslate(translateX, translateY)
        viewMatrix.invert(inverseMatrix)
    }

    /** Convert screen coordinates to canvas-space coordinates */
    private fun screenToCanvas(screenX: Float, screenY: Float): FloatArray {
        val pts = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    // --- Paint factory ---
    private fun buildPaint(): Paint = Paint().apply {
        color = brushColor
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = brushSize
        isAntiAlias = true
        alpha = brushOpacity
        if (isEraser) {
            // Use background-colored strokes instead of CLEAR xfermode
            // so we don't need a backing bitmap
            color = canvasBackgroundColor
            strokeWidth = brushSize * 2.5f
            xfermode = null
        } else {
            xfermode = null
        }
    }

    private fun updateCurrentPaint() {
        currentPaint = buildPaint()
    }

    // --- Lifecycle ---
    /** Allows initializing the canvas with a previously saved image. */
    fun initializeWithBitmap(bitmap: Bitmap) {
        initialBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (width > 0 && height > 0) {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            updateMatrix()
            invalidate()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Drawing
    // ══════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Fill background
        canvas.drawColor(canvasBackgroundColor)

        // 2. Draw paper lines relative to viewport (infinite appearance)
        drawPaperLines(canvas)

        // 3. Apply view transform for all canvas-space content
        canvas.save()
        canvas.concat(viewMatrix)

        // 4. Draw initial bitmap if present
        initialBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // 5. Draw all committed strokes
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, stroke.paint)
        }

        // 6. Draw in-progress stroke live
        if (isDrawing) {
            canvas.drawPath(currentPath, currentPaint)
        }

        // 7. Draw debug bounding box if present
        debugBoundingBox?.let {
            canvas.drawRect(it, debugPaint)
        }

        canvas.restore()
    }

    private fun drawPaperLines(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val lineSpacing = 100f

        // Compute the visible region in canvas-space
        val topLeft = screenToCanvas(0f, 0f)
        val bottomRight = screenToCanvas(w, h)

        val canvasLeft = topLeft[0]
        val canvasTop = topLeft[1]
        val canvasRight = bottomRight[0]
        val canvasBottom = bottomRight[1]

        // Adjust line paint stroke width for zoom so lines stay visually consistent
        val adjustedLinePaint = Paint(linePaint).apply {
            strokeWidth = 2f / scaleFactor
        }
        val adjustedMarginPaint = Paint(marginPaint).apply {
            strokeWidth = 3f / scaleFactor
        }

        // Apply the view matrix for line drawing in canvas-space
        canvas.save()
        canvas.concat(viewMatrix)

        // Horizontal lines: find the first line >= canvasTop
        val firstLineY = (Math.ceil((canvasTop / lineSpacing).toDouble()) * lineSpacing).toFloat()
        var y = firstLineY
        while (y < canvasBottom) {
            canvas.drawLine(canvasLeft, y, canvasRight, y, adjustedLinePaint)
            y += lineSpacing
        }

        // Vertical margin line at x=120 (in canvas-space)
        val marginX = 120f
        if (marginX in canvasLeft..canvasRight) {
            canvas.drawLine(marginX, canvasTop, marginX, canvasBottom, adjustedMarginPaint)
        }

        canvas.restore()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Touch handling — multi-touch with draw/pan/zoom
    // ══════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always let scale detector see the event
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // First finger down — start drawing
                activePointerId = event.getPointerId(0)
                val canvasCoords = screenToCanvas(event.x, event.y)
                touchStart(canvasCoords[0], canvasCoords[1])
                isDrawing = true
                isPanning = false
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down — switch to pan/zoom mode
                if (isDrawing) {
                    // Cancel the in-progress stroke
                    cancelCurrentStroke()
                    isDrawing = false
                }
                isPanning = true
                // Track pan from primary pointer
                lastPanX = event.x
                lastPanY = event.y
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (isPanning && pointerCount >= 2) {
                    // Two-finger pan
                    if (!scaleDetector.isInProgress) {
                        val dx = event.x - lastPanX
                        val dy = event.y - lastPanY
                        translateX += dx
                        translateY += dy
                        updateMatrix()
                        invalidate()
                    }
                    lastPanX = event.x
                    lastPanY = event.y
                } else if (isDrawing && pointerCount == 1) {
                    // Single finger drawing
                    val canvasCoords = screenToCanvas(event.x, event.y)
                    touchMove(canvasCoords[0], canvasCoords[1])
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDrawing) {
                    touchUp()
                    isDrawing = false
                    invalidate()
                }
                isPanning = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A finger lifted, but at least one remains
                if (pointerCount <= 2) {
                    // Going from 2 fingers to 1 — don't start drawing again
                    // Stay in pan mode until all fingers lift
                    isPanning = true
                }
            }
        }
        return true
    }

    private fun touchStart(x: Float, y: Float) {
        currentPath = Path()
        currentCommands = mutableListOf()
        currentPath.moveTo(x, y)
        currentCommands.add(PathCommand.MoveTo(x, y))
        lastX = x
        lastY = y
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = abs(x - lastX)
        val dy = abs(y - lastY)
        if (dx >= TOUCH_TOLERANCE / scaleFactor || dy >= TOUCH_TOLERANCE / scaleFactor) {
            val midX = (x + lastX) / 2
            val midY = (y + lastY) / 2
            // Quadratic bezier for smoothness
            currentPath.quadTo(lastX, lastY, midX, midY)
            currentCommands.add(PathCommand.QuadTo(lastX, lastY, midX, midY))
            lastX = x
            lastY = y
        }
    }

    private fun touchUp() {
        currentPath.lineTo(lastX, lastY)
        currentCommands.add(PathCommand.LineTo(lastX, lastY))

        // Snapshot paint
        val strokePaint = buildPaint()

        // Compute bounds
        val bounds = RectF()
        currentPath.computeBounds(bounds, true)
        // Adjust for stroke width
        val halfWidth = strokePaint.strokeWidth / 2f
        bounds.inset(-halfWidth, -halfWidth)

        // Save stroke for undo (with serializable commands)
        strokes.add(Stroke(currentPath, strokePaint, bounds, currentCommands.toList(), isEraser))
        redoStack.clear()

        // Reset current path
        currentPath = Path()
        currentCommands = mutableListOf()

        // Notify listener so recognition can be debounced
        onStrokeCompleted?.invoke()
    }

    /** Cancel the current in-progress stroke (e.g. when a second finger touches down) */
    private fun cancelCurrentStroke() {
        currentPath = Path()
        currentCommands = mutableListOf()
    }

    // --- Undo / Redo ---
    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeLast())
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val stroke = redoStack.removeLast()
            strokes.add(stroke)
            invalidate()
        }
    }

    fun canUndo() = strokes.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    // --- Clear ---
    fun clear() {
        strokes.clear()
        redoStack.clear()
        currentPath = Path()
        initialBitmap = null
        // Reset view to origin
        scaleFactor = 1.0f
        translateX = 0f
        translateY = 0f
        updateMatrix()
        invalidate()
    }

    // --- Save ---
    fun getBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        // Render the current viewport into a bitmap
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }

    // --- Content bounding box (for SVG serialization) ---
    /** Returns the bounding box of all stroke content in canvas-space, or null if empty. */
    fun getContentBounds(): RectF? {
        if (strokes.isEmpty()) return null
        val result = RectF(strokes[0].bounds)
        for (i in 1 until strokes.size) {
            result.union(strokes[i].bounds)
        }
        return result
    }

    // --- Cropped Bitmap for Recognition ---
    fun getRecentClusterBitmap(): Bitmap? {
        val bounds = getRecentClusterBounds() ?: return null
        
        // Add some margin (e.g., 40px in canvas-space)
        val margin = 40f
        val left = bounds.left - margin
        val top = bounds.top - margin
        val right = bounds.right + margin
        val bottom = bounds.bottom + margin
        
        val cropRect = RectF(left, top, right, bottom)
        val cropWidth = cropRect.width().toInt()
        val cropHeight = cropRect.height().toInt()
        if (cropWidth < 10 || cropHeight < 10) return null

        // Update debug box for visualization (in canvas-space)
        debugBoundingBox = cropRect
        invalidate()

        // Render the cluster strokes into a temporary bitmap at 1:1 scale
        val bmp = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(canvasBackgroundColor)
        
        // Translate so that cropRect.left,top maps to 0,0
        c.translate(-left, -top)
        
        for (stroke in strokes) {
            if (RectF.intersects(cropRect, stroke.bounds)) {
                c.drawPath(stroke.path, stroke.paint)
            }
        }
        
        return bmp
    }

    private fun getRecentClusterBounds(): RectF? {
        if (strokes.isEmpty()) return null
        
        val lastStroke = strokes.last()
        val cluster = mutableSetOf(lastStroke)
        val clusterBounds = RectF(lastStroke.bounds)
        
        // Distance to consider "close together"
        val hPadding = 156f // 120 * 1.3
        val vPadding = 60f  // 120 * 0.5
        
        var changed = true
        while (changed) {
            changed = false
            for (stroke in strokes) {
                if (stroke in cluster) continue
                
                // Check if this stroke is near the current cluster
                val nearBounds = RectF(clusterBounds).apply {
                    inset(-hPadding, -vPadding)
                }
                
                if (RectF.intersects(nearBounds, stroke.bounds)) {
                    cluster.add(stroke)
                    clusterBounds.union(stroke.bounds)
                    changed = true
                }
            }
        }
        return clusterBounds
    }

    fun clearDebugBox() {
        debugBoundingBox = null
        invalidate()
    }

    // ── SVG serialization helpers ────────────────────────────────────────

    /** Returns all strokes as serializable data for SVG export. */
    fun getStrokeDataList(): List<StrokeData> {
        return strokes.map { stroke ->
            StrokeData(
                commands = stroke.commands,
                color = stroke.paint.color,
                strokeWidth = stroke.paint.strokeWidth,
                opacity = stroke.paint.alpha,
                isEraser = stroke.isEraserStroke
            )
        }
    }

    /** Rebuilds all strokes from serialized data (used when loading an SVG). */
    fun loadFromStrokeData(strokeDataList: List<StrokeData>) {
        strokes.clear()
        redoStack.clear()
        initialBitmap = null

        for (data in strokeDataList) {
            val path = Path()
            for (cmd in data.commands) {
                when (cmd) {
                    is PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                    is PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                    is PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                }
            }

            val paint = Paint().apply {
                color = data.color
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = data.strokeWidth
                isAntiAlias = true
                alpha = data.opacity
                if (data.isEraser) {
                    // Eraser strokes just paint in the background color
                    color = canvasBackgroundColor
                }
            }

            val bounds = RectF()
            path.computeBounds(bounds, true)
            val halfWidth = paint.strokeWidth / 2f
            bounds.inset(-halfWidth, -halfWidth)

            strokes.add(Stroke(path, paint, bounds, data.commands, data.isEraser))
        }

        // Center view on loaded content
        if (width > 0 && height > 0) {
            centerOnContent()
            invalidate()
        }
    }

    /** Centers and fits the view on the stroke content. */
    private fun centerOnContent() {
        val contentBounds = getContentBounds() ?: return
        if (contentBounds.isEmpty) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val contentW = contentBounds.width()
        val contentH = contentBounds.height()

        // Add some padding
        val padding = 60f
        val paddedW = contentW + padding * 2
        val paddedH = contentH + padding * 2

        // Compute scale to fit content in view
        val fitScale = min(viewW / paddedW, viewH / paddedH).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Don't zoom in past 1x when loading — only zoom out if content is large
        scaleFactor = min(fitScale, 1.0f)

        // Center the content
        translateX = (viewW - contentW * scaleFactor) / 2f - contentBounds.left * scaleFactor
        translateY = (viewH - contentH * scaleFactor) / 2f - contentBounds.top * scaleFactor

        updateMatrix()
    }
}
