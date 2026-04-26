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
    var notebookType: NotebookType = NotebookType.NOTEBOOK
    var numPages: Int = 1

    // --- Page Constants (for Notebook mode) ---
    val PAGE_WIDTH = 4000f
    val PAGE_HEIGHT = 5656f
    val PAGE_MARGIN = 240f

    private val pageBgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        // Shadow removed as it's expensive and can cause crashes on zoom
    }


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
        open fun invalidateCache() {}
    }

    data class StrokeItem(
        val path: Path,
        val paint: Paint,
        val boundsRect: RectF,
        val commands: List<PathCommand> = emptyList(),
        val isEraser: Boolean = false
    ) : CanvasItem() {
        override val bounds: RectF get() = boundsRect
    }

    data class ImageItem(
        val bitmap: Bitmap,
        var matrix: Matrix,
        var removeBackground: Boolean = true,
        var text: String = "",
        var isShowingText: Boolean = false,
        var textMatrix: Matrix = Matrix(),
        var textBounds: RectF = RectF()
    ) : CanvasItem() {
        private var _processedBitmap: Bitmap? = null
        val displayBitmap: Bitmap
            get() {
                if (!removeBackground) return bitmap
                return _processedBitmap ?: processBitmap().also { _processedBitmap = it }
            }

        override fun invalidateCache() {
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
        private var cachedBounds: RectF? = null
        override val bounds: RectF
            get() {
                cachedBounds?.let { return it }
                val r = RectF()
                if (strokes.isNotEmpty()) {
                    r.set(strokes[0].bounds)
                    for (i in 1 until strokes.size) {
                        r.union(strokes[i].bounds)
                    }
                }
                matrix.mapRect(r)
                cachedBounds = r
                return r
            }
        
        override fun invalidateCache() {
            cachedBounds = null
        }
    }

    data class ClusterResult(
        val bitmap: Bitmap,
        val strokes: List<StrokeItem>,
        val mergedWords: List<WordItem>,
        val cropRect: RectF
    )

    // --- Undo / Redo Actions ---
    interface UndoAction {
        fun undo()
        fun redo()
    }

    inner class AddItemAction(val item: CanvasItem) : UndoAction {
        override fun undo() { drawItems.remove(item); invalidate() }
        override fun redo() { drawItems.add(item); invalidate() }
    }

    inner class RemoveItemAction(val item: CanvasItem, val index: Int) : UndoAction {
        override fun undo() { drawItems.add(index, item); invalidate() }
        override fun redo() { drawItems.remove(item); invalidate() }
    }

    inner class TransformAction(
        val item: CanvasItem,
        val oldState: ItemState,
        val newState: ItemState
    ) : UndoAction {
        override fun undo() { applyState(item, oldState); invalidate() }
        override fun redo() { applyState(item, newState); invalidate() }
    }

    inner class StyleAction(
        val item: CanvasItem,
        val property: String,
        val oldValue: Any?,
        val newValue: Any?
    ) : UndoAction {
        override fun undo() { applyStyle(item, property, oldValue); invalidate() }
        override fun redo() { applyStyle(item, property, newValue); invalidate() }
    }

    inner class GroupAction(
        val strokesToRemove: List<StrokeItem>,
        val wordsToRemove: List<WordItem>,
        val wordToAdd: WordItem
    ) : UndoAction {
        override fun undo() {
            drawItems.remove(wordToAdd)
            drawItems.addAll(strokesToRemove)
            drawItems.addAll(wordsToRemove)
            invalidate()
        }
        override fun redo() {
            drawItems.removeAll(strokesToRemove)
            drawItems.removeAll(wordsToRemove)
            drawItems.add(wordToAdd)
            invalidate()
        }
    }

    data class ItemState(
        val matrix: Matrix,
        val textMatrix: Matrix,
        val textBounds: RectF,
        val strokes: List<StrokeItem>? = null
    )

    private fun captureState(item: CanvasItem): ItemState {
        return when (item) {
            is ImageItem -> ItemState(Matrix(item.matrix), Matrix(item.textMatrix), RectF(item.textBounds))
            is WordItem -> ItemState(Matrix(item.matrix), Matrix(item.textMatrix), RectF(item.textBounds), item.strokes.map { it.copy() })
            else -> ItemState(Matrix(), Matrix(), RectF())
        }
    }

    private fun applyState(item: CanvasItem, state: ItemState) {
        when (item) {
            is ImageItem -> {
                item.matrix.set(state.matrix)
                item.textMatrix.set(state.textMatrix)
                item.textBounds.set(state.textBounds)
            }
            is WordItem -> {
                item.matrix.set(state.matrix)
                item.textMatrix.set(state.textMatrix)
                item.textBounds.set(state.textBounds)
                state.strokes?.let { item.strokes = it }
            }
            is StrokeItem -> {}
        }
        item.invalidateCache()
    }

    private fun applyStyle(item: CanvasItem, property: String, value: Any?) {
        when (property) {
            "tintColor" -> if (item is WordItem) item.tintColor = value as Int?
            "backgroundColor" -> if (item is WordItem) item.backgroundColor = value as Int?
            "isShowingText" -> {
                if (item is WordItem) item.isShowingText = value as Boolean
                if (item is ImageItem) item.isShowingText = value as Boolean
            }
            "removeBackground" -> if (item is ImageItem) {
                item.removeBackground = value as Boolean
                item.invalidateCache()
            }
            "paintColor" -> if (item is StrokeItem) item.paint.color = value as Int
        }
    }

    // --- State ---
    private val drawItems = mutableListOf<CanvasItem>()
    private val undoStack = mutableListOf<UndoAction>()
    private val redoStack = mutableListOf<UndoAction>()

    fun setItemStyle(item: CanvasItem, property: String, newValue: Any?) {
        val oldValue = when (property) {
            "tintColor" -> if (item is WordItem) item.tintColor else null
            "backgroundColor" -> if (item is WordItem) item.backgroundColor else null
            "isShowingText" -> {
                if (item is WordItem) item.isShowingText
                else if (item is ImageItem) item.isShowingText
                else false
            }
            "removeBackground" -> if (item is ImageItem) item.removeBackground else false
            "paintColor" -> if (item is StrokeItem) item.paint.color else Color.BLACK
            else -> null
        }
        pushAction(StyleAction(item, property, oldValue, newValue))
    }

    fun pushAction(action: UndoAction, executeNow: Boolean = true) {
        if (executeNow) action.redo()
        undoStack.add(action)
        redoStack.clear()
        onStateChanged?.invoke()
    }

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


    var brushOpacity: Int = 255
        set(value) { field = value; updateCurrentPaint() }

    var isEraser: Boolean = false
        set(value) { field = value; updateCurrentPaint() }

    /** Called once each time the user lifts their finger after a stroke. */
    var onStrokeCompleted: (() -> Unit)? = null
    var onWordModified: ((WordItem) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null
    var onShowItemColorPicker: ((CanvasItem) -> Unit)? = null
    var onPageAdded: (() -> Unit)? = null

    
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

    private val tempWordBgPaint = Paint()

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

    var scaleFactor = 1.0f
    var translateX = -120f
    var translateY = 0f

    // Zoom limits
    private val MIN_ZOOM = 0.25f
    private val MAX_ZOOM: Float
        get() = if (notebookType == NotebookType.WHITEBOARD) 100.0f else 5.0f

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

    // Rotation state
    private var isRotating = false
    private var rotationInitialAngle = 0f
    private var rotationCenter = PointF()
    
    private var lastMotionEvent: MotionEvent? = null
    
    // Two-finger manipulation state
    private var isTwoFingerManipulating = false
    private var twoFingerStartSpan = 1f
    private var twoFingerStartAngle = 0f
    private var twoFingerStartMatrix = Matrix()
    private var twoFingerStartFocal = PointF()

    private var beforeTransformState: ItemState? = null

    /** Currently focused word for search results */
    var searchHighlightedWord: WordItem? = null
        set(value) { field = value; invalidate() }

    // Scale gesture detector
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Remove item manipulation from here, handled in onTouchEvent
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Remove item manipulation from here, handled in onTouchEvent

                // Canvas panning/zooming
                val oldScale = scaleFactor
                var detectorScale = detector.scaleFactor
                
                // Boost sensitivity for faster zooming
                detectorScale = 1.0f + (detectorScale - 1.0f) * 3.0f

                // --- Auto-Fit Selection Logic ---
                val currentSelectedItem = selectedImage ?: selectedWord
                if (currentSelectedItem != null && detectorScale > 1.0f) {
                    val screenBounds = RectF()
                    val localBounds = when (currentSelectedItem) {
                        is ImageItem -> RectF(0f, 0f, currentSelectedItem.bitmap.width.toFloat(), currentSelectedItem.bitmap.height.toFloat())
                        is WordItem -> currentSelectedItem.textBounds
                        else -> RectF()
                    }
                    
                    val combinedMatrix = Matrix(viewMatrix)
                    when (currentSelectedItem) {
                        is ImageItem -> combinedMatrix.preConcat(currentSelectedItem.matrix)
                        is WordItem -> {
                            combinedMatrix.preConcat(currentSelectedItem.matrix)
                            combinedMatrix.preConcat(currentSelectedItem.textMatrix)
                        }
                        else -> {}
                    }
                    
                    combinedMatrix.mapRect(screenBounds, localBounds)
                    
                    val margin = 50f
                    if (screenBounds.left < margin || screenBounds.right > width - margin || 
                        screenBounds.top < margin || screenBounds.bottom > height - margin) {
                        // Item is hitting screen edges and user is zooming in.
                        // Invert the detector scale to zoom the canvas OUT instead.
                        detectorScale = 1.0f / (detectorScale * 1.02f) 
                    }
                }

                scaleFactor *= detectorScale
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
        if (notebookType == NotebookType.NOTEBOOK && width > 0 && height > 0) {
            val totalHeight = numPages * PAGE_HEIGHT + (numPages - 1) * PAGE_MARGIN
            val screenW = width.toFloat()
            val screenH = height.toFloat()
            val margin = 300f * scaleFactor
            
            // X clamping
            val contentW = PAGE_WIDTH * scaleFactor
            if (contentW < screenW) {
                translateX = (screenW - contentW) / 2f
            } else {
                translateX = translateX.coerceIn(screenW - contentW - margin, margin)
            }
            
            // Y clamping
            val contentH = totalHeight * scaleFactor
            if (contentH < screenH) {
                // If content is smaller than screen, we can still pan a bit or center it.
                // Let's center it if it's much smaller, otherwise allow some panning.
                if (contentH < screenH * 0.8f) {
                    translateY = (screenH - contentH) / 2f
                } else {
                    translateY = translateY.coerceIn(screenH - contentH - margin, margin)
                }
            } else {
                val minTranslateY = screenH - contentH - margin
                
                // Auto-create page if we reach the bottom
                if (translateY <= minTranslateY + 500f) {
                    numPages++
                    onPageAdded?.invoke()
                }
                
                translateY = translateY.coerceIn(minTranslateY, margin)
            }
        }

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

        // 1. Fill background (dark for notebook, canvas color for whiteboard)
        if (notebookType == NotebookType.NOTEBOOK) {
            canvas.drawColor(Color.parseColor("#1A1A2E")) // Dark desk color
        } else {
            canvas.drawColor(canvasBackgroundColor)
        }

        // 2. Draw paper relative to viewport
        if (notebookType == NotebookType.NOTEBOOK) {
            canvas.save()
            canvas.concat(viewMatrix)
            for (i in 0 until numPages) {
                val top = i * (PAGE_HEIGHT + PAGE_MARGIN)
                val rect = RectF(0f, top, PAGE_WIDTH, top + PAGE_HEIGHT)
                canvas.drawRect(rect, pageBgPaint)
            }
            canvas.restore()
        }
        
        drawPaperLines(canvas)

        // 3. Apply view transform for all canvas-space content
        canvas.save()
        canvas.concat(viewMatrix)

        // 4. Draw initial bitmap if present
        initialBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        val viewport = getViewportCanvasRect()

        // 5. Draw all committed items (with culling)
        for (item in drawItems) {
            if (RectF.intersects(item.bounds, viewport)) {
                when (item) {
                    is StrokeItem -> canvas.drawPath(item.path, item.paint)
                    is ImageItem -> {
                        canvas.save()
                        canvas.concat(item.matrix)

                        if (item.isShowingText && item.text.isNotEmpty()) {
                            drawImageText(canvas, item)
                        } else {
                            canvas.drawBitmap(item.displayBitmap, 0f, 0f, null)
                        }
                        canvas.restore()

                        if (item == selectedImage) {
                            drawSelectionBox(canvas, item)
                        }
                    }
                    is WordItem -> {
                        canvas.save()
                        canvas.concat(item.matrix)

                        if (item.backgroundColor != null) {
                            tempWordBgPaint.color = item.backgroundColor!!
                            tempWordBgPaint.style = Paint.Style.FILL
                            val localRect = RectF()
                            if (item.strokes.isNotEmpty()) {
                                localRect.set(item.strokes[0].bounds)
                                for (i in 1 until item.strokes.size) {
                                    localRect.union(item.strokes[i].bounds)
                                }
                            }
                            canvas.drawRect(localRect, tempWordBgPaint)
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
                            drawSelectionBox(canvas, item)
                        }
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

    private fun drawSelectionBox(canvas: Canvas, item: CanvasItem) {
        val matrix = when (item) {
            is ImageItem -> item.matrix
            is WordItem -> item.matrix
            else -> Matrix()
        }

        val localBounds = when (item) {
            is ImageItem -> RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
            is WordItem -> {
                val r = RectF()
                if (item.strokes.isNotEmpty()) {
                    r.set(item.textBounds)
                }
                r
            }
            else -> item.bounds
        }

        canvas.save()
        canvas.concat(matrix)
        
        // For WordItem, we also need to account for textMatrix which holds the orientation
        if (item is WordItem) {
            canvas.concat(item.textMatrix)
        }

        // Draw dashed bounding box
        canvas.drawRect(localBounds, selectionPaint)
        canvas.restore()

        // --- Draw Fixed-Size Buttons ---
        val buttonRadius = 24f // Fixed size in pixels
        
        val itemTransform = Matrix(matrix)
        if (item is WordItem) itemTransform.preConcat(item.textMatrix)
        
        fun getButtonScreenPos(lx: Float, ly: Float): PointF {
            val pts = floatArrayOf(lx, ly)
            itemTransform.mapPoints(pts)
            val screenPts = floatArrayOf(pts[0], pts[1])
            viewMatrix.mapPoints(screenPts)
            return PointF(screenPts[0], screenPts[1])
        }

        val delPos = getButtonScreenPos(localBounds.right, localBounds.top)
        val colPos = getButtonScreenPos(localBounds.right, localBounds.bottom)
        val filPos = getButtonScreenPos(localBounds.left, localBounds.bottom)
        val togPos = getButtonScreenPos(localBounds.left, localBounds.top)
        
        val rotLocalX = localBounds.centerX()
        val rotLocalY = localBounds.top
        val rotBasePos = getButtonScreenPos(rotLocalX, rotLocalY)
        
        val upVec = floatArrayOf(0f, -1f)
        itemTransform.mapVectors(upVec)
        viewMatrix.mapVectors(upVec)
        val upLen = Math.sqrt((upVec[0] * upVec[0] + upVec[1] * upVec[1]).toDouble()).toFloat()
        val ux = upVec[0] / (if (upLen == 0f) 1f else upLen)
        val uy = upVec[1] / (if (upLen == 0f) 1f else upLen)
        val rotPos = PointF(rotBasePos.x + ux * 60f, rotBasePos.y + uy * 60f)

        canvas.save()
        canvas.setMatrix(Matrix()) // Reset to screen space

        // Lollipop line
        canvas.drawLine(rotBasePos.x, rotBasePos.y, rotPos.x, rotPos.y, selectionPaint)

        // Rotation Button
        canvas.drawCircle(rotPos.x, rotPos.y, buttonRadius, Paint().apply { 
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(4f, 0f, 2f, Color.argb(100, 0, 0, 0))
        })
        val rotIconPaint = Paint().apply { 
            color = Color.parseColor("#7C4DFF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        val rotIconSize = 8f
        canvas.drawArc(rotPos.x - rotIconSize, rotPos.y - rotIconSize, rotPos.x + rotIconSize, rotPos.y + rotIconSize, 45f, 270f, false, rotIconPaint)

        // Delete Button
        canvas.drawCircle(delPos.x, delPos.y, buttonRadius, deleteBgPaint)
        val crossSize = 10f
        val crossPaint = Paint(deleteIconPaint).apply { strokeWidth = 3f }
        canvas.drawLine(delPos.x - crossSize, delPos.y - crossSize, delPos.x + crossSize, delPos.y + crossSize, crossPaint)
        canvas.drawLine(delPos.x + crossSize, delPos.y - crossSize, delPos.x - crossSize, delPos.y + crossSize, crossPaint)

        // Color Button (Not for ImageItem anymore, only for WordItem)
        if (item is WordItem) {
            canvas.drawCircle(colPos.x, colPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
            canvas.drawCircle(colPos.x, colPos.y, buttonRadius * 0.7f, Paint().apply { 
                color = item.tintColor ?: item.strokes.firstOrNull()?.paint?.color ?: Color.BLACK
                style = Paint.Style.FILL 
            })
        }

        // Fill Button (Not for ImageItem anymore, only for WordItem)
        if (item is WordItem) {
            canvas.drawCircle(filPos.x, filPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
            val bgColor = item.backgroundColor
            canvas.drawCircle(filPos.x, filPos.y, buttonRadius * 0.7f, Paint().apply { 
                color = bgColor ?: Color.TRANSPARENT
                style = Paint.Style.FILL 
            })
            if (bgColor == null) {
                val slashPaint = Paint().apply { color = Color.RED; strokeWidth = 3f; style = Paint.Style.STROKE }
                canvas.drawLine(filPos.x - buttonRadius * 0.5f, filPos.y + buttonRadius * 0.5f, filPos.x + buttonRadius * 0.5f, filPos.y - buttonRadius * 0.5f, slashPaint)
            }
        }

        // Toggle Button
        canvas.drawCircle(togPos.x, togPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        val toggleIconPaint = Paint().apply { color = Color.BLACK; textSize = 16f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val tMetrics = toggleIconPaint.fontMetrics
        val tBaseline = togPos.y - (tMetrics.ascent + tMetrics.descent) / 2
        canvas.drawText("T", togPos.x, tBaseline, toggleIconPaint)

        // Image-specific: Background removal
        if (item is ImageItem) {
            val bgTogPos = getButtonScreenPos(localBounds.centerX(), localBounds.top)
            canvas.drawCircle(bgTogPos.x, bgTogPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
            canvas.drawText(if (item.removeBackground) "B" else "W", bgTogPos.x, bgTogPos.y - (tMetrics.ascent + tMetrics.descent) / 2, toggleIconPaint)
        }

        canvas.restore()
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

    private fun drawImageText(canvas: Canvas, item: ImageItem) {
        canvas.save()
        canvas.concat(item.textMatrix)

        val localBounds = if (!item.textBounds.isEmpty) {
            item.textBounds
        } else {
            RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
        }
        
        textPaint.color = Color.BLACK
        
        val text = item.text
        textPaint.textSize = 100f
        val textWidth = textPaint.measureText(text)
        val fontMetrics = textPaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        
        val scaleX = if (textWidth > 0) localBounds.width() / textWidth else 1f
        val scaleY = if (textHeight > 0) localBounds.height() / textHeight else 1f
        val scale = min(scaleX, scaleY) * 0.9f
        
        textPaint.textSize = 100f * scale
        
        val centerX = localBounds.centerX()
        val centerY = localBounds.centerY()
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

        val viewport = getViewportCanvasRect()

        canvas.save()
        canvas.concat(viewMatrix)

        if (notebookType == NotebookType.NOTEBOOK) {
            for (i in 0 until numPages) {
                val top = i * (PAGE_HEIGHT + PAGE_MARGIN)
                val rect = RectF(0f, top, PAGE_WIDTH, top + PAGE_HEIGHT)
                if (RectF.intersects(rect, viewport)) {
                    drawLinesOnRect(canvas, rect)
                }
            }
        } else {
            // Infinite whiteboard lines
            drawLinesOnRect(canvas, viewport, isInfinite = true)
        }

        canvas.restore()
    }

    private val tempLinePaint = Paint()
    private val tempMarginPaint = Paint()

    private fun drawLinesOnRect(canvas: Canvas, rect: RectF, isInfinite: Boolean = false) {
        val lineSpacing = if (notebookType == NotebookType.NOTEBOOK) 50f else 25f
        val gridSpacing = if (notebookType == NotebookType.NOTEBOOK) 40f else 20f
        
        tempLinePaint.set(linePaint)
        tempLinePaint.strokeWidth = 2f / scaleFactor
        
        tempMarginPaint.set(marginPaint)
        tempMarginPaint.strokeWidth = 3f / scaleFactor

        if (backgroundType == BackgroundType.RULED) {
            val firstLineY = (Math.ceil((rect.top / lineSpacing).toDouble()) * lineSpacing).toFloat()
            if (firstLineY.isNaN()) return
            var y = if (isInfinite) firstLineY else max(firstLineY, rect.top + lineSpacing)
            var count = 0
            while (y < rect.bottom && count < 2000) {
                canvas.drawLine(rect.left, y, rect.right, y, tempLinePaint)
                y += lineSpacing
                count++
            }

            val marginX = if (isInfinite) 120f else rect.left + 320f
            if (marginX in rect.left..rect.right) {
                canvas.drawLine(marginX, rect.top, marginX, rect.bottom, tempMarginPaint)
            }
        } else if (backgroundType == BackgroundType.GRAPH) {
            // Horizontal lines
            val firstLineY = (Math.ceil((rect.top / gridSpacing).toDouble()) * gridSpacing).toFloat()
            if (firstLineY.isNaN()) return
            var y = firstLineY
            var countH = 0
            while (y < rect.bottom && countH < 2000) {
                canvas.drawLine(rect.left, y, rect.right, y, tempLinePaint)
                y += gridSpacing
                countH++
            }
            // Vertical lines
            val firstLineX = (Math.ceil((rect.left / gridSpacing).toDouble()) * gridSpacing).toFloat()
            if (firstLineX.isNaN()) return
            var x = firstLineX
            var countV = 0
            while (x < rect.right && countV < 2000) {
                canvas.drawLine(x, rect.top, x, rect.bottom, tempLinePaint)
                x += gridSpacing
                countV++
            }
        }
    }

    private fun getViewportCanvasRect(): RectF {
        if (width <= 0 || height <= 0) return RectF()
        val pts = floatArrayOf(
            0f, 0f, 
            width.toFloat(), 0f, 
            0f, height.toFloat(), 
            width.toFloat(), height.toFloat()
        )
        inverseMatrix.mapPoints(pts)
        var l = pts[0]; var r = pts[0]; var t = pts[1]; var b = pts[1]
        for (i in 2 until pts.size step 2) {
            if (pts[i].isNaN() || pts[i].isInfinite()) continue
            l = min(l, pts[i]); r = max(r, pts[i])
            t = min(t, pts[i+1]); b = max(b, pts[i+1])
        }
        return RectF(l, t, r, b)
    }


    // ══════════════════════════════════════════════════════════════════════
    // Touch handling — multi-touch with draw/pan/zoom
    // ══════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastMotionEvent = event
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val canvasCoords = screenToCanvas(event.x, event.y)
                
                // Check if user tapped the delete cross of the selected image/word
                val selectedItem = selectedImage ?: selectedWord
                if (selectedItem != null) {
                    // TRANSFORM canvasCoords to LOCAL SPACE of the item
                    val localCoords = floatArrayOf(canvasCoords[0], canvasCoords[1])
                    val localInverse = Matrix()
                    
                    val itemMatrix = when(selectedItem) {
                        is ImageItem -> selectedItem.matrix
                        is WordItem -> selectedItem.matrix
                        else -> Matrix()
                    }
                    
                    val touchRadiusSq = 48f * 48f // 48dp target in screen space
                    
                    val localBounds = when(selectedItem) {
                        is ImageItem -> RectF(0f, 0f, selectedItem.bitmap.width.toFloat(), selectedItem.bitmap.height.toFloat())
                        is WordItem -> selectedItem.textBounds
                        else -> selectedItem.bounds
                    }

                    fun isScreenButtonHit(lx: Float, ly: Float): Boolean {
                        val pts = floatArrayOf(lx, ly)
                        val itemTransform = Matrix(itemMatrix)
                        if (selectedItem is WordItem) itemTransform.preConcat(selectedItem.textMatrix)
                        itemTransform.mapPoints(pts)
                        val screenPts = floatArrayOf(pts[0], pts[1])
                        viewMatrix.mapPoints(screenPts)
                        
                        val dx = event.x - screenPts[0]
                        val dy = event.y - screenPts[1]
                        return dx * dx + dy * dy <= touchRadiusSq
                    }

                    // Check delete button (top-right)
                    if (isScreenButtonHit(localBounds.right, localBounds.top)) {
                        deleteSelectedItem()
                        return true
                    }
                    
                    // Check toggle button (top-left)
                    if (isScreenButtonHit(localBounds.left, localBounds.top)) {
                        val oldValue = if (selectedItem is WordItem) selectedItem.isShowingText else if (selectedItem is ImageItem) selectedItem.isShowingText else false
                        val newValue = !oldValue
                        pushAction(StyleAction(selectedItem, "isShowingText", oldValue, newValue))
                        invalidate()
                        return true
                    }

                    // Check background toggle for ImageItem (top-center)
                    if (selectedItem is ImageItem) {
                        if (isScreenButtonHit(localBounds.centerX(), localBounds.top)) {
                            val oldValue = selectedItem.removeBackground
                            val newValue = !oldValue
                            pushAction(StyleAction(selectedItem, "removeBackground", oldValue, newValue))
                            invalidate()
                            return true
                        }
                    }

                    // Check color button (bottom-right)
                    if (selectedItem is WordItem && isScreenButtonHit(localBounds.right, localBounds.bottom)) {
                        onShowItemColorPicker?.invoke(selectedItem)
                        return true
                    }

                    // Check fill color button (bottom-left)
                    if (selectedItem is WordItem && isScreenButtonHit(localBounds.left, localBounds.bottom)) {
                        showItemColorPicker(selectedItem, isBackground = true)
                        return true
                    }

                    // Check rotation handle (lollipop)
                    // We need to calculate the screen position of the handle
                    val rotBasePts = floatArrayOf(localBounds.centerX(), localBounds.top)
                    val itemTransform = Matrix(itemMatrix)
                    if (selectedItem is WordItem) itemTransform.preConcat(selectedItem.textMatrix)
                    itemTransform.mapPoints(rotBasePts)
                    val rotBaseScreen = floatArrayOf(rotBasePts[0], rotBasePts[1])
                    viewMatrix.mapPoints(rotBaseScreen)
                    
                    val upVec = floatArrayOf(0f, -1f)
                    itemTransform.mapVectors(upVec)
                    viewMatrix.mapVectors(upVec)
                    val upLen = Math.sqrt((upVec[0] * upVec[0] + upVec[1] * upVec[1]).toDouble()).toFloat()
                    val ux = upVec[0] / upLen
                    val uy = upVec[1] / upLen
                    val rotScreenX = rotBaseScreen[0] + ux * 60f
                    val rotScreenY = rotBaseScreen[1] + uy * 60f
                    
                    val dRotX = event.x - rotScreenX
                    val dRotY = event.y - rotScreenY
                    if (dRotX * dRotX + dRotY * dRotY <= touchRadiusSq) {
                        isRotating = true
                        isImageManipulating = false
                        isWordManipulating = false
                        isDrawing = false
                        isPanning = false
                        
                        // Calculate center in canvas space and cache it
                        val center = getItemCanvasCenter(selectedItem)
                        rotationCenter.set(center.x, center.y)
                        rotationInitialAngle = Math.toDegrees(atan2((canvasCoords[1] - center.y).toDouble(), (canvasCoords[0] - center.x).toDouble())).toFloat()
                        
                        initialMatrix.set(itemMatrix)
                        beforeTransformState = captureState(selectedItem)
                        return true
                    }
                }
                
                // Hit testing for images and words
                val hitItem = drawItems.reversed().find { isItemHit(it, canvasCoords[0], canvasCoords[1]) }

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
                        beforeTransformState = captureState(hitItem)
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
                        beforeTransformState = captureState(hitItem)
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
                
                val currentSelectedItem = selectedImage ?: selectedWord
                if (currentSelectedItem != null && pointerCount >= 2) {
                    isTwoFingerManipulating = true
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    twoFingerStartSpan = max(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                    twoFingerStartAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    twoFingerStartMatrix.set(when(currentSelectedItem) {
                        is ImageItem -> currentSelectedItem.matrix
                        is WordItem -> currentSelectedItem.matrix
                        else -> Matrix()
                    })
                    val focal = screenToCanvas((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
                    twoFingerStartFocal.set(focal[0], focal[1])
                    
                    beforeTransformState = captureState(currentSelectedItem)
                    isPanning = false
                } else if (!isImageManipulating && !isWordManipulating) {
                    isPanning = true
                    lastPanX = event.x
                    lastPanY = event.y
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val currentSelectedItem = selectedImage ?: selectedWord
                if (isRotating && currentSelectedItem != null) {
                    val targetItem = currentSelectedItem
                    val canvasCoords = screenToCanvas(event.x, event.y)
                    val currentAngle = Math.toDegrees(atan2((canvasCoords[1] - rotationCenter.y).toDouble(), (canvasCoords[0] - rotationCenter.x).toDouble())).toFloat()
                    val rotationDelta = currentAngle - rotationInitialAngle
                    
                    val targetMatrix = when(targetItem) {
                        is ImageItem -> targetItem.matrix
                        is WordItem -> targetItem.matrix
                        else -> Matrix()
                    }
                    
                    // Rotate around center relative to initial state
                    targetMatrix.set(initialMatrix)
                    targetMatrix.postRotate(rotationDelta, rotationCenter.x, rotationCenter.y)
                    invalidate()
                } else if (isTwoFingerManipulating && currentSelectedItem != null && pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    val currentSpan = max(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                    val currentAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val focal = screenToCanvas((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
                    
                    val scale = currentSpan / twoFingerStartSpan
                    val rotationDelta = currentAngle - twoFingerStartAngle
                    val transX = focal[0] - twoFingerStartFocal.x
                    val transY = focal[1] - twoFingerStartFocal.y
                    
                    val targetMatrix = when(currentSelectedItem) {
                        is ImageItem -> currentSelectedItem.matrix
                        is WordItem -> currentSelectedItem.matrix
                        else -> Matrix()
                    }
                    
                    val tempMatrix = Matrix(twoFingerStartMatrix)
                    tempMatrix.postTranslate(transX, transY)
                    tempMatrix.postScale(scale, scale, focal[0], focal[1])
                    tempMatrix.postRotate(rotationDelta, focal[0], focal[1])
                    
                    targetMatrix.set(tempMatrix)
                    invalidate()
                } else if ((isImageManipulating && selectedImage != null || isWordManipulating && selectedWord != null) && pointerCount == 1) {
                    // Single finger translate
                    val targetMatrix = selectedImage?.matrix ?: selectedWord!!.matrix
                    val canvasCoords = screenToCanvas(event.x, event.y)
                    val lastCanvasCoords = screenToCanvas(lastPanX, lastPanY)
                    val dx = canvasCoords[0] - lastCanvasCoords[0]
                    val dy = canvasCoords[1] - lastCanvasCoords[1]
                    
                    targetMatrix.postTranslate(dx, dy)
                    (selectedImage ?: selectedWord)?.invalidateCache()
                    
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                } else if (isPanning && pointerCount >= 2 && !isImageManipulating && !isWordManipulating) {
                    if (!scaleDetector.isInProgress) {
                        // Boost panning speed for more responsive feel
                        val dx = (event.x - lastPanX) * 1.8f
                        val dy = (event.y - lastPanY) * 1.8f
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
                }
                if ((isRotating || isTwoFingerManipulating) && selectedWord != null) {
                    freezeWordTransform(selectedWord!!)
                }

                // Push transform action if anything was manipulated
                if (isImageManipulating || isWordManipulating || isRotating || isTwoFingerManipulating) {
                    val currentSelectedItem = selectedImage ?: selectedWord
                    if (currentSelectedItem != null && beforeTransformState != null) {
                        val afterState = captureState(currentSelectedItem)
                        if (afterState != beforeTransformState) {
                            pushAction(TransformAction(currentSelectedItem, beforeTransformState!!, afterState), executeNow = false)
                            onStrokeCompleted?.invoke()
                        }
                    }
                }

                isPanning = false
                isImageManipulating = false
                isWordManipulating = false
                isRotating = false
                isTwoFingerManipulating = false
                beforeTransformState = null
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isTwoFingerManipulating) {
                    if (selectedWord != null) {
                        freezeWordTransform(selectedWord!!)
                    }
                    val currentSelectedItem = selectedImage ?: selectedWord
                    if (currentSelectedItem != null && beforeTransformState != null) {
                        val afterState = captureState(currentSelectedItem)
                        if (afterState != beforeTransformState) {
                            pushAction(TransformAction(currentSelectedItem, beforeTransformState!!, afterState), executeNow = false)
                            onStrokeCompleted?.invoke()
                        }
                    }
                    isTwoFingerManipulating = false
                    beforeTransformState = null
                }
                if (pointerCount <= 2 && !isImageManipulating && !isWordManipulating) {
                    isPanning = true
                }
            }
        }
        return true
    }

    private var hasErasedLooseStrokes = false
    private val erasedWordsDuringStroke = mutableSetOf<WordItem>()

    private fun touchStart(x: Float, y: Float) {
        if (isEraser) {
            erasedWordsDuringStroke.clear()
            hasErasedLooseStrokes = false
            eraseIntersectingItems(x, y)
            lastX = x
            lastY = y
            return
        }
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
        if (isEraser) {
            val dx = x - lastX
            val dy = y - lastY
            // Only erase if we moved a bit, to avoid too many checks, though every move is fine.
            if (abs(dx) >= TOUCH_TOLERANCE / scaleFactor || abs(dy) >= TOUCH_TOLERANCE / scaleFactor) {
                eraseIntersectingItems(x, y)
                lastX = x
                lastY = y
            }
            return
        }
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
        if (isEraser) {
            if (hasErasedLooseStrokes) {
                onStrokeCompleted?.invoke()
            }
            for (word in erasedWordsDuringStroke) {
                onWordModified?.invoke(word)
            }
            erasedWordsDuringStroke.clear()
            hasErasedLooseStrokes = false
            return
        }
        currentPath.lineTo(lastX, lastY)
        currentPoints.add(PointF(lastX, lastY))

        // --- Smoothing ---
        val smoothedCommands = if (currentPoints.size >= 2) {
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

        val strokeItem = StrokeItem(smoothedPath, strokePaint, bounds, smoothedCommands, isEraser)
        pushAction(AddItemAction(strokeItem))
        onStrokeCompleted?.invoke()

        currentPath = Path()
        currentCommands = mutableListOf()
        currentPoints.clear()
    }

    private fun smoothPoints(points: List<PointF>): List<PathCommand> {
        if (points.size < 3) {
            val p0 = points[0]
            val p1 = if (points.size > 1) points[1] else p0
            
            // If the points are identical (a tap), add a tiny offset to ensure visibility
            val finalP1 = if (p0.x == p1.x && p0.y == p1.y) {
                PointF(p1.x + 0.1f, p1.y)
            } else p1
            
            return listOf(PathCommand.MoveTo(p0.x, p0.y), PathCommand.LineTo(finalP1.x, finalP1.y))
        }

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
    fun addImage(bitmap: Bitmap): ImageItem {
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
        pushAction(AddItemAction(imageItem))
        selectedImage = imageItem
        invalidate()
        onStrokeCompleted?.invoke()
        return imageItem
    }

    fun deleteSelectedItem(): Boolean {
        val image = selectedImage
        if (image != null) {
            val index = drawItems.indexOf(image)
            if (index != -1) {
                pushAction(RemoveItemAction(image, index))
                selectedImage = null
                isImageManipulating = false
                invalidate()
                onStrokeCompleted?.invoke()
                return true
            }
        }
        val word = selectedWord
        if (word != null) {
            val index = drawItems.indexOf(word)
            if (index != -1) {
                pushAction(RemoveItemAction(word, index))
                selectedWord = null
                isWordManipulating = false
                invalidate()
                onStrokeCompleted?.invoke()
                return true
            }
        }
        return false
    }

    @Deprecated("Use deleteSelectedItem instead", ReplaceWith("deleteSelectedItem()"))
    fun deleteSelectedImage() = deleteSelectedItem()

    fun groupStrokesIntoWord(strokesToGroup: List<StrokeItem>, text: String, wordsToMerge: List<WordItem> = emptyList()): WordItem? {
        val stillInCanvasLoose = strokesToGroup.filter { it in drawItems }
        val stillInCanvasWords = wordsToMerge.filter { it in drawItems }
        
        if (stillInCanvasLoose.isEmpty() && stillInCanvasWords.isEmpty()) return null
        
        val finalStrokes = mutableListOf<StrokeItem>()
        finalStrokes.addAll(stillInCanvasLoose)
        for (word in stillInCanvasWords) {
            finalStrokes.addAll(dissolveWordToStrokes(word))
        }

        // Calculate initial text orientation and bounds
        val (writingAngle, center) = calculateWritingAngle(finalStrokes)
        val textBounds = calculateRotatedBounds(finalStrokes, writingAngle, center)
        val textMatrix = Matrix()
        textMatrix.postRotate(writingAngle, center.x, center.y)

        // Create WordItem with identity matrix (strokes are already in canvas space)
        val wordItem = WordItem(finalStrokes, Matrix(), text, false, textMatrix, textBounds)
        
        // Preserve tint/background color from the first merged word
        stillInCanvasWords.firstOrNull()?.let { firstWord ->
            wordItem.tintColor = firstWord.tintColor
            wordItem.backgroundColor = firstWord.backgroundColor
        }

        pushAction(GroupAction(stillInCanvasLoose, stillInCanvasWords, wordItem))
        invalidate()
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
        if (undoStack.isNotEmpty()) {
            val action = undoStack.removeLast()
            action.undo()
            redoStack.add(action)
            onStateChanged?.invoke()
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val action = redoStack.removeLast()
            action.redo()
            undoStack.add(action)
            onStateChanged?.invoke()
            invalidate()
        }
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    // --- Clear ---
    fun clear() {
        drawItems.clear()
        undoStack.clear()
        redoStack.clear()
        currentPath = Path()
        initialBitmap = null
        selectedImage = null
        selectedWord = null
        searchHighlightedWord = null
        scaleFactor = 1.0f
        translateX = -120f
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

    fun loadFromSvgDataWithOffset(items: List<SvgData>, dy: Float) {
        val loadedItems = mutableListOf<CanvasItem>()
        for (data in items) {
            val item = when (data) {
                is StrokeData -> {
                    val path = Path()
                    for (cmd in data.commands) {
                        when (cmd) {
                            is PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                            is PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                            is PathCommand.CubicTo -> path.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
                            is PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                        }
                    }
                    path.offset(0f, dy)
                    val paint = Paint().apply {
                        color = data.color
                        strokeWidth = data.strokeWidth
                        alpha = data.opacity
                        style = Paint.Style.STROKE
                        strokeJoin = Paint.Join.ROUND
                        strokeCap = Paint.Cap.ROUND
                        isAntiAlias = true
                    }
                    val bounds = RectF()
                    path.computeBounds(bounds, true)
                    val halfWidth = paint.strokeWidth / 2f
                    bounds.inset(-halfWidth, -halfWidth)
                    
                    // Also shift the commands for saving back later
                    val shiftedCommands = data.commands.map { cmd ->
                        when (cmd) {
                            is PathCommand.MoveTo -> PathCommand.MoveTo(cmd.x, cmd.y + dy)
                            is PathCommand.QuadTo -> PathCommand.QuadTo(cmd.x1, cmd.y1 + dy, cmd.x2, cmd.y2 + dy)
                            is PathCommand.CubicTo -> PathCommand.CubicTo(cmd.x1, cmd.y1 + dy, cmd.x2, cmd.y2 + dy, cmd.x3, cmd.y3 + dy)
                            is PathCommand.LineTo -> PathCommand.LineTo(cmd.x, cmd.y + dy)
                        }
                    }
                    
                    StrokeItem(path, paint, bounds, shiftedCommands, data.isEraser)
                }
                is ImageData -> {
                    val decodedString = android.util.Base64.decode(data.base64, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                    if (bmp != null) {
                        val matrix = Matrix()
                        matrix.setValues(data.matrix)
                        matrix.postTranslate(0f, dy)
                        
                        val textMatrix = Matrix()
                        textMatrix.setValues(data.textMatrix)
                        // Note: textMatrix is usually local to the image, but if it has translation it might need shifting?
                        // Actually, textMatrix in ImageItem is usually local.
                        
                        ImageItem(bmp, matrix, data.removeBackground, data.text, data.isShowingText, textMatrix, data.textBounds?.let { RectF(it.left, it.top, it.right, it.bottom) } ?: RectF())
                    } else null
                }
                is WordData -> {
                    val strokes = mutableListOf<StrokeItem>()
                    for (sData in data.strokes) {
                        val path = Path()
                        for (cmd in sData.commands) {
                            when (cmd) {
                                is PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                                is PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                                is PathCommand.CubicTo -> path.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
                                is PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                            }
                        }
                        // Word strokes are in local space? No, usually canvas space.
                        // Wait, WordItem.matrix handles the transform.
                        // Let's assume strokes are local to the word if matrix is used.
                        // Actually, looking at groupStrokesIntoWord, strokes are already in canvas space and matrix is identity.
                        
                        val paint = Paint().apply {
                            color = sData.color; strokeWidth = sData.strokeWidth; alpha = sData.opacity
                            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
                        }
                        val bounds = RectF()
                        path.computeBounds(bounds, true)
                        strokes.add(StrokeItem(path, paint, bounds, sData.commands, sData.isEraser))
                    }
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    matrix.postTranslate(0f, dy)
                    
                    val textMatrix = Matrix()
                    textMatrix.setValues(data.textMatrix)
                    
                    WordItem(strokes, matrix, data.text, data.isShowingText, textMatrix, data.textBounds?.let { RectF(it.left, it.top, it.right, it.bottom) } ?: RectF(), data.tintColor, data.backgroundColor)
                }
                else -> null
            }
            item?.let { loadedItems.add(it) }
        }
        drawItems.addAll(loadedItems)
        invalidate()
    }

    fun hasItemsOnPage(pageIndex: Int): Boolean {
        val top = pageIndex * (PAGE_HEIGHT + PAGE_MARGIN)
        val bottom = top + PAGE_HEIGHT
        val pageRect = RectF(0f, top, PAGE_WIDTH, bottom)
        return drawItems.any { RectF.intersects(it.bounds, pageRect) }
    }

    fun getItemsOnPage(pageIndex: Int): List<CanvasItem> {
        val top = pageIndex * (PAGE_HEIGHT + PAGE_MARGIN)
        val bottom = top + PAGE_HEIGHT
        return drawItems.filter { item ->
            val b = item.bounds
            b.centerY() in top..bottom
        }
    }

    fun getShiftedItemsOnPage(pageIndex: Int): List<CanvasItem> {
        val dy = -(pageIndex * (PAGE_HEIGHT + PAGE_MARGIN))
        return getItemsOnPage(pageIndex).map { item ->
            when (item) {
                is StrokeItem -> {
                    val newPath = Path(item.path)
                    newPath.offset(0f, dy)
                    val newBounds = RectF(item.boundsRect)
                    newBounds.offset(0f, dy)
                    val newCommands = item.commands.map { cmd ->
                        when (cmd) {
                            is PathCommand.MoveTo -> PathCommand.MoveTo(cmd.x, cmd.y + dy)
                            is PathCommand.LineTo -> PathCommand.LineTo(cmd.x, cmd.y + dy)
                            is PathCommand.QuadTo -> PathCommand.QuadTo(cmd.x1, cmd.y1 + dy, cmd.x2, cmd.y2 + dy)
                            is PathCommand.CubicTo -> PathCommand.CubicTo(cmd.x1, cmd.y1 + dy, cmd.x2, cmd.y2 + dy, cmd.x3, cmd.y3 + dy)
                        }
                    }
                    item.copy(path = newPath, boundsRect = newBounds, commands = newCommands)
                }
                is ImageItem -> {
                    val newMatrix = Matrix(item.matrix)
                    newMatrix.postTranslate(0f, dy)
                    item.copy(matrix = newMatrix)
                }
                is WordItem -> {
                    val newMatrix = Matrix(item.matrix)
                    newMatrix.postTranslate(0f, dy)
                    item.copy(matrix = newMatrix)
                }
            }
        }
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
        
        val bmp = createBitmapForStrokes(strokes) ?: return null
        
        return ClusterResult(bmp, strokes, mergedWords, cropRect)
    }

    fun createBitmapForStrokes(strokes: List<StrokeItem>): Bitmap? {
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

        val maxDim = 1024
        var finalWidth = cropWidth
        var finalHeight = cropHeight
        var scale = 1f
        if (finalWidth > maxDim || finalHeight > maxDim) {
            scale = maxDim.toFloat() / Math.max(finalWidth, finalHeight)
            finalWidth = (finalWidth * scale).toInt()
            finalHeight = (finalHeight * scale).toInt()
        }

        if (finalWidth < 1 || finalHeight < 1) return null
        val bmp = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(canvasBackgroundColor)
        
        c.scale(scale, scale)
        c.translate(-left, -top)
        
        for (stroke in strokes) {
            c.drawPath(stroke.path, stroke.paint)
        }
        
        return bmp
    }
    // Removed create bitmap logic since it's now in createBitmapForStrokes


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

    fun dissolveWordToStrokes(word: WordItem): List<StrokeItem> {
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
                        opacity = item.paint.alpha
                    )
                }
                is ImageItem -> {
                    val stream = ByteArrayOutputStream()
                    item.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    val m = FloatArray(9)
                    item.matrix.getValues(m)
                    
                    val tm = FloatArray(9)
                    item.textMatrix.getValues(tm)
                    val tb = FloatRect(item.textBounds.left, item.textBounds.top, item.textBounds.right, item.textBounds.bottom)
                    
                    ImageData(base64, m, item.removeBackground, item.text, item.isShowingText, tm, tb)
                }
                is WordItem -> {
                    val strokeDataList = item.strokes.map {
                        StrokeData(
                            commands = it.commands,
                            color = it.paint.color,
                            strokeWidth = it.paint.strokeWidth,
                            opacity = it.paint.alpha
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
                            
                            val textMatrix = Matrix()
                            if (data.textMatrix != null) {
                                textMatrix.setValues(data.textMatrix)
                            }
                            
                            val textBounds = RectF()
                            data.textBounds?.let {
                                textBounds.set(it.left, it.top, it.right, it.bottom)
                            }
                            
                            drawItems.add(ImageItem(bitmap, matrix, data.removeBackground, data.text, data.isShowingText, textMatrix, textBounds))
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
                        if (item is WordItem) {
                            val oldValue = item.backgroundColor
                            pushAction(StyleAction(item, "backgroundColor", oldValue, color))
                        }
                    } else {
                        if (item is WordItem) {
                            val oldValue = item.tintColor
                            pushAction(StyleAction(item, "tintColor", oldValue, color))
                        }
                    }
                    this@DrawingView.postInvalidate()
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

    fun getItemAtIndex(index: Int): CanvasItem? = drawItems.getOrNull(index)

    fun getContentBounds(): RectF? {
        if (drawItems.isEmpty()) return null
        val result = RectF()
        var first = true
        for (item in drawItems) {
            val b = item.bounds
            if (b.isEmpty) continue
            if (first) {
                result.set(b)
                first = false
            } else {
                result.union(b)
            }
        }
        return if (first) null else result
    }

    fun CanvasItem.toSvgData(): SvgData? {
        return when (this) {
            is StrokeItem -> StrokeData(commands, paint.color, paint.strokeWidth, paint.alpha, isEraser)
            is ImageItem -> {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                val m = FloatArray(9); matrix.getValues(m)
                val tm = FloatArray(9); textMatrix.getValues(tm)
                val tb = FloatRect(textBounds.left, textBounds.top, textBounds.right, textBounds.bottom)
                ImageData(base64, m, removeBackground, text, isShowingText, tm, tb)
            }
            is WordItem -> {
                val sData = strokes.map { StrokeData(it.commands, it.paint.color, it.paint.strokeWidth, it.paint.alpha, it.isEraser) }
                val m = FloatArray(9); matrix.getValues(m)
                val tm = FloatArray(9); textMatrix.getValues(tm)
                val tb = FloatRect(textBounds.left, textBounds.top, textBounds.right, textBounds.bottom)
                WordData(sData, m, text, isShowingText, tm, tb, tintColor, backgroundColor)
            }
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
        }

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val halfWidth = paint.strokeWidth / 2f
        bounds.inset(-halfWidth, -halfWidth)

        return StrokeItem(path, paint, bounds, data.commands)
    }
    private fun getPointersAngle(event: MotionEvent?): Float {
        if (event == null || event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun getItemCanvasCenter(item: CanvasItem): PointF {
        val localBounds = when (item) {
            is ImageItem -> RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
            is WordItem -> item.textBounds
            else -> item.bounds
        }
        val center = floatArrayOf(localBounds.centerX(), localBounds.centerY())
        val matrix = when (item) {
            is ImageItem -> item.matrix
            is WordItem -> {
                val m = Matrix(item.matrix)
                m.preConcat(item.textMatrix)
                m
            }
            else -> Matrix()
        }
        matrix.mapPoints(center)
        return PointF(center[0], center[1])
    }

    private fun getItemRotation(item: CanvasItem): Float {
        val matrix = when (item) {
            is ImageItem -> item.matrix
            is WordItem -> {
                val m = Matrix(item.matrix)
                m.preConcat(item.textMatrix)
                m
            }
            else -> Matrix()
        }
        val v = FloatArray(9)
        matrix.getValues(v)
        val r = Math.toDegrees(atan2(v[Matrix.MSKEW_X].toDouble(), v[Matrix.MSCALE_X].toDouble())).toFloat()
        return -r
    }

    private fun isItemHit(item: CanvasItem, canvasX: Float, canvasY: Float): Boolean {
        val localCoords = floatArrayOf(canvasX, canvasY)
        val inv = Matrix()
        val matrix = when(item) {
            is ImageItem -> item.matrix
            is WordItem -> {
                val m = Matrix(item.matrix)
                m.preConcat(item.textMatrix)
                m
            }
            else -> Matrix()
        }
        matrix.invert(inv)
        inv.mapPoints(localCoords)
        
        val localBounds = when(item) {
            is ImageItem -> RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
            is WordItem -> item.textBounds
            else -> item.bounds
        }
        
        // Slightly expand hit area for thin strokes
        val hitBounds = RectF(localBounds)
        if (item is WordItem) hitBounds.inset(-20f, -20f)
        
        return hitBounds.contains(localCoords[0], localCoords[1])
    }

    inner class ModifyWordStrokesAction(
        val word: WordItem,
        val oldStrokes: List<StrokeItem>,
        val newStrokes: List<StrokeItem>
    ) : UndoAction {
        override fun undo() { word.strokes = oldStrokes; word.invalidateCache(); invalidate() }
        override fun redo() { word.strokes = newStrokes; word.invalidateCache(); invalidate() }
    }

    private fun eraseIntersectingItems(canvasX: Float, canvasY: Float) {
        val eraserRadius = brushSize * 1.5f
        val itemsToRemove = mutableListOf<CanvasItem>()
        val wordsToModify = mutableListOf<Pair<WordItem, StrokeItem>>()
        
        for (item in drawItems.reversed()) {
            if (item is StrokeItem) {
                if (isStrokeHit(item, canvasX, canvasY, eraserRadius)) {
                    itemsToRemove.add(item)
                }
            } else if (item is WordItem) {
                val strokeInv = Matrix()
                item.matrix.invert(strokeInv)
                val strokeLocal = floatArrayOf(canvasX, canvasY)
                strokeInv.mapPoints(strokeLocal)
                
                var hitStroke: StrokeItem? = null
                for (stroke in item.strokes) {
                    if (isStrokeHit(stroke, strokeLocal[0], strokeLocal[1], eraserRadius)) {
                        hitStroke = stroke
                        break
                    }
                }
                if (hitStroke != null) {
                    wordsToModify.add(Pair(item, hitStroke))
                }
            } else if (item is ImageItem) {
                if (isItemHit(item, canvasX, canvasY)) {
                    itemsToRemove.add(item)
                }
            }
        }
        
        var changed = false
        if (itemsToRemove.isNotEmpty()) {
            for (item in itemsToRemove) {
                val index = drawItems.indexOf(item)
                if (index != -1) {
                    pushAction(RemoveItemAction(item, index), executeNow = true)
                    changed = true
                    if (item is StrokeItem) hasErasedLooseStrokes = true
                }
            }
        }

        if (wordsToModify.isNotEmpty()) {
            for ((word, hitStroke) in wordsToModify) {
                val newStrokes = word.strokes.filter { it != hitStroke }
                if (newStrokes.isEmpty()) {
                    val index = drawItems.indexOf(word)
                    if (index != -1) {
                        pushAction(RemoveItemAction(word, index), executeNow = true)
                        erasedWordsDuringStroke.remove(word)
                        changed = true
                    }
                } else {
                    pushAction(ModifyWordStrokesAction(word, word.strokes, newStrokes), executeNow = true)
                    word.strokes = newStrokes
                    word.invalidateCache()
                    erasedWordsDuringStroke.add(word)
                    changed = true
                }
            }
        }
        
        if (changed) {
            invalidate()
        }
    }

    private fun isStrokeHit(item: StrokeItem, x: Float, y: Float, radius: Float): Boolean {
        if (!RectF.intersects(item.bounds, RectF(x - radius, y - radius, x + radius, y + radius))) return false
        
        var lastPt: PointF? = null
        for (cmd in item.commands) {
            val pt = when (cmd) {
                is PathCommand.MoveTo -> PointF(cmd.x, cmd.y)
                is PathCommand.LineTo -> PointF(cmd.x, cmd.y)
                is PathCommand.QuadTo -> PointF(cmd.x2, cmd.y2)
                is PathCommand.CubicTo -> PointF(cmd.x3, cmd.y3)
            }
            if (lastPt != null) {
                if (distToSegment(x, y, lastPt.x, lastPt.y, pt.x, pt.y) <= radius) return true
            } else {
                if (Math.hypot((x - pt.x).toDouble(), (y - pt.y).toDouble()) <= radius) return true
            }
            lastPt = pt
        }
        return false
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val l2 = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
        if (l2 == 0f) return Math.hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
        var t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2
        t = Math.max(0f, Math.min(1f, t))
        val projX = x1 + t * (x2 - x1)
        val projY = y1 + t * (y2 - y1)
        return Math.hypot((px - projX).toDouble(), (py - projY).toDouble()).toFloat()
    }
}
