package com.drawapp

import android.graphics.Color
import android.graphics.RectF
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

// Raw path commands that can be serialized to/from SVG
sealed class PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand()
    data class QuadTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PathCommand()
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) : PathCommand()
    data class LineTo(val x: Float, val y: Float) : PathCommand()
}

// Serializable stroke representation
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
    val isLocked: Boolean = false
) : SvgData()

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
) : SvgData()

data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class SvgResult(
    val items: List<SvgData>,
    val backgroundType: String = "RULED"
)


object SvgSerializer {

    fun serialize(items: List<SvgData>, width: Int, height: Int, backgroundColor: Int, backgroundType: String = "RULED", contentBounds: RectF? = null): String {

        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")

        // Use content bounds for viewBox if available, otherwise use screen dimensions
        val vbX: Float
        val vbY: Float
        val vbW: Float
        val vbH: Float
        if (contentBounds != null && !contentBounds.isEmpty) {
            val margin = 20f
            vbX = contentBounds.left - margin
            vbY = contentBounds.top - margin
            vbW = contentBounds.width() + margin * 2
            vbH = contentBounds.height() + margin * 2
        } else {
            vbX = 0f
            vbY = 0f
            vbW = width.toFloat()
            vbH = height.toFloat()
        }
        sb.appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${vbW.toInt()}" height="${vbH.toInt()}" viewBox="$vbX $vbY $vbW $vbH" data-background-type="$backgroundType">""")


        // Background rect
        sb.appendLine("""  <rect x="$vbX" y="$vbY" width="$vbW" height="$vbH" fill="${colorToHex(backgroundColor)}"/>""")

        // Each item
        for ((index, item) in items.withIndex()) {
            when (item) {
                is StrokeData -> {
                    val d = commandsToSvgPath(item.commands)
                    if (d.isBlank()) continue
                    val hex = colorToHex(item.color)
                    val opacity = item.opacity / 255f

                    sb.append("""  <path id="stroke-$index" d="$d"""")
                    sb.append(""" stroke="$hex" stroke-width="${item.strokeWidth}"""")
                    sb.append(""" stroke-opacity="$opacity"""")
                    sb.append(""" stroke-linecap="round" stroke-linejoin="round" fill="none"""")
                    if (item.isLocked) sb.append(""" data-locked="true"""")
                    sb.appendLine("""/>""")
                }
                is ImageData -> {
                    val m = item.matrix
                    val transform = "matrix(${m[0]},${m[3]},${m[1]},${m[4]},${m[2]},${m[5]})"
                    sb.append("""  <image id="image-$index" transform="$transform" href="data:image/jpeg;base64,${item.base64}"""")
                    sb.append(""" data-remove-bg="${item.removeBackground}"""")
                    sb.append(""" data-text="${item.text}" data-showing-text="${item.isShowingText}"""")
                    if (item.isLocked) sb.append(""" data-locked="true"""")
                    item.textMatrix?.let { tm ->
                        val tmStr = "matrix(${tm[0]},${tm[3]},${tm[1]},${tm[4]},${tm[2]},${tm[5]})"
                        sb.append(""" data-text-transform="$tmStr"""")
                    }
                    item.textBounds?.let { tb ->
                        sb.append(""" data-text-bounds="${tb.left},${tb.top},${tb.right},${tb.bottom}"""")
                    }
                    sb.appendLine("/>")
                }
                is WordData -> {
                    val m = item.matrix
                    val transform = "matrix(${m[0]},${m[3]},${m[1]},${m[4]},${m[2]},${m[5]})"
                    sb.append("""  <g id="word-$index" transform="$transform" data-text="${item.text}" data-showing-text="${item.isShowingText}"""")
                    if (item.isLocked) sb.append(""" data-locked="true"""")
                    item.tintColor?.let { sb.append(""" data-tint="${colorToHex(it)}"""") }
                    item.backgroundColor?.let { sb.append(""" data-bg-fill="${colorToHex(it)}"""") }
                    
                    item.textMatrix?.let { tm ->
                        val tmStr = "matrix(${tm[0]},${tm[3]},${tm[1]},${tm[4]},${tm[2]},${tm[5]})"
                        sb.append(""" data-text-transform="$tmStr"""")
                    }
                    item.textBounds?.let { tb ->
                        sb.append(""" data-text-bounds="${tb.left},${tb.top},${tb.right},${tb.bottom}"""")
                    }
                    sb.appendLine(">")

                    for (stroke in item.strokes) {
                        val d = commandsToSvgPath(stroke.commands)
                        if (d.isBlank()) continue
                        val hex = colorToHex(stroke.color)
                        val opacity = stroke.opacity / 255f
                        sb.append("""    <path d="$d" stroke="$hex" stroke-width="${stroke.strokeWidth}" stroke-opacity="$opacity" stroke-linecap="round" stroke-linejoin="round" fill="none"""")
                        sb.appendLine("""/>""")
                    }
                    sb.appendLine("  </g>")
                }
            }
        }

        sb.appendLine("</svg>")
        return sb.toString()
    }

    fun deserialize(svg: String): SvgResult {
        val items = mutableListOf<SvgData>()
        var backgroundType = "RULED"

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(svg))

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                if (parser.name == "svg") {
                    backgroundType = parser.getAttributeValue(null, "data-background-type") ?: "RULED"
                } else if (parser.name == "path") {

                    val pathData = parser.getAttributeValue(null, "d") ?: ""
                    val strokeColor = parser.getAttributeValue(null, "stroke") ?: "#000000"
                    val strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: 12f
                    val strokeOpacity = parser.getAttributeValue(null, "stroke-opacity")?.toFloatOrNull() ?: 1f

                    val commands = svgPathToCommands(pathData)
                    if (commands.isNotEmpty()) {
                        items.add(StrokeData(
                            commands = commands,
                            color = parseHexColor(strokeColor),
                            strokeWidth = strokeWidth,
                            opacity = (strokeOpacity * 255).toInt().coerceIn(0, 255),
                            isLocked = parser.getAttributeValue(null, "data-locked") == "true"
                        ))
                    }
                } else if (parser.name == "image") {
                    val transform = parser.getAttributeValue(null, "transform") ?: ""
                    val href = parser.getAttributeValue(null, "href") ?: ""
                    val m = parseMatrix(transform)
                    val base64Data = if (href.startsWith("data:image/png;base64,")) {
                        href.substring("data:image/png;base64,".length)
                    } else if (href.startsWith("data:image/jpeg;base64,")) {
                        href.substring("data:image/jpeg;base64,".length)
                    } else href
                    val removeBg = parser.getAttributeValue(null, "data-remove-bg") != "false"
                    val text = parser.getAttributeValue(null, "data-text") ?: ""
                    val isShowingText = parser.getAttributeValue(null, "data-showing-text") == "true"
                    val textMatrixStr = parser.getAttributeValue(null, "data-text-transform")
                    val textMatrix = if (textMatrixStr != null) parseMatrix(textMatrixStr) else null
                    val textBoundsStr = parser.getAttributeValue(null, "data-text-bounds")
                    val textBounds = if (textBoundsStr != null) {
                        val parts = textBoundsStr.split(",")
                        if (parts.size == 4) {
                            FloatRect(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        } else null
                    } else null
                    val isLocked = parser.getAttributeValue(null, "data-locked") == "true"
                    
                    items.add(ImageData(base64Data, m, removeBg, text, isShowingText, textMatrix, textBounds, isLocked))
                } else if (parser.name == "g") {
                    val text = parser.getAttributeValue(null, "data-text") ?: ""
                    val transform = parser.getAttributeValue(null, "transform") ?: ""
                    val m = parseMatrix(transform)
                    val isShowingText = parser.getAttributeValue(null, "data-showing-text") == "true"
                    val isLocked = parser.getAttributeValue(null, "data-locked") == "true"
                    
                    val textMatrixStr = parser.getAttributeValue(null, "data-text-transform")
                    val textMatrix = if (textMatrixStr != null) parseMatrix(textMatrixStr) else null
                    val tintColor = parser.getAttributeValue(null, "data-tint")?.let { parseHexColor(it) }
                    val backgroundColor = parser.getAttributeValue(null, "data-bg-fill")?.let { parseHexColor(it) }
                    
                    val textBoundsStr = parser.getAttributeValue(null, "data-text-bounds")
                    val textBounds = if (textBoundsStr != null) {
                        val parts = textBoundsStr.split(",")
                        if (parts.size == 4) {
                            FloatRect(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        } else null
                    } else null
                    
                    val strokes = mutableListOf<StrokeData>()
                    var innerEvent = parser.next()
                    while (!(innerEvent == XmlPullParser.END_TAG && parser.name == "g")) {
                        if (innerEvent == XmlPullParser.START_TAG && parser.name == "path") {
                            val strokeData = parsePath(parser)
                            if (strokeData != null) strokes.add(strokeData)
                        }
                        innerEvent = parser.next()
                    }
                    items.add(WordData(strokes, m, text, isShowingText, textMatrix, textBounds, tintColor, backgroundColor, isLocked))
                }
            }
            eventType = parser.next()
        }
        return SvgResult(items, backgroundType)
    }

    private fun parseMatrix(transform: String): FloatArray {
        val m = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
        if (transform.startsWith("matrix(") && transform.endsWith(")")) {
            val vals = transform.removePrefix("matrix(").removeSuffix(")").split(",")
            if (vals.size == 6) {
                val a = vals[0].toFloatOrNull() ?: 1f
                val b = vals[1].toFloatOrNull() ?: 0f
                val c = vals[2].toFloatOrNull() ?: 0f
                val d = vals[3].toFloatOrNull() ?: 1f
                val e = vals[4].toFloatOrNull() ?: 0f
                val f = vals[5].toFloatOrNull() ?: 0f
                
                m[android.graphics.Matrix.MSCALE_X] = a
                m[android.graphics.Matrix.MSKEW_X] = c
                m[android.graphics.Matrix.MTRANS_X] = e
                m[android.graphics.Matrix.MSKEW_Y] = b
                m[android.graphics.Matrix.MSCALE_Y] = d
                m[android.graphics.Matrix.MTRANS_Y] = f
            }
        }
        return m
    }

    private fun parsePath(parser: XmlPullParser): StrokeData? {
        val pathData = parser.getAttributeValue(null, "d") ?: ""
        val strokeColor = parser.getAttributeValue(null, "stroke") ?: "#000000"
        val strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: 12f
        val strokeOpacity = parser.getAttributeValue(null, "stroke-opacity")?.toFloatOrNull() ?: 1f

        val commands = svgPathToCommands(pathData)
        return if (commands.isNotEmpty()) {
            StrokeData(
                commands = commands,
                color = parseHexColor(strokeColor),
                strokeWidth = strokeWidth,
                opacity = (strokeOpacity * 255).toInt().coerceIn(0, 255)
            )
        } else null
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    private fun commandsToSvgPath(commands: List<PathCommand>): String {
        val sb = StringBuilder()
        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo -> sb.append("M ${cmd.x} ${cmd.y} ")
                is PathCommand.QuadTo -> sb.append("Q ${cmd.x1} ${cmd.y1} ${cmd.x2} ${cmd.y2} ")
                is PathCommand.CubicTo -> sb.append("C ${cmd.x1} ${cmd.y1} ${cmd.x2} ${cmd.y2} ${cmd.x3} ${cmd.y3} ")
                is PathCommand.LineTo -> sb.append("L ${cmd.x} ${cmd.y} ")
            }
        }
        return sb.toString().trim()
    }

    private fun svgPathToCommands(pathData: String): List<PathCommand> {
        val commands = mutableListOf<PathCommand>()
        val tokens = pathData.trim().split(Regex("\\s+"))
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "M" -> {
                    if (i + 2 < tokens.size) {
                        commands.add(PathCommand.MoveTo(tokens[i+1].toFloat(), tokens[i+2].toFloat()))
                        i += 3
                    } else i++
                }
                "Q" -> {
                    if (i + 4 < tokens.size) {
                        commands.add(PathCommand.QuadTo(
                            tokens[i+1].toFloat(), tokens[i+2].toFloat(),
                            tokens[i+3].toFloat(), tokens[i+4].toFloat()
                        ))
                        i += 5
                    } else i++
                }
                "C" -> {
                    if (i + 6 < tokens.size) {
                        commands.add(PathCommand.CubicTo(
                            tokens[i+1].toFloat(), tokens[i+2].toFloat(),
                            tokens[i+3].toFloat(), tokens[i+4].toFloat(),
                            tokens[i+5].toFloat(), tokens[i+6].toFloat()
                        ))
                        i += 7
                    } else i++
                }
                "L" -> {
                    if (i + 2 < tokens.size) {
                        commands.add(PathCommand.LineTo(tokens[i+1].toFloat(), tokens[i+2].toFloat()))
                        i += 3
                    } else i++
                }
                else -> i++
            }
        }
        return commands
    }

    private fun colorToHex(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    private fun parseHexColor(hex: String): Int =
        try { Color.parseColor(hex) } catch (_: Exception) { Color.BLACK }
}
