package com.drawapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import android.util.Base64
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Layout

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    enum class BackgroundType { NONE, RULED, GRAPH }
    enum class ActiveTool { BRUSH, ERASER, LASSO, SELECT }

    var activeTool: ActiveTool = ActiveTool.SELECT
        set(value) {
            field = value
            isEraser = (value == ActiveTool.ERASER)
            if (value != ActiveTool.LASSO && value != ActiveTool.SELECT) {
                clearLassoSelection()
            }
            updateCurrentPaint()
            invalidate()
        }


    // Background color
    var canvasBackgroundColor: Int = Color.parseColor("#FDFCF5")
    var backgroundType: BackgroundType = BackgroundType.RULED
    var notebookType: NotebookType = NotebookType.NOTEBOOK
    var numPages: Int = 1
    var hiddenItem: CanvasItem? = null

    // --- Page Constants (for Notebook mode) ---
    val PAGE_WIDTH = 2800f
    val PAGE_HEIGHT = 3960f
    val PAGE_MARGIN = 168f

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
        var isLocked: Boolean = false
        open fun invalidateCache() {}
        abstract fun toSvgData(): SvgData
    }

    data class StrokeItem(
        val path: Path,
        val paint: Paint,
        val boundsRect: RectF,
        val commands: List<PathCommand> = emptyList(),
        val isEraser: Boolean = false
    ) : CanvasItem() {
        override val bounds: RectF get() = boundsRect
        override fun toSvgData(): SvgData = StrokeData(
            commands = commands,
            color = paint.color,
            strokeWidth = paint.strokeWidth,
            opacity = paint.alpha,
            isEraser = isEraser,
            isLocked = isLocked
        )
    }

    data class ImageItem(
        val bitmap: Bitmap,
        var matrix: Matrix,
        var removeBackground: Boolean = false,
        var text: String = "",
        var isShowingText: Boolean = false,
        var textMatrix: Matrix = Matrix(),
        var textBounds: RectF = RectF(),
        var pdfWords: List<PdfHelper.PdfWord> = emptyList(),
        var isAiRecognized: Boolean = false
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
                        // If it's very bright (common for PDF/image backgrounds), make it transparent
                        if (r > 220 && g > 220 && b > 220) {
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

        override fun toSvgData(): SvgData {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            val mValues = FloatArray(9)
            matrix.getValues(mValues)
            val tmValues = FloatArray(9)
            textMatrix.getValues(tmValues)
            return ImageData(
                base64 = base64,
                matrix = mValues,
                isShowingText = isShowingText,
                textMatrix = tmValues,
                textBounds = FloatRect(textBounds.left, textBounds.top, textBounds.right, textBounds.bottom),
                isLocked = isLocked
            )
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
                } else if (!textBounds.isEmpty) {
                    r.set(textBounds)
                }
                matrix.mapRect(r)
                cachedBounds = r
                return r
            }

        override fun toSvgData(): SvgData {
            val mValues = FloatArray(9)
            matrix.getValues(mValues)
            val tmValues = FloatArray(9)
            textMatrix.getValues(tmValues)
            return WordData(
                strokes = strokes.map { it.toSvgData() as StrokeData },
                matrix = mValues,
                text = text,
                isShowingText = isShowingText,
                textMatrix = tmValues,
                textBounds = FloatRect(textBounds.left, textBounds.top, textBounds.right, textBounds.bottom),
                tintColor = tintColor,
                backgroundColor = backgroundColor,
                isLocked = isLocked
            )
        }
        
        override fun invalidateCache() {
            cachedBounds = null
        }
    }

    data class PromptItem(
        var prompt: String = "",
        var result: String = "",
        var isShowingResult: Boolean = false,
        var audioFilePath: String? = null,  // Path to attached audio file
        var audioTranscription: String? = null,  // Cached transcription
        val matrix: Matrix = Matrix(),
        var width: Float = 1200f,
        var height: Float = 600f
    ) : CanvasItem() {
        override val bounds: RectF
            get() {
                val r = RectF(0f, 0f, width, height)
                matrix.mapRect(r)
                return r
            }

        override fun toSvgData(): SvgData {
            val mValues = FloatArray(9)
            matrix.getValues(mValues)
            return PromptData(
                prompt = prompt,
                result = result,
                isShowingResult = isShowingResult,
                matrix = mValues,
                width = width,
                height = height,
                isLocked = isLocked
            )
        }

        fun hasAudio(): Boolean = !audioFilePath.isNullOrEmpty()
    }

    data class TextItem(
        var text: String = "",
        val matrix: Matrix = Matrix(),
        var color: Int = Color.BLACK,
        var fontSize: Float = 48f,
        var width: Float = 400f,
        var height: Float = 100f
    ) : CanvasItem() {
        override val bounds: RectF
            get() {
                val r = RectF(0f, 0f, width, height)
                matrix.mapRect(r)
                return r
            }

        override fun toSvgData(): SvgData {
            val mValues = FloatArray(9)
            matrix.getValues(mValues)
            return TextData(
                text = text,
                matrix = mValues,
                color = color,
                fontSize = fontSize,
                width = width,
                height = height,
                isLocked = isLocked
            )
        }
    }

    data class ClusterResult(
        val bitmap: Bitmap,
        val strokes: List<StrokeItem>,
        val mergedWords: List<WordItem>,
        val cropRect: RectF
    )

    data class DetectedBox(val text: String, val ymin: Float, val xmin: Float, val ymax: Float, val xmax: Float)


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

    inner class FusedAutoGroupAction(
        val lastStrokeAction: UndoAction,
        val groupAction: UndoAction
    ) : UndoAction {
        override fun undo() {
            groupAction.undo()
            lastStrokeAction.undo()
        }
        override fun redo() {
            lastStrokeAction.redo()
            groupAction.redo()
        }
    }

    inner class GroupTransformAction(
        val items: List<CanvasItem>,
        val oldStates: List<ItemState>,
        val newStates: List<ItemState>
    ) : UndoAction {
        override fun undo() {
            for (i in items.indices) {
                applyState(items[i], oldStates[i])
            }
            invalidate()
        }
        override fun redo() {
            for (i in items.indices) {
                applyState(items[i], newStates[i])
            }
            invalidate()
        }
    }

    data class ItemState(
        val matrix: Matrix,
        val textMatrix: Matrix,
        val textBounds: RectF,
        val strokes: List<StrokeItem>? = null,
        val strokePath: Path? = null,
        val strokeBounds: RectF? = null,
        val prompt: String? = null,
        val result: String? = null,
        val isShowingResult: Boolean? = null
    )

    private fun captureState(item: CanvasItem): ItemState {
        return when (item) {
            is ImageItem -> ItemState(Matrix(item.matrix), Matrix(item.textMatrix), RectF(item.textBounds))
            is WordItem -> ItemState(Matrix(item.matrix), Matrix(item.textMatrix), RectF(item.textBounds), item.strokes.map { it.copy() })
            is StrokeItem -> ItemState(Matrix(), Matrix(), RectF(), null, Path(item.path), RectF(item.boundsRect))
            is PromptItem -> ItemState(Matrix(item.matrix), Matrix(), RectF(), null, null, null, item.prompt, item.result, item.isShowingResult)
            is TextItem -> ItemState(Matrix(item.matrix), Matrix(), RectF(), null, null, null, item.text, null, null)
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
            is StrokeItem -> {
                state.strokePath?.let { item.path.set(it) }
                state.strokeBounds?.let { item.boundsRect.set(it) }
            }
            is PromptItem -> {
                item.matrix.set(state.matrix)
                state.prompt?.let { item.prompt = it }
                state.result?.let { item.result = it }
                state.isShowingResult?.let { item.isShowingResult = it }
            }
            is TextItem -> {
                item.matrix.set(state.matrix)
                state.prompt?.let { item.text = it } // Using prompt field for text
            }
        }
        item.invalidateCache()
    }

    private fun applyStyle(item: CanvasItem, property: String, value: Any?) {
        when (property) {
            "tintColor" -> {
                if (item is WordItem) item.tintColor = value as Int?
                if (item is StrokeItem) item.paint.color = (value as? Int) ?: Color.BLACK
            }
            "backgroundColor" -> if (item is WordItem) item.backgroundColor = value as Int?
            "isShowingText" -> {
                if (item is WordItem) item.isShowingText = value as Boolean
                if (item is ImageItem) item.isShowingText = value as Boolean
            }
            "removeBackground" -> if (item is ImageItem) {
                item.removeBackground = value as Boolean
                item.invalidateCache()
            }
            "fontSize" -> if (item is TextItem) {
                item.fontSize = value as Float
                item.invalidateCache()
            }
            "color" -> if (item is TextItem) {
                item.color = value as Int
            }
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

    var brushSize: Float = 8.4f
        set(value) { field = value; updateCurrentPaint() }


    var brushOpacity: Int = 255
        set(value) { field = value; updateCurrentPaint() }

    var isEraser: Boolean = false
        set(value) { 
            field = value
            if (value && activeTool != ActiveTool.ERASER) {
                activeTool = ActiveTool.ERASER
            } else if (!value && activeTool == ActiveTool.ERASER) {
                activeTool = ActiveTool.BRUSH
            }
            updateCurrentPaint() 
        }

    // --- Lasso Selection ---
    private var lassoPath: Path? = null
    private val lassoPoints = mutableListOf<PointF>()
    private val selectedItems = mutableListOf<CanvasItem>()
    private var isLassoing = false
    private var isDraggingSelectedItems = false
    private var isResizing = false
    private var isRotating = false
    private var manipulationPivot = PointF()
    private var manipulationStartAngle = 0f
    private var manipulationStartDistance = 0f
    private var manipulationStartMatrix = Matrix()
    private var manipulationStartBounds = RectF()
    private var beforeManipulationStates: List<ItemState>? = null
    
    private var initialGroupItemMatrices = mutableListOf<Matrix>()
    private var beforeGroupTransformStates: List<ItemState>? = null
    
    private var isTwoFingerGroupManipulating = false
    private var isRotatingGroup = false
    private var twoFingerGroupStartSpan = 1f
    private var twoFingerGroupStartAngle = 0f
    private var twoFingerGroupStartFocal = PointF()

    fun clearLassoSelection() {
        selectedItems.clear()
        lassoPath = null
        lassoPoints.clear()
        isLassoing = false
        isDraggingSelectedItems = false
        isRotatingGroup = false
        isTwoFingerGroupManipulating = false
        beforeGroupTransformStates = null
        initialGroupItemMatrices.clear()
        invalidate()
    }

    /** Called once each time the user lifts their finger after a stroke. */
    var onStrokeCompleted: (() -> Unit)? = null
    var onWordModified: ((WordItem) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null
    var onShowItemColorPicker: ((CanvasItem) -> Unit)? = null
    var onPageAdded: (() -> Unit)? = null
    var onRecognizeSelectedItems: ((List<CanvasItem>) -> Unit)? = null
    var onPromptTriggered: ((PromptItem) -> Unit)? = null
    var onPromptEditRequested: ((PromptItem) -> Unit)? = null
    var onTextEditRequested: ((TextItem) -> Unit)? = null

    
    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
    }

    private val SELECTION_BUFFER = 20f

    private val lassoPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        isAntiAlias = true
    }

    private val lassoFillPaint = Paint().apply {
        color = Color.argb(40, 124, 77, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
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

    private val slashPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
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

    private val debugTouchPaint = Paint().apply {
        color = Color.argb(80, 255, 82, 82) // Semi-transparent red
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val debugItemHitPaint = Paint().apply {
        color = Color.argb(40, 76, 175, 80) // Semi-transparent green
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var showTouchAreas: Boolean = false
        set(value) { field = value; invalidate() }

    private val promptBoxPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(10f, 0f, 4f, Color.argb(40, 0, 0, 0))
    }

    private val promptBorderPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val promptTextPaint = TextPaint().apply {
        color = Color.BLACK
        isAntiAlias = true
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val resultTextPaint = TextPaint().apply {
        color = Color.parseColor("#1A237E")
        isAntiAlias = true
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
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
    val viewMatrix = Matrix()
    /** Maps screen-space → canvas-space (inverse of viewMatrix) */
    private val inverseMatrix = Matrix()

    var scaleFactor = 1.0f
    var translateX = -84f
    var translateY = 0f

    // Zoom limits
    private val MIN_ZOOM: Float
        get() {
            if (width <= 0 || height <= 0) return 0.25f
            if (notebookType == NotebookType.WHITEBOARD) return 0.25f
            val totalHeight = numPages * PAGE_HEIGHT + (numPages - 1) * PAGE_MARGIN
            val fitH = height.toFloat() / totalHeight
            val fitW = width.toFloat() / PAGE_WIDTH
            return minOf(fitH, fitW).coerceAtLeast(0.1f)
        }

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
    
    private var lastMotionEvent: MotionEvent? = null
    
    // Two-finger manipulation state
    private var isTwoFingerManipulating = false
    private var twoFingerStartSpan = 1f
    private var twoFingerStartAngle = 0f
    private var twoFingerStartMatrix = Matrix()
    private var twoFingerStartFocal = PointF()

    private var beforeTransformState: ItemState? = null
    
    /** Optional sub-rect for precise search highlighting */
    var searchHighlightedSubRect: RectF? = null

    /** Currently focused item for search results */
    var searchHighlightedItem: CanvasItem? = null
        set(value) { field = value; invalidate() }

    // Scale gesture detector
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Remove item manipulation from here, handled in onTouchEvent
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Canvas panning/zooming
                val oldScale = scaleFactor
                var detectorScale = detector.scaleFactor
                
                // Boost sensitivity slightly for more responsive zooming
                detectorScale = 1.0f + (detectorScale - 1.0f) * 1.5f

                scaleFactor *= detectorScale
                scaleFactor = scaleFactor.coerceIn(MIN_ZOOM, MAX_ZOOM)

                // Scale around the focal point (pinch center)
                val focusX = detector.focusX
                val focusY = detector.focusY
                val scaleChange = scaleFactor / oldScale

                // Adjust translation so the focal point stays fixed
                translateX = focusX - scaleChange * (focusX - translateX)
                translateY = focusY - scaleChange * (focusY - translateY)

                updateMatrix(allowPageCreation = false)
                invalidate()
                return true
            }
        }
    )

    private fun updateMatrix(allowPageCreation: Boolean = true) {
        if (notebookType == NotebookType.NOTEBOOK && width > 0 && height > 0) {
            val totalHeight = numPages * PAGE_HEIGHT + (numPages - 1) * PAGE_MARGIN
            val screenW = width.toFloat()
            val screenH = height.toFloat()
            val margin = 210f * scaleFactor
            
            // X clamping
            val contentW = PAGE_WIDTH * scaleFactor
            if (contentW < screenW) {
                translateX = (screenW - contentW) / 2f
            } else {
                translateX = translateX.coerceIn(screenW - contentW - margin, margin)
            }
            
            // Y clamping
            val contentH = totalHeight * scaleFactor
            val limitBottom = screenH - contentH - margin
            val limitTop = margin

            // 1. Page creation logic (works for both small and large content)
            if (allowPageCreation) {
                val trigger = if (contentH < screenH) {
                    // For small content, trigger if we pull the page up enough
                    screenH - contentH - 350f
                } else {
                    // Original logic for large content
                    limitBottom + 350f
                }
                
                if (translateY <= trigger) {
                    numPages++
                    onPageAdded?.invoke()
                }
            }

            // 2. Clamping logic
            if (contentH < screenH * 0.8f) {
                val center = (screenH - contentH) / 2f
                if (allowPageCreation && translateY < center - 10f) {
                    // Allow some upward panning to trigger a new page
                    translateY = translateY.coerceIn(screenH - contentH - 500f, center)
                } else {
                    translateY = center
                }
            } else {
                // Determine safe range for coerceIn
                var rMin = minOf(limitBottom, limitTop)
                var rMax = maxOf(limitBottom, limitTop)
                
                if (allowPageCreation && contentH < screenH) {
                    // Expand lower bound to allow pulling up for a new page
                    rMin = minOf(rMin, screenH - contentH - 500f)
                }
                
                translateY = translateY.coerceIn(rMin, rMax)
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

        val viewport = getViewportCanvasRect()

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
                if (RectF.intersects(rect, viewport)) {
                    canvas.drawRect(rect, pageBgPaint)
                }
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

        // 5. Draw all committed items (with culling)
        val hasGroupSelection = selectedItems.size > 1
        for (item in drawItems) {
            if (item == hiddenItem) continue
            if (RectF.intersects(item.bounds, viewport)) {
                // Draw search highlight behind the item
                if (item == searchHighlightedItem) {
                    if (searchHighlightedSubRect != null) {
                        canvas.save()
                        when (item) {
                            is ImageItem -> canvas.concat(item.matrix)
                            is WordItem -> canvas.concat(item.matrix)
                            is PromptItem -> canvas.concat(item.matrix)
                            else -> {}
                        }
                        canvas.drawRect(searchHighlightedSubRect!!, searchHighlightPaint)
                        canvas.restore()
                    } else {
                        canvas.drawRect(item.bounds, searchHighlightPaint)
                    }
                }
                
                item.draw(canvas)
                
                // Draw individual selection/highlight on top only if NOT in a multi-item group
                if (!hasGroupSelection) {
                    val isSelected = (item is ImageItem && item == selectedImage) || 
                                    (item is WordItem && item == selectedWord) ||
                                    (item in selectedItems)

                    if (isSelected) {
                        drawSelectionBox(canvas, item)
                    } else if (showTouchAreas && activeTool == ActiveTool.SELECT) {
                        // Visualize hit area for unselected items in SELECT mode
                        drawItemTouchArea(canvas, item)
                    }
                }
            }
        }

        // Draw combined bounding box for group
        if (hasGroupSelection) {
            drawGroupSelectionBox(canvas, selectedItems)
        }

        // 6. Draw in-progress stroke live
        if (isDrawing) {
            canvas.drawPath(currentPath, currentPaint)
        }

        // 7. Draw lasso path
        lassoPath?.let {
            canvas.drawPath(it, lassoFillPaint)
            canvas.drawPath(it, lassoPaint)
        }

        canvas.restore()
    }

    private fun CanvasItem.draw(canvas: Canvas) {
        when (this) {
            is StrokeItem -> canvas.drawPath(path, paint)
            is ImageItem -> {
                canvas.save()
                canvas.concat(matrix)
                if (isShowingText) {
                    if (text.isNotEmpty()) {
                        drawImageText(canvas, this)
                    } else {
                        // Subtle placeholder for empty text mode
                        val placeholderPaint = Paint(textPaint).apply { 
                            color = Color.LTGRAY
                            alpha = 100
                            textSize = 40f
                        }
                        canvas.drawText("...", bitmap.width / 2f, bitmap.height / 2f, placeholderPaint)
                    }
                } else {
                    canvas.drawBitmap(displayBitmap, 0f, 0f, null)
                }
                canvas.restore()
            }
            is WordItem -> {
                canvas.save()
                canvas.concat(matrix)
                if (backgroundColor != null) {
                    tempWordBgPaint.color = backgroundColor!!
                    tempWordBgPaint.style = Paint.Style.FILL
                    val localRect = RectF()
                    if (strokes.isNotEmpty()) {
                        localRect.set(strokes[0].bounds)
                        for (i in 1 until strokes.size) {
                            localRect.union(strokes[i].bounds)
                        }
                    } else if (!textBounds.isEmpty) {
                        localRect.set(textBounds)
                    }
                    canvas.drawRect(localRect, tempWordBgPaint)
                }
                
                if (isShowingText) {
                    if (text.isNotEmpty()) {
                        drawWordText(canvas, this)
                    } else {
                        // Placeholder for empty word
                        val placeholderPaint = Paint(textPaint).apply { 
                            color = Color.LTGRAY
                            alpha = 100
                            textSize = 30f
                        }
                        val centerX = bounds.width() / 2f
                        val centerY = bounds.height() / 2f
                        canvas.drawText("...", 0f, 0f, placeholderPaint)
                    }
                } else {
                    val wordPaint = if (tintColor != null) {
                        Paint().apply {
                            color = tintColor!!
                            style = Paint.Style.STROKE
                            strokeWidth = strokes.firstOrNull()?.paint?.strokeWidth ?: 5f
                            isAntiAlias = true
                            strokeCap = Paint.Cap.ROUND
                            strokeJoin = Paint.Join.ROUND
                        }
                    } else null
                    for (stroke in strokes) {
                        canvas.drawPath(stroke.path, wordPaint ?: stroke.paint)
                    }
                }
                canvas.restore()
            }
            is PromptItem -> {
                canvas.save()
                canvas.concat(matrix)
                
                val rect = RectF(0f, 0f, width, height)
                canvas.drawRoundRect(rect, 20f, 20f, promptBoxPaint)
                canvas.drawRoundRect(rect, 20f, 20f, promptBorderPaint)
                
                val padding = 40f
                val textToDraw = if (isShowingResult) result else prompt
                val paint = if (isShowingResult) resultTextPaint else promptTextPaint
                
                if (textToDraw.isNotEmpty()) {
                    canvas.save()
                    canvas.translate(padding, padding)
                    
                    val layout = StaticLayout.Builder.obtain(textToDraw, 0, textToDraw.length, paint, (width - padding * 2).toInt())
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0f, 1.2f)
                        .setIncludePad(false)
                        .build()
                    
                    canvas.clipRect(0f, 0f, width - padding * 2, height - padding * 2)
                    layout.draw(canvas)
                    canvas.restore()
                } else {
                    if (isShowingResult) {
                        canvas.drawText("Generating...", padding, padding + resultTextPaint.textSize, resultTextPaint.apply { alpha = 100 })
                        resultTextPaint.alpha = 255
                    } else {
                        canvas.drawText("Tap P to enter prompt...", padding, padding + promptTextPaint.textSize, promptTextPaint.apply { alpha = 100 })
                        promptTextPaint.alpha = 255
                    }
                }
                
                canvas.restore()
            }
            is TextItem -> {
                canvas.save()
                canvas.concat(matrix)
                
                val paint = TextPaint().apply {
                    color = this@draw.color
                    textSize = fontSize
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                }
                
                val padding = 10f
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (width - padding * 2).toInt().coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build()
                
                canvas.save()
                canvas.translate(padding, padding)
                layout.draw(canvas)
                canvas.restore()
                
                // Update height based on text content if it's too small
                val neededHeight = layout.height + padding * 2
                if (neededHeight > height) {
                    height = neededHeight
                }
                
                canvas.restore()
            }
        }
    }

    private fun drawItemTouchArea(canvas: Canvas, item: CanvasItem) {
        when (item) {
            is StrokeItem -> {
                // Stroke hit area is a buffer around the path
                val hitPaint = Paint(debugItemHitPaint).apply { 
                    style = Paint.Style.STROKE
                    strokeWidth = 60f // 2 * radius (30f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(item.path, hitPaint)
            }
            is WordItem -> {
                val hitRect = RectF(item.textBounds).apply { inset(-20f, -20f) }
                canvas.save()
                canvas.concat(item.matrix)
                canvas.concat(item.textMatrix)
                canvas.drawRect(hitRect, debugItemHitPaint)
                canvas.restore()
            }
            is ImageItem -> {
                val hitRect = RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
                canvas.save()
                canvas.concat(item.matrix)
                canvas.drawRect(hitRect, debugItemHitPaint)
                canvas.restore()
            }
            is PromptItem -> {
                val hitRect = RectF(0f, 0f, item.width, item.height)
                canvas.save()
                canvas.concat(item.matrix)
                canvas.drawRect(hitRect, debugItemHitPaint)
                canvas.restore()
            }
            is TextItem -> {
                val hitRect = RectF(0f, 0f, item.width, item.height)
                canvas.save()
                canvas.concat(item.matrix)
                canvas.drawRect(hitRect, debugItemHitPaint)
                canvas.restore()
            }
        }
    }

    private fun drawSelectionBox(canvas: Canvas, item: CanvasItem) {
        // 1. Draw item hit area in canvas space (before resetting matrix)
        if (showTouchAreas) {
            drawItemTouchArea(canvas, item)
        }

        val globalBounds = RectF(item.bounds).apply { inset(-SELECTION_BUFFER, -SELECTION_BUFFER) }
        
        // 2. Draw dashed bounding box in canvas space
        canvas.drawRect(globalBounds, selectionPaint)

        // 3. Draw Fixed-Size Buttons in screen space
        val buttonRadius = 24f 
        
        fun getButtonScreenPos(cx: Float, cy: Float): PointF {
            val pts = floatArrayOf(cx, cy)
            viewMatrix.mapPoints(pts)
            return PointF(pts[0], pts[1])
        }

        val delPos = getButtonScreenPos(globalBounds.right, globalBounds.top)
        val resPos = getButtonScreenPos(globalBounds.right, globalBounds.bottom)
        val filPos = getButtonScreenPos(globalBounds.left, globalBounds.bottom)
        val togPos = getButtonScreenPos(globalBounds.left, globalBounds.top)
        val rotPos = getButtonScreenPos(globalBounds.centerX(), globalBounds.top - 40f) // Above the box
        val colPos = getButtonScreenPos(globalBounds.centerX(), globalBounds.bottom)
        
        canvas.save()
        canvas.setMatrix(Matrix()) // Reset to screen space

        // Rotation Handle (Top Center)
        canvas.drawCircle(rotPos.x, rotPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        val rotPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
        val arcRect = RectF(rotPos.x - 10f, rotPos.y - 10f, rotPos.x + 10f, rotPos.y + 10f)
        canvas.drawArc(arcRect, -45f, 270f, false, rotPaint)
        // Arrow head
        val arrowPath = Path().apply {
            moveTo(rotPos.x + 10f, rotPos.y - 5f)
            lineTo(rotPos.x + 15f, rotPos.y)
            lineTo(rotPos.x + 5f, rotPos.y)
            close()
        }
        canvas.drawPath(arrowPath, Paint().apply { color = Color.BLACK; style = Paint.Style.FILL })

        // Resize Handle (Bottom Right Tab)
        val resizeTabPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) }
        canvas.drawRoundRect(resPos.x - 12f, resPos.y - 12f, resPos.x + 12f, resPos.y + 12f, 4f, 4f, resizeTabPaint)
        val resizeLinePaint = Paint().apply { color = Color.GRAY; strokeWidth = 2f }
        canvas.drawLine(resPos.x - 6f, resPos.y + 6f, resPos.x + 6f, resPos.y - 6f, resizeLinePaint)
        canvas.drawLine(resPos.x, resPos.y + 6f, resPos.x + 6f, resPos.y, resizeLinePaint)

        // Consolidated Button Drawing
        
        // 1. Delete Button (Top-Right) - RED
        canvas.drawCircle(delPos.x, delPos.y, buttonRadius, Paint().apply { color = Color.parseColor("#FF5252"); style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        canvas.drawText("×", delPos.x, delPos.y + 10f, Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD })

        // 2. Toggle Button (Top-Left) - WHITE
        // Used for toggling result in PromptItem, or text mode in WordItem/ImageItem
        if (item is WordItem || item is ImageItem || item is PromptItem) {
            canvas.drawCircle(togPos.x, togPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
            val tIconPaint = Paint().apply { color = Color.BLACK; textSize = 28f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
            canvas.drawText("T", togPos.x, togPos.y - (tIconPaint.fontMetrics.ascent + tIconPaint.fontMetrics.descent) / 2, tIconPaint)
        }

        // 3. Edit / Special Action Button (Bottom-Left) - PURPLE
        // Used for Edit Text (T), Edit Prompt (P), or Remove Background (B/W)
        if (item is TextItem || item is PromptItem || item is ImageItem) {
            canvas.drawCircle(filPos.x, filPos.y, buttonRadius, Paint().apply { color = Color.parseColor("#7C4DFF"); style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
            val label = when (item) {
                is TextItem -> "T"
                is PromptItem -> "P"
                is ImageItem -> if (item.removeBackground) "B" else "W"
                else -> ""
            }
            canvas.drawText(label, filPos.x, filPos.y + 8f, Paint().apply { color = Color.WHITE; textSize = if (item is ImageItem) 16f else 28f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD })
        }

        // 4. Color Button (Bottom-Center) - SWATCH
        if (item is WordItem || item is StrokeItem || item is TextItem) {
            canvas.drawCircle(colPos.x, colPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
            canvas.drawCircle(colPos.x, colPos.y, buttonRadius * 0.7f, Paint().apply { 
                color = when(item) {
                    is WordItem -> item.tintColor ?: item.strokes.firstOrNull()?.paint?.color ?: Color.BLACK
                    is StrokeItem -> item.paint.color
                    is TextItem -> item.color
                    else -> Color.BLACK
                }
                style = Paint.Style.FILL 
            })
        }

        // 5. Fill Button (Bottom-Left? No, let's keep it separate if needed)
        // Actually WordItem uses Bottom-Left for Fill.
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

        // --- Visualize Touch Areas (Debug) ---
        if (showTouchAreas) {
            val touchRadius = 64f 
            canvas.drawCircle(delPos.x, delPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(resPos.x, resPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(togPos.x, togPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(rotPos.x, rotPos.y, touchRadius, debugTouchPaint)
            if (item is WordItem || item is StrokeItem) {
                canvas.drawCircle(colPos.x, colPos.y, touchRadius, debugTouchPaint)
            }
            if (item is WordItem) {
                canvas.drawCircle(filPos.x, filPos.y, touchRadius, debugTouchPaint)
            }
            if (item is ImageItem || item is PromptItem) {
                canvas.drawCircle(filPos.x, filPos.y, touchRadius, debugTouchPaint)
            }
        }

        canvas.restore()
    }

    private fun drawGroupSelectionBox(canvas: Canvas, items: List<CanvasItem>) {
        if (items.isEmpty()) return
        
        // 1. Draw individual item hit areas in canvas space
        if (showTouchAreas) {
            for (item in items) {
                drawItemTouchArea(canvas, item)
            }
        }

        val groupBounds = RectF(getGroupBounds(items)).apply { inset(-SELECTION_BUFFER, -SELECTION_BUFFER) }
        
        // 2. Draw dashed bounding box in canvas space
        canvas.drawRect(groupBounds, selectionPaint)

        // Buttons
        val buttonRadius = 24f
        
        fun getScreenPos(cx: Float, cy: Float): PointF {
            val pts = floatArrayOf(cx, cy)
            viewMatrix.mapPoints(pts)
            return PointF(pts[0], pts[1])
        }

        val delPos = getScreenPos(groupBounds.right, groupBounds.top)
        val togPos = getScreenPos(groupBounds.left, groupBounds.top)
        val resPos = getScreenPos(groupBounds.right, groupBounds.bottom)
        val filPos = getScreenPos(groupBounds.left, groupBounds.bottom)
        val rotPos = getScreenPos(groupBounds.centerX(), groupBounds.top - 40f)
        val colPos = getScreenPos(groupBounds.centerX(), groupBounds.bottom)

        canvas.save()
        canvas.setMatrix(Matrix()) // Switch to screen space for buttons

        // Rotation Handle
        canvas.drawCircle(rotPos.x, rotPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        val rotPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
        canvas.drawArc(rotPos.x - 10f, rotPos.y - 10f, rotPos.x + 10f, rotPos.y + 10f, -45f, 270f, false, rotPaint)
        canvas.drawPath(Path().apply {
            moveTo(rotPos.x + 10f, rotPos.y - 5f)
            lineTo(rotPos.x + 15f, rotPos.y)
            lineTo(rotPos.x + 5f, rotPos.y)
            close()
        }, Paint().apply { color = Color.BLACK; style = Paint.Style.FILL })

        // Resize Handle
        canvas.drawRoundRect(resPos.x - 12f, resPos.y - 12f, resPos.x + 12f, resPos.y + 12f, 4f, 4f, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        val resLinePaint = Paint().apply { color = Color.GRAY; strokeWidth = 2f }
        canvas.drawLine(resPos.x - 6f, resPos.y + 6f, resPos.x + 6f, resPos.y - 6f, resLinePaint)
        canvas.drawLine(resPos.x, resPos.y + 6f, resPos.x + 6f, resPos.y, resLinePaint)

        // Delete Button
        canvas.drawCircle(delPos.x, delPos.y, buttonRadius, deleteBgPaint)
        val crossSize = 10f
        canvas.drawLine(delPos.x - crossSize, delPos.y - crossSize, delPos.x + crossSize, delPos.y + crossSize, deleteIconPaint)
        canvas.drawLine(delPos.x + crossSize, delPos.y - crossSize, delPos.x - crossSize, delPos.y + crossSize, deleteIconPaint)

        // Toggle/Recognize Button (Top-Left)
        canvas.drawCircle(togPos.x, togPos.y, buttonRadius, toggleBgPaint)
        val tIconPaint = Paint(toggleIconPaint).apply { textSize = 28f }
        canvas.drawText("T", togPos.x, togPos.y - (tIconPaint.fontMetrics.ascent + tIconPaint.fontMetrics.descent) / 2, tIconPaint)

        // Color Button
        canvas.drawCircle(colPos.x, colPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        canvas.drawCircle(colPos.x, colPos.y, buttonRadius * 0.7f, Paint().apply { 
            val firstWord = items.filterIsInstance<WordItem>().firstOrNull()
            color = firstWord?.tintColor ?: firstWord?.strokes?.firstOrNull()?.paint?.color ?: Color.BLACK
            style = Paint.Style.FILL 
        })

        // Fill Button (Bottom-Left)
        canvas.drawCircle(filPos.x, filPos.y, buttonRadius, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(4f, 0f, 2f, Color.BLACK) })
        val firstWordWithBg = items.filterIsInstance<WordItem>().firstOrNull()
        val bgColor = firstWordWithBg?.backgroundColor
        canvas.drawCircle(filPos.x, filPos.y, buttonRadius * 0.7f, Paint().apply { 
            color = bgColor ?: Color.TRANSPARENT
            style = Paint.Style.FILL 
        })
        if (bgColor == null) {
            canvas.drawLine(filPos.x - buttonRadius * 0.5f, filPos.y + buttonRadius * 0.5f, filPos.x + buttonRadius * 0.5f, filPos.y - buttonRadius * 0.5f, slashPaint)
        }

        // --- Visualize Touch Areas (Debug) ---
        if (showTouchAreas) {
            val touchRadius = 64f
            canvas.drawCircle(delPos.x, delPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(resPos.x, resPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(togPos.x, togPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(rotPos.x, rotPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(colPos.x, colPos.y, touchRadius, debugTouchPaint)
            canvas.drawCircle(filPos.x, filPos.y, touchRadius, debugTouchPaint)
        }

        canvas.restore()
    }

    private fun getGroupBounds(items: List<CanvasItem>): RectF {
        val bounds = RectF()
        if (items.isEmpty()) return bounds
        bounds.set(items[0].bounds)
        for (i in 1 until items.size) {
            bounds.union(items[i].bounds)
        }
        return bounds
    }

    private fun drawWordText(canvas: Canvas, item: WordItem) {
        if (item.strokes.isEmpty() && item.textBounds.isEmpty) return
        
        canvas.save()
        canvas.concat(item.textMatrix)

        val localBounds = if (!item.textBounds.isEmpty) {
            item.textBounds
        } else {
            val b = RectF(item.strokes[0].bounds)
            for (i in 1 until item.strokes.size) {
                b.union(item.strokes[i].bounds)
            }
            b
        }
        
        val color = item.tintColor ?: if (item.strokes.isNotEmpty()) {
            item.strokes[0].paint.color
        } else {
            Color.BLACK
        }
        
        drawWrappedText(canvas, item.text, localBounds, color)
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
        
        drawWrappedText(canvas, item.text, localBounds, Color.BLACK)
        canvas.restore()
    }

    private fun drawWrappedText(canvas: Canvas, text: String, bounds: RectF, color: Int) {
        if (text.isEmpty()) return
        
        val tp = TextPaint(textPaint)
        tp.color = color
        tp.textAlign = Paint.Align.LEFT // StaticLayout handles its own alignment
        
        val width = bounds.width().toInt().coerceAtLeast(1)
        val height = bounds.height()

        // Binary search for optimal font size to fill the box
        var low = 1f
        var high = 1000f // Large upper bound for giant items
        var optimalSize = 10f
        
        repeat(12) { // 12 iterations gives good precision
            val mid = (low + high) / 2
            tp.textSize = mid
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            
            if (layout.height <= height) {
                optimalSize = mid
                low = mid
            } else {
                high = mid
            }
        }
        
        tp.textSize = optimalSize
        val finalLayout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
            
        canvas.save()
        // Center vertically within the bounds
        val yOffset = bounds.top + (height - finalLayout.height) / 2f
        canvas.translate(bounds.left, yOffset)
        finalLayout.draw(canvas)
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
        val lineSpacing = if (notebookType == NotebookType.NOTEBOOK) 70f else 35f
        val gridSpacing = if (notebookType == NotebookType.NOTEBOOK) 56f else 28f
        
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

            val marginX = if (isInfinite) 84f else rect.left + 224f
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
                
                // 1. If we have a lasso selection, check if we hit any of the selected items to start dragging
                if (selectedItems.isNotEmpty()) {
                    val groupBounds = RectF(getGroupBounds(selectedItems)).apply { inset(-SELECTION_BUFFER, -SELECTION_BUFFER) }
                    
                    // Check group buttons first
                    val buttonRadiusSq = 64f * 64f // Increased from 48 for easier tapping
                    
                    fun getScreenPos(cx: Float, cy: Float): PointF {
                        val pts = floatArrayOf(cx, cy)
                        viewMatrix.mapPoints(pts)
                        return PointF(pts[0], pts[1])
                    }

                    fun isGroupButtonHit(cx: Float, cy: Float): Boolean {
                        val pos = getScreenPos(cx, cy)
                        val dx = event.x - pos.x
                        val dy = event.y - pos.y
                        return dx * dx + dy * dy <= buttonRadiusSq
                    }

                    // Delete group (Top-Right)
                    if (isGroupButtonHit(groupBounds.right, groupBounds.top)) {
                        deleteSelectedItem()
                        return true
                    }

                    // Recognize / Toggle group (Top-Left)
                    if (isGroupButtonHit(groupBounds.left, groupBounds.top)) {
                        val firstItem = selectedItems.firstOrNull()
                        if (selectedItems.size == 1 && firstItem is PromptItem) {
                            firstItem.isShowingResult = !firstItem.isShowingResult
                            if (firstItem.isShowingResult && firstItem.result.isEmpty() && firstItem.prompt.isNotEmpty()) {
                                onPromptTriggered?.invoke(firstItem)
                            }
                            invalidate()
                        } else {
                            onRecognizeSelectedItems?.invoke(selectedItems.toList())
                        }
                        return true
                    }

                    // Color button (bottom-center)
                    if (isGroupButtonHit(groupBounds.centerX(), groupBounds.bottom)) {
                        showColorPicker(selectedItems.toList(), isBackground = false)
                        return true
                    }

                    // Fill button / Background removal / Edit Prompt (bottom-left)
                    if (isGroupButtonHit(groupBounds.left, groupBounds.bottom)) {
                        if (selectedItems.any { it is WordItem }) {
                            showColorPicker(selectedItems.toList(), isBackground = true)
                            return true
                        }
                        if (selectedItems.size == 1) {
                            val item = selectedItems[0]
                            if (item is ImageItem) {
                                item.removeBackground = !item.removeBackground
                                item.invalidateCache()
                                invalidate()
                                return true
                            }
                            if (item is PromptItem) {
                                onPromptEditRequested?.invoke(item)
                                return true
                            }
                            if (item is TextItem) {
                                onTextEditRequested?.invoke(item)
                                return true
                            }
                        }
                    }

                    // Rotate Handle (Top-Center)
                    if (isGroupButtonHit(groupBounds.centerX(), groupBounds.top - 40f)) {
                        isRotating = true
                        manipulationPivot.set(groupBounds.centerX(), groupBounds.centerY())
                        manipulationStartAngle = Math.toDegrees(atan2((canvasCoords[1] - manipulationPivot.y).toDouble(), (canvasCoords[0] - manipulationPivot.x).toDouble())).toFloat()
                        beforeManipulationStates = selectedItems.map { captureState(it) }
                        return true
                    }

                    // Resize Handle (Bottom-Right)
                    if (isGroupButtonHit(groupBounds.right, groupBounds.bottom)) {
                        isResizing = true
                        manipulationPivot.set(groupBounds.left, groupBounds.top) // Fixed top-left for resize
                        val dx = canvasCoords[0] - manipulationPivot.x
                        val dy = canvasCoords[1] - manipulationPivot.y
                        manipulationStartDistance = max(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                        beforeManipulationStates = selectedItems.map { captureState(it) }
                        return true
                    }

                    val hitInSelection = selectedItems.any { isItemHit(it, canvasCoords[0], canvasCoords[1]) }
                    if (hitInSelection) {
                        isDraggingSelectedItems = true
                        isDrawing = false
                        isPanning = false
                        lastPanX = event.x
                        lastPanY = event.y
                        
                        // Capture initial states for all selected items
                        beforeGroupTransformStates = selectedItems.map { captureState(it) }
                        
                        return true
                    } else {
                        // Tapped outside selection, clear it unless we are in LASSO tool or SELECT mode
                        if (activeTool != ActiveTool.LASSO && activeTool != ActiveTool.SELECT) {
                            clearLassoSelection()
                        }
                    }
                }

                if (activeTool == ActiveTool.SELECT) {
                    val hitItem = drawItems.reversed().find { isItemHit(it, canvasCoords[0], canvasCoords[1]) }
                    if (hitItem != null) {
                        selectedItems.clear()
                        selectedItems.add(hitItem)
                        isDraggingSelectedItems = true
                        lastPanX = event.x
                        lastPanY = event.y
                        beforeGroupTransformStates = selectedItems.map { captureState(it) }
                        invalidate()
                        return true
                    } else {
                        clearLassoSelection()
                    }
                } else if (activeTool == ActiveTool.LASSO) {
                    isLassoing = true
                    isDrawing = false
                    lassoPoints.clear()
                    lassoPoints.add(PointF(canvasCoords[0], canvasCoords[1]))
                    lassoPath = Path().apply { moveTo(canvasCoords[0], canvasCoords[1]) }
                } else {
                    isDrawing = true
                    isPanning = false
                    touchStart(canvasCoords[0], canvasCoords[1])
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (isDrawing) {
                    cancelCurrentStroke()
                    isDrawing = false
                }
                if (isLassoing) {
                    isLassoing = false
                    lassoPath = null
                }
                
                // ALWAYS pan when 2 fingers are down
                isPanning = true
                lastPanX = event.x
                lastPanY = event.y
                
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val canvasCoords = screenToCanvas(event.x, event.y)
                val currentSelectedItem = selectedImage ?: selectedWord
                
                if (isRotating) {
                    val currentAngle = Math.toDegrees(atan2((canvasCoords[1] - manipulationPivot.y).toDouble(), (canvasCoords[0] - manipulationPivot.x).toDouble())).toFloat()
                    val rotationDelta = currentAngle - manipulationStartAngle
                    
                    for (i in selectedItems.indices) {
                        val item = selectedItems[i]
                        val initialState = beforeManipulationStates?.get(i) ?: continue
                        
                        when (item) {
                            is ImageItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postRotate(rotationDelta, manipulationPivot.x, manipulationPivot.y)
                            }
                            is WordItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postRotate(rotationDelta, manipulationPivot.x, manipulationPivot.y)
                            }
                            is StrokeItem -> {
                                initialState.strokePath?.let { item.path.set(it) }
                                val matrix = Matrix()
                                matrix.postRotate(rotationDelta, manipulationPivot.x, manipulationPivot.y)
                                item.path.transform(matrix)
                                item.boundsRect.setEmpty()
                                item.path.computeBounds(item.boundsRect, true)
                            }
                            is PromptItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postRotate(rotationDelta, manipulationPivot.x, manipulationPivot.y)
                            }
                            is TextItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postRotate(rotationDelta, manipulationPivot.x, manipulationPivot.y)
                            }
                        }
                        item.invalidateCache()
                    }
                    invalidate()
                } else if (isResizing) {
                    val dx = canvasCoords[0] - manipulationPivot.x
                    val dy = canvasCoords[1] - manipulationPivot.y
                    val currentDistance = max(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                    val scale = currentDistance / manipulationStartDistance
                    
                    for (i in selectedItems.indices) {
                        val item = selectedItems[i]
                        val initialState = beforeManipulationStates?.get(i) ?: continue
                        
                        when (item) {
                            is ImageItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postScale(scale, scale, manipulationPivot.x, manipulationPivot.y)
                            }
                            is WordItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postScale(scale, scale, manipulationPivot.x, manipulationPivot.y)
                            }
                            is StrokeItem -> {
                                initialState.strokePath?.let { item.path.set(it) }
                                val matrix = Matrix()
                                matrix.postScale(scale, scale, manipulationPivot.x, manipulationPivot.y)
                                item.path.transform(matrix)
                                item.boundsRect.setEmpty()
                                item.path.computeBounds(item.boundsRect, true)
                            }
                            is PromptItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postScale(scale, scale, manipulationPivot.x, manipulationPivot.y)
                            }
                            is TextItem -> {
                                item.matrix.set(initialState.matrix)
                                item.matrix.postScale(scale, scale, manipulationPivot.x, manipulationPivot.y)
                            }
                        }
                        item.invalidateCache()
                    }
                    invalidate()
                } else if (isDraggingSelectedItems) {
                    val lastCanvasCoords = screenToCanvas(lastPanX, lastPanY)
                    val dx = canvasCoords[0] - lastCanvasCoords[0]
                    val dy = canvasCoords[1] - lastCanvasCoords[1]
                    for (item in selectedItems) {
                        when (item) {
                            is ImageItem -> item.matrix.postTranslate(dx, dy)
                            is WordItem -> item.matrix.postTranslate(dx, dy)
                            is StrokeItem -> {
                                item.path.offset(dx, dy)
                                item.boundsRect.offset(dx, dy)
                            }
                            is PromptItem -> item.matrix.postTranslate(dx, dy)
                            is TextItem -> item.matrix.postTranslate(dx, dy)
                        }
                        item.invalidateCache()
                    }
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                } else if (isLassoing) {
                    lassoPoints.add(PointF(canvasCoords[0], canvasCoords[1]))
                    lassoPath?.lineTo(canvasCoords[0], canvasCoords[1])
                    invalidate()
                } else if (isTwoFingerManipulating && currentSelectedItem != null && pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    val currentSpan = max(1f, Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                    val currentAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    val focal = screenToCanvas((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
                    
                    val scale = currentSpan / twoFingerStartSpan
                    val transX = focal[0] - twoFingerStartFocal.x
                    val transY = focal[1] - twoFingerStartFocal.y
                    
                    val targetMatrix = when(currentSelectedItem) {
                        is ImageItem -> currentSelectedItem.matrix
                        is WordItem -> currentSelectedItem.matrix
                        is TextItem -> currentSelectedItem.matrix
                        is PromptItem -> currentSelectedItem.matrix
                        else -> Matrix()
                    }
                    
                    val tempMatrix = Matrix(twoFingerStartMatrix)
                    tempMatrix.postTranslate(transX, transY)
                    tempMatrix.postScale(scale, scale, focal[0], focal[1])
                    
                    targetMatrix.set(tempMatrix)
                    invalidate()
                } else if ((isImageManipulating && selectedImage != null || isWordManipulating && selectedWord != null) && pointerCount == 1) {
                    // Single finger translate
                    val targetMatrix = selectedImage?.matrix ?: selectedWord!!.matrix
                    val lastCanvasCoords = screenToCanvas(lastPanX, lastPanY)
                    val dx = canvasCoords[0] - lastCanvasCoords[0]
                    val dy = canvasCoords[1] - lastCanvasCoords[1]
                    
                    targetMatrix.postTranslate(dx, dy)
                    (selectedImage ?: selectedWord)?.invalidateCache()
                    lastPanX = event.x
                    lastPanY = event.y
                    invalidate()
                } else if (isPanning && pointerCount >= 2) {
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
                    touchMove(canvasCoords[0], canvasCoords[1])
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isResizing || isRotating || isDraggingSelectedItems) {
                    val statesBefore = beforeManipulationStates ?: beforeGroupTransformStates
                    if (statesBefore != null) {
                        val afterStates = selectedItems.map { captureState(it) }
                        pushAction(GroupTransformAction(selectedItems.toList(), statesBefore, afterStates), executeNow = false)
                        // Removed onStrokeCompleted trigger for transformations
                    }
                    isResizing = false
                    isRotating = false
                    isDraggingSelectedItems = false
                    beforeManipulationStates = null
                    beforeGroupTransformStates = null
                    invalidate()
                } else if (isLassoing) {
                    isLassoing = false
                    lassoPath?.close()
                    performLassoSelection()
                    invalidate()
                } else if (isDrawing) {
                    touchUp()
                    isDrawing = false
                    invalidate()
                }

                if (isTwoFingerGroupManipulating) {
                    isTwoFingerGroupManipulating = false
                    if (beforeGroupTransformStates != null) {
                        val afterStates = selectedItems.map { captureState(it) }
                        pushAction(GroupTransformAction(selectedItems.toList(), beforeGroupTransformStates!!, afterStates))
                        // Removed onStrokeCompleted trigger for transformations
                    }
                    beforeGroupTransformStates = null
                    
                    // Freeze transforms for WordItems
                    for (item in selectedItems) {
                        if (item is WordItem) freezeWordTransform(item)
                    }
                    invalidate()
                }
                
                isPanning = false
                isImageManipulating = false
                isWordManipulating = false
                isTwoFingerManipulating = false
                beforeTransformState = null
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (isTwoFingerGroupManipulating) {
                    isTwoFingerGroupManipulating = false
                    if (beforeGroupTransformStates != null) {
                        val afterStates = selectedItems.map { captureState(it) }
                        pushAction(GroupTransformAction(selectedItems.toList(), beforeGroupTransformStates!!, afterStates))
                        onStrokeCompleted?.invoke()
                    }
                    beforeGroupTransformStates = null
                    for (item in selectedItems) {
                        if (item is WordItem) freezeWordTransform(item)
                    }
                    invalidate()
                }
                if (pointerCount <= 2) {
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

    fun addTextItem(text: String = "Double tap to edit"): TextItem {
        val viewW = if (width > 0) width.toFloat() else 800f
        val viewH = if (height > 0) height.toFloat() else 800f
        val viewCenter = screenToCanvas(viewW / 2f, viewH / 2f)
        
        val matrix = Matrix()
        matrix.postTranslate(viewCenter[0] - 200f, viewCenter[1] - 50f)
        
        val textItem = TextItem(text, matrix)
        pushAction(AddItemAction(textItem))
        selectedItems.clear()
        selectedItems.add(textItem)
        invalidate()
        onStrokeCompleted?.invoke()
        return textItem
    }

    fun deleteSelectedItem(): Boolean {
        if (selectedItems.isNotEmpty()) {
            val itemsToRemove = selectedItems.toList()
            val indices = itemsToRemove.map { drawItems.indexOf(it) }
            pushAction(GroupRemoveAction(itemsToRemove, indices))
            selectedItems.clear()
            invalidate()
            // Removed onStrokeCompleted trigger for deletion
            return true
        }

        val image = selectedImage
        if (image != null) {
            val index = drawItems.indexOf(image)
            if (index != -1) {
                pushAction(RemoveItemAction(image, index))
                selectedImage = null
                isImageManipulating = false
                invalidate()
                // Removed onStrokeCompleted trigger for deletion
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
                // Removed onStrokeCompleted trigger for deletion
                return true
            }
        }
        return false
    }

    inner class GroupRemoveAction(val items: List<CanvasItem>, val indices: List<Int>) : UndoAction {
        override fun undo() {
            for (i in items.indices) {
                if (indices[i] != -1) {
                    drawItems.add(indices[i], items[i])
                } else {
                    drawItems.add(items[i])
                }
            }
            invalidate()
        }
        override fun redo() {
            drawItems.removeAll(items)
            invalidate()
        }
    }

    fun groupStrokesIntoWord(strokesToGroup: List<StrokeItem>, text: String, wordsToMerge: List<WordItem> = emptyList(), isAutoGroup: Boolean = false): WordItem? {
        val stillInCanvasLoose = strokesToGroup.filter { it in drawItems }
        val stillInCanvasWords = wordsToMerge.filter { it in drawItems }
        
        if (stillInCanvasLoose.isEmpty() && stillInCanvasWords.isEmpty()) return null
        
        val finalStrokes = mutableListOf<StrokeItem>()
        finalStrokes.addAll(stillInCanvasLoose)
        for (word in stillInCanvasWords) {
            finalStrokes.addAll(dissolveWordToStrokes(word))
        }

        // Calculate initial text orientation and bounds (always axis-aligned)
        val textBounds = getRecentClusterBounds(finalStrokes)
        val textMatrix = Matrix()

        // Create WordItem with identity matrix (strokes are already in canvas space)
        val wordItem = WordItem(finalStrokes, Matrix(), text, false, textMatrix, textBounds)
        
        // Preserve tint/background color from the first merged word
        stillInCanvasWords.firstOrNull()?.let { firstWord ->
            wordItem.tintColor = firstWord.tintColor
            wordItem.backgroundColor = firstWord.backgroundColor
        }

        // Optimization: If this is an automated grouping, merge it with all relevant previous actions
        // (strokes and previous recognition steps) to ensure a single undo removes the entire cluster
        // of new changes.
        if (isAutoGroup && undoStack.isNotEmpty()) {
            val groupAction = GroupAction(stillInCanvasLoose, stillInCanvasWords, wordItem)
            groupAction.redo() // Apply the swap immediately
            
            var combinedAction: UndoAction = groupAction
            var fusedAny = false
            
            while (undoStack.isNotEmpty()) {
                val last = undoStack.last()
                val canFuse = when (last) {
                    is AddItemAction -> last.item in stillInCanvasLoose
                    is FusedAutoGroupAction -> {
                        val innerGroup = last.groupAction
                        innerGroup is GroupAction && innerGroup.wordToAdd in stillInCanvasWords
                    }
                    else -> false
                }
                
                if (canFuse) {
                    val popped = undoStack.removeAt(undoStack.size - 1)
                    val strokeAction = if (popped is FusedAutoGroupAction) popped.lastStrokeAction else popped
                    combinedAction = FusedAutoGroupAction(strokeAction, combinedAction)
                    fusedAny = true
                } else {
                    break
                }
            }
            
            pushAction(combinedAction, executeNow = false)
            return wordItem
        }

        pushAction(GroupAction(stillInCanvasLoose, stillInCanvasWords, wordItem))
        invalidate()
        return wordItem
    }

    inner class UpdateTextAction(val item: TextItem, val oldText: String, val newText: String) : UndoAction {
        override fun undo() { item.text = oldText; invalidate() }
        override fun redo() { item.text = newText; invalidate() }
    }

    inner class UpdatePromptAction(val item: PromptItem, val oldPrompt: String, val newPrompt: String) : UndoAction {
        override fun undo() { item.prompt = oldPrompt; invalidate() }
        override fun redo() { item.prompt = newPrompt; invalidate() }
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
        searchHighlightedItem = null
        scaleFactor = 1.0f
        translateX = -120f
        translateY = 0f
        updateMatrix()
        invalidate()
    }

    fun centerOnItem(item: CanvasItem) {
        val bounds = item.bounds
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        
        // Center the item in the view
        translateX = width / 2f - centerX * scaleFactor
        translateY = height / 2f - centerY * scaleFactor
        
        updateMatrix()
        invalidate()
    }

    fun centerOnRect(item: CanvasItem, localRect: RectF) {
        val globalRect = RectF(localRect)
        when (item) {
            is ImageItem -> item.matrix.mapRect(globalRect)
            is WordItem -> item.matrix.mapRect(globalRect)
            is PromptItem -> item.matrix.mapRect(globalRect)
            is TextItem -> item.matrix.mapRect(globalRect)
            else -> {}
        }
        
        val centerX = globalRect.centerX()
        val centerY = globalRect.centerY()
        
        translateX = width / 2f - centerX * scaleFactor
        translateY = height / 2f - centerY * scaleFactor
        
        updateMatrix()
        invalidate()
    }

    fun getCurrentPageIndex(): Int {
        if (notebookType != NotebookType.NOTEBOOK || height <= 0) return 0
        // Calculate which page is in the middle of the screen
        val centerCanvasY = (height / 2f - translateY) / scaleFactor
        val pageFullHeight = PAGE_HEIGHT + PAGE_MARGIN
        return (centerCanvasY / pageFullHeight).toInt().coerceIn(0, numPages - 1)
    }

    fun scrollToPage(pageIndex: Int) {
        if (notebookType != NotebookType.NOTEBOOK) return
        val pageFullHeight = PAGE_HEIGHT + PAGE_MARGIN
        val targetCanvasY = pageIndex * pageFullHeight
        
        // Position the top of the page at the top of the screen (with a small margin)
        translateY = -targetCanvasY * scaleFactor + 100f * scaleFactor
        
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
                        if (data.textMatrix != null) {
                            textMatrix.setValues(data.textMatrix)
                        }
                        
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
                    if (data.textMatrix != null) {
                        textMatrix.setValues(data.textMatrix)
                    }
                    
                    WordItem(strokes, matrix, data.text, data.isShowingText, textMatrix, data.textBounds?.let { RectF(it.left, it.top, it.right, it.bottom) } ?: RectF(), data.tintColor, data.backgroundColor)
                }
                is PromptData -> {
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    matrix.postTranslate(0f, dy)
                    PromptItem(data.prompt, data.result, data.isShowingResult, null, null, matrix, data.width, data.height)
                }
                is TextData -> {
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    matrix.postTranslate(0f, dy)
                    TextItem(data.text, matrix, data.color, data.fontSize, data.width, data.height)
                }
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
                is PromptItem -> {
                    val newMatrix = Matrix(item.matrix)
                    newMatrix.postTranslate(0f, dy)
                    item.copy(matrix = newMatrix)
                }
                is TextItem -> {
                    val newMatrix = Matrix(item.matrix)
                    newMatrix.postTranslate(0f, dy)
                    item.copy(matrix = newMatrix)
                }
            }
        }
    }

    fun createPageThumbnail(pageIndex: Int): Bitmap? {
        val thumbWidth = 400
        val thumbHeight = (thumbWidth * (PAGE_HEIGHT / PAGE_WIDTH)).toInt()
        
        val bmp = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE) // Always white background for thumbnails
        
        val scale = thumbWidth.toFloat() / PAGE_WIDTH
        canvas.scale(scale, scale)
        
        val top = pageIndex * (PAGE_HEIGHT + PAGE_MARGIN)
        canvas.translate(0f, -top)
        
        // Draw background lines
        val pageRect = RectF(0f, top, PAGE_WIDTH, top + PAGE_HEIGHT)
        drawLinesOnRect(canvas, pageRect)
        
        // Draw items
        val items = getItemsOnPage(pageIndex)
        for (item in items) {
            item.draw(canvas)
        }
        
        return bmp
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

        val maxDim = 448
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


    fun createBitmapForItems(items: List<CanvasItem>): Bitmap? {
        val bounds = getGroupBounds(items)
        if (bounds.isEmpty) return null
        
        val margin = 40f
        val left = bounds.left - margin
        val top = bounds.top - margin
        val right = bounds.right + margin
        val bottom = bounds.bottom + margin
        
        val cropWidth = (right - left).toInt()
        val cropHeight = (bottom - top).toInt()
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

        val bmp = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        
        c.scale(scale, scale)
        c.translate(-left, -top)
        
        for (item in items) {
            item.draw(c)
        }
        
        return bmp
    }

    fun groupSelectedItemsIntoWord(items: List<CanvasItem>, text: String): WordItem? {
        val strokes = items.filterIsInstance<StrokeItem>()
        val words = items.filterIsInstance<WordItem>()
        return groupStrokesIntoWord(strokes, text, words)
    }

    fun addPromptItem(): PromptItem {
        val viewport = getViewportCanvasRect()
        val item = PromptItem(
            prompt = "",
            result = "",
            isShowingResult = false,
            matrix = Matrix().apply { postTranslate(viewport.centerX() - 600f, viewport.centerY() - 300f) },
            width = 1200f,
            height = 600f
        )
        drawItems.add(item)
        undoStack.add(AddItemAction(item))
        redoStack.clear()
        selectedItems.clear()
        selectedItems.add(item)
        invalidate()
        onStateChanged?.invoke()
        return item
    }


    fun createFullPageBitmap(pageIndex: Int): Bitmap? {

        if (width <= 0 || height <= 0) return null
        
        // For OCR, a 1024x1024 or similar target is good. 
        // We'll use a fixed size that Gemma vision models often prefer, or scale based on page aspect.
        val targetWidth = 1024
        val targetHeight = (targetWidth * (PAGE_HEIGHT / PAGE_WIDTH)).toInt()
        
        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        
        // Draw white background for best OCR results
        canvas.drawColor(Color.WHITE)
        
        val scale = targetWidth.toFloat() / PAGE_WIDTH
        canvas.scale(scale, scale)
        
        val top = pageIndex * (PAGE_HEIGHT + PAGE_MARGIN)
        canvas.translate(0f, -top)
        
        // Culling: only draw items on this page
        val items = getItemsOnPage(pageIndex)
        
        // Boost stroke width for OCR visibility on full page
        // To get ~4px thickness on the final 1024px bitmap, we need absolute width of 4 / scale.
        val minStrokeWidth = 4f / scale
        val originalWidths = mutableMapOf<StrokeItem, Float>()
        
        for (item in items) {
            if (item is StrokeItem) {
                originalWidths[item] = item.paint.strokeWidth
                if (item.paint.strokeWidth < minStrokeWidth) {
                    item.paint.strokeWidth = minStrokeWidth
                }
            } else if (item is WordItem) {
                for (stroke in item.strokes) {
                    originalWidths[stroke] = stroke.paint.strokeWidth
                    if (stroke.paint.strokeWidth < minStrokeWidth) {
                        stroke.paint.strokeWidth = minStrokeWidth
                    }
                }
            }
        }

        for (item in items) {
            // Draw only strokes and words (skip images if we just want handwriting)
            // But sometimes handwriting is on images... let's draw images too.
            // SKIP PromptItems as they should be ignored for Gemma analysis.
            if (item !is PromptItem) {
                item.draw(canvas)
            }
        }
        
        // Restore original stroke widths
        for ((stroke, originalWidth) in originalWidths) {
            stroke.paint.strokeWidth = originalWidth
        }
        
        return bmp
    }

    fun groupStrokesByBoxes(pageIndex: Int, boxes: List<DetectedBox>) {
        val pageTop = pageIndex * (PAGE_HEIGHT + PAGE_MARGIN)
        val looseStrokes = getItemsOnPage(pageIndex).filterIsInstance<StrokeItem>()
        if (looseStrokes.isEmpty() && boxes.isEmpty()) return

        for (box in boxes) {
            // Map normalized 0-1000 to canvas space
            val left = box.xmin * PAGE_WIDTH / 1000f
            val right = box.xmax * PAGE_WIDTH / 1000f
            val top = box.ymin * PAGE_HEIGHT / 1000f + pageTop
            val bottom = box.ymax * PAGE_HEIGHT / 1000f + pageTop
            
            val boxRect = RectF(left, top, right, bottom)
            
            // Find strokes whose center is in this box
            val strokesInBox = looseStrokes.filter { stroke ->
                boxRect.contains(stroke.bounds.centerX(), stroke.bounds.centerY())
            }
            
            if (strokesInBox.isNotEmpty()) {
                groupStrokesIntoWord(strokesInBox, box.text, isAutoGroup = true)
            } else {
                // If no strokes found but Gemma detected something, it might be a tiny word 
                // or we already grouped it. Or maybe it's just a false positive.
                // We could create a text-only WordItem if we want, but let's stick to grouping for now.
            }
        }
        invalidate()
    }

    // Removed create bitmap logic since it's now in createBitmapForStrokes


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
                        opacity = item.paint.alpha,
                        isLocked = item.isLocked
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
                    
                    ImageData(
                        base64 = base64,
                        matrix = m,
                        removeBackground = item.removeBackground,
                        text = item.text,
                        isShowingText = item.isShowingText,
                        textMatrix = tm,
                        textBounds = tb,
                        pdfWords = item.pdfWords,
                        isLocked = item.isLocked
                    )
                }
                is WordItem -> {
                    val strokeDataList = item.strokes.map {
                        StrokeData(
                            commands = it.commands,
                            color = it.paint.color,
                            strokeWidth = it.paint.strokeWidth,
                            opacity = it.paint.alpha,
                            isLocked = it.isLocked
                        )
                    }
                    val m = FloatArray(9)
                    item.matrix.getValues(m)
                    
                    val tm = FloatArray(9)
                    item.textMatrix.getValues(tm)
                    val tb = FloatRect(item.textBounds.left, item.textBounds.top, item.textBounds.right, item.textBounds.bottom)
                    
                    WordData(
                        strokes = strokeDataList,
                        matrix = m,
                        text = item.text,
                        isShowingText = item.isShowingText,
                        textMatrix = tm,
                        textBounds = tb,
                        tintColor = item.tintColor,
                        backgroundColor = item.backgroundColor,
                        isLocked = item.isLocked
                    )
                }
                is PromptItem -> {
                    val m = FloatArray(9)
                    item.matrix.getValues(m)
                    PromptData(
                        prompt = item.prompt,
                        result = item.result,
                        isShowingResult = item.isShowingResult,
                        matrix = m,
                        width = item.width,
                        height = item.height,
                        isLocked = item.isLocked
                    )
                }
                is TextItem -> {
                    val m = FloatArray(9)
                    item.matrix.getValues(m)
                    TextData(
                        text = item.text,
                        matrix = m,
                        width = item.width,
                        height = item.height,
                        fontSize = item.fontSize,
                        color = item.color,
                        isLocked = item.isLocked
                    )
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
                    val item = createStrokeItem(data)
                    item.isLocked = data.isLocked
                    drawItems.add(item)
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
                            
                            val img = ImageItem(bitmap, matrix, data.removeBackground, data.text, data.isShowingText, textMatrix, textBounds)
                            img.isLocked = data.isLocked
                            drawItems.add(img)
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
                        
                        val word = WordItem(strokes, matrix, data.text, data.isShowingText, textMatrix, textBounds, data.tintColor, data.backgroundColor)
                        word.isLocked = data.isLocked
                        drawItems.add(word)
                }
                is PromptData -> {
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    val prompt = PromptItem(data.prompt, data.result, data.isShowingResult, null, null, matrix, data.width, data.height)
                    prompt.isLocked = data.isLocked
                    drawItems.add(prompt)
                }
                is TextData -> {
                    val matrix = Matrix()
                    matrix.setValues(data.matrix)
                    val textItem = TextItem(data.text, matrix, data.color, data.fontSize, data.width, data.height)
                    textItem.isLocked = data.isLocked
                    drawItems.add(textItem)
                }
            }
        }

        if (width > 0 && height > 0) {
            centerOnContent()
            invalidate()
        }
    }

    private fun showColorPicker(items: List<CanvasItem>, isBackground: Boolean = false) {
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
                    for (item in items) {
                        if (isBackground) {
                            if (item is WordItem) {
                                val oldValue = item.backgroundColor
                                if (oldValue != color) {
                                    pushAction(StyleAction(item, "backgroundColor", oldValue, color))
                                }
                            }
                        } else {
                            if (item is WordItem || item is StrokeItem) {
                                val field = "tintColor"
                                val oldValue = when(item) {
                                    is WordItem -> item.tintColor
                                    is StrokeItem -> item.paint.color
                                    else -> null
                                }
                                if (oldValue != color) {
                                    pushAction(StyleAction(item, field, oldValue, color))
                                }
                            }
                        }
                    }
                    this@DrawingView.invalidate()
                    dialog.dismiss()
                }
            }
            container.addView(colorView)
        }
        
        scroll.addView(container)
        dialog.setView(scroll)
        val title = if (items.size > 1) {
            if (isBackground) "Select Fill Color for Group" else "Select Tint Color for Group"
        } else {
            if (isBackground) "Select Fill Color" else "Select Tint Color"
        }
        dialog.setTitle(title)
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
            is PromptItem -> RectF(0f, 0f, item.width, item.height)
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
            is PromptItem -> item.matrix
            else -> Matrix()
        }
        matrix.mapPoints(center)
        return PointF(center[0], center[1])
    }

    fun getItemScreenBounds(item: CanvasItem): RectF {
        val r = RectF(item.bounds)
        viewMatrix.mapRect(r)
        return r
    }

    fun getItemRotation(item: CanvasItem): Float {
        val m = when(item) {
            is ImageItem -> item.matrix
            is WordItem -> item.matrix
            is PromptItem -> item.matrix
            is TextItem -> item.matrix
            else -> Matrix()
        }
        val pts = floatArrayOf(0f, 1f)
        m.mapVectors(pts)
        val angle = Math.toDegrees(Math.atan2(pts[1].toDouble(), pts[0].toDouble())).toFloat() - 90f
        return angle
    }

    fun getCanvasPointOnScreen(canvasX: Float, canvasY: Float): PointF {
        val pts = floatArrayOf(canvasX, canvasY)
        viewMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    private fun isItemHit(item: CanvasItem, canvasX: Float, canvasY: Float): Boolean {
        if (item.isLocked) return false
        val localCoords = floatArrayOf(canvasX, canvasY)
        val inv = Matrix()
        val matrix = when(item) {
            is ImageItem -> item.matrix
            is WordItem -> {
                val m = Matrix(item.matrix)
                m.preConcat(item.textMatrix)
                m
            }
            is PromptItem -> item.matrix
            is TextItem -> item.matrix
            else -> Matrix()
        }
        matrix.invert(inv)
        inv.mapPoints(localCoords)
        
        val localBounds = when(item) {
            is ImageItem -> RectF(0f, 0f, item.bitmap.width.toFloat(), item.bitmap.height.toFloat())
            is WordItem -> item.textBounds
            is PromptItem -> RectF(0f, 0f, item.width, item.height)
            is TextItem -> RectF(0f, 0f, item.width, item.height)
            else -> item.bounds
        }
        
        if (item is StrokeItem) {
            return isStrokeHit(item, canvasX, canvasY, 30f)
        }

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
            } else if (item is ImageItem || item is TextItem || item is PromptItem) {
                if (!item.isLocked && isItemHit(item, canvasX, canvasY)) {
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
        if (item.isLocked) return false
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

    // --- Lasso Selection ---
    private fun performLassoSelection() {
        if (lassoPoints.size < 3) {
            clearLassoSelection()
            return
        }
        
        selectedItems.clear()
        selectedImage = null
        selectedWord = null
        
        for (item in drawItems) {
            if (isItemInsideLasso(item, lassoPoints)) {
                selectedItems.add(item)
            }
        }
        
        if (selectedItems.isEmpty()) {
            lassoPath = null
        } else {
            // Keep lasso path visible to indicate selection area?
            // Actually, usually it disappears and items get highlighted.
            lassoPath = null
        }
        invalidate()
    }

    private fun isItemInsideLasso(item: CanvasItem, polygon: List<PointF>): Boolean {
        val bounds = item.bounds
        // Check if the center of the item is inside the lasso
        return isPointInPolygon(bounds.centerX(), bounds.centerY(), polygon)
    }

    private fun isPointInPolygon(px: Float, py: Float, polygon: List<PointF>): Boolean {
        var collision = false
        var next = 0
        for (current in polygon.indices) {
            next = current + 1
            if (next == polygon.size) next = 0
            val vc = polygon[current]
            val vn = polygon[next]
            if (((vc.y >= py && vn.y < py) || (vc.y < py && vn.y >= py)) &&
                (px < (vn.x - vc.x) * (py - vc.y) / (vn.y - vc.y) + vc.x)
            ) {
                collision = !collision
            }
        }
        return collision
    }
    // ══════════════════════════════════════════════════════════════════════
    // Export Helpers
    // ══════════════════════════════════════════════════════════════════════

    fun getAllContentBounds(): RectF {
        val bounds = RectF()
        if (drawItems.isEmpty()) return bounds
        bounds.set(drawItems[0].bounds)
        for (i in 1 until drawItems.size) {
            bounds.union(drawItems[i].bounds)
        }
        return bounds
    }

    fun getPageRect(pageIndex: Int): RectF {
        val top = pageIndex * (PAGE_HEIGHT + PAGE_MARGIN)
        return RectF(0f, top, PAGE_WIDTH, top + PAGE_HEIGHT)
    }

    fun getNonEmptyPageIndices(): List<Int> {
        if (notebookType == NotebookType.WHITEBOARD) return listOf(0)
        
        val indices = mutableListOf<Int>()
        for (i in 0 until numPages) {
            val pageRect = getPageRect(i)
            val hasItems = drawItems.any { RectF.intersects(it.bounds, pageRect) }
            if (hasItems) {
                indices.add(i)
            }
        }
        return indices
    }

    /**
     * Renders specified pages or content to an external canvas.
     * If pageIndices is null, renders the whole content within the specified bounds.
     */
    fun renderToExternalCanvas(canvas: Canvas, bounds: RectF, pageIndices: List<Int>? = null) {
        canvas.save()
        // Translate canvas so that 'bounds.left, bounds.top' is at '0, 0'
        canvas.translate(-bounds.left, -bounds.top)
        
        // Draw background
        if (notebookType == NotebookType.NOTEBOOK) {
            val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
            if (pageIndices != null) {
                for (idx in pageIndices) {
                    canvas.drawRect(getPageRect(idx), bgPaint)
                }
            } else {
                canvas.drawRect(bounds, bgPaint)
            }
        }

        // Draw items
        for (item in drawItems) {
            if (pageIndices != null) {
                // If specific pages are requested, check if item intersects with any of them
                val onRequestedPage = pageIndices.any { RectF.intersects(item.bounds, getPageRect(it)) }
                if (onRequestedPage) {
                    item.draw(canvas)
                }
            } else {
                // Otherwise check intersection with the overall bounds
                if (RectF.intersects(item.bounds, bounds)) {
                    item.draw(canvas)
                }
            }
        }
        
        canvas.restore()
    }

    fun getSvgDataForExport(pageIndices: List<Int>? = null): List<SvgData> {
        return if (pageIndices == null) {
            drawItems.map { it.toSvgData() }
        } else {
            drawItems.filter { item ->
                pageIndices.any { RectF.intersects(item.bounds, getPageRect(it)) }
            }.map { it.toSvgData() }
        }
    }
}
