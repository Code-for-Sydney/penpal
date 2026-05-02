package com.drawapp

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream

object PdfHelper {

    fun extractText(context: Context, uri: Uri, pageIndex: Int): String {
        var document: PDDocument? = null
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                document = PDDocument.load(inputStream)
                if (pageIndex < document.numberOfPages) {
                    val stripper = PDFTextStripper()
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    return stripper.getText(document).trim()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            document?.close()
        }
        return ""
    }

    /**
     * Extracts text from a specific rectangle on a PDF page.
     * Uses a custom stripper to avoid AWT dependencies.
     */
    fun extractTextInRect(context: Context, uri: Uri, pageIndex: Int, rect: RectF, pageWidth: Float, pageHeight: Float): String {
        var document: PDDocument? = null
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                document = PDDocument.load(inputStream)
                if (pageIndex < document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val cropBox = page.cropBox
                    
                    val scaleX = cropBox.width / pageWidth
                    val scaleY = cropBox.height / pageHeight
                    
                    val pdfRect = RectF(
                        rect.left * scaleX,
                        rect.top * scaleY,
                        rect.right * scaleX,
                        rect.bottom * scaleY
                    )
                    
                    val stripper = object : PDFTextStripper() {
                        val result = StringBuilder()
                        
                        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                            textPositions?.forEach { tp ->
                                if (pdfRect.contains(tp.xDirAdj, tp.yDirAdj)) {
                                    result.append(tp.unicode)
                                }
                            }
                            result.append(" ")
                        }
                    }
                    
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.getText(document)
                    
                    return stripper.result.toString().trim().replace(Regex("\\s+"), " ")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            document?.close()
        }
        return ""
    }

    data class PdfWord(val text: String, val bounds: RectF)

    fun extractWords(context: Context, uri: Uri, pageIndex: Int, pageWidth: Float, pageHeight: Float): List<PdfWord> {
        var document: PDDocument? = null
        val words = mutableListOf<PdfWord>()
        try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                document = PDDocument.load(inputStream)
                if (pageIndex < document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val cropBox = page.cropBox
                    
                    val scaleX = pageWidth / cropBox.width
                    val scaleY = pageHeight / cropBox.height
                    
                    val stripper = object : PDFTextStripper() {
                        override fun writeString(string: String?, textPositions: MutableList<TextPosition>?) {
                            if (string == null || textPositions == null || textPositions.isEmpty()) return
                            
                            // Group positions into words
                            var currentWord = StringBuilder()
                            var wordRect: RectF? = null
                            
                            for (tp in textPositions) {
                                val charText = tp.unicode
                                if (charText.isNotBlank()) {
                                    currentWord.append(charText)
                                    val charRect = RectF(
                                        tp.xDirAdj * scaleX,
                                        tp.yDirAdj * scaleY,
                                        (tp.xDirAdj + tp.widthDirAdj) * scaleX,
                                        (tp.yDirAdj + tp.heightDir) * scaleY
                                    )
                                    if (wordRect == null) {
                                        wordRect = RectF(charRect)
                                    } else {
                                        wordRect.union(charRect)
                                    }
                                } else {
                                    if (currentWord.isNotEmpty()) {
                                        words.add(PdfWord(currentWord.toString(), RectF(wordRect)))
                                        currentWord = StringBuilder()
                                        wordRect = null
                                    }
                                }
                            }
                            if (currentWord.isNotEmpty()) {
                                words.add(PdfWord(currentWord.toString(), RectF(wordRect)))
                            }
                        }
                    }
                    
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    stripper.getText(document)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            document?.close()
        }
        return words
    }

    fun parseWords(wordsStr: String?): List<PdfWord> {
        if (wordsStr == null) return emptyList()
        return wordsStr.split(";").mapNotNull { wordPart ->
            val colonIdx = wordPart.lastIndexOf(":")
            if (colonIdx != -1) {
                val wordText = wordPart.substring(0, colonIdx)
                val coords = wordPart.substring(colonIdx + 1).split(",")
                if (coords.size == 4) {
                    PdfWord(wordText, RectF(coords[0].toFloat(), coords[1].toFloat(), coords[2].toFloat(), coords[3].toFloat()))
                } else null
            } else null
        }
    }
}
