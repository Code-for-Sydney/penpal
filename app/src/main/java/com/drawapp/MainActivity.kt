package com.drawapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
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
    private lateinit var btnBrushSize: ImageButton
    private lateinit var btnColorPicker: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnJumpMarkers: ImageButton
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

    // Search
    private lateinit var searchBarContainer: LinearLayout
    private lateinit var etSearch: EditText
    private lateinit var btnCloseSearch: ImageButton
    private lateinit var btnNextMatch: ImageButton
    private lateinit var btnPrevMatch: ImageButton
    private lateinit var tvSearchCount: TextView

    private data class SearchMatch(val pageIndex: Int, val text: String, val itemIndex: Int)
    private var allMatches = mutableListOf<SearchMatch>()
    private var currentMatchIndex = -1

    // ── State ──────────────────────────────────────────────────────────────
    private var activeColor: Int = Color.BLACK

    // ── Recognizer ─────────────────────────────────────────────────────────
    private lateinit var recognizer: HandwritingRecognizer
    private val recognitionHandler = Handler(Looper.getMainLooper())
    private val DEBOUNCE_MS = 1500L
    private var hasPendingRecognition = false
    private val recognitionRunnable = Runnable { 
        hasPendingRecognition = false
        triggerRecognition() 
    }
    private val autosaveRunnable = Runnable { performAutosave() }
    private val AUTOSAVE_DEBOUNCE_MS = 2000L
    private var notebookName: String = "My Notebook"
    private var notebookId: String = ""
    private var currentNotebook: Notebook? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val busyStrokes = mutableSetOf<DrawingView.StrokeItem>()

    private var currentPageIndex: Int = 0

    // Multi-cluster tracking
    private var pendingClusterStrokes: List<DrawingView.StrokeItem>? = null
    private var pendingClusterMergedWords: List<DrawingView.WordItem>? = null
    private var pendingClusterBitmap: android.graphics.Bitmap? = null
    private var fullRecognizedText: String = ""

    // ── Misc ───────────────────────────────────────────────────────────────
    private val pickMedia = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val imageItem = drawingView.addImage(bitmap)
                    triggerImageSummaryRecognition(imageItem, bitmap)
                    updateButtonStates()
                    scheduleAutosave()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickPdf = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val intent = android.content.Intent(this, PdfSelectionActivity::class.java)
            intent.putExtra("PDF_URI", uri.toString())
            pdfSelectionLauncher.launch(intent)
        }
    }

    private val pdfSelectionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val snippetPath = result.data?.getStringExtra("SNIPPET_PATH")
            if (snippetPath != null) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(snippetPath)
                    if (bitmap != null) {
                        val imageItem = drawingView.addImage(bitmap)
                        triggerImageSummaryRecognition(imageItem, bitmap)
                        updateButtonStates()
                        scheduleAutosave()
                        // Clean up temp file
                        java.io.File(snippetPath).delete()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Failed to load snippet", Toast.LENGTH_SHORT).show()
                }
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
        btnSearch           = findViewById(R.id.btnSearch)
        btnJumpMarkers      = findViewById(R.id.btnJumpMarkers)
        btnBackground       = findViewById(R.id.btnBackground)
        tvPageIndicator     = findViewById(R.id.tvPageIndicator)

        searchBarContainer  = findViewById(R.id.searchBarContainer)
        etSearch            = findViewById(R.id.etSearch)
        btnCloseSearch      = findViewById(R.id.btnCloseSearch)
        btnNextMatch        = findViewById(R.id.btnNextMatch)
        btnPrevMatch        = findViewById(R.id.btnPrevMatch)
        tvSearchCount       = findViewById(R.id.tvSearchCount)


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
        drawingView.onShowItemColorPicker = { item -> showColorPickerDialog(item) }
        drawingView.onStateChanged = { scheduleAutosave() }
        updateColorSwatch()
        setupRecognizer()
        loadNotebookDrawing(currentPageIndex)
    }

    override fun onPause() {
        super.onPause()
        flushPendingRecognition()
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
            val clusterData = drawingView.getRecentClusterWithStrokes()
            var currentStrokes = clusterData?.strokes ?: emptyList()
            val mergedWords = clusterData?.mergedWords ?: emptyList()
            
            // Filter out strokes that are already being recognized to prevent duplication
            currentStrokes = currentStrokes.filter { it !in busyStrokes }
            
            if (pendingClusterStrokes != null && currentStrokes.isNotEmpty()) {
                val prevStrokes = pendingClusterStrokes!!
                // If the new stroke is not part of the current pending cluster, flush recognition for the previous one
                if (currentStrokes.none { it in prevStrokes }) {
                    if (pendingClusterBitmap != null) {
                        val prevBitmap = pendingClusterBitmap!!
                        val prevMerged = pendingClusterMergedWords ?: emptyList()
                        
                        // Clear pending state BEFORE triggering to prevent recursion or accidental re-flushing
                        pendingClusterStrokes = null
                        pendingClusterBitmap = null
                        pendingClusterMergedWords = null
                        
                        triggerRecognitionForBitmap(prevBitmap, prevStrokes, prevMerged)
                    }
                }
            }
            
            pendingClusterStrokes = if (currentStrokes.isNotEmpty()) currentStrokes else null
            pendingClusterMergedWords = if (currentStrokes.isNotEmpty()) mergedWords else null
            pendingClusterBitmap = if (currentStrokes.isNotEmpty()) clusterData?.bitmap else null

            if (recognizer.isReady) {
                recognitionHandler.removeCallbacks(recognitionRunnable)
                recognitionHandler.postDelayed(recognitionRunnable, DEBOUNCE_MS)
                hasPendingRecognition = true
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
        activityScope.launch {
            recognizer.isProcessingFlow.collect { isProcessing ->
                if (isProcessing) {
                    setRecognitionState(RecognitionState.RUNNING)
                } else if (recognizer.isReady) {
                    setRecognitionState(RecognitionState.DONE)
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
        if (!recognizer.isReady) return
        
        val bitmap = pendingClusterBitmap ?: return
        val strokes = pendingClusterStrokes ?: return
        val mergedWords = pendingClusterMergedWords ?: emptyList()
        
        pendingClusterStrokes = null
        pendingClusterMergedWords = null
        pendingClusterBitmap = null

        triggerRecognitionForBitmap(bitmap, strokes, mergedWords)
    }

    private fun triggerRecognitionForBitmap(bitmap: Bitmap, strokes: List<DrawingView.StrokeItem>, mergedWords: List<DrawingView.WordItem> = emptyList()) {
        if (strokes.isEmpty()) return
        busyStrokes.addAll(strokes)
        
        // Group immediately so they are transformable as a Word object
        val wordItem = drawingView.groupStrokesIntoWord(strokes, "…", mergedWords)
        
        var accumulated = ""
        var currentPrefix = ""
        var prefixEvaluated = false

        recognizer.recognize(
            bitmap = bitmap,
            onPartialResult = { partial ->
                if (!prefixEvaluated) {
                    // If we merged words, we might want to preserve the text of the first one?
                    // Actually, let's just use the current fullRecognizedText if we are continuing a sequence,
                    // but if we merged a word, fullRecognizedText might be irrelevant for this specific WordItem.
                    // However, triggerRecognitionForBitmap is usually called for the "recent" cluster.
                    currentPrefix = if (fullRecognizedText.isNotEmpty()) "$fullRecognizedText " else ""
                    prefixEvaluated = true
                }
                accumulated += partial
                recognitionText.text = currentPrefix + accumulated.trim()
                recognitionText.setTextColor(Color.WHITE)
                
                // Update the word item text as we go
                wordItem?.text = accumulated.trim()
            },
            onDone = {
                if (!prefixEvaluated) {
                    currentPrefix = if (fullRecognizedText.isNotEmpty()) "$fullRecognizedText " else ""
                    prefixEvaluated = true
                }
                if (accumulated.isNotBlank()) {
                    val result = currentPrefix + accumulated.trim()
                    fullRecognizedText = result
                    wordItem?.text = accumulated.trim()
                    scheduleAutosave()
                } else if (fullRecognizedText.isBlank()) {
                    recognitionText.text = "(nothing recognized)"
                    recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
                    wordItem?.text = ""
                }
                busyStrokes.removeAll(strokes)
                drawingView.invalidate()
            },
            onError = { msg ->
                busyStrokes.removeAll(strokes)
                setRecognitionState(RecognitionState.ERROR)
                recognitionText.text = "Error: $msg"
                recognitionText.setTextColor(Color.parseColor("#FF6B6B"))
                wordItem?.text = ""
                drawingView.invalidate()
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
        btnBack.setOnClickListener { 
            flushPendingRecognition()
            finish() 
        }
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
        btnBrushSize.setOnClickListener   { showBrushSizeDialog() }
        btnColorPicker.setOnClickListener { showColorPickerDialog() }
        findViewById<ImageButton>(R.id.btnAddImage).setOnClickListener {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<ImageButton>(R.id.btnAddPdf).setOnClickListener {
            pickPdf.launch("application/pdf")
        }
        btnBackground.setOnClickListener { showBackgroundDialog() }
        
        btnPageUp.setOnClickListener {

            if (currentPageIndex > 0) {
                flushPendingRecognition()
                performAutosave() // Save current
                currentPageIndex--
                loadNotebookDrawing(currentPageIndex)
            }
        }
        btnPageDown.setOnClickListener {
            flushPendingRecognition()
            performAutosave() // Save current
            currentPageIndex++
            loadNotebookDrawing(currentPageIndex)
        }
        btnOverview.setOnClickListener { showOverviewDialog() }
        btnSearch.setOnClickListener { showSearchMode(true) }
        btnJumpMarkers.setOnClickListener { jumpToNextMarker() }

        btnCloseSearch.setOnClickListener { showSearchMode(false) }
        btnNextMatch.setOnClickListener {
            if (allMatches.isNotEmpty()) {
                currentMatchIndex = (currentMatchIndex + 1) % allMatches.size
                navigateToMatch(allMatches[currentMatchIndex])
                updateSearchUI()
            }
        }
        btnPrevMatch.setOnClickListener {
            if (allMatches.isNotEmpty()) {
                currentMatchIndex = if (currentMatchIndex <= 0) allMatches.size - 1 else currentMatchIndex - 1
                navigateToMatch(allMatches[currentMatchIndex])
                updateSearchUI()
            }
        }
        
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performGlobalSearch(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                if (allMatches.isNotEmpty()) {
                    currentMatchIndex = (currentMatchIndex + 1) % allMatches.size
                    navigateToMatch(allMatches[currentMatchIndex])
                    updateSearchUI()
                }
                true
            } else false
        }

        // Long-press ✦ to re-trigger model setup (inform user to do it from the main screen instead)
        recognitionIcon.setOnLongClickListener {
            Toast.makeText(this, "Manage model downloads from the home screen.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun scheduleRecognition() {
        if (recognizer.isReady) {
            recognitionHandler.removeCallbacks(recognitionRunnable)
            recognitionHandler.postDelayed(recognitionRunnable, DEBOUNCE_MS)
            hasPendingRecognition = true
        }
    }

    private fun flushPendingRecognition() {
        if (hasPendingRecognition) {
            recognitionHandler.removeCallbacks(recognitionRunnable)
            hasPendingRecognition = false
            triggerRecognition()
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


    private fun updateColorSwatch() {
        colorSwatch.setBackgroundColor(activeColor)
    }

    // ── Color picker ──────────────────────────────────────────────────────

    private fun showColorPickerDialog(item: DrawingView.CanvasItem? = null) {
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
                    val color = Color.parseColor(hex)
                    if (item == null) {
                        activeColor = color
                        drawingView.brushColor = activeColor
                        updateColorSwatch()
                    } else {
                        updateItemColor(item, color)
                    }
                    dialog.dismiss()
                }
            }
            gridLayout.addView(swatch)
        }
        dialogView.findViewById<Button>(R.id.btnCustomColor).setOnClickListener {
            dialog.dismiss(); showHsvColorDialog(item)
        }
        dialog.show()
    }

    private fun updateItemColor(item: DrawingView.CanvasItem, color: Int) {
        when (item) {
            is DrawingView.WordItem -> {
                item.tintColor = color
            }
            is DrawingView.ImageItem -> {
                item.tintColor = color
                item.invalidateCache()
            }
            is DrawingView.StrokeItem -> {
                item.paint.color = color
            }
        }
        drawingView.invalidate()
        scheduleAutosave()
    }

    private fun circleDrawable(color: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(3, Color.parseColor("#40FFFFFF"))
        }

    // ── HSV picker ────────────────────────────────────────────────────────

    private fun showHsvColorDialog(item: DrawingView.CanvasItem? = null) {
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
                val color = Color.HSVToColor(hsv)
                if (item == null) {
                    activeColor = color
                    drawingView.brushColor = activeColor
                    drawingView.brushOpacity = opa.progress
                    drawingView.isEraser = false
                    updateColorSwatch()
                } else {
                    updateItemColor(item, color)
                }
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
                hasPendingRecognition = false
                pendingClusterBitmap = null
                fullRecognizedText = ""
                recognitionText.text = "Draw something to recognize…"
                recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
                setRecognitionState(RecognitionState.IDLE)
                busyStrokes.clear()
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

    private fun performAutosave() {
        val svgData = drawingView.getSvgDataList()
        val file = getNotebookSvgFile(currentPageIndex)
        val thumbFile = getNotebookThumbFile(currentPageIndex)
        val width = drawingView.width
        val height = drawingView.height
        val bgColor = drawingView.canvasBackgroundColor
        val bgType = drawingView.backgroundType.name
        val contentBounds = drawingView.getContentBounds()

        activityScope.launch(Dispatchers.IO) {
            try {
                val svgContent = SvgSerializer.serialize(
                    items = svgData,
                    width = width,
                    height = height,
                    backgroundColor = bgColor,
                    backgroundType = bgType,
                    contentBounds = contentBounds
                )

                file.writeText(svgContent)
                
                // Generate and save thumbnail
                if (contentBounds != null && !contentBounds.isEmpty) {
                    saveThumbnail(thumbFile, svgData, bgColor, contentBounds)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
        hasPendingRecognition = false
        pendingClusterBitmap = null
        fullRecognizedText = ""
        recognitionText.text = "Draw something to recognize…"
        recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
        busyStrokes.clear()
        
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
    
    private fun saveThumbnail(file: File, svgData: List<SvgData>, bgColor: Int, bounds: RectF) {
        
        // Render to a 400x400 thumbnail max bounds
        val thumbSize = 400
        val scale = minOf(thumbSize / bounds.width(), thumbSize / bounds.height())
        val scaledW = (bounds.width() * scale).toInt().coerceAtLeast(1)
        val scaledH = (bounds.height() * scale).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(bgColor)
        
        canvas.scale(scale, scale)
        canvas.translate(-bounds.left, -bounds.top)
        
        for (item in svgData) {
            when (item) {
                is StrokeData -> {
                    val path = android.graphics.Path()
                    for (cmd in item.commands) {
                        when (cmd) {
                            is com.drawapp.PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                            is com.drawapp.PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                            is com.drawapp.PathCommand.CubicTo -> path.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
                            is com.drawapp.PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                        }
                    }
                    val paint = android.graphics.Paint().apply {
                        color = if (item.isEraser) bgColor else item.color
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
                is WordData -> {
                    if (item.isShowingText && item.text.isNotEmpty()) {
                        // Render text for thumbnail
                        val paint = android.graphics.Paint().apply {
                            color = if (item.strokes.isNotEmpty()) item.strokes[0].color else Color.BLACK
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        }
                        
                        // Calculate local bounds of strokes
                        val localBounds = RectF()
                        for (stroke in item.strokes) {
                            val path = android.graphics.Path()
                            for (cmd in stroke.commands) {
                                    when (cmd) {
                                        is com.drawapp.PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                                        is com.drawapp.PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                                        is com.drawapp.PathCommand.CubicTo -> path.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
                                        is com.drawapp.PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                                    }
                            }
                            val b = RectF()
                            path.computeBounds(b, true)
                            localBounds.union(b)
                        }
                        
                        val text = item.text
                        paint.textSize = 100f
                        val textWidth = paint.measureText(text)
                        val fontMetrics = paint.fontMetrics
                        val textHeight = fontMetrics.descent - fontMetrics.ascent
                        val scaleX = localBounds.width() / textWidth
                        val scaleY = localBounds.height() / textHeight
                        val scaleT = minOf(scaleX, scaleY) * 0.9f
                        paint.textSize = 100f * scaleT
                        
                        val matrix = android.graphics.Matrix()
                        matrix.setValues(item.matrix)
                        canvas.save()
                        canvas.concat(matrix)
                        val baseline = localBounds.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
                        canvas.drawText(text, localBounds.centerX(), baseline, paint)
                        canvas.restore()
                    } else {
                        for (stroke in item.strokes) {
                            val path = android.graphics.Path()
                            for (cmd in stroke.commands) {
                                    when (cmd) {
                                        is com.drawapp.PathCommand.MoveTo -> path.moveTo(cmd.x, cmd.y)
                                        is com.drawapp.PathCommand.QuadTo -> path.quadTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2)
                                        is com.drawapp.PathCommand.CubicTo -> path.cubicTo(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x3, cmd.y3)
                                        is com.drawapp.PathCommand.LineTo -> path.lineTo(cmd.x, cmd.y)
                                    }
                            }
                            val paint = android.graphics.Paint().apply {
                                color = if (stroke.isEraser) bgColor else stroke.color
                                style = android.graphics.Paint.Style.STROKE
                                strokeJoin = android.graphics.Paint.Join.ROUND
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeWidth = stroke.strokeWidth
                                isAntiAlias = true
                                alpha = stroke.opacity
                            }
                            val matrix = android.graphics.Matrix()
                            matrix.setValues(item.matrix)
                            path.transform(matrix)
                            canvas.drawPath(path, paint)
                        }
                    }
                }
            }
        }
        
        try {
            val out = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            out.close()
        } catch (e: Exception) {
            e.printStackTrace()
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
    
    private fun showSearchMode(show: Boolean) {
        searchBarContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            etSearch.requestFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            etSearch.setText("")
            allMatches.clear()
            currentMatchIndex = -1
            drawingView.searchHighlightedWord = null
            updateSearchUI()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        }
    }

    private fun performGlobalSearch(query: String) {
        if (query.isBlank()) {
            allMatches.clear()
            currentMatchIndex = -1
            updateSearchUI()
            drawingView.searchHighlightedWord = null
            return
        }

        activityScope.launch(Dispatchers.IO) {
            val matches = mutableListOf<SearchMatch>()
            
            val dir = File(filesDir, "notebooks")
            val files = dir.listFiles { _, name -> 
                name.startsWith("${notebookName}_page_") && name.endsWith(".svg") 
            } ?: emptyArray()
            
            val pageIndices = files.mapNotNull { 
                it.name.removePrefix("${notebookName}_page_").removeSuffix(".svg").toIntOrNull() 
            }.sorted()

            for (pageIdx in pageIndices) {
                val file = getNotebookSvgFile(pageIdx)
                if (file.exists()) {
                    val content = file.readText()
                    // Quick check for performance
                    if (content.contains("data-text=", ignoreCase = true) && content.contains(query, ignoreCase = true)) {
                        val result = SvgSerializer.deserialize(content)
                        result.items.forEachIndexed { index, item ->
                            if (item is WordData && item.text.contains(query, ignoreCase = true)) {
                                matches.add(SearchMatch(pageIdx, item.text, index))
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                allMatches = matches
                if (allMatches.isNotEmpty()) {
                    // Try to stay on current page match if possible, otherwise start from first
                    val currentIdx = allMatches.indexOfFirst { it.pageIndex == currentPageIndex }
                    currentMatchIndex = if (currentIdx != -1) currentIdx else 0
                    navigateToMatch(allMatches[currentMatchIndex])
                } else {
                    currentMatchIndex = -1
                    drawingView.searchHighlightedWord = null
                }
                updateSearchUI()
            }
        }
    }

    private fun navigateToMatch(match: SearchMatch) {
        if (match.pageIndex != currentPageIndex) {
            flushPendingRecognition()
            performAutosave()
            currentPageIndex = match.pageIndex
            loadNotebookDrawing(currentPageIndex)
        }
        
        val item = drawingView.getItemAtIndex(match.itemIndex)
        if (item is DrawingView.WordItem) {
            drawingView.searchHighlightedWord = item
            drawingView.scrollToWord(item)
        }
    }

    private fun updateSearchUI() {
        if (allMatches.isEmpty()) {
            tvSearchCount.text = "0/0"
            btnNextMatch.isEnabled = false
            btnPrevMatch.isEnabled = false
            btnNextMatch.alpha = 0.4f
            btnPrevMatch.alpha = 0.4f
        } else {
            tvSearchCount.text = "${currentMatchIndex + 1}/${allMatches.size}"
            btnNextMatch.isEnabled = true
            btnPrevMatch.isEnabled = true
            btnNextMatch.alpha = 1.0f
            btnPrevMatch.alpha = 1.0f
        }
    }

    @Deprecated("Use showSearchMode instead")
    private fun showSearchDialog() {
        // ... kept for compatibility if needed, but not used
    }

    // ── Overview Dialog ───────────────────────────────────────────────────

    private fun deletePageAndShift(pageIndexToDelete: Int, overviewDialog: AlertDialog) {
        // Delete the page files
        deleteNotebookSvg(pageIndexToDelete)
        
        // Find remaining files to shift down
        val dir = File(filesDir, "notebooks")
        val allFiles = dir.listFiles { _, name -> 
            name.startsWith("${notebookName}_page_") && name.endsWith(".svg") 
        } ?: emptyArray()
        
        val existingPages = allFiles.mapNotNull { 
            it.name.removePrefix("${notebookName}_page_").removeSuffix(".svg").toIntOrNull() 
        }.filter { it > pageIndexToDelete }.sorted()
        
        for (pageIdx in existingPages) {
            val oldSvg = getNotebookSvgFile(pageIdx)
            val newSvg = getNotebookSvgFile(pageIdx - 1)
            oldSvg.renameTo(newSvg)
            
            val oldThumb = getNotebookThumbFile(pageIdx)
            val newThumb = getNotebookThumbFile(pageIdx - 1)
            if (oldThumb.exists()) {
                oldThumb.renameTo(newThumb)
            }
        }
        
        // If we deleted the current page, load the new current page
        if (currentPageIndex == pageIndexToDelete) {
            overviewDialog.dismiss()
            if (currentPageIndex > 0 && !getNotebookSvgFile(currentPageIndex).exists()) {
                currentPageIndex--
            }
            loadNotebookDrawing(currentPageIndex)
            showOverviewDialog()
            return
        } else if (currentPageIndex > pageIndexToDelete) {
            currentPageIndex--
            val notebooks = NotebookManager.getNotebooks(this)
            val notebook = notebooks.find { it.id == notebookId }
            if (notebook != null) {
                notebook.lastDisplayedPage = currentPageIndex
                NotebookManager.updateNotebook(this, notebook)
            }
        }
        
        overviewDialog.dismiss()
        showOverviewDialog()
    }
    
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
                
                val btnRemovePage = view.findViewById<ImageButton>(R.id.btnRemovePage)
                
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
                
                btnRemovePage.setOnClickListener {
                    AlertDialog.Builder(this@MainActivity, R.style.DarkDialogTheme)
                        .setTitle("Remove Page")
                        .setMessage("Are you sure you want to delete this page? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            deletePageAndShift(pageIdx, dialog)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                
                view.setOnClickListener {
                    flushPendingRecognition()
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

    // ── Marker Navigation ─────────────────────────────────────────────────

    private var allMarkers = mutableListOf<SearchMatch>()
    private var currentMarkerIndex = -1

    private fun jumpToNextMarker() {
        activityScope.launch(Dispatchers.IO) {
            val markers = mutableListOf<SearchMatch>()
            
            val dir = File(filesDir, "notebooks")
            val files = dir.listFiles { _, name -> 
                name.startsWith("${notebookName}_page_") && name.endsWith(".svg") 
            } ?: emptyArray()
            
            val pageIndices = files.mapNotNull { 
                it.name.removePrefix("${notebookName}_page_").removeSuffix(".svg").toIntOrNull() 
            }.sorted()

            val markerSymbols = listOf("*", "★", "star", "asterisk")

            for (pageIdx in pageIndices) {
                val file = getNotebookSvgFile(pageIdx)
                if (file.exists()) {
                    val content = file.readText()
                    // Broad check for markers
                    var hasMarker = false
                    for (sym in markerSymbols) {
                        if (content.contains(sym, ignoreCase = true)) {
                            hasMarker = true
                            break
                        }
                    }

                    if (hasMarker) {
                        val result = SvgSerializer.deserialize(content)
                        result.items.forEachIndexed { index, item ->
                            if (item is WordData) {
                                val text = item.text.lowercase()
                                if (markerSymbols.any { text.contains(it) }) {
                                    markers.add(SearchMatch(pageIdx, item.text, index))
                                }
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                allMarkers = markers
                if (allMarkers.isNotEmpty()) {
                    // If we haven't started or markers changed, find the best "next" marker
                    if (currentMarkerIndex == -1 || currentMarkerIndex >= allMarkers.size) {
                        // Find first marker on or after current page
                        val idx = allMarkers.indexOfFirst { it.pageIndex >= currentPageIndex }
                        currentMarkerIndex = if (idx != -1) idx else 0
                    } else {
                        // Advance to next
                        currentMarkerIndex = (currentMarkerIndex + 1) % allMarkers.size
                    }
                    
                    val match = allMarkers[currentMarkerIndex]
                    navigateToMatch(match)
                    
                    Toast.makeText(this@MainActivity, "Jumped to marker ${currentMarkerIndex + 1}/${allMarkers.size}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "No markers found. Draw a '*' or 'star'!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun triggerImageSummaryRecognition(imageItem: DrawingView.ImageItem, bitmap: Bitmap) {
        if (!recognizer.isReady) return
        
        recognizer.recognize(
            bitmap = bitmap,
            prompt = "Provide a very short, one-line summary of what is in this image. No more than 10 words.",
            onPartialResult = { partial ->
                imageItem.text = (imageItem.text + partial).trim()
                drawingView.invalidate()
            },
            onDone = {
                scheduleAutosave()
                drawingView.invalidate()
            },
            onError = { _ -> }
        )
    }
}
