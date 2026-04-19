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
    data class LineTo(val x: Float, val y: Float) : PathCommand()
}

// Serializable stroke representation
sealed class SvgData

data class StrokeData(
    val commands: List<PathCommand>,
    val color: Int,
    val strokeWidth: Float,
    val opacity: Int,
    val isEraser: Boolean
) : SvgData()

data class ImageData(
    val base64: String,
    val matrix: FloatArray
) : SvgData()

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
                    if (item.isEraser) {
                        sb.append(""" data-eraser="true"""")
                    }
                    sb.appendLine("""/>""")
                }
                is ImageData -> {
                    val m = item.matrix
                    val transform = "matrix(${m[0]},${m[3]},${m[1]},${m[4]},${m[2]},${m[5]})"
                    sb.appendLine("""  <image id="image-$index" transform="$transform" href="data:image/png;base64,${item.base64}"/>""")
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
                    val isEraser = parser.getAttributeValue(null, "data-eraser") == "true"

                    val commands = svgPathToCommands(pathData)
                    if (commands.isNotEmpty()) {
                        items.add(StrokeData(
                            commands = commands,
                            color = parseHexColor(strokeColor),
                            strokeWidth = strokeWidth,
                            opacity = (strokeOpacity * 255).toInt().coerceIn(0, 255),
                            isEraser = isEraser
                        ))
                    }
                } else if (parser.name == "image") {
                    val href = parser.getAttributeValue(null, "href") ?: ""
                    val transform = parser.getAttributeValue(null, "transform") ?: ""
                    
                    val base64 = href.removePrefix("data:image/png;base64,")
                    
                    // Parse matrix(a,b,c,d,e,f)
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
                            
                            // SVG matrix is a, b, c, d, e, f
                            // a c e
                            // b d f
                            // 0 0 1
                            // Android Matrix is:
                            // MSCALE_X (0), MSKEW_X (1), MTRANS_X (2)
                            // MSKEW_Y (3), MSCALE_Y (4), MTRANS_Y (5)
                            m[android.graphics.Matrix.MSCALE_X] = a
                            m[android.graphics.Matrix.MSKEW_X] = c
                            m[android.graphics.Matrix.MTRANS_X] = e
                            m[android.graphics.Matrix.MSKEW_Y] = b
                            m[android.graphics.Matrix.MSCALE_Y] = d
                            m[android.graphics.Matrix.MTRANS_Y] = f
                        }
                    }
                    items.add(ImageData(base64, m))
                }
            }
            eventType = parser.next()
        }
        return SvgResult(items, backgroundType)
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    private fun commandsToSvgPath(commands: List<PathCommand>): String {
        val sb = StringBuilder()
        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo -> sb.append("M ${cmd.x} ${cmd.y} ")
                is PathCommand.QuadTo -> sb.append("Q ${cmd.x1} ${cmd.y1} ${cmd.x2} ${cmd.y2} ")
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
