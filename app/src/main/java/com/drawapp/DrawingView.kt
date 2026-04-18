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
        val paint: Paint
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


    private var currentPaint = buildPaint()

    // --- Canvas bitmap for performance ---
    private var canvasBitmap: Bitmap? = null
    private var drawingCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)

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
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawingCanvas = Canvas(canvasBitmap!!)
        drawingCanvas!!.drawColor(canvasBackgroundColor)
        // Redraw all existing strokes onto new bitmap
        redrawAll()
    }

    private fun redrawAll() {
        val dc = drawingCanvas ?: return
        dc.drawColor(canvasBackgroundColor)
        for (stroke in strokes) {
            dc.drawPath(stroke.path, stroke.paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the committed strokes bitmap
        canvasBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        // Draw in-progress stroke live
        canvas.drawPath(currentPath, currentPaint)
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

        // Save stroke for undo
        strokes.add(Stroke(currentPath, strokePaint))
        redoStack.clear()

        // Reset current path
        currentPath = Path()

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
}
