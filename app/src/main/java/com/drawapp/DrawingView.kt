package com.drawapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Data classes ---
    data class Stroke(
        val path: Path,
        val paint: Paint,
        val bounds: RectF
    )

    // --- State ---
    private val strokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()

    private var currentPath = Path()
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

    // --- Canvas bitmap for performance ---
    private var canvasBitmap: Bitmap? = null
    private var drawingCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)
    
    // Original background image if loaded from file
    private var initialBitmap: Bitmap? = null

    // Background color
    var canvasBackgroundColor: Int = Color.parseColor("#1A1A2E")

    // Touch tolerance for smoothness
    private val TOUCH_TOLERANCE = 4f

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
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            color = canvasBackgroundColor
            strokeWidth = brushSize * 2.5f
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
            redrawAll()
            invalidate()
        }
    }

    private fun redrawAll() {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        
        // Recreate/prepare the canvas bitmap
        if (canvasBitmap == null || canvasBitmap!!.width != w || canvasBitmap!!.height != h) {
            canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawingCanvas = Canvas(canvasBitmap!!)
        }
        
        val dc = drawingCanvas ?: return
        
        // 1. Draw background color
        dc.drawColor(canvasBackgroundColor)
        
        // 2. Draw initial bitmap if present (scaled to fit)
        initialBitmap?.let {
            if (it.width != w || it.height != h) {
                val scaled = Bitmap.createScaledBitmap(it, w, h, true)
                dc.drawBitmap(scaled, 0f, 0f, bitmapPaint)
            } else {
                dc.drawBitmap(it, 0f, 0f, bitmapPaint)
            }
        }
        
        // 3. Redraw all strokes on top
        for (stroke in strokes) {
            dc.drawPath(stroke.path, stroke.paint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            redrawAll()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the committed strokes bitmap
        canvasBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        // Draw in-progress stroke live
        canvas.drawPath(currentPath, currentPaint)

        // Draw debug bounding box if present
        debugBoundingBox?.let {
            canvas.drawRect(it, debugPaint)
        }
    }

    // --- Touch handling ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStart(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                touchMove(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                touchUp()
                invalidate()
            }
        }
        return true
    }

    private fun touchStart(x: Float, y: Float) {
        currentPath = Path()
        currentPath.moveTo(x, y)
        lastX = x
        lastY = y
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = abs(x - lastX)
        val dy = abs(y - lastY)
        if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
            // Quadratic bezier for smoothness
            currentPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
            lastX = x
            lastY = y
        }
    }

    private fun touchUp() {
        currentPath.lineTo(lastX, lastY)

        // Snapshot paint
        val strokePaint = buildPaint()

        // Commit to bitmap
        drawingCanvas?.drawPath(currentPath, strokePaint)

        // Compute bounds
        val bounds = RectF()
        currentPath.computeBounds(bounds, true)
        // Adjust for stroke width
        val halfWidth = strokePaint.strokeWidth / 2f
        bounds.inset(-halfWidth, -halfWidth)

        // Save stroke for undo
        strokes.add(Stroke(currentPath, strokePaint, bounds))
        redoStack.clear()

        // Reset current path
        currentPath = Path()

        // Clear debug box on new interaction (optional, or keep it until next recognition)
        // debugBoundingBox = null 

        // Notify listener so recognition can be debounced
        onStrokeCompleted?.invoke()
    }

    // --- Undo / Redo ---
    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeLast())
            redrawAll()
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val stroke = redoStack.removeLast()
            strokes.add(stroke)
            drawingCanvas?.drawPath(stroke.path, stroke.paint)
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
        drawingCanvas?.drawColor(canvasBackgroundColor)
        invalidate()
    }

    // --- Save ---
    fun getBitmap(): Bitmap? = canvasBitmap?.copy(Bitmap.Config.ARGB_8888, false)

    // --- Cropped Bitmap for Recognition ---
    fun getRecentClusterBitmap(): Bitmap? {
        val bounds = getRecentClusterBounds() ?: return null
        
        // Add some margin (e.g., 40px)
        val margin = 40f
        val left = (bounds.left - margin).coerceAtLeast(0f)
        val top = (bounds.top - margin).coerceAtLeast(0f)
        val right = (bounds.right + margin).coerceAtMost(width.toFloat())
        val bottom = (bounds.bottom + margin).coerceAtMost(height.toFloat())
        
        val cropRect = RectF(left, top, right, bottom)
        if (cropRect.width() < 10 || cropRect.height() < 10) return null

        // Update debug box for visualization
        debugBoundingBox = cropRect
        invalidate()

        val fullBitmap = canvasBitmap ?: return null
        
        // Ensure coordinates are within bitmap
        val ix = left.toInt().coerceIn(0, fullBitmap.width - 1)
        val iy = top.toInt().coerceIn(0, fullBitmap.height - 1)
        val iw = cropRect.width().toInt().coerceAtMost(fullBitmap.width - ix)
        val ih = cropRect.height().toInt().coerceAtMost(fullBitmap.height - iy)

        if (iw <= 0 || ih <= 0) return null

        return Bitmap.createBitmap(fullBitmap, ix, iy, iw, ih)
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
}
