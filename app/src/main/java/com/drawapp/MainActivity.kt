package com.drawapp

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var drawingView: DrawingView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnBrushSize: ImageButton
    private lateinit var btnColorPicker: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnBackground: ImageButton
    private lateinit var colorSwatch: View

    private lateinit var recognitionProgress: ProgressBar
    private lateinit var recognitionIcon: TextView
    private lateinit var recognitionText: TextView
    
    // Page Navigation
    private lateinit var btnPageUp: ImageButton
    private lateinit var btnPageDown: ImageButton
    private lateinit var btnOverview: ImageButton
    private lateinit var tvPageIndicator: TextView

    // ── State ──────────────────────────────────────────────────────────────
    private var activeColor: Int = Color.BLACK
    private var isEraserActive = false

    // ── Recognizer ─────────────────────────────────────────────────────────
    private lateinit var recognizer: HandwritingRecognizer
    private val recognitionHandler = Handler(Looper.getMainLooper())
    private val DEBOUNCE_MS = 1500L
    private var isRecognizing = false
    private val recognitionRunnable = Runnable { triggerRecognition() }
    private val autosaveRunnable = Runnable { performAutosave() }
    private val AUTOSAVE_DEBOUNCE_MS = 2000L
    private var notebookName: String = "My Notebook"
    private var notebookId: String = ""
    private var currentNotebook: Notebook? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentPageIndex: Int = 0

    // ── Misc ───────────────────────────────────────────────────────────────
    private val pickMedia = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    drawingView.addImage(bitmap)
                    updateButtonStates()
                    scheduleAutosave()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val STORAGE_PERMISSION_CODE = 101
    private val colors = listOf(
        "#000000", "#1A237E", "#1B5E20", "#B71C1C", "#4A148C",
        "#FFFFFF", "#FF4081", "#F44336", "#FF9800", "#FFEB3B",
        "#4CAF50", "#00BCD4", "#2196F3", "#9C27B0", "#795548",
        "#607D8B", "#E91E63", "#8BC34A", "#FF5722", "#00E5FF"
    )

    // ══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        setContentView(R.layout.activity_main)

        drawingView         = findViewById(R.id.drawingView)
        btnUndo             = findViewById(R.id.btnUndo)
        btnRedo             = findViewById(R.id.btnRedo)
        btnClear            = findViewById(R.id.btnClear)
        btnEraser           = findViewById(R.id.btnEraser)
        btnBrushSize        = findViewById(R.id.btnBrushSize)
        colorSwatch         = findViewById(R.id.colorSwatch)
        btnColorPicker      = colorSwatch
        recognitionProgress = findViewById(R.id.recognitionProgress)
        recognitionIcon     = findViewById(R.id.recognitionIcon)
        recognitionText     = findViewById(R.id.recognitionText)
        btnBack             = findViewById(R.id.btnBack)
        btnPageUp           = findViewById(R.id.btnPageUp)
        btnPageDown         = findViewById(R.id.btnPageDown)
        btnOverview         = findViewById(R.id.btnOverview)
        btnBackground       = findViewById(R.id.btnBackground)
        tvPageIndicator     = findViewById(R.id.tvPageIndicator)


        // Set notebook title from intent
        notebookId = intent.getStringExtra("NOTEBOOK_ID") ?: ""
        notebookName = intent.getStringExtra("NOTEBOOK_NAME") ?: "My Notebook"
        findViewById<TextView>(R.id.tvNotebookTitle).text = notebookName

        val notebook = NotebookManager.getNotebooks(this).find { it.id == notebookId }
        currentNotebook = notebook
        currentPageIndex = notebook?.lastDisplayedPage ?: 0


        migrateOldNotebookToPageZero()

        setupListeners()
        drawingView.brushColor = activeColor
        updateColorSwatch()
        setupRecognizer()
        loadNotebookDrawing(currentPageIndex)
    }

    override fun onPause() {
        super.onPause()
        performAutosave()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognitionHandler.removeCallbacks(recognitionRunnable)
        recognitionHandler.removeCallbacks(autosaveRunnable)
        activityScope.cancel()
        // No longer closing the recognizer here as it is shared
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recognizer / model setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupRecognizer() {
        recognizer = HandwritingRecognizer.getInstance(this)

        // Wire stroke callback → debounced recognition & autosave
        drawingView.onStrokeCompleted = {
            if (recognizer.isReady && !isRecognizing) {
                recognitionHandler.removeCallbacks(recognitionRunnable)
                recognitionHandler.postDelayed(recognitionRunnable, DEBOUNCE_MS)
            }
            // Always schedule autosave when a stroke is completed
            recognitionHandler.removeCallbacks(autosaveRunnable)
            recognitionHandler.postDelayed(autosaveRunnable, AUTOSAVE_DEBOUNCE_MS)
        }

        // Observe readiness to update UI
        activityScope.launch {
            recognizer.isReadyFlow.collect { ready ->
                if (ready) {
                    setRecognitionState(RecognitionState.IDLE)
                }
            }
        }

        // 1. Check if recognizer is already ready from startup
        if (recognizer.isReady) {
            setRecognitionState(RecognitionState.IDLE)
            return
        }

        // 2. Check if model already exists anywhere on device (being loaded)
        val existing = ModelManager.findExistingModel(this)
        if (existing != null) {
            loadModel(existing)
            return
        }

        // 3. Otherwise, model doesn't exist. Tell user to go back.
        setRecognitionState(RecognitionState.ERROR)
        recognitionText.text = "Model not found. Download on home screen."
        recognitionText.setTextColor(Color.parseColor("#FF6B6B"))
    }

    private fun loadModel(path: String) {
        setRecognitionState(RecognitionState.LOADING)
        recognizer.load(
            modelPath = path,
            onReady = {
                setRecognitionState(RecognitionState.IDLE)
            },
            onError = { msg ->
                setRecognitionState(RecognitionState.ERROR)
                recognitionText.text = "Load error — tap ✦ to retry"
                recognitionText.setTextColor(Color.parseColor("#FF6B6B"))
                Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
            }
        )
    }



    // ══════════════════════════════════════════════════════════════════════
    // Recognition
    // ══════════════════════════════════════════════════════════════════════

    private fun triggerRecognition() {
        if (isRecognizing || !recognizer.isReady) return
        
        // Use the cropped cluster bitmap instead of the full canvas
        val bitmap = drawingView.getRecentClusterBitmap() ?: return

        isRecognizing = true
        setRecognitionState(RecognitionState.RUNNING)
        var accumulated = ""

        recognizer.recognize(
            bitmap = bitmap,
            onPartialResult = { partial ->
                accumulated += partial
                recognitionText.text = accumulated
                recognitionText.setTextColor(Color.WHITE)
            },
            onDone = {
                isRecognizing = false
                setRecognitionState(RecognitionState.DONE)
                if (accumulated.isBlank()) {
                    recognitionText.text = "(nothing recognized)"
                    recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
                }
            },
            onError = { msg ->
                isRecognizing = false
                setRecognitionState(RecognitionState.ERROR)
                recognitionText.text = "Error: $msg"
                recognitionText.setTextColor(Color.parseColor("#FF6B6B"))
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // RecognitionState UI
    // ══════════════════════════════════════════════════════════════════════

    private enum class RecognitionState { LOADING, RUNNING, IDLE, DONE, ERROR }

    private fun setRecognitionState(state: RecognitionState) {
        when (state) {
            RecognitionState.LOADING -> {
                recognitionProgress.visibility = View.VISIBLE
                recognitionIcon.visibility     = View.GONE
                recognitionText.text           = "Loading Gemma model…"
                recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
            }
            RecognitionState.RUNNING -> {
                recognitionProgress.visibility = View.VISIBLE
                recognitionIcon.visibility     = View.GONE
                if (recognitionText.text.isBlank()) {
                    recognitionText.text = "Recognizing…"
                    recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
                }
            }
            RecognitionState.IDLE -> {
                recognitionProgress.visibility = View.GONE
                recognitionIcon.visibility     = View.VISIBLE
                if (recognitionText.text.toString().let {
                    it.isBlank() || it == "Loading Gemma model…" || it == "Waiting for Gemma model…"
                }) {
                    recognitionText.text = "Draw something to recognize…"
                    recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
                }
            }
            RecognitionState.DONE -> {
                recognitionProgress.visibility = View.GONE
                recognitionIcon.visibility     = View.VISIBLE
            }
            RecognitionState.ERROR -> {
                recognitionProgress.visibility = View.GONE
                recognitionIcon.visibility     = View.VISIBLE
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Toolbar / drawing listeners
    // ══════════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }
        btnUndo.setOnClickListener {
            drawingView.undo(); updateButtonStates()
            scheduleRecognition()
            scheduleAutosave()
        }
        btnRedo.setOnClickListener {
            drawingView.redo(); updateButtonStates()
            scheduleRecognition()
            scheduleAutosave()
        }
        btnClear.setOnClickListener { 
            if (!drawingView.deleteSelectedImage()) {
                showClearConfirmDialog() 
            }
        }
        btnEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            drawingView.isEraser = isEraserActive
            updateEraserButton()
        }
        btnBrushSize.setOnClickListener   { showBrushSizeDialog() }
        btnColorPicker.setOnClickListener { showColorPickerDialog() }
        findViewById<ImageButton>(R.id.btnAddImage).setOnClickListener {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnBackground.setOnClickListener { showBackgroundDialog() }
        
        btnPageUp.setOnClickListener {

            if (currentPageIndex > 0) {
                performAutosave() // Save current
                currentPageIndex--
                loadNotebookDrawing(currentPageIndex)
            }
        }
        btnPageDown.setOnClickListener {
            performAutosave() // Save current
            currentPageIndex++
            loadNotebookDrawing(currentPageIndex)
        }
        btnOverview.setOnClickListener { showOverviewDialog() }

        // Long-press ✦ to re-trigger model setup (inform user to do it from the main screen instead)
        recognitionIcon.setOnLongClickListener {
            Toast.makeText(this, "Manage model downloads from the home screen.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun scheduleRecognition() {
        if (recognizer.isReady && !isRecognizing) {
            recognitionHandler.removeCallbacks(recognitionRunnable)
            recognitionHandler.postDelayed(recognitionRunnable, DEBOUNCE_MS)
        }
    }

    private fun scheduleAutosave() {
        recognitionHandler.removeCallbacks(autosaveRunnable)
        recognitionHandler.postDelayed(autosaveRunnable, AUTOSAVE_DEBOUNCE_MS)
    }

    private fun updateButtonStates() {
        btnUndo.alpha = if (drawingView.canUndo()) 1.0f else 0.4f
        btnRedo.alpha = if (drawingView.canRedo()) 1.0f else 0.4f
    }

    private fun updateEraserButton() {
        if (isEraserActive) btnEraser.setColorFilter(Color.parseColor("#FF4081"))
        else btnEraser.clearColorFilter()
    }

    private fun updateColorSwatch() {
        colorSwatch.setBackgroundColor(activeColor)
    }

    // ── Color picker ──────────────────────────────────────────────────────

    private fun showColorPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val gridLayout = dialogView.findViewById<GridLayout>(R.id.colorGrid)
        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        colors.forEach { hex ->
            val swatch = View(this).apply {
                val size = resources.getDimensionPixelSize(R.dimen.color_swatch_size)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size; setMargins(8, 8, 8, 8)
                }
                background = circleDrawable(Color.parseColor(hex))
                setOnClickListener {
                    activeColor = Color.parseColor(hex)
                    drawingView.brushColor = activeColor
                    isEraserActive = false
                    drawingView.isEraser = false
                    updateEraserButton(); updateColorSwatch(); dialog.dismiss()
                }
            }
            gridLayout.addView(swatch)
        }
        dialogView.findViewById<Button>(R.id.btnCustomColor).setOnClickListener {
            dialog.dismiss(); showHsvColorDialog()
        }
        dialog.show()
    }

    private fun circleDrawable(color: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(3, Color.parseColor("#40FFFFFF"))
        }

    // ── HSV picker ────────────────────────────────────────────────────────

    private fun showHsvColorDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_hsv_picker, null)
        val hue = v.findViewById<SeekBar>(R.id.sliderHue)
        val sat = v.findViewById<SeekBar>(R.id.sliderSaturation)
        val bri = v.findViewById<SeekBar>(R.id.sliderValue)
        val opa = v.findViewById<SeekBar>(R.id.sliderOpacity)
        val pre = v.findViewById<View>(R.id.hsvPreview)
        val hsv = floatArrayOf(0f, 1f, 1f)
        hue.max = 360; sat.max = 100; bri.max = 100; opa.max = 255
        hue.progress = 0; sat.progress = 100; bri.progress = 100; opa.progress = 255
        fun refresh() {
            hsv[0] = hue.progress.toFloat()
            hsv[1] = sat.progress / 100f
            hsv[2] = bri.progress / 100f
            pre.background = circleDrawable(Color.HSVToColor(hsv))
        }
        refresh()
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = refresh()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        listOf(hue, sat, bri, opa).forEach { it.setOnSeekBarChangeListener(l) }
        AlertDialog.Builder(this, R.style.DarkDialogTheme).setView(v)
            .setPositiveButton("Apply") { _, _ ->
                hsv[0] = hue.progress.toFloat()
                hsv[1] = sat.progress / 100f
                hsv[2] = bri.progress / 100f
                activeColor = Color.HSVToColor(hsv)
                drawingView.brushColor = activeColor
                drawingView.brushOpacity = opa.progress
                isEraserActive = false; drawingView.isEraser = false
                updateEraserButton(); updateColorSwatch()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Brush size picker ─────────────────────────────────────────────────

    private fun showBrushSizeDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_brush_size, null)
        val slider = v.findViewById<SeekBar>(R.id.brushSizeSlider)
        val preview = v.findViewById<View>(R.id.brushPreview)
        val label = v.findViewById<TextView>(R.id.brushSizeLabel)
        slider.max = 95; slider.progress = drawingView.brushSize.toInt() - 5
        fun update(p: Int) {
            val s = p + 5; label.text = "${s}px"
            val lp = preview.layoutParams; lp.width = s * 2; lp.height = s * 2
            preview.layoutParams = lp; preview.background = circleDrawable(activeColor)
        }
        update(slider.progress)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = update(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        AlertDialog.Builder(this, R.style.DarkDialogTheme).setView(v).setTitle("Brush Size")
            .setPositiveButton("Apply") { _, _ -> drawingView.brushSize = (slider.progress + 5).toFloat() }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Clear confirm ─────────────────────────────────────────────────────

    private fun showClearConfirmDialog() {
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Clear Canvas")
            .setMessage("Are you sure you want to erase everything?")
            .setPositiveButton("Clear") { _, _ ->
                drawingView.clear()
                drawingView.clearDebugBox()
                recognitionHandler.removeCallbacks(recognitionRunnable)
                recognitionText.text = "Draw something to recognize…"
                recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
                setRecognitionState(RecognitionState.IDLE)
                // Trigger autosave to preserve background type even if canvas is cleared
                performAutosave()
            }

            .setNegativeButton("Cancel", null).show()
    }

    private fun showBackgroundDialog() {
        val options = arrayOf("Ruled Paper", "Graph Paper", "No Background")
        val types = arrayOf(DrawingView.BackgroundType.RULED, DrawingView.BackgroundType.GRAPH, DrawingView.BackgroundType.NONE)
        
        val currentTypeIndex = types.indexOf(drawingView.backgroundType)
        
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Page Background")
            .setSingleChoiceItems(options, currentTypeIndex) { dialog, which ->
                drawingView.backgroundType = types[which]
                drawingView.invalidate()
                scheduleAutosave()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Save / Load SVG ───────────────────────────────────────────────────

    
    private fun migrateOldNotebookToPageZero() {
        val dir = File(filesDir, "notebooks")
        if (!dir.exists()) dir.mkdirs()
        val oldFile = File(dir, "${notebookName}.svg")
        if (oldFile.exists()) {
            val newFile = File(dir, "${notebookName}_page_0.svg")
            oldFile.renameTo(newFile)
        }
    }

    private fun getNotebookSvgFile(pageIndex: Int): File {
        val dir = File(filesDir, "notebooks")
        dir.mkdirs()
        return File(dir, "${notebookName}_page_${pageIndex}.svg")
    }
    
    private fun getNotebookThumbFile(pageIndex: Int): File {
        val dir = File(filesDir, "notebooks")
        dir.mkdirs()
        return File(dir, "${notebookName}_page_${pageIndex}_thumb.png")
    }

    private fun loadNotebookDrawing(pageIndex: Int = 0) {
        tvPageIndicator.text = "${pageIndex + 1}"
        drawingView.clear()
        drawingView.clearDebugBox()
        val file = getNotebookSvgFile(pageIndex)
        
        btnPageUp.alpha = if (pageIndex > 0) 1.0f else 0.4f
        btnPageUp.isEnabled = pageIndex > 0
        
        val notebooks = NotebookManager.getNotebooks(this)
        val notebook = notebooks.find { it.id == notebookId }
        if (notebook != null && notebook.lastDisplayedPage != pageIndex) {
            notebook.lastDisplayedPage = pageIndex
            NotebookManager.updateNotebook(this, notebook)
        }
        
        if (!file.exists()) {
            // Set default background for new page
            drawingView.backgroundType = when (currentNotebook?.defaultBackground) {
                "GRAPH" -> DrawingView.BackgroundType.GRAPH
                "NONE" -> DrawingView.BackgroundType.NONE
                else -> DrawingView.BackgroundType.RULED
            }
            return
        }

        try {
            val svgContent = file.readText()
            val result = SvgSerializer.deserialize(svgContent)
            drawingView.backgroundType = when (result.backgroundType) {
                "GRAPH" -> DrawingView.BackgroundType.GRAPH
                "NONE" -> DrawingView.BackgroundType.NONE
                else -> DrawingView.BackgroundType.RULED
            }
            if (result.items.isNotEmpty()) {
                drawingView.loadFromSvgData(result.items)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun performAutosave() {
        val svgData = drawingView.getSvgDataList()
        val file = getNotebookSvgFile(currentPageIndex)
        val thumbFile = getNotebookThumbFile(currentPageIndex)

        // Only delete if absolutely everything is default (empty items AND default background)
        // But actually, it's safer to always save if the user interacted.
        // Let's at least save if svgData is not empty OR if background is set.
        // Actually, let's just remove the delete-on-empty logic for now to ensure persistence.
        
        try {

            val svgContent = SvgSerializer.serialize(
                items = svgData,
                width = drawingView.width,
                height = drawingView.height,
                backgroundColor = drawingView.canvasBackgroundColor,
                backgroundType = drawingView.backgroundType.name,
                contentBounds = drawingView.getContentBounds()
            )

            file.writeText(svgContent)
            
            // Generate and save thumbnail
            saveThumbnail(thumbFile)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun saveThumbnail(file: File) {
        val bounds = drawingView.getContentBounds() ?: return
        if (bounds.isEmpty) return
        
        // Render to a 400x400 thumbnail max bounds
        val thumbSize = 400
        val scale = minOf(thumbSize / bounds.width(), thumbSize / bounds.height())
        val scaledW = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val scaledH = (bounds.height() * scale).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(drawingView.canvasBackgroundColor)
        
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)
        
        for (item in drawingView.getSvgDataList()) {
            when (item) {
                is StrokeData -> {
                    val path = android.graphics.Path()
                    for (cmd in item.commands) {
                        when (cmd) {
                            is com.drawapp.PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                            is com.drawapp.PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                            is com.drawapp.PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                        }
                    }
                    val paint = android.graphics.Paint().apply {
                        color = if (item.isEraser) drawingView.canvasBackgroundColor else item.color
                        style = android.graphics.Paint.Style.STROKE
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeWidth = item.strokeWidth
                        isAntiAlias = true
                        alpha = item.opacity
                    }
                    canvas.drawPath(path, paint)
                }
                is ImageData -> {
                    try {
                        val decodedString = android.util.Base64.decode(item.base64, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                        if (bmp != null) {
                            val matrix = android.graphics.Matrix()
                            matrix.setValues(item.matrix)
                            canvas.drawBitmap(bmp, matrix, null)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        
        file.outputStream().use { 
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)
        }
    }

    private fun deleteNotebookSvg(pageIndex: Int) {
        try {
            val file = getNotebookSvgFile(pageIndex)
            val thumb = getNotebookThumbFile(pageIndex)
            if (file.exists()) file.delete()
            if (thumb.exists()) thumb.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // ── Overview Dialog ───────────────────────────────────────────────────
    
    private fun showOverviewDialog() {
        performAutosave() // ensure current page is up to date
        val dialogView = layoutInflater.inflate(R.layout.dialog_overview, null)
        val gridView = dialogView.findViewById<GridView>(R.id.gridOverview)
        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setView(dialogView).create()
            
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Find all pages
        val pages = mutableListOf<Int>()
        var pIndex = 0
        while (true) {
            val svg = getNotebookSvgFile(pIndex)
            // Or if they skipped pages, just check up to a limit. Let's list files instead.
            // But if they are sequential:
            if (svg.exists() || pIndex == currentPageIndex) {
                if (!pages.contains(pIndex)) {
                    pages.add(pIndex)
                }
            } else if (pIndex > currentPageIndex) {
                break
            }
            pIndex++
        }
        
        // Actually, let's just search the directory for this notebook's pages to be robust
        val dir = File(filesDir, "notebooks")
        val allFiles = dir.listFiles { _, name -> 
            name.startsWith("${notebookName}_page_") && name.endsWith(".svg") 
        } ?: emptyArray()
        
        val existingPages = allFiles.mapNotNull { 
            it.name.removePrefix("${notebookName}_page_").removeSuffix(".svg").toIntOrNull() 
        }.toMutableSet()
        
        existingPages.add(currentPageIndex) // always show current page
        val sortedPages = existingPages.sorted().toList()
        
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = sortedPages.size
            override fun getItem(position: Int) = sortedPages[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_overview_page, parent, false)
                val imgThumb = view.findViewById<ImageView>(R.id.imgThumb)
                val tvPage = view.findViewById<TextView>(R.id.tvPageNumber)
                val pageIdx = getItem(position)
                
                tvPage.text = "Page ${pageIdx + 1}"
                val thumbFile = getNotebookThumbFile(pageIdx)
                if (thumbFile.exists()) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(thumbFile.absolutePath)
                    imgThumb.setImageBitmap(bmp)
                } else {
                    imgThumb.setImageResource(R.drawable.toolbar_background) // placeholder
                }
                
                if (pageIdx == currentPageIndex) {
                    view.setBackgroundResource(R.drawable.icon_btn_bg)
                } else {
                    view.background = null
                }
                
                view.setOnClickListener {
                    currentPageIndex = pageIdx
                    loadNotebookDrawing(currentPageIndex)
                    dialog.dismiss()
                }
                return view
            }
        }
        
        gridView.adapter = adapter
        dialog.show()
    }
}
