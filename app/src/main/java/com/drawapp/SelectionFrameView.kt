package com.drawapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SelectionFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pdfBitmap: Bitmap? = null
    var selectionRect = RectF(100f, 100f, 400f, 400f)
    
    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.argb(40, 124, 77, 255)
        style = Paint.Style.FILL
    }

    private val HANDLE_RADIUS = 30f
    private var lastX = 0f
    private var lastY = 0f
    private var isResizing = false
    private var isMovingBox = false
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(0.2f, 5.0f)

            val focusX = detector.focusX
            val focusY = detector.focusY
            val scaleChange = scaleFactor / oldScale

            translateX = focusX - scaleChange * (focusX - translateX)
            translateY = focusY - scaleChange * (focusY - translateY)

            updateMatrix()
            invalidate()
            return true
        }
    })

    init {
        updateMatrix()
    }

    fun setBitmap(bitmap: Bitmap) {
        pdfBitmap = bitmap
        // Center the bitmap initially
        post {
            if (width > 0 && height > 0) {
                val scaleW = width.toFloat() / bitmap.width
                val scaleH = height.toFloat() / bitmap.height
                scaleFactor = min(scaleW, scaleH) * 0.9f
                translateX = (width - bitmap.width * scaleFactor) / 2f
                translateY = (height - bitmap.height * scaleFactor) / 2f
                
                // Initial selection box
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                selectionRect.set(bw * 0.1f, bh * 0.1f, bw * 0.9f, bh * 0.3f)
                
                updateMatrix()
                invalidate()
            }
        }
    }

    private fun updateMatrix() {
        viewMatrix.reset()
        viewMatrix.postScale(scaleFactor, scaleFactor)
        viewMatrix.postTranslate(translateX, translateY)
        viewMatrix.invert(inverseMatrix)
    }

    private fun screenToCanvas(x: Float, y: Float): FloatArray {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        canvas.save()
        canvas.concat(viewMatrix)
        
        pdfBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        canvas.drawRect(selectionRect, fillPaint)
        canvas.drawRect(selectionRect, borderPaint)
        
        // Draw resize handle at bottom-right of selection box
        val handleRadiusInCanvas = HANDLE_RADIUS / scaleFactor
        canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadiusInCanvas, handlePaint)
        
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        val pointerCount = event.pointerCount
        val canvasCoords = screenToCanvas(event.x, event.y)
        val cx = canvasCoords[0]
        val cy = canvasCoords[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                
                // Check handle hit (in canvas space)
                val handleRadiusInCanvas = HANDLE_RADIUS / scaleFactor
                val dx = cx - selectionRect.right
                val dy = cy - selectionRect.bottom
                if (dx * dx + dy * dy <= (handleRadiusInCanvas * 2) * (handleRadiusInCanvas * 2)) {
                    isResizing = true
                    isMovingBox = false
                    isPanning = false
                } else if (selectionRect.contains(cx, cy)) {
                    isMovingBox = true
                    isResizing = false
                    isPanning = false
                } else {
                    isPanning = false // Wait for second pointer or movement
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isPanning = true
                isMovingBox = false
                isResizing = false
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning || pointerCount > 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    translateX += dx
                    translateY += dy
                    updateMatrix()
                    invalidate()
                } else if (isResizing) {
                    selectionRect.right = max(selectionRect.left + 20f, cx)
                    selectionRect.bottom = max(selectionRect.top + 20f, cy)
                    invalidate()
                } else if (isMovingBox) {
                    val lastCanvas = screenToCanvas(lastX, lastY)
                    val dx = cx - lastCanvas[0]
                    val dy = cy - lastCanvas[1]
                    selectionRect.offset(dx, dy)
                    invalidate()
                }
                
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isResizing = false
                isMovingBox = false
                isPanning = false
            }
        }
        return true
    }

    fun getCropRect(): android.graphics.Rect {
        return android.graphics.Rect(
            selectionRect.left.toInt(),
            selectionRect.top.toInt(),
            selectionRect.right.toInt(),
            selectionRect.bottom.toInt()
        )
    }
}
