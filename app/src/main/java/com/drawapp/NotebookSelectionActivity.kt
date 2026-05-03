package com.drawapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.util.Base64
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drawapp.DrawingView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

class NotebookSelectionActivity : AppCompatActivity() {

    private lateinit var rvNotebooks: RecyclerView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var fabImport: FloatingActionButton
    private lateinit var adapter: NotebookAdapter
    private lateinit var loadingOverlay: View
    private lateinit var importProgressOverlay: View
    private lateinit var tvImportStatus: TextView
    private lateinit var tvImportDetail: TextView
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var downloadDialog: android.app.Dialog? = null

    private val colors = listOf(
        "#FF4081", "#F44336", "#FF9800", "#FFEB3B", "#4CAF50",
        "#00BCD4", "#2196F3", "#9C27B0", "#795548", "#607D8B"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notebook_selection)

        rvNotebooks = findViewById(R.id.rvNotebooks)
        fabAdd = findViewById(R.id.fabAddNotebook)
        fabImport = findViewById(R.id.fabImportPdf)

        loadingOverlay = findViewById(R.id.loadingOverlay)
        importProgressOverlay = findViewById(R.id.importProgressOverlay)
        tvImportStatus = findViewById(R.id.tvImportStatusText)
        tvImportDetail = findViewById(R.id.tvImportDetailText)

        setupRecyclerView()

        fabAdd.setOnClickListener {
            showAddEditDialog(null)
        }

        fabImport.setOnClickListener {
            pickPdf.launch("application/pdf")
        }

        // Observe readiness to hide loading overlay
        val recognizer = HandwritingRecognizer.getInstance(this)

        activityScope.launch {
            recognizer.isReadyFlow.collect { ready ->
                if (ready) {
                    loadingOverlay.visibility = View.GONE
                }
            }
        }

        checkAndLoadModel(recognizer)
    }

    private fun checkAndLoadModel(recognizer: HandwritingRecognizer) {
        if (recognizer.isReady) {
            loadingOverlay.visibility = View.GONE
            return
        }

        val existing = ModelManager.findExistingModel(this)
        if (existing != null) {
            loadingOverlay.visibility = View.VISIBLE
            recognizer.load(existing, HandwritingRecognizer.InferenceConfig())
            return
        }

        val savedId = ModelManager.savedDownloadId(this)
        if (savedId != -1L) {
            val status = ModelManager.queryDownload(this, savedId)
            when (status.state) {
                ModelManager.DownloadState.RUNNING,
                ModelManager.DownloadState.PAUSED -> {
                    showDownloadDialog(alreadyDownloading = true)
                    return
                }
                ModelManager.DownloadState.DONE -> {
                    val path = ModelManager.modelFile(this).absolutePath
                    if (File(path).exists()) {
                        ModelManager.saveModelPath(this, path)
                        loadingOverlay.visibility = View.VISIBLE
                        recognizer.load(path, HandwritingRecognizer.InferenceConfig())
                        return
                    }
                }
                else -> { /* fall through to show dialog */ }
            }
        }

        showDownloadDialog(alreadyDownloading = false)
    }

    private fun showDownloadDialog(alreadyDownloading: Boolean) {
        downloadDialog?.dismiss()
        downloadDialog = ModelDownloadHelper.showDownloadDialog(
            activity = this,
            alreadyDownloading = alreadyDownloading,
            onDownloadIdAcquired = { /* managed by helper/manager */ },
            onDialogDismissed = { downloadDialog = null },
            onModelLoaded = { path ->
                loadingOverlay.visibility = View.VISIBLE
                HandwritingRecognizer.getInstance(this).load(path, HandwritingRecognizer.InferenceConfig())
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            adapter.updateData(NotebookManager.getNotebooks(this))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun setupRecyclerView() {
        adapter = NotebookAdapter(
            NotebookManager.getNotebooks(this),
            onNotebookClick = { notebook ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("NOTEBOOK_ID", notebook.id)
                    putExtra("NOTEBOOK_NAME", notebook.name)
                }
                startActivity(intent)
            },
            onEditClick = { notebook ->
                showAddEditDialog(notebook)
            }
        )
        rvNotebooks.layoutManager = GridLayoutManager(this, 2)
        rvNotebooks.adapter = adapter
    }

    private fun showAddEditDialog(notebook: Notebook?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_notebook, null)
        val etName = dialogView.findViewById<EditText>(R.id.etNotebookName)
        val colorGrid = dialogView.findViewById<GridLayout>(R.id.colorGrid)
        val rgType = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgNotebookType)
        val rgBackground = dialogView.findViewById<android.widget.RadioGroup>(R.id.rgDefaultBackground)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteNotebook)


        var selectedColor = notebook?.color ?: Color.parseColor(colors[0])
        val initialName = notebook?.name ?: NotebookManager.generateDefaultName(this)
        etName.setText(initialName)
        if (notebook == null) {
            etName.selectAll()
        }
        
        if (notebook?.type == NotebookType.WHITEBOARD) {
            rgType.check(R.id.rbWhiteboard)
            rgBackground.isEnabled = false
            for (i in 0 until rgBackground.childCount) rgBackground.getChildAt(i).isEnabled = false
        } else {
            rgType.check(R.id.rbNotebook)
            rgBackground.isEnabled = true
            for (i in 0 until rgBackground.childCount) rgBackground.getChildAt(i).isEnabled = true
        }

        rgType.setOnCheckedChangeListener { _, checkedId ->
            val isWhiteboard = checkedId == R.id.rbWhiteboard
            rgBackground.isEnabled = !isWhiteboard
            for (i in 0 until rgBackground.childCount) rgBackground.getChildAt(i).isEnabled = !isWhiteboard
            if (isWhiteboard) {
                rgBackground.check(R.id.rbNone)
            }
        }
        
        when (notebook?.defaultBackground) {
            "GRAPH" -> rgBackground.check(R.id.rbGraph)
            "NONE" -> rgBackground.check(R.id.rbNone)
            else -> rgBackground.check(R.id.rbRuled)
        }
        
        btnDelete.visibility = if (notebook != null) View.VISIBLE else View.GONE


        // Populate color grid
        colors.forEach { hex ->
            val color = Color.parseColor(hex)
            val swatch = View(this).apply {
                val size = resources.getDimensionPixelSize(R.dimen.color_swatch_size)
                val params = GridLayout.LayoutParams().apply {
                    width = size; height = size; setMargins(8, 8, 8, 8)
                }
                layoutParams = params
                background = createSwatchDrawable(color, color == selectedColor)
                setOnClickListener {
                    selectedColor = color
                    // Update all swatches to show selection
                    for (i in 0 until colorGrid.childCount) {
                        val child = colorGrid.getChildAt(i)
                        val childColor = child.tag as Int
                        child.background = createSwatchDrawable(childColor, childColor == selectedColor)
                    }
                }
                tag = color
            }
            colorGrid.addView(swatch)
        }

        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(if (notebook == null) "New Notebook" else "Edit Notebook")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = "Name cannot be empty"
                return@setOnClickListener
            }

            val type = when (rgType.checkedRadioButtonId) {
                R.id.rbWhiteboard -> NotebookType.WHITEBOARD
                else -> NotebookType.NOTEBOOK
            }

            val background = if (type == NotebookType.WHITEBOARD) "NONE" else {
                when (rgBackground.checkedRadioButtonId) {
                    R.id.rbGraph -> "GRAPH"
                    R.id.rbNone -> "NONE"
                    else -> "RULED"
                }
            }

            if (notebook == null) {
                NotebookManager.addNotebook(this, Notebook(name = name, color = selectedColor, defaultBackground = background, type = type))
            } else {
                notebook.name = name
                notebook.color = selectedColor
                notebook.defaultBackground = background
                notebook.type = type
                NotebookManager.updateNotebook(this, notebook)
            }

            adapter.updateData(NotebookManager.getNotebooks(this))
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            notebook?.let { 
                NotebookManager.deleteNotebook(this, it.id)
                adapter.updateData(NotebookManager.getNotebooks(this))
                dialog.dismiss()
            }
        }
    }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val intent = Intent(this, PdfImportActivity::class.java)
            intent.putExtra("PDF_URI", uri.toString())
            pdfImportLauncher.launch(intent)
        }
    }

    private val pdfImportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val pdfUri = result.data?.getStringExtra("PDF_URI")?.let { Uri.parse(it) }
            val selectedPages = result.data?.getIntArrayExtra("SELECTED_PAGES")
            if (pdfUri != null && selectedPages != null) {
                performPdfImport(pdfUri, selectedPages)
            }
        }
    }

    private fun performPdfImport(uri: Uri, pages: IntArray) {
        val recognizer = HandwritingRecognizer.getInstance(this)
        if (!recognizer.isReady) {
            Toast.makeText(this, "Recognition model not ready", Toast.LENGTH_SHORT).show()
            return
        }

        importProgressOverlay.visibility = View.VISIBLE
        
        activityScope.launch {
            try {
                val pdfName = getFileName(uri) ?: "Imported PDF"
                val notebookName = generateUniqueNotebookName(pdfName.removeSuffix(".pdf"))
                val notebook = Notebook(name = notebookName, color = Color.parseColor(colors[0]), defaultBackground = "NONE")
                
                // Clear any orphaned files for this notebook name just in case
                val dir = File(filesDir, "notebooks")
                if (!dir.exists()) dir.mkdirs()
                val prefix = "${notebookName}_page_"
                dir.listFiles { _, name -> name.startsWith(prefix) }?.forEach { it.delete() }

                withContext(Dispatchers.IO) {
                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw Exception("Failed to open PDF")
                    val renderer = PdfRenderer(pfd)
                    
                    val totalPages = pages.size
                    
                    for ((index, pageIdx) in pages.withIndex()) {
                        withContext(Dispatchers.Main) {
                            tvImportStatus.text = "Importing Page ${index + 1}/$totalPages"
                            tvImportDetail.text = "Rendering PDF page..."
                        }
                        
                        val page = renderer.openPage(pageIdx)
                        
                        // Render PDF page to high-res bitmap, but cap at 2048px to prevent OOM
                        var scale = 2.0f
                        var renderW = (page.width * scale).toInt()
                        var renderH = (page.height * scale).toInt()
                        
                        val maxDim = 2048
                        if (renderW > maxDim || renderH > maxDim) {
                            scale = if (renderW > renderH) maxDim.toFloat() / page.width else maxDim.toFloat() / page.height
                            renderW = (page.width * scale).toInt()
                            renderH = (page.height * scale).toInt()
                        }
                        
                        val bitmap = Bitmap.createBitmap(renderW.coerceAtLeast(1), renderH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        
                        // IMPORTANT: Fill with white first, as some PDFs are transparent
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        
                        withContext(Dispatchers.Main) {
                            tvImportDetail.text = "Extracting digital text..."
                        }
                        
                        // Extract digital text instead of OCR
                        val fullText = PdfHelper.extractText(this@NotebookSelectionActivity, uri, pageIdx)
                        
                        val svgDataList = mutableListOf<SvgData>()
                        
                        // Add FULL PDF page as a locked ImageItem
                        // Use JPEG for better compression and faster loading
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        
                        // Scale image to fit PAGE_WIDTH / PAGE_HEIGHT
                        val PAGE_WIDTH = 2800f
                        val PAGE_HEIGHT = 3960f
                        
                        val matrix = Matrix()
                        val scaleX = PAGE_WIDTH / renderW
                        val scaleY = PAGE_HEIGHT / renderH
                        matrix.postScale(scaleX, scaleY)
                        val mValues = FloatArray(9)
                        matrix.getValues(mValues)

                        val pdfWords = PdfHelper.extractWords(this@NotebookSelectionActivity, uri, index, PAGE_WIDTH, PAGE_HEIGHT)

                        svgDataList.add(ImageData(
                            base64 = base64,
                            matrix = mValues,
                            removeBackground = false,
                            text = fullText,
                            isShowingText = false,
                            pdfWords = pdfWords,
                            isLocked = true // Keep fixed in position
                        ))
                        
                        // Save SVG
                        val svgContent = SvgSerializer.serialize(
                            items = svgDataList,
                            width = PAGE_WIDTH.toInt(),
                            height = PAGE_HEIGHT.toInt(),
                            backgroundColor = Color.WHITE,
                            backgroundType = "NONE"
                        )
                        
                        val dir = File(filesDir, "notebooks")
                        if (!dir.exists()) dir.mkdirs()
                        val svgFile = File(dir, "${notebookName}_page_$index.svg")
                        svgFile.writeText(svgContent)
                        
                        // Save Thumbnail
                        val thumbFile = File(dir, "${notebookName}_page_${index}_thumb.png")
                        FileOutputStream(thumbFile).use { out ->
                            // Create a small thumbnail from the bitmap
                            val thumbScale = 0.2f
                            val thumbW = (renderW * thumbScale).toInt().coerceAtLeast(1)
                            val thumbH = (renderH * thumbScale).toInt().coerceAtLeast(1)
                            val thumbBmp = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)
                            thumbBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                            thumbBmp.recycle()
                        }
                        
                        // Free high-res bitmap
                        bitmap.recycle()
                    }
                    
                    renderer.close()
                    pfd.close()
                    
                    // Add to manager
                    NotebookManager.addNotebook(this@NotebookSelectionActivity, notebook)
                    
                    withContext(Dispatchers.Main) {
                        importProgressOverlay.visibility = View.GONE
                        adapter.updateData(NotebookManager.getNotebooks(this@NotebookSelectionActivity))
                        
                        // Open the notebook
                        val intent = Intent(this@NotebookSelectionActivity, MainActivity::class.java).apply {
                            putExtra("NOTEBOOK_ID", notebook.id)
                            putExtra("NOTEBOOK_NAME", notebook.name)
                        }
                        startActivity(intent)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                withContext(Dispatchers.Main) {
                    importProgressOverlay.visibility = View.GONE
                    Toast.makeText(this@NotebookSelectionActivity, "Import failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun generateUniqueNotebookName(base: String): String {
        val notebooks = NotebookManager.getNotebooks(this)
        var name = base
        var count = 1
        while (notebooks.any { it.name.equals(name, ignoreCase = true) }) {
            name = "$base ($count)"
            count++
        }
        return name
    }

    private fun extractJson(text: String): String {
        val stripped = text
            .replace(Regex("```(?:json)?\\s*"), "")
            .replace("```", "")
            .trim()

        val start = stripped.indexOf("[")
        val end = stripped.lastIndexOf("]")
        if (start != -1 && end != -1 && end > start) {
            return stripped.substring(start, end + 1)
        }
        return "[]"
    }

    private fun createSwatchDrawable(color: Int, isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isSelected) {
                setStroke(6, Color.WHITE)
            } else {
                setStroke(2, Color.parseColor("#40FFFFFF"))
            }
        }
    }
}
