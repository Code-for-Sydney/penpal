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
data class StrokeData(
    val commands: List<PathCommand>,
    val color: Int,
    val strokeWidth: Float,
    val opacity: Int,
    val isEraser: Boolean
)

object SvgSerializer {

    fun serialize(strokes: List<StrokeData>, width: Int, height: Int, backgroundColor: Int, contentBounds: RectF? = null): String {
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
        sb.appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${vbW.toInt()}" height="${vbH.toInt()}" viewBox="$vbX $vbY $vbW $vbH">""")

        // Background rect
        sb.appendLine("""  <rect x="$vbX" y="$vbY" width="$vbW" height="$vbH" fill="${colorToHex(backgroundColor)}"/>""")

        // Each stroke as a separate <path> element
        for ((index, stroke) in strokes.withIndex()) {
            val d = commandsToSvgPath(stroke.commands)
            if (d.isBlank()) continue
            val hex = colorToHex(stroke.color)
            val opacity = stroke.opacity / 255f

            sb.append("""  <path id="stroke-$index" d="$d"""")
            sb.append(""" stroke="$hex" stroke-width="${stroke.strokeWidth}"""")
            sb.append(""" stroke-opacity="$opacity"""")
            sb.append(""" stroke-linecap="round" stroke-linejoin="round" fill="none"""")
            if (stroke.isEraser) {
                sb.append(""" data-eraser="true"""")
            }
            sb.appendLine("""/>""")
        }

        sb.appendLine("</svg>")
        return sb.toString()
    }

    fun deserialize(svg: String): List<StrokeData> {
        val strokes = mutableListOf<StrokeData>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(svg))

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "path") {
                val pathData = parser.getAttributeValue(null, "d") ?: ""
                val strokeColor = parser.getAttributeValue(null, "stroke") ?: "#000000"
                val strokeWidth = parser.getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: 12f
                val strokeOpacity = parser.getAttributeValue(null, "stroke-opacity")?.toFloatOrNull() ?: 1f
                val isEraser = parser.getAttributeValue(null, "data-eraser") == "true"

                val commands = svgPathToCommands(pathData)
                if (commands.isNotEmpty()) {
                    strokes.add(StrokeData(
                        commands = commands,
                        color = parseHexColor(strokeColor),
                        strokeWidth = strokeWidth,
                        opacity = (strokeOpacity * 255).toInt().coerceIn(0, 255),
                        isEraser = isEraser
                    ))
                }
            }
            eventType = parser.next()
        }
        return strokes
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
