package com.drawapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: android.graphics.Bitmap? = null
    private val selectionRect = RectF()
    private var isSelecting = false
    private var startX = 0f
    private var startY = 0f

    private val selectionPaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#337C4DFF")
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#7C4DFF")
        style = Paint.Style.FILL
    }

    fun setBitmap(bmp: android.graphics.Bitmap) {
        bitmap = bmp
        selectionRect.set(0f, 0f, 0f, 0f)
        isSelecting = false
        invalidate()
    }

    fun getCropRect(): android.graphics.Rect {
        val left = selectionRect.left.toInt().coerceIn(0, (bitmap?.width ?: 0) - 1)
        val top = selectionRect.top.toInt().coerceIn(0, (bitmap?.height ?: 0) - 1)
        val right = selectionRect.right.toInt().coerceIn(left + 1, bitmap?.width ?: 0)
        val bottom = selectionRect.bottom.toInt().coerceIn(top + 1, bitmap?.height ?: 0)
        return android.graphics.Rect(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let { bmp ->
            val scaleX = width.toFloat() / bmp.width
            val scaleY = height.toFloat() / bmp.height
            val scale = minOf(scaleX, scaleY)

            val scaledWidth = bmp.width * scale
            val scaledHeight = bmp.height * scale
            val offsetX = (width - scaledWidth) / 2
            val offsetY = (height - scaledHeight) / 2

            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)

            canvas.drawBitmap(bmp, 0f, 0f, null)

            if (selectionRect.width() > 0 && selectionRect.height() > 0) {
                canvas.drawRect(selectionRect, fillPaint)
                canvas.drawRect(selectionRect, selectionPaint)

                val cornerSize = 12f
                val corners = arrayOf(
                    selectionRect.left to selectionRect.top,
                    selectionRect.right to selectionRect.top,
                    selectionRect.left to selectionRect.bottom,
                    selectionRect.right to selectionRect.bottom
                )
                corners.forEach { (x, y) ->
                    canvas.drawCircle(x, y, cornerSize, handlePaint)
                }
            }

            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                selectionRect.set(startX, startY, startX, startY)
                isSelecting = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSelecting) {
                    selectionRect.set(
                        minOf(startX, event.x),
                        minOf(startY, event.y),
                        maxOf(startX, event.x),
                        maxOf(startY, event.y)
                    )
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isSelecting = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}