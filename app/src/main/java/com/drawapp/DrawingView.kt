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
        var matrix: Matrix,
        var tintColor: Int? = null,
        var removeBackground: Boolean = true,
        var backgroundColor: Int? = null
    ) : CanvasItem() {
        private var _processedBitmap: Bitmap? = null
        val displayBitmap: Bitmap
            get() {
                if (!removeBackground && tintColor == null) return bitmap
                return _processedBitmap ?: processBitmap().also { _processedBitmap = it }
            }

        fun invalidateCache() {
            _processedBitmap = null
        }

        private fun processBitmap(): Bitmap {
            val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (removeBackground) {
                // Make white-ish pixels transparent
                for (y in 0 until result.height) {
                    for (x in 0 until result.width) {
                        val color = result.getPixel(x, y)
                        val r = Color.red(color)
                        val g = Color.green(color)
                        val b = Color.blue(color)
                        // If it's very bright, make it transparent
                        if (r > 240 && g > 240 && b > 240) {
                            result.setPixel(x, y, Color.TRANSPARENT)
                        }
                    }
                }
            }
            if (tintColor != null) {
                // Apply tint to non-transparent pixels
                val canvas = Canvas(result)
                val paint = Paint()
                paint.colorFilter = PorterDuffColorFilter(tintColor!!, PorterDuff.Mode.SRC_IN)
                canvas.drawBitmap(result, 0f, 0f, paint)
            }
            return result
        }

        override val bounds: RectF
            get() {
                val r = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                matrix.mapRect(r)
                return r
            }
    }

    data class WordItem(
        var strokes: List<StrokeItem>,
        val matrix: Matrix,
        var text: String = "",
        var isShowingText: Boolean = false,
        var textMatrix: Matrix = Matrix(),
        var textBounds: RectF = RectF(),
        var tintColor: Int? = null,
        var backgroundColor: Int? = null
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

    data class ClusterResult(
        val bitmap: Bitmap,
        val strokes: List<StrokeItem>,
        val mergedWords: List<WordItem>,
        val cropRect: RectF
    )

    // --- State ---
    private val drawItems = mutableListOf<CanvasItem>()
    private val redoStack = mutableListOf<CanvasItem>()

    private var currentPath = Path()
    private var currentCommands = mutableListOf<PathCommand>()
    private val currentPoints = mutableListOf<PointF>()
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
    var onStateChanged: (() -> Unit)? = null
    var onShowItemColorPicker: ((CanvasItem) -> Unit)? = null

    
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


    private val textPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val toggleBgPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val toggleIconPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val searchHighlightPaint = Paint().apply {
        color = Color.argb(120, 255, 235, 59) // Semi-transparent yellow (Yellow 500)
        style = Paint.Style.FILL
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

    /** Currently focused word for search results */
    var searchHighlightedWord: WordItem? = null
        set(value) { field = value; invalidate() }

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
                    if (item.backgroundColor != null) {
                        val bgPaint = Paint().apply { color = item.backgroundColor!!; style = Paint.Style.FILL }
                        val localRect = RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
                        canvas.save()
                        canvas.concat(item.matrix)
                        canvas.drawRect(localRect, bgPaint)
                        canvas.restore()
                    }
                    canvas.drawBitmap(item.displayBitmap, item.matrix, null)
                    if (item == selectedImage) {
                        drawSelectionBox(canvas, item.bounds)
                    }
                }
                is WordItem -> {
                    canvas.save()
                    canvas.concat(item.matrix)

                    if (item.backgroundColor != null) {
                        val bgPaint = Paint().apply { color = item.backgroundColor!!; style = Paint.Style.FILL }
                        val localRect = RectF()
                        if (item.strokes.isNotEmpty()) {
                            localRect.set(item.strokes[0].bounds)
                            for (i in 1 until item.strokes.size) {
                                localRect.union(item.strokes[i].bounds)
                            }
                        }
                        canvas.drawRect(localRect, bgPaint)
                    }
                    
                    // Draw search highlight if this is the focused word
                    if (item == searchHighlightedWord) {
                        val localBounds = item.bounds // This uses the full matrix calculation
                        // But for highlight we want to draw in the word's space
                        // Actually WordItem.bounds is already in world space if we don't concat.
                        // Since we ALREADY concatted item.matrix, we should draw in local space.
                        val localBoundsRect = RectF()
                        if (item.strokes.isNotEmpty()) {
                            localBoundsRect.set(item.strokes[0].bounds)
                            for (i in 1 until item.strokes.size) {
                                localBoundsRect.union(item.strokes[i].bounds)
                            }
                        }
                        canvas.drawRect(localBoundsRect, searchHighlightPaint)
                    }

                    if (item.isShowingText && item.text.isNotEmpty()) {
                        drawWordText(canvas, item)
                    } else {
                        val wordPaint = if (item.tintColor != null) {
                            Paint().apply { 
                                color = item.tintColor!!
                                style = Paint.Style.STROKE
                                strokeWidth = item.strokes.firstOrNull()?.paint?.strokeWidth ?: 5f
                                isAntiAlias = true
                                strokeCap = Paint.Cap.ROUND
                                strokeJoin = Paint.Join.ROUND
                            }
                        } else null

                        for (stroke in item.strokes) {
                            canvas.drawPath(stroke.path, wordPaint ?: stroke.paint)
                        }
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

        val radius = 24f / scaleFactor
        
        // Draw delete button at top-right corner
        val deleteX = bounds.right
        val deleteY = bounds.top
        canvas.drawCircle(deleteX, deleteY, radius, deleteBgPaint)

        val crossSize = 10f / scaleFactor
        val iconPaint = Paint(deleteIconPaint).apply { strokeWidth = 3f / scaleFactor }
        canvas.drawLine(deleteX - crossSize, deleteY - crossSize, deleteX + crossSize, deleteY + crossSize, iconPaint)
        canvas.drawLine(deleteX + crossSize, deleteY - crossSize, deleteX - crossSize, deleteY + crossSize, iconPaint)
        
        // Draw color button (foreground/tint) at bottom-right corner
        val colorX = bounds.right
        val colorY = bounds.bottom
        canvas.drawCircle(colorX, colorY, radius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        canvas.drawCircle(colorX, colorY, radius * 0.7f, Paint().apply { 
            color = if (selectedImage != null) (selectedImage!!.tintColor ?: Color.BLACK) else (selectedWord!!.tintColor ?: selectedWord!!.strokes.firstOrNull()?.paint?.color ?: Color.BLACK)
            style = Paint.Style.FILL 
        })

        // Draw fill color button (background) at bottom-left corner
        val fillX = bounds.left
        val fillY = bounds.bottom
        canvas.drawCircle(fillX, fillY, radius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        canvas.drawCircle(fillX, fillY, radius * 0.7f, Paint().apply { 
            color = (selectedImage?.backgroundColor ?: selectedWord?.backgroundColor ?: Color.TRANSPARENT)
            style = Paint.Style.FILL 
        })
        if ((selectedImage?.backgroundColor ?: selectedWord?.backgroundColor) == null) {
            // Draw a red slash for "transparent"
            val slashPaint = Paint().apply { color = Color.RED; strokeWidth = 3f / scaleFactor; style = Paint.Style.STROKE }
            canvas.drawLine(fillX - radius * 0.5f, fillY + radius * 0.5f, fillX + radius * 0.5f, fillY - radius * 0.5f, slashPaint)
        }

        // Draw toggle button at top-left corner
        // For Word: Toggle Text
        // For Image: Toggle Background Removal
        val toggleX = bounds.left
        val toggleY = bounds.top
        canvas.drawCircle(toggleX, toggleY, radius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        val toggleIconPaint = Paint().apply { color = Color.BLACK; textSize = 16f / scaleFactor; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val tMetrics = toggleIconPaint.fontMetrics
        val tBaseline = toggleY - (tMetrics.ascent + tMetrics.descent) / 2
        
        if (selectedWord != null) {
            canvas.drawText("T", toggleX, tBaseline, toggleIconPaint)
        } else if (selectedImage != null) {
            canvas.drawText(if (selectedImage!!.removeBackground) "B" else "W", toggleX, tBaseline, toggleIconPaint)
        }
    }

    private fun drawWordText(canvas: Canvas, item: WordItem) {
        if (item.strokes.isEmpty() && item.textBounds.isEmpty) return
        
        canvas.save()
        canvas.concat(item.textMatrix)

        val localBounds = if (!item.textBounds.isEmpty) {
            item.textBounds
        } else {
            // Fallback to current strokes if textBounds is missing (legacy support)
            val b = RectF(item.strokes[0].bounds)
            for (i in 1 until item.strokes.size) {
                b.union(item.strokes[i].bounds)
            }
            b
        }
        
        // Use the color of the first stroke or the tint color
        textPaint.color = item.tintColor ?: if (item.strokes.isNotEmpty()) {
            item.strokes[0].paint.color
        } else {
            Color.BLACK
        }
        
        // Scale text to fit bounds (approximated)
        val text = item.text
        textPaint.textSize = 100f // Base size for measurement
        val textWidth = textPaint.measureText(text)
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        
        val scaleX = if (textWidth > 0) localBounds.width() / textWidth else 1f
        val scaleY = if (textHeight > 0) localBounds.height() / textHeight else 1f
        val scale = min(scaleX, scaleY) * 0.9f // small margin
        
        textPaint.textSize = 100f * scale
        
        val centerX = localBounds.centerX()
        val centerY = localBounds.centerY()
        
        // Center text vertically
        val finalMetrics = textPaint.fontMetrics
        val baseline = centerY - (finalMetrics.ascent + finalMetrics.descent) / 2
        
        canvas.drawText(text, centerX, baseline, textPaint)
        canvas.restore()
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
                    val touchRadius = 60f / scaleFactor // generous touch target
                    
                    // Check delete button (top-right)
                    val delCx = selectedItem.bounds.right
                    val delCy = selectedItem.bounds.top
                    val dDelX = canvasCoords[0] - delCx
                    val dDelY = canvasCoords[1] - delCy
                    if (dDelX * dDelX + dDelY * dDelY <= touchRadius * touchRadius) {
                        deleteSelectedItem()
                        return true
                    }
                    
                    // Check toggle button (top-left)
                    val togCx = selectedItem.bounds.left
                    val togCy = selectedItem.bounds.top
                    val dTogX = canvasCoords[0] - togCx
                    val dTogY = canvasCoords[1] - togCy
                    if (dTogX * dTogX + dTogY * dTogY <= touchRadius * touchRadius) {
                        if (selectedItem is WordItem) {
                            selectedItem.isShowingText = !selectedItem.isShowingText
                        } else if (selectedItem is ImageItem) {
                            selectedItem.removeBackground = !selectedItem.removeBackground
                            selectedItem.invalidateCache()
                        }
                        invalidate()
                        onStateChanged?.invoke()
                        return true
                    }

                    // Check color button (bottom-right)
                    val colorCx = selectedItem.bounds.right
                    val colorCy = selectedItem.bounds.bottom
                    val dColX = canvasCoords[0] - colorCx
                    val dColY = canvasCoords[1] - colorCy
                    if (dColX * dColX + dColY * dColY <= touchRadius * touchRadius) {
                        onShowItemColorPicker?.invoke(selectedItem)
                        return true
                    }

                    // Check fill color button (bottom-left)
                    val fillCx = selectedItem.bounds.left
                    val fillCy = selectedItem.bounds.bottom
                    val dFillX = canvasCoords[0] - fillCx
                    val dFillY = canvasCoords[1] - fillCy
                    if (dFillX * dFillX + dFillY * dFillY <= touchRadius * touchRadius) {
                        showItemColorPicker(selectedItem, isBackground = true)
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
                if (isWordManipulating && selectedWord != null) {
                    freezeWordTransform(selectedWord!!)
                    onStrokeCompleted?.invoke()
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
        currentPoints.clear()
        currentPoints.add(PointF(x, y))
        currentPath.moveTo(x, y)
        currentCommands.add(PathCommand.MoveTo(x, y))
        lastX = x
        lastY = y
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = abs(x - lastX)
        val dy = abs(y - lastY)
        if (dx >= TOUCH_TOLERANCE / scaleFactor || dy >= TOUCH_TOLERANCE / scaleFactor) {
            currentPoints.add(PointF(x, y))
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
        currentPoints.add(PointF(lastX, lastY))

        // --- Smoothing ---
        val smoothedCommands = if (currentPoints.size > 2) {
            smoothPoints(currentPoints)
        } else {
            currentCommands.toList()
        }

        // Rebuild path from smoothed commands
        val smoothedPath = Path()
        for (cmd in smoothedCommands) {
            when (cmd) {
                is PathCommand.MoveTo -> smoothedPath.moveTo(cmd.x, cmd.y)
                is PathCommand.QuadTo -> smoothedPath.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                is PathCommand.CubicTo -> smoothedPath.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
                is PathCommand.LineTo -> smoothedPath.lineTo(cmd.x, cmd.y)
            }
        }

        val strokePaint = buildPaint()
        val bounds = RectF()
        smoothedPath.computeBounds(bounds, true)
        val halfWidth = strokePaint.strokeWidth / 2f
        bounds.inset(-halfWidth, -halfWidth)

        drawItems.add(StrokeItem(smoothedPath, strokePaint, bounds, smoothedCommands, isEraser))
        redoStack.clear()

        currentPath = Path()
        currentCommands = mutableListOf()
        currentPoints.clear()

        onStrokeCompleted?.invoke()
    }

    private fun smoothPoints(points: List<PointF>): List<PathCommand> {
        if (points.size < 3) return listOf(PathCommand.MoveTo(points[0].x, points[0].y)) + 
                points.drop(1).map { PathCommand.LineTo(it.x, it.y) }

        // 1. Simplify points (remove points that are too close to each other)
        val simplified = mutableListOf<PointF>()
        simplified.add(points[0])
        var lastPoint = points[0]
        val minDistance = 2f / scaleFactor
        for (i in 1 until points.size) {
            val p = points[i]
            val dx = p.x - lastPoint.x
            val dy = p.y - lastPoint.y
            if (dx * dx + dy * dy > minDistance * minDistance || i == points.size - 1) {
                simplified.add(p)
                lastPoint = p
            }
        }

        if (simplified.size < 3) return listOf(PathCommand.MoveTo(simplified[0].x, simplified[0].y)) + 
                simplified.drop(1).map { PathCommand.LineTo(it.x, it.y) }

        // 2. Catmull-Rom to Cubic Bezier conversion for smoothing
        val result = mutableListOf<PathCommand>()
        result.add(PathCommand.MoveTo(simplified[0].x, simplified[0].y))

        for (i in 0 until simplified.size - 1) {
            val p0 = if (i == 0) simplified[i] else simplified[i - 1]
            val p1 = simplified[i]
            val p2 = simplified[i + 1]
            val p3 = if (i + 2 < simplified.size) simplified[i + 2] else p2

            // Catmull-Rom to Bezier control points
            val cp1x = p1.x + (p2.x - p0.x) / 6f
            val cp1y = p1.y + (p2.y - p0.y) / 6f
            val cp2x = p2.x - (p3.x - p1.x) / 6f
            val cp2y = p2.y - (p3.y - p1.y) / 6f

            result.add(PathCommand.CubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y))
        }

        return result
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

    fun groupStrokesIntoWord(strokesToGroup: List<StrokeItem>, text: String, wordsToMerge: List<WordItem> = emptyList()): WordItem? {
        val stillInCanvas = strokesToGroup.filter { it in drawItems }
        
        // Remove individual strokes from drawItems
        drawItems.removeAll { it in stillInCanvas }
        
        // Remove words being merged
        drawItems.removeAll { it in wordsToMerge }
        
        if (strokesToGroup.isEmpty()) return null
        
        // Calculate initial text orientation and bounds
        val (writingAngle, center) = calculateWritingAngle(strokesToGroup)
        val textBounds = calculateRotatedBounds(strokesToGroup, writingAngle, center)
        val textMatrix = Matrix()
        textMatrix.postRotate(writingAngle, center.x, center.y)

        // Create WordItem with identity matrix (strokes are already in canvas space)
        val wordItem = WordItem(strokesToGroup, Matrix(), text, false, textMatrix, textBounds)
        drawItems.add(wordItem)
        
        // Clear redo stack as we modified the item structure
        redoStack.clear()
        invalidate()
        onStrokeCompleted?.invoke()
        return wordItem
    }

    private fun calculateWritingAngle(strokes: List<StrokeItem>): Pair<Float, PointF> {
        if (strokes.isEmpty()) return Pair(0f, PointF())

        val allPoints = mutableListOf<PointF>()
        for (stroke in strokes) {
            for (cmd in stroke.commands) {
                when (cmd) {
                    is PathCommand.MoveTo -> allPoints.add(PointF(cmd.x, cmd.y))
                    is PathCommand.LineTo -> allPoints.add(PointF(cmd.x, cmd.y))
                    is PathCommand.QuadTo -> allPoints.add(PointF(cmd.x2, cmd.y2))
                    is PathCommand.CubicTo -> allPoints.add(PointF(cmd.x3, cmd.y3))
                }
            }
        }
        if (allPoints.isEmpty()) {
            // Fallback to center of bounds if no points found
            val b = getRecentClusterBounds(strokes)
            return Pair(0f, PointF(b.centerX(), b.centerY()))
        }

        var centerX = 0f
        var centerY = 0f
        for (p in allPoints) {
            centerX += p.x
            centerY += p.y
        }
        centerX /= allPoints.size
        centerY /= allPoints.size

        var covXX = 0f
        var covYY = 0f
        var covXY = 0f
        for (p in allPoints) {
            val dx = p.x - centerX
            val dy = p.y - centerY
            covXX += dx * dx
            covYY += dy * dy
            covXY += dx * dy
        }
        
        // PCA to find principal axis
        val angle = 0.5 * atan2(2 * covXY.toDouble(), (covXX - covYY).toDouble())
        val degrees = Math.toDegrees(angle).toFloat()
        
        return Pair(degrees, PointF(centerX, centerY))
    }

    private fun calculateRotatedBounds(strokes: List<StrokeItem>, angleDeg: Float, center: PointF): RectF {
        val rotationMatrix = Matrix()
        rotationMatrix.postRotate(-angleDeg, center.x, center.y)
        
        val totalBounds = RectF()
        var first = true
        for (stroke in strokes) {
            val path = Path(stroke.path)
            path.transform(rotationMatrix)
            val b = RectF()
            path.computeBounds(b, true)
            val halfWidth = stroke.paint.strokeWidth / 2f
            b.inset(-halfWidth, -halfWidth)
            
            if (first) {
                totalBounds.set(b)
                first = false
            } else {
                totalBounds.union(b)
            }
        }
        return totalBounds
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
        searchHighlightedWord = null
        scaleFactor = 1.0f
        translateX = 0f
        translateY = 0f
        updateMatrix()
        invalidate()
    }

    fun scrollToWord(word: WordItem) {
        val bounds = word.bounds
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        
        // Center the word in the view
        translateX = width / 2f - centerX * scaleFactor
        translateY = height / 2f - centerY * scaleFactor
        
        updateMatrix()
        invalidate()
    }

    fun getWordItems(): List<WordItem> {
        return drawItems.filterIsInstance<WordItem>()
    }

    fun getItemAtIndex(index: Int): CanvasItem? {
        if (index >= 0 && index < drawItems.size) {
            return drawItems[index]
        }
        return null
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
    fun getRecentClusterWithStrokes(): ClusterResult? {
        val cluster = getRecentClusterItems() ?: return null
        val strokes = cluster.first
        val mergedWords = cluster.second
        val bounds = getRecentClusterBounds(strokes)
        
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
        
        // Draw the strokes in the cluster.
        // These strokes are already in canvas space.
        for (stroke in strokes) {
            c.drawPath(stroke.path, stroke.paint)
        }
        
        return ClusterResult(bmp, strokes, mergedWords, cropRect)
    }

    @Deprecated("Use getRecentClusterWithStrokes instead")
    fun getRecentClusterBitmap(): Bitmap? = getRecentClusterWithStrokes()?.bitmap

    fun getRecentClusterItems(): Pair<List<StrokeItem>, List<WordItem>>? {
        val allLooseStrokes = drawItems.filterIsInstance<StrokeItem>()
        if (allLooseStrokes.isEmpty()) return null
        
        val lastStroke = allLooseStrokes.last()
        val clusterStrokes = mutableSetOf(lastStroke)
        val clusterWords = mutableSetOf<WordItem>()
        val clusterBounds = RectF(lastStroke.bounds)
        
        val hPadding = 156f
        val vPadding = 60f
        
        var changed = true
        while (changed) {
            changed = false
            
            // 1. Check loose strokes
            for (stroke in allLooseStrokes) {
                if (stroke in clusterStrokes) continue
                if (isNear(clusterBounds, stroke.bounds, hPadding, vPadding)) {
                    clusterStrokes.add(stroke)
                    clusterBounds.union(stroke.bounds)
                    changed = true
                }
            }
            
            // 2. Check WordItems
            val allWords = drawItems.filterIsInstance<WordItem>()
            for (word in allWords) {
                if (word in clusterWords) continue
                if (isNear(clusterBounds, word.bounds, hPadding, vPadding)) {
                    clusterWords.add(word)
                    val dissolved = dissolveWordToStrokes(word)
                    for (s in dissolved) {
                        clusterStrokes.add(s)
                        clusterBounds.union(s.bounds)
                    }
                    changed = true
                }
            }
        }
        return Pair(clusterStrokes.toList(), clusterWords.toList())
    }

    private fun isNear(b1: RectF, b2: RectF, hp: Float, vp: Float): Boolean {
        val near = RectF(b1).apply { inset(-hp, -vp) }
        return RectF.intersects(near, b2)
    }

    private fun dissolveWordToStrokes(word: WordItem): List<StrokeItem> {
        return word.strokes.map { stroke ->
            val newPath = Path(stroke.path)
            newPath.transform(word.matrix)
            
            val newCommands = stroke.commands.map { cmd ->
                transformCommand(cmd, word.matrix)
            }

            val newBounds = RectF()
            newPath.computeBounds(newBounds, true)
            val halfWidth = stroke.paint.strokeWidth / 2f
            newBounds.inset(-halfWidth, -halfWidth)
            stroke.copy(path = newPath, commands = newCommands, boundsRect = newBounds)
        }
    }
    
    private fun transformCommand(cmd: PathCommand, matrix: Matrix): PathCommand {
        val pts = when (cmd) {
            is PathCommand.MoveTo -> floatArrayOf(cmd.x, cmd.y)
            is PathCommand.LineTo -> floatArrayOf(cmd.x, cmd.y)
            is PathCommand.QuadTo -> floatArrayOf(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
            is PathCommand.CubicTo -> floatArrayOf(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
        }
        matrix.mapPoints(pts)
        return when (cmd) {
            is PathCommand.MoveTo -> PathCommand.MoveTo(pts[0], pts[1])
            is PathCommand.LineTo -> PathCommand.LineTo(pts[0], pts[1])
            is PathCommand.QuadTo -> PathCommand.QuadTo(pts[0], pts[1], pts[2], pts[3])
            is PathCommand.CubicTo -> PathCommand.CubicTo(pts[0], pts[1], pts[2], pts[3], pts[4], pts[5])
        }
    }

    private fun freezeWordTransform(word: WordItem) {
        if (word.matrix.isIdentity) return

        word.strokes = word.strokes.map { stroke ->
            val newPath = Path(stroke.path)
            newPath.transform(word.matrix)

            val newCommands = stroke.commands.map { cmd ->
                transformCommand(cmd, word.matrix)
            }

            val newBounds = RectF()
            newPath.computeBounds(newBounds, true)
            val halfWidth = stroke.paint.strokeWidth / 2f
            newBounds.inset(-halfWidth, -halfWidth)

            stroke.copy(
                path = newPath,
                commands = newCommands,
                boundsRect = newBounds
            )
        }
        
        // Accumulate transformation for text
        word.textMatrix.postConcat(word.matrix)
        
        word.matrix.reset()
        invalidate()
    }

    fun getRecentClusterStrokes(): List<StrokeItem>? = getRecentClusterItems()?.first

    fun getRecentClusterBounds(strokes: List<StrokeItem>? = null): RectF {
        val targetStrokes = strokes ?: getRecentClusterStrokes() ?: return RectF()
        val bounds = RectF()
        if (targetStrokes.isNotEmpty()) {
            bounds.set(targetStrokes[0].bounds)
            for (i in 1 until targetStrokes.size) {
                bounds.union(targetStrokes[i].bounds)
            }
        }
        return bounds
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
                    ImageData(base64, m, item.tintColor, item.removeBackground, item.backgroundColor)
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
                    
                    val tm = FloatArray(9)
                    item.textMatrix.getValues(tm)
                    val tb = FloatRect(item.textBounds.left, item.textBounds.top, item.textBounds.right, item.textBounds.bottom)
                    
                    WordData(strokeDataList, m, item.text, item.isShowingText, tm, tb, item.tintColor, item.backgroundColor)
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
                            drawItems.add(ImageItem(bitmap, matrix, data.tintColor, data.removeBackground, data.backgroundColor))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                is WordData -> {
                    val strokes = data.strokes.map { createStrokeItem(it) }
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    
                    val textMatrix = Matrix()
                    if (data.textMatrix != null) {
                        textMatrix.setValues(data.textMatrix)
                    }
                    
                    val textBounds = RectF()
                    data.textBounds?.let {
                        textBounds.set(it.left, it.top, it.right, it.bottom)
                    }
                    
                    drawItems.add(WordItem(strokes, matrix, data.text, data.isShowingText, textMatrix, textBounds, data.tintColor, data.backgroundColor))
                }
            }
        }

        if (width > 0 && height > 0) {
            centerOnContent()
            invalidate()
        }
    }

    private fun showItemColorPicker(item: CanvasItem, isBackground: Boolean = false) {
        val colors = mutableListOf(
            "#000000", "#FFFFFF", "#1A237E", "#1B5E20", "#B71C1C", "#4A148C",
            "#FF4081", "#F44336", "#FF9800", "#FFEB3B", "#4CAF50",
            "#00BCD4", "#2196F3", "#9C27B0", "#795548", "#607D8B"
        )
        if (isBackground) colors.add(0, "TRANSPARENT")
        
        val dialog = android.app.AlertDialog.Builder(context).create()
        val scroll = android.widget.HorizontalScrollView(context)
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(40, 40, 40, 40)
        }
        
        for (colorStr in colors) {
            val color = if (colorStr == "TRANSPARENT") null else Color.parseColor(colorStr)
            val colorView = View(context).apply {
                val size = 100
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    setMargins(10, 0, 10, 0)
                }
                setBackground(if (color == null) {
                    val base = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.WHITE)
                        setStroke(2, Color.LTGRAY)
                    }
                    val slash = object : android.graphics.drawable.Drawable() {
                        override fun draw(canvas: Canvas) {
                            val p = Paint().apply { setColor(Color.RED); strokeWidth = 5f; isAntiAlias = true }
                            canvas.drawLine(bounds.width() * 0.2f, bounds.height() * 0.8f, bounds.width() * 0.8f, bounds.height() * 0.2f, p)
                        }
                        override fun setAlpha(alpha: Int) {}
                        override fun setColorFilter(colorFilter: ColorFilter?) {}
                        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
                    }
                    android.graphics.drawable.LayerDrawable(arrayOf(base, slash))
                } else {
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                        setStroke(2, Color.LTGRAY)
                    }
                })
                
                setOnClickListener {
                    if (isBackground) {
                        if (item is ImageItem) item.backgroundColor = color
                        else if (item is WordItem) item.backgroundColor = color
                    } else {
                        if (item is ImageItem) {
                            item.tintColor = color
                            item.invalidateCache()
                        } else if (item is WordItem) {
                            item.tintColor = color
                        }
                    }
                    this@DrawingView.postInvalidate()
                    onStateChanged?.invoke()
                    dialog.dismiss()
                }
            }
            container.addView(colorView)
        }
        
        scroll.addView(container)
        dialog.setView(scroll)
        dialog.setTitle(if (isBackground) "Select Fill Color" else "Select Tint Color")
        dialog.show()
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
                is PathCommand.CubicTo -> path.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
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
