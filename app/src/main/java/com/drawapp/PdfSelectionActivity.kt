package com.drawapp

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class PdfSelectionActivity : AppCompatActivity() {

    private lateinit var pdfSelectionView: SelectionFrameView
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnBack: ImageButton
    private lateinit var btnConfirmSelection: Button
    private lateinit var progressBar: ProgressBar

    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPageIndex: Int = 0
    private var currentBitmap: Bitmap? = null
    private var pdfUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_selection)

        pdfSelectionView = findViewById(R.id.pdfSelectionView)
        tvPageIndicator = findViewById(R.id.tvPdfPageIndicator)
        btnPrev = findViewById(R.id.btnPdfPrev)
        btnNext = findViewById(R.id.btnPdfNext)
        btnBack = findViewById(R.id.btnPdfBack)
        btnConfirmSelection = findViewById(R.id.btnConfirmSelection)
        progressBar = findViewById(R.id.pdfLoadingProgress)

        val pdfUriString = intent.getStringExtra("PDF_URI")
        if (pdfUriString == null) {
            Toast.makeText(this, "No PDF provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pdfUri = Uri.parse(pdfUriString)
        setupPdfRenderer(pdfUri!!)

        btnBack.setOnClickListener { finish() }
        btnPrev.setOnClickListener { navigatePage(-1) }
        btnNext.setOnClickListener { navigatePage(1) }
        btnConfirmSelection.setOnClickListener { confirmCrop() }

        displayPage(0)
    }

    private fun setupPdfRenderer(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pdfRenderer = PdfRenderer(pfd)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error opening PDF", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun navigatePage(delta: Int) {
        val nextIndex = currentPageIndex + delta
        pdfRenderer?.let {
            if (nextIndex in 0 until it.pageCount) {
                displayPage(nextIndex)
            }
        }
    }

    private fun displayPage(index: Int) {
        pdfRenderer?.let { renderer ->
            if (index < 0 || index >= renderer.pageCount) return

            currentPage?.close()
            val page = renderer.openPage(index)
            currentPage = page
            currentPageIndex = index

            // Higher quality for cropping
            val scale = 2.0f
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            
            pdfSelectionView.setBitmap(bitmap)
            currentBitmap = bitmap
            tvPageIndicator.text = "Page ${index + 1}/${renderer.pageCount}"
            
            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < renderer.pageCount - 1
        }
    }

    private fun confirmCrop() {
        val bitmap = currentBitmap ?: return
        val rect = pdfSelectionView.getCropRect()
        extractSnippet(rect, bitmap)
    }

    private fun extractSnippet(rect: Rect, fullBitmap: Bitmap) {
        try {
            // Ensure rect is within bitmap bounds
            val safeRect = Rect(
                rect.left.coerceAtLeast(0),
                rect.top.coerceAtLeast(0),
                rect.right.coerceAtMost(fullBitmap.width),
                rect.bottom.coerceAtMost(fullBitmap.height)
            )
            
            if (safeRect.isEmpty || safeRect.width() < 10 || safeRect.height() < 10) {
                Toast.makeText(this, "Selection too small", Toast.LENGTH_SHORT).show()
                return
            }

            val cropped = Bitmap.createBitmap(fullBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
            
            // Extract digital text and word bounds from the crop region
            var extractedText = ""
            var pdfWordsStr = ""
            pdfUri?.let { uri ->
                extractedText = PdfHelper.extractTextInRect(
                    this,
                    uri,
                    currentPageIndex,
                    android.graphics.RectF(safeRect),
                    fullBitmap.width.toFloat(),
                    fullBitmap.height.toFloat()
                )
                
                // Also get individual words for precise search in the snippet
                val allWords = PdfHelper.extractWords(this, uri, currentPageIndex, fullBitmap.width.toFloat(), fullBitmap.height.toFloat())
                val cropRectF = android.graphics.RectF(safeRect)
                val filteredWords = allWords.filter { cropRectF.contains(it.bounds.centerX(), it.bounds.centerY()) }
                // Adjust coordinates to be relative to the crop origin
                val relativeWords = filteredWords.map { word ->
                    val rb = android.graphics.RectF(word.bounds)
                    rb.offset(-cropRectF.left, -cropRectF.top)
                    PdfHelper.PdfWord(word.text, rb)
                }
                pdfWordsStr = relativeWords.joinToString(";") { "${it.text.replace(":", " ")}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" }
            }

            // Save to temp file and return path
            val fileName = "pdf_snippet_${UUID.randomUUID()}.png"
            val file = File(cacheDir, fileName)
            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val resultIntent = android.content.Intent()
            resultIntent.putExtra("SNIPPET_PATH", file.absolutePath)
            resultIntent.putExtra("EXTRACTED_TEXT", extractedText)
            resultIntent.putExtra("PDF_WORDS", pdfWordsStr)
            setResult(RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to extract snippet", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
    }
}
