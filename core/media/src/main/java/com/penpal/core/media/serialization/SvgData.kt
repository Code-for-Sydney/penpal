package com.penpal.core.media.serialization

import android.graphics.RectF

data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        fun fromRectF(rect: RectF) = FloatRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}

sealed class PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand()
    data class QuadTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PathCommand()
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : PathCommand()
    data class LineTo(val x: Float, val y: Float) : PathCommand()
}

sealed class SvgData

data class StrokeData(
    val commands: List<PathCommand>,
    val color: Int,
    val strokeWidth: Float,
    val opacity: Int,
    val isEraser: Boolean = false,
    val isLocked: Boolean = false
) : SvgData()

data class ImageData(
    val base64: String,
    val matrix: FloatArray,
    val removeBackground: Boolean = true,
    val text: String = "",
    val isShowingText: Boolean = false,
    val textMatrix: FloatArray? = null,
    val textBounds: FloatRect? = null,
    val pdfWords: List<PdfWord> = emptyList(),
    val isAiRecognized: Boolean = false,
    val isLocked: Boolean = false
) : SvgData() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageData
        if (base64 != other.base64) return false
        if (!matrix.contentEquals(other.matrix)) return false
        if (removeBackground != other.removeBackground) return false
        if (text != other.text) return false
        if (isShowingText != other.isShowingText) return false
        if (textMatrix != null) {
            if (other.textMatrix == null) return false
            if (!textMatrix.contentEquals(other.textMatrix)) return false
        } else if (other.textMatrix != null) return false
        if (textBounds != other.textBounds) return false
        if (pdfWords != other.pdfWords) return false
        if (isAiRecognized != other.isAiRecognized) return false
        if (isLocked != other.isLocked) return false
        return true
    }

    override fun hashCode(): Int {
        var result = base64.hashCode()
        result = 31 * result + matrix.contentHashCode()
        result = 31 * result + removeBackground.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + isShowingText.hashCode()
        result = 31 * result + (textMatrix?.contentHashCode() ?: 0)
        result = 31 * result + (textBounds?.hashCode() ?: 0)
        result = 31 * result + pdfWords.hashCode()
        result = 31 * result + isAiRecognized.hashCode()
        result = 31 * result + isLocked.hashCode()
        return result
    }
}

data class WordData(
    val strokes: List<StrokeData>,
    val matrix: FloatArray,
    val text: String,
    val isShowingText: Boolean = false,
    val textMatrix: FloatArray? = null,
    val textBounds: FloatRect? = null,
    val tintColor: Int? = null,
    val backgroundColor: Int? = null,
    val isLocked: Boolean = false
) : SvgData() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WordData
        if (strokes != other.strokes) return false
        if (!matrix.contentEquals(other.matrix)) return false
        if (text != other.text) return false
        if (isShowingText != other.isShowingText) return false
        if (textMatrix != null) {
            if (other.textMatrix == null) return false
            if (!textMatrix.contentEquals(other.textMatrix)) return false
        } else if (other.textMatrix != null) return false
        if (textBounds != other.textBounds) return false
        if (tintColor != other.tintColor) return false
        if (backgroundColor != other.backgroundColor) return false
        if (isLocked != other.isLocked) return false
        return true
    }

    override fun hashCode(): Int {
        var result = strokes.hashCode()
        result = 31 * result + matrix.contentHashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + isShowingText.hashCode()
        result = 31 * result + (textMatrix?.contentHashCode() ?: 0)
        result = 31 * result + (textBounds?.hashCode() ?: 0)
        result = 31 * result + (tintColor ?: 0)
        result = 31 * result + (backgroundColor ?: 0)
        result = 31 * result + isLocked.hashCode()
        return result
    }
}

data class PromptData(
    val prompt: String,
    val result: String,
    val isShowingResult: Boolean,
    val matrix: FloatArray,
    val width: Float,
    val height: Float,
    val isLocked: Boolean = false
) : SvgData() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PromptData
        if (prompt != other.prompt) return false
        if (result != other.result) return false
        if (isShowingResult != other.isShowingResult) return false
        if (!matrix.contentEquals(other.matrix)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (isLocked != other.isLocked) return false
        return true
    }

    override fun hashCode(): Int {
        var result = prompt.hashCode()
        result = 31 * result + result.hashCode()
        result = 31 * result + isShowingResult.hashCode()
        result = 31 * result + matrix.contentHashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + isLocked.hashCode()
        return result
    }
}

data class TextData(
    val text: String,
    val matrix: FloatArray,
    val color: Int,
    val fontSize: Float,
    val width: Float,
    val height: Float,
    val isLocked: Boolean = false
) : SvgData() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TextData
        if (text != other.text) return false
        if (!matrix.contentEquals(other.matrix)) return false
        if (color != other.color) return false
        if (fontSize != other.fontSize) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (isLocked != other.isLocked) return false
        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + matrix.contentHashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + isLocked.hashCode()
        return result
    }
}

data class PdfWord(val text: String, val bounds: FloatRect)