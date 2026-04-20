package com.drawapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.ByteArrayOutputStream
import android.util.Base64
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    enum class BackgroundType { NONE, RULED, GRAPH }


    // Background color
    var canvasBackgroundColor: Int = Color.parseColor("#FDFCF5")
    var backgroundType: BackgroundType = BackgroundType.RULED


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
    sealed class CanvasItem {
        abstract val bounds: RectF
    }

    data class StrokeItem(
        val path: Path,
        val paint: Paint,
        val boundsRect: RectF,
        val commands: List<PathCommand> = emptyList(),
        val isEraserStroke: Boolean = false
    ) : CanvasItem() {
        override val bounds: RectF get() = boundsRect
    }

    data class ImageItem(
        val bitmap: Bitmap,
        var matrix: Matrix
    ) : CanvasItem() {
        override val bounds: RectF
            get() {
                val r = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                matrix.mapRect(r)
                return r
            }
    }

    data class WordItem(
        val strokes: List<StrokeItem>,
        val matrix: Matrix,
        var text: String = ""
    ) : CanvasItem() {
        override val bounds: RectF
            get() {
                val r = RectF()
                if (strokes.isNotEmpty()) {
                    r.set(strokes[0].bounds)
                    for (i in 1 until strokes.size) {
                        r.union(strokes[i].bounds)
                    }
                }
                matrix.mapRect(r)
                return r
            }
    }

    // --- State ---
    private val drawItems = mutableListOf<CanvasItem>()
    private val redoStack = mutableListOf<CanvasItem>()

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

    
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }

    private val deleteBgPaint = Paint().apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val deleteIconPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
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
    private var isImageManipulating = false
    private var isWordManipulating = false
    private var selectedImage: ImageItem? = null
    private var selectedWord: WordItem? = null
    
    // Manipulation State
    private var initialMatrix = Matrix()
    private var startAngle = 0f
    private var startSpan = 1f
    private var startFocalX = 0f
    private var startFocalY = 0f

    // Pan tracking
    private var lastPanX = 0f
    private var lastPanY = 0f

    // Scale gesture detector
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if ((isImageManipulating && selectedImage != null) || (isWordManipulating && selectedWord != null)) {
                    val currentMatrix = selectedImage?.matrix ?: selectedWord!!.matrix
                    initialMatrix.set(currentMatrix)
                    startSpan = max(1f, detector.currentSpan)
                    
                    // Angle between two fingers
                    val dx = detector.currentSpanX
                    val dy = detector.currentSpanY
                    startAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    
                    val pt = screenToCanvas(detector.focusX, detector.focusY)
                    startFocalX = pt[0]
                    startFocalY = pt[1]
                    return true
                }
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if ((isImageManipulating && selectedImage != null) || (isWordManipulating && selectedWord != null)) {
                    val targetMatrix = selectedImage?.matrix ?: selectedWord!!.matrix
                    val scale = detector.currentSpan / startSpan
                    
                    val dx = detector.currentSpanX
                    val dy = detector.currentSpanY
                    val currentAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val rotation = currentAngle - startAngle
                    
                    val pt = screenToCanvas(detector.focusX, detector.focusY)
                    val transX = pt[0] - startFocalX
                    val transY = pt[1] - startFocalY

                    val tempMatrix = Matrix(initialMatrix)
                    tempMatrix.postTranslate(transX, transY)
                    tempMatrix.postScale(scale, scale, pt[0], pt[1])
                    tempMatrix.postRotate(rotation, pt[0], pt[1])
                    
                    targetMatrix.set(tempMatrix)
                    invalidate()
                    return true
                }

                // Canvas panning/zooming
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

        // 5. Draw all committed items
        for (item in drawItems) {
            when (item) {
                is StrokeItem -> canvas.drawPath(item.path, item.paint)
                is ImageItem -> {
                    canvas.drawBitmap(item.bitmap, item.matrix, null)
                    if (item == selectedImage) {
                        drawSelectionBox(canvas, item.bounds)
                    }
                }
                is WordItem -> {
                    canvas.save()
                    canvas.concat(item.matrix)
                    for (stroke in item.strokes) {
                        canvas.drawPath(stroke.path, stroke.paint)
                    }
                    canvas.restore()
                    if (item == selectedWord) {
                        drawSelectionBox(canvas, item.bounds)
                    }
                }
            }
        }

        // 6. Draw in-progress stroke live
        if (isDrawing) {
            canvas.drawPath(currentPath, currentPaint)
        }


        canvas.restore()
    }

    private fun drawSelectionBox(canvas: Canvas, bounds: RectF) {
        // Draw dashed bounding box
        canvas.drawRect(bounds, selectionPaint)

        // Draw delete button at top-right corner
        val radius = 24f / scaleFactor
        val cx = bounds.right
        val cy = bounds.top

        canvas.drawCircle(cx, cy, radius, deleteBgPaint)

        val crossSize = 10f / scaleFactor
        val iconPaint = Paint(deleteIconPaint).apply { strokeWidth = 3f / scaleFactor }
        canvas.drawLine(cx - crossSize, cy - crossSize, cx + crossSize, cy + crossSize, iconPaint)
        canvas.drawLine(cx + crossSize, cy - crossSize, cx - crossSize, cy + crossSize, iconPaint)
    }

    private fun drawPaperLines(canvas: Canvas) {
        if (backgroundType == BackgroundType.NONE) return
        
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val lineSpacing = 100f
        val gridSpacing = 80f

        val topLeft = screenToCanvas(0f, 0f)
        val bottomRight = screenToCanvas(w, h)

        val canvasLeft = topLeft[0]
        val canvasTop = topLeft[1]
        val canvasRight = bottomRight[0]
        val canvasBottom = bottomRight[1]

        val adjustedLinePaint = Paint(linePaint).apply { strokeWidth = 2f / scaleFactor }
        val adjustedMarginPaint = Paint(marginPaint).apply { strokeWidth = 3f / scaleFactor }

        canvas.save()
        canvas.concat(viewMatrix)

        if (backgroundType == BackgroundType.RULED) {
            val firstLineY = (Math.ceil((canvasTop / lineSpacing).toDouble()) * lineSpacing).toFloat()
            var y = firstLineY
            while (y < canvasBottom) {
                canvas.drawLine(canvasLeft, y, canvasRight, y, adjustedLinePaint)
                y += lineSpacing
            }

            val marginX = 120f
            if (marginX in canvasLeft..canvasRight) {
                canvas.drawLine(marginX, canvasTop, marginX, canvasBottom, adjustedMarginPaint)
            }
        } else if (backgroundType == BackgroundType.GRAPH) {
            // Horizontal lines
            val firstLineY = (Math.ceil((canvasTop / gridSpacing).toDouble()) * gridSpacing).toFloat()
            var y = firstLineY
            while (y < canvasBottom) {
                canvas.drawLine(canvasLeft, y, canvasRight, y, adjustedLinePaint)
                y += gridSpacing
            }
            // Vertical lines
            val firstLineX = (Math.ceil((canvasLeft / gridSpacing).toDouble()) * gridSpacing).toFloat()
            var x = firstLineX
            while (x < canvasRight) {
                canvas.drawLine(x, canvasTop, x, canvasBottom, adjustedLinePaint)
                x += gridSpacing
            }
        }

        canvas.restore()
    }


    // ══════════════════════════════════════════════════════════════════════
    // Touch handling — multi-touch with draw/pan/zoom
    // ══════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val canvasCoords = screenToCanvas(event.x, event.y)
                
                // Check if user tapped the delete cross of the selected image/word
                val selectedItem = selectedImage ?: selectedWord
                if (selectedItem != null) {
                    val cx = selectedItem.bounds.right
                    val cy = selectedItem.bounds.top
                    val touchRadius = 60f / scaleFactor // generous touch target
                    val dx = canvasCoords[0] - cx
                    val dy = canvasCoords[1] - cy
                    if (dx * dx + dy * dy <= touchRadius * touchRadius) {
                        deleteSelectedItem()
                        return true
                    }
                }
                
                // Hit testing for images and words
                val hitItem = drawItems.reversed().find { 
                    it.bounds.contains(canvasCoords[0], canvasCoords[1])
                }

                when (hitItem) {
                    is ImageItem -> {
                        selectedImage = hitItem
                        selectedWord = null
                        isImageManipulating = true
                        isWordManipulating = false
                        isDrawing = false
                        isPanning = false
                        lastPanX = event.x
                        lastPanY = event.y
                    }
                    is WordItem -> {
                        selectedWord = hitItem
                        selectedImage = null
                        isWordManipulating = true
                        isImageManipulating = false
                        isDrawing = false
                        isPanning = false
                        lastPanX = event.x
                        lastPanY = event.y
                    }
                    else -> {
                        selectedImage = null
                        selectedWord = null
                        isImageManipulating = false
                        isWordManipulating = false
                        isDrawing = true
                        isPanning = false
                        touchStart(canvasCoords[0], canvasCoords[1])
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (isDrawing) {
                    cancelCurrentStroke()
                    isDrawing = false
                }
                if (!isImageManipulating && !isWordManipulating) {
                    isPanning = true
                    lastPanX = event.x
                    lastPanY = event.y
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if ((isImageManipulating && selectedImage != null || isWordManipulating && selectedWord != null) && pointerCount == 1) {
                    // Single finger translate
                    val targetMatrix = selectedImage?.matrix ?: selectedWord!!.matrix
                    val canvasCoords = screenToCanvas(event.x, event.y)
                    val lastCanvasCoords = screenToCanvas(lastPanX, lastPanY)
                    val dx = canvasCoords[0] - lastCanvasCoords[0]
                    val dy = canvasCoords[1] - lastCanvasCoords[1]
                    
                    targetMatrix.postTranslate(dx, dy)
                    
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                } else if (isPanning && pointerCount >= 2 && !isImageManipulating && !isWordManipulating) {
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
                isImageManipulating = false
                isWordManipulating = false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount <= 2 && !isImageManipulating && !isWordManipulating) {
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
            currentPath.quadTo(lastX, lastY, midX, midY)
            currentCommands.add(PathCommand.QuadTo(lastX, lastY, midX, midY))
            lastX = x
            lastY = y
        }
    }

    private fun touchUp() {
        currentPath.lineTo(lastX, lastY)
        currentCommands.add(PathCommand.LineTo(lastX, lastY))

        val strokePaint = buildPaint()
        val bounds = RectF()
        currentPath.computeBounds(bounds, true)
        val halfWidth = strokePaint.strokeWidth / 2f
        bounds.inset(-halfWidth, -halfWidth)

        drawItems.add(StrokeItem(currentPath, strokePaint, bounds, currentCommands.toList(), isEraser))
        redoStack.clear()

        currentPath = Path()
        currentCommands = mutableListOf()

        onStrokeCompleted?.invoke()
    }

    private fun cancelCurrentStroke() {
        currentPath = Path()
        currentCommands = mutableListOf()
    }

    // --- Images ---
    fun addImage(bitmap: Bitmap) {
        // Downscale image if too large to prevent huge SVG files and memory issues
        val MAX_DIM = 1024
        val maxLen = max(bitmap.width, bitmap.height)
        val scaledBitmap = if (maxLen > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / maxLen
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        
        // Center the image in the current viewport
        val viewW = if (width > 0) width.toFloat() else 800f
        val viewH = if (height > 0) height.toFloat() else 800f
        val viewCenter = screenToCanvas(viewW / 2f, viewH / 2f)
        
        val matrix = Matrix()
        matrix.postTranslate(viewCenter[0] - scaledBitmap.width / 2f, viewCenter[1] - scaledBitmap.height / 2f)
        
        val imageItem = ImageItem(scaledBitmap, matrix)
        drawItems.add(imageItem)
        selectedImage = imageItem
        redoStack.clear()
        invalidate()
        onStrokeCompleted?.invoke()
    }

    fun deleteSelectedItem(): Boolean {
        val image = selectedImage
        if (image != null) {
            drawItems.remove(image)
            selectedImage = null
            isImageManipulating = false
            redoStack.clear()
            invalidate()
            onStrokeCompleted?.invoke()
            return true
        }
        val word = selectedWord
        if (word != null) {
            drawItems.remove(word)
            selectedWord = null
            isWordManipulating = false
            redoStack.clear()
            invalidate()
            onStrokeCompleted?.invoke()
            return true
        }
        return false
    }

    @Deprecated("Use deleteSelectedItem instead", ReplaceWith("deleteSelectedItem()"))
    fun deleteSelectedImage() = deleteSelectedItem()

    fun groupStrokesIntoWord(strokesToGroup: List<StrokeItem>, text: String): WordItem? {
        val stillInCanvas = strokesToGroup.filter { it in drawItems }
        if (stillInCanvas.isEmpty()) return null
        
        // Remove individual strokes from drawItems
        drawItems.removeAll { it in stillInCanvas }
        
        // Create WordItem with identity matrix (strokes are already in canvas space)
        val wordItem = WordItem(stillInCanvas, Matrix(), text)
        drawItems.add(wordItem)
        
        // Clear redo stack as we modified the item structure
        redoStack.clear()
        invalidate()
        onStrokeCompleted?.invoke()
        return wordItem
    }

    // --- Undo / Redo ---
    fun undo() {
        if (drawItems.isNotEmpty()) {
            val item = drawItems.removeLast()
            if (item == selectedImage) selectedImage = null
            if (item == selectedWord) selectedWord = null
            redoStack.add(item)
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            drawItems.add(redoStack.removeLast())
            invalidate()
        }
    }

    fun canUndo() = drawItems.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    // --- Clear ---
    fun clear() {
        drawItems.clear()
        redoStack.clear()
        currentPath = Path()
        initialBitmap = null
        selectedImage = null
        selectedWord = null
        scaleFactor = 1.0f
        translateX = 0f
        translateY = 0f
        updateMatrix()
        invalidate()
    }

    fun getBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }

    fun getContentBounds(): RectF? {
        if (drawItems.isEmpty()) return null
        val result = RectF(drawItems[0].bounds)
        for (i in 1 until drawItems.size) {
            result.union(drawItems[i].bounds)
        }
        return result
    }

    // --- Cropped Bitmap for Recognition ---
    fun getRecentClusterWithStrokes(): Triple<Bitmap, List<StrokeItem>, RectF>? {
        val bounds = getRecentClusterBounds() ?: return null
        val strokes = getRecentClusterStrokes() ?: return null
        
        val margin = 40f
        val left = bounds.left - margin
        val top = bounds.top - margin
        val right = bounds.right + margin
        val bottom = bounds.bottom + margin
        
        val cropRect = RectF(left, top, right, bottom)
        val cropWidth = cropRect.width().toInt()
        val cropHeight = cropRect.height().toInt()
        if (cropWidth < 10 || cropHeight < 10) return null


        val bmp = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(canvasBackgroundColor)
        
        c.translate(-left, -top)
        
        for (item in drawItems) {
            if (item is StrokeItem && strokes.contains(item)) {
                c.drawPath(item.path, item.paint)
            }
        }
        
        return Triple(bmp, strokes, cropRect)
    }

    @Deprecated("Use getRecentClusterWithStrokes instead")
    fun getRecentClusterBitmap(): Bitmap? = getRecentClusterWithStrokes()?.first

    fun getRecentClusterStrokes(): List<StrokeItem>? {
        val strokes = drawItems.filterIsInstance<StrokeItem>()
        if (strokes.isEmpty()) return null
        
        val lastStroke = strokes.last()
        val cluster = mutableSetOf(lastStroke)
        val clusterBounds = RectF(lastStroke.bounds)
        
        val hPadding = 156f
        val vPadding = 60f
        
        var changed = true
        while (changed) {
            changed = false
            for (stroke in strokes) {
                if (stroke in cluster) continue
                
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
        return cluster.toList()
    }

    fun getRecentClusterBounds(): RectF? {
        val strokes = drawItems.filterIsInstance<StrokeItem>()
        if (strokes.isEmpty()) return null
        
        val lastStroke = strokes.last()
        val cluster = mutableSetOf(lastStroke)
        val clusterBounds = RectF(lastStroke.bounds)
        
        val hPadding = 156f
        val vPadding = 60f
        
        var changed = true
        while (changed) {
            changed = false
            for (stroke in strokes) {
                if (stroke in cluster) continue
                
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
        invalidate()
    }

    // ── SVG serialization helpers ────────────────────────────────────────

    fun getSvgDataList(): List<SvgData> {
        return drawItems.mapNotNull { item ->
            when (item) {
                is StrokeItem -> {
                    StrokeData(
                        commands = item.commands,
                        color = item.paint.color,
                        strokeWidth = item.paint.strokeWidth,
                        opacity = item.paint.alpha,
                        isEraser = item.isEraserStroke
                    )
                }
                is ImageItem -> {
                    val stream = ByteArrayOutputStream()
                    item.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    val m = FloatArray(9)
                    item.matrix.getValues(m)
                    ImageData(base64, m)
                }
                is WordItem -> {
                    val strokeDataList = item.strokes.map {
                        StrokeData(
                            commands = it.commands,
                            color = it.paint.color,
                            strokeWidth = it.paint.strokeWidth,
                            opacity = it.paint.alpha,
                            isEraser = it.isEraserStroke
                        )
                    }
                    val m = FloatArray(9)
                    item.matrix.getValues(m)
                    WordData(strokeDataList, m, item.text)
                }
            }
        }
    }

    fun loadFromSvgData(dataList: List<SvgData>) {
        drawItems.clear()
        redoStack.clear()
        initialBitmap = null
        selectedImage = null
        selectedWord = null

        for (data in dataList) {
            when (data) {
                is StrokeData -> {
                    drawItems.add(createStrokeItem(data))
                }
                is ImageData -> {
                    try {
                        val decodedString = Base64.decode(data.base64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        if (bitmap != null) {
                            val matrix = Matrix()
                            matrix.setValues(data.matrix)
                            drawItems.add(ImageItem(bitmap, matrix))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                is WordData -> {
                    val strokes = data.strokes.map { createStrokeItem(it) }
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    drawItems.add(WordItem(strokes, matrix, data.text))
                }
            }
        }

        if (width > 0 && height > 0) {
            centerOnContent()
            invalidate()
        }
    }

    fun centerOnContent() {
        val bounds = getContentBounds() ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val contentW = bounds.width()
        val contentH = bounds.height()

        val scale = (min(viewW / contentW, viewH / contentH) * 0.8f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scaleFactor = scale

        translateX = viewW / 2f - (bounds.centerX() * scaleFactor)
        translateY = viewH / 2f - (bounds.centerY() * scaleFactor)

        updateMatrix()
        invalidate()
    }

    private fun createStrokeItem(data: StrokeData): StrokeItem {
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
                color = canvasBackgroundColor
            }
        }

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val halfWidth = paint.strokeWidth / 2f
        bounds.inset(-halfWidth, -halfWidth)

        return StrokeItem(path, paint, bounds, data.commands, data.isEraser)
    }
}
