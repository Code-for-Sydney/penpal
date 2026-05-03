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
import java.io.FileOutputStream
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject


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
    private lateinit var toolToolbar: LinearLayout
    private lateinit var btnToggleTools: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnLasso: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var btnAddPrompt: ImageButton
    private lateinit var btnSelectMode: ImageButton

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

    private data class SearchMatch(val pageIndex: Int, val text: String, val itemIndex: Int, val subRect: RectF? = null)
    private var allMatches = mutableListOf<SearchMatch>()
    private var currentMatchIndex = -1

    // ── State ──────────────────────────────────────────────────────────────
    private var activeColor: Int = Color.BLACK

    // ── Recognizer ─────────────────────────────────────────────────────────
    private lateinit var recognizer: HandwritingRecognizer
    private val recognitionHandler = Handler(Looper.getMainLooper())
    private val DEBOUNCE_MS = 2000L
    private var hasPendingRecognition = false
    private val recognitionRunnable = Runnable { 
        hasPendingRecognition = false
        triggerRecognition() 
    }
    private val autosaveRunnable = Runnable { performAutosave() }
    private val AUTOSAVE_DEBOUNCE_MS = 2000L
    private var notebookName: String = "My Notebook"
    private var notebookId: String = ""
    private var isDeletingPage = false
    private fun getNotebookPagePrefix(): String = "${notebookName}_page_"
    private var currentNotebook: Notebook? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentPageIndex: Int = 0
    private var fullRecognizedText: String = ""
    private var pendingClusterBitmap: Bitmap? = null
    private val busyStrokes = mutableSetOf<DrawingView.StrokeItem>()
    private val lastPageDetectionResults = mutableMapOf<Int, List<DrawingView.DetectedBox>>()
    private val recognizedSessionItems = mutableSetOf<Int>()



    // ── Misc ───────────────────────────────────────────────────────────────
    private val pickMedia = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val imageItem = drawingView.addImage(bitmap)
                    recognizedSessionItems.add(System.identityHashCode(imageItem))
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
                        val extractedText = result.data?.getStringExtra("EXTRACTED_TEXT") ?: ""
                        val pdfWordsStr = result.data?.getStringExtra("PDF_WORDS")
                        val imageItem = drawingView.addImage(bitmap)
                        recognizedSessionItems.add(System.identityHashCode(imageItem))
                        if (extractedText.isNotEmpty()) {
                            imageItem.text = extractedText
                        }
                        if (!pdfWordsStr.isNullOrEmpty()) {
                            imageItem.pdfWords = PdfHelper.parseWords(pdfWordsStr)
                        }
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

    private var exportFormat: String = "pdf"
    private val createDocumentLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument()) { uri ->
        if (uri != null) {
            when (exportFormat) {
                "pdf" -> performPdfExport(uri)
                "svg" -> performSvgExport(uri)
                "png" -> performPngExport(uri)
            }
        }
    }

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
        toolToolbar         = findViewById(R.id.toolToolbar)
        btnToggleTools      = findViewById(R.id.btnToggleTools)
        btnEraser           = findViewById(R.id.btnEraser)
        btnLasso            = findViewById(R.id.btnLasso)
        btnSelectMode       = findViewById(R.id.btnSelectMode)
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
        btnExport           = findViewById(R.id.btnExport)
        btnAddPrompt        = findViewById(R.id.btnAddPrompt)

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
        drawingView.notebookType = notebook?.type ?: NotebookType.NOTEBOOK

        migrateOldNotebookToPageZero()

        setupListeners()
        drawingView.brushColor = activeColor
        drawingView.onShowItemColorPicker = { item -> showColorPickerDialog(item) }
        drawingView.onStateChanged = { 
            updateButtonStates()
            scheduleAutosave() 
        }
        drawingView.onPageAdded = {
            updatePageIndicator()
            scheduleAutosave()
        }
        drawingView.onWordModified = { word ->
            triggerRecognitionForWord(word)
        }
        drawingView.onRecognizeSelectedItems = { items ->
            handleLassoRecognition(items)
        }
        drawingView.onPromptTriggered = { promptItem ->
            triggerPromptGemma(promptItem)
        }
        drawingView.onPromptEditRequested = { promptItem ->
            showPromptEditDialog(promptItem)
        }

        updateColorSwatch()
        updateToolState()
        setupRecognizer()
        
        if (drawingView.notebookType == NotebookType.WHITEBOARD) {
            setupWhiteboardMode()
        } else {
            setupNotebookMode()
        }
    }

    private fun setupWhiteboardMode() {
        findViewById<View>(R.id.pageNavigationContainer).visibility = View.GONE
        btnBackground.visibility = View.GONE
        drawingView.canvasBackgroundColor = Color.WHITE
        drawingView.backgroundType = DrawingView.BackgroundType.NONE
        currentPageIndex = 0
        loadNotebookDrawing(0)
    }

    private fun setupNotebookMode() {
        findViewById<View>(R.id.pageNavigationContainer).visibility = View.GONE // Use scroll instead
        btnBackground.visibility = View.VISIBLE
        
        currentNotebook?.let {
            try {
                drawingView.backgroundType = DrawingView.BackgroundType.valueOf(it.defaultBackground)
            } catch (e: Exception) {
                drawingView.backgroundType = DrawingView.BackgroundType.RULED
            }
        }
        
        loadAllPages()
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
        
        val pageIndex = drawingView.getCurrentPageIndex()
        val bitmap = drawingView.createFullPageBitmap(pageIndex) ?: return
        
        // Reset fullRecognizedText for new page-wide recognition
        fullRecognizedText = ""
        
        triggerRecognitionForFullPage(bitmap, pageIndex)
    }

    private fun triggerRecognitionForFullPage(bitmap: Bitmap, pageIndex: Int) {
        setRecognitionState(RecognitionState.RUNNING)
        recognitionText.text = "Analyzing page..."

        val prompt = """
            Detect all handwriting in this image. For each word, number, or star (*), 
            provide its text and its bounding box in JSON format: 
            [{"text": "...", "box_2d": [ymin, xmin, ymax, xmax]}, ...]. 
            Coordinates are 0-1000 relative to the image. 
            Output ONLY the JSON.
        """.trimIndent()

        var accumulated = ""
        recognizer.recognize(
            bitmap = bitmap,
            prompt = prompt,
            onPartialResult = { partial ->
                accumulated += partial
            },
            onDone = {
                val cleanJson = extractJson(accumulated)
                try {
                    val array = JSONArray(cleanJson)
                    val detectedBoxes = mutableListOf<DrawingView.DetectedBox>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val text = obj.optString("text", obj.optString("label", ""))
                        if (text.isEmpty() || !obj.has("box_2d")) continue
                        val box = obj.getJSONArray("box_2d")
                        detectedBoxes.add(DrawingView.DetectedBox(
                            text,
                            box.getDouble(0).toFloat(),
                            box.getDouble(1).toFloat(),
                            box.getDouble(2).toFloat(),
                            box.getDouble(3).toFloat()
                        ))
                    }
                    lastPageDetectionResults[pageIndex] = detectedBoxes
                    drawingView.groupStrokesByBoxes(pageIndex, detectedBoxes)
                    setRecognitionState(RecognitionState.DONE)
                    if (detectedBoxes.isEmpty()) {
                        recognitionText.text = "0 items. Model said: $accumulated"
                    } else {
                        recognitionText.text = "Page analyzed: ${detectedBoxes.size} items found"
                    }
                    scheduleAutosave()
                } catch (e: Exception) {
                    e.printStackTrace()
                    android.util.Log.e("Penpal", "Parse error. Raw model output: $accumulated")
                    setRecognitionState(RecognitionState.ERROR)
                    val shortOutput = if (accumulated.length > 50) accumulated.substring(0, 50) + "..." else accumulated
                    recognitionText.text = "Parse error: $shortOutput"
                }
            },
            onError = { msg ->
                setRecognitionState(RecognitionState.ERROR)
                recognitionText.text = "Error: $msg"
            }
        )
    }

    private fun extractJson(text: String): String {
        // 1. Strip markdown code fences (```json ... ``` or ``` ... ```)
        val stripped = text
            .replace(Regex("```(?:json)?\\s*"), "")
            .replace("```", "")
            .trim()

        // 2. Extract the outermost JSON array [...]
        val start = stripped.indexOf("[")
        val end = stripped.lastIndexOf("]")
        if (start != -1 && end != -1 && end > start) {
            return stripped.substring(start, end + 1)
        }

        // 3. Fallback: return empty array so JSONArray parsing succeeds with 0 items
        return "[]"
    }




    private fun triggerRecognitionForWord(word: DrawingView.WordItem) {

        val strokesInCanvasSpace = drawingView.dissolveWordToStrokes(word)
        if (strokesInCanvasSpace.isEmpty()) return
        
        val bitmap = drawingView.createBitmapForStrokes(strokesInCanvasSpace) ?: return
        
        recognizer.recognize(
            bitmap = bitmap,
            onPartialResult = { partial ->
                word.text += partial
                drawingView.invalidate()
            },
            onDone = {
                scheduleAutosave()
            },
            onError = {
                // Ignore errors on background update
            }
        )
    }

    private fun handleLassoRecognition(items: List<DrawingView.CanvasItem>) {
        if (items.isEmpty()) return

        // 1. Separate recognized and unrecognized items
        val itemsWithText = items.filter { item ->
            (item is DrawingView.WordItem && item.text.isNotEmpty()) ||
            (item is DrawingView.ImageItem && item.text.isNotEmpty())
        }
        val itemsWithoutText = items.filter { !itemsWithText.contains(it) }
        val hasRawStrokes = items.any { it is DrawingView.StrokeItem }

        // 2. Fast path: All items already recognized and no raw strokes -> Toggle mode
        if (itemsWithText.size == items.size && !hasRawStrokes) {
            val anyShowingStrokes = items.any { (it is DrawingView.WordItem && !it.isShowingText) || (it is DrawingView.ImageItem && !it.isShowingText) }
            val target = anyShowingStrokes
            for (item in items) {
                drawingView.setItemStyle(item, "isShowingText", target)
            }
            drawingView.invalidate()
            return
        }

        // 3. Consolidate text from existing recognized items
        val parts = mutableListOf<Pair<RectF, String>>()
        for (item in itemsWithText) {
            val txt = when(item) {
                is DrawingView.WordItem -> item.text
                is DrawingView.ImageItem -> item.text
                else -> ""
            }
            if (txt.isNotEmpty()) parts.add(item.bounds to txt)
        }

        // 4. Try to find text for unrecognized items in background scan cache
        val pageResults = lastPageDetectionResults[drawingView.getCurrentPageIndex()]
        if (pageResults != null && itemsWithoutText.isNotEmpty()) {
            val selectionBounds = RectF(itemsWithoutText[0].bounds)
            for (i in 1 until itemsWithoutText.size) {
                selectionBounds.union(itemsWithoutText[i].bounds)
            }
            
            val pageTop = drawingView.getCurrentPageIndex() * (drawingView.PAGE_HEIGHT + drawingView.PAGE_MARGIN)
            val normLeft = selectionBounds.left * 1000f / drawingView.PAGE_WIDTH
            val normRight = selectionBounds.right * 1000f / drawingView.PAGE_WIDTH
            val normTop = (selectionBounds.top - pageTop) * 1000f / drawingView.PAGE_HEIGHT
            val normBottom = (selectionBounds.bottom - pageTop) * 1000f / drawingView.PAGE_HEIGHT
            val selectionRectNorm = RectF(normLeft, normTop, normRight, normBottom)

            val match = pageResults.find { box ->
                val boxRect = RectF(box.xmin, box.ymin, box.xmax, box.ymax)
                val intersection = RectF(boxRect)
                if (intersection.intersect(selectionRectNorm)) {
                    val intersectArea = intersection.width() * intersection.height()
                    val selectionArea = selectionRectNorm.width() * selectionRectNorm.height()
                    val boxArea = boxRect.width() * boxRect.height()
                    intersectArea / selectionArea > 0.7f && intersectArea / boxArea > 0.7f
                } else false
            }
            
            if (match != null && match.text.isNotEmpty()) {
                parts.add(selectionBounds to match.text)
            }
        }

        // 5. Finalize consolidated text and group items
        parts.sortBy { it.first.left }
        val finalText = parts.joinToString(" ") { it.second }.trim()

        // Group Word/Stroke items
        val word = drawingView.groupSelectedItemsIntoWord(items, finalText)
        word?.isShowingText = true
        
        // Toggle Image items
        for (item in items) {
            if (item is DrawingView.ImageItem) item.isShowingText = true
        }

        drawingView.clearLassoSelection()
        drawingView.invalidate()
        
        if (finalText.isNotEmpty()) {
            setRecognitionState(RecognitionState.DONE)
            recognitionText.text = "Selection: $finalText"
        } else {
            setRecognitionState(RecognitionState.IDLE)
            recognitionText.text = "Items grouped"
        }
        scheduleAutosave()
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
            scheduleAutosave()
        }
        btnRedo.setOnClickListener {
            drawingView.redo(); updateButtonStates()
            scheduleAutosave()
        }
        btnClear.setOnClickListener { 
            if (!drawingView.deleteSelectedItem()) {
                showClearConfirmDialog() 
            }
        }
        btnBrushSize.setOnClickListener   { showBrushSizeDialog() }
        btnColorPicker.setOnClickListener { showColorPickerDialog() }
        btnEraser.setOnClickListener {
            if (drawingView.activeTool == DrawingView.ActiveTool.ERASER) {
                drawingView.activeTool = DrawingView.ActiveTool.BRUSH
            } else {
                drawingView.activeTool = DrawingView.ActiveTool.ERASER
            }
            updateToolState()
        }
        btnLasso.setOnClickListener {
            if (drawingView.activeTool == DrawingView.ActiveTool.LASSO) {
                drawingView.activeTool = DrawingView.ActiveTool.BRUSH
            } else {
                drawingView.activeTool = DrawingView.ActiveTool.LASSO
            }
            updateToolState()
        }
        btnSelectMode.setOnClickListener {
            if (drawingView.activeTool == DrawingView.ActiveTool.SELECT) {
                drawingView.activeTool = DrawingView.ActiveTool.BRUSH
            } else {
                drawingView.activeTool = DrawingView.ActiveTool.SELECT
            }
            updateToolState()
        }
        btnToggleTools.setOnClickListener {
            if (toolToolbar.visibility == View.VISIBLE) {
                toolToolbar.visibility = View.GONE
            } else {
                toolToolbar.visibility = View.VISIBLE
            }
            updateToolState()
        }
        findViewById<ImageButton>(R.id.btnAddImage).setOnClickListener {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<ImageButton>(R.id.btnAddPdf).setOnClickListener {
            pickPdf.launch("application/pdf")
        }
        btnBackground.setOnClickListener { showBackgroundDialog() }
        
        btnAddPrompt.setOnClickListener {
            val item = drawingView.addPromptItem()
            showPromptEditDialog(item)
        }
        
        btnPageUp.setOnClickListener {
            val isNotebook = currentNotebook?.type == NotebookType.NOTEBOOK
            val currentIdx = if (isNotebook) drawingView.getCurrentPageIndex() else currentPageIndex
            
            if (currentIdx > 0) {
                flushPendingRecognition()
                performAutosave()
                currentPageIndex = currentIdx - 1
                if (isNotebook) {
                    drawingView.scrollToPage(currentPageIndex)
                } else {
                    loadNotebookDrawing(currentPageIndex)
                }
            }
        }
        btnPageDown.setOnClickListener {
            val isNotebook = currentNotebook?.type == NotebookType.NOTEBOOK
            val currentIdx = if (isNotebook) drawingView.getCurrentPageIndex() else currentPageIndex
            
            flushPendingRecognition()
            performAutosave()
            currentPageIndex = currentIdx + 1
            if (isNotebook) {
                drawingView.scrollToPage(currentPageIndex)
            } else {
                loadNotebookDrawing(currentPageIndex)
            }
        }
        btnOverview.setOnClickListener { showOverviewDialog() }
        btnSearch.setOnClickListener { showSearchMode(true) }
        btnJumpMarkers.setOnClickListener { jumpToNextMarker() }
        btnExport.setOnClickListener { showExportDialog() }

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

    private fun updateToolState() {
        // Eraser highlight
        if (drawingView.activeTool == DrawingView.ActiveTool.ERASER) {
            btnEraser.setColorFilter(Color.WHITE)
            btnEraser.alpha = 1.0f
        } else {
            btnEraser.setColorFilter(Color.parseColor("#CCCCCC"))
            btnEraser.alpha = 0.8f
        }

        // Lasso highlight
        if (drawingView.activeTool == DrawingView.ActiveTool.LASSO) {
            btnLasso.setColorFilter(Color.WHITE)
            btnLasso.alpha = 1.0f
        } else {
            btnLasso.setColorFilter(Color.parseColor("#CCCCCC"))
            btnLasso.alpha = 0.8f
        }
        // Select mode highlight
        if (drawingView.activeTool == DrawingView.ActiveTool.SELECT) {
            btnSelectMode.setColorFilter(Color.WHITE)
            btnSelectMode.alpha = 1.0f
            btnSelectMode.setImageResource(R.drawable.ic_select)
        } else if (drawingView.activeTool == DrawingView.ActiveTool.BRUSH) {
            btnSelectMode.setColorFilter(Color.WHITE)
            btnSelectMode.alpha = 1.0f
            btnSelectMode.setImageResource(R.drawable.ic_brush)
        } else {
            btnSelectMode.setColorFilter(Color.parseColor("#CCCCCC"))
            btnSelectMode.alpha = 0.8f
            btnSelectMode.setImageResource(R.drawable.ic_select)
        }
        
        // Ensure Toggle button reflects toolbar visibility
        btnToggleTools.setImageResource(R.drawable.ic_hammer)
        if (toolToolbar.visibility == View.VISIBLE) {
            btnToggleTools.alpha = 1.0f
            btnToggleTools.setColorFilter(Color.WHITE)
        } else {
            btnToggleTools.alpha = 0.5f
            btnToggleTools.setColorFilter(Color.parseColor("#CCCCCC"))
        }
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
                        drawingView.isEraser = false
                        updateToolState()
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
                drawingView.setItemStyle(item, "tintColor", color)
            }
            is DrawingView.ImageItem -> {
                // tintColor removed for ImageItem
            }
            is DrawingView.StrokeItem -> {
                drawingView.setItemStyle(item, "paintColor", color)
            }
            is DrawingView.PromptItem -> {
                // Not applicable
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
                    updateToolState()
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
            .setPositiveButton("Apply") { _, _ -> 
                drawingView.brushSize = (slider.progress + 5).toFloat()
                drawingView.isEraser = false
                updateToolState()
            }
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

    private fun loadAllPages() {
        drawingView.clear()

        // Set default background from notebook settings
        drawingView.backgroundType = when (currentNotebook?.defaultBackground) {
            "GRAPH" -> DrawingView.BackgroundType.GRAPH
            "NONE" -> DrawingView.BackgroundType.NONE
            else -> DrawingView.BackgroundType.RULED
        }
        
        val dir = File(filesDir, "notebooks")
        val prefix = getNotebookPagePrefix()
        val pageFiles = dir.listFiles { _, name -> 
            name.startsWith(prefix) && name.endsWith(".svg") 
        }
        
        var maxPage = -1
        
        pageFiles?.forEach { file ->
            val numStr = file.name.removePrefix(prefix).removeSuffix(".svg")
            val pageNum = numStr.toIntOrNull()
            if (pageNum != null) {
                try {
                    val svgContent = file.readText()
                    val result = SvgSerializer.deserialize(svgContent)
                    val dy = pageNum * (drawingView.PAGE_HEIGHT + drawingView.PAGE_MARGIN)
                    drawingView.loadFromSvgDataWithOffset(result.items, dy)
                    if (pageNum > maxPage) {
                        maxPage = pageNum
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        drawingView.numPages = if (maxPage >= 0) maxPage + 1 else 1
        updatePageIndicator()
    }



    private fun updatePageIndicator() {
        tvPageIndicator.text = "${drawingView.numPages} Pages"
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
        if (isDeletingPage) return
        if (currentNotebook?.type == NotebookType.WHITEBOARD) {
            savePage(0)
        } else {
            for (i in 0 until drawingView.numPages) {
                savePage(i)
            }
        }
    }

    private fun savePage(pageIndex: Int) {
        val items = if (currentNotebook?.type == NotebookType.WHITEBOARD) {
            drawingView.getSvgDataList()
        } else {
            // Need to shift them back
            val shiftedItems = drawingView.getShiftedItemsOnPage(pageIndex)
            shiftedItems.mapNotNull { with(drawingView) { it.toSvgData() } }
        }

        val file = getNotebookSvgFile(pageIndex)
        val thumbFile = getNotebookThumbFile(pageIndex)

        if (items.isEmpty()) {
            if (file.exists()) file.delete()
            if (thumbFile.exists()) thumbFile.delete()
            return
        }

        val width = drawingView.width
        val height = drawingView.height
        val bgColor = drawingView.canvasBackgroundColor
        val bgType = drawingView.backgroundType.name
        
        // Generate thumbnail on UI thread
        val thumbBmp = drawingView.createPageThumbnail(pageIndex)

        activityScope.launch(Dispatchers.IO) {
            try {
                // Save SVG
                val svgContent = SvgSerializer.serialize(
                    items = items,
                    width = width,
                    height = height,
                    backgroundColor = bgColor,
                    backgroundType = bgType,
                    contentBounds = null
                )
                file.writeText(svgContent)
                
                // Save Thumbnail
                if (thumbBmp != null) {
                    val out = FileOutputStream(thumbFile)
                    thumbBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                    out.close()
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
                
                // Trigger background recognition for any images/PDFs on the page
                result.items.forEach { itemData ->
                    if (itemData is ImageData) {
                        // Find the corresponding item in drawingView
                        val imageItem = drawingView.getItemsOnPage(pageIndex).find { 
                            it is DrawingView.ImageItem && it.text == itemData.text
                        } as? DrawingView.ImageItem
                        
                        if (imageItem != null && !imageItem.isAiRecognized && !recognizedSessionItems.contains(System.identityHashCode(imageItem))) {
                            recognizedSessionItems.add(System.identityHashCode(imageItem))
                            triggerImageSummaryRecognition(imageItem, imageItem.bitmap)
                        }
                    }
                }
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
                is PromptData -> {
                    // Just draw a rectangle placeholder
                    val matrix = android.graphics.Matrix()
                    matrix.setValues(item.matrix)
                    canvas.save()
                    canvas.concat(matrix)
                    val paint = android.graphics.Paint().apply {
                        color = Color.LTGRAY
                        style = android.graphics.Paint.Style.FILL
                        alpha = 100
                    }
                    canvas.drawRect(0f, 0f, item.width, item.height, paint)
                    canvas.restore()
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
            drawingView.searchHighlightedItem = null
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
            drawingView.searchHighlightedItem = null
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
                    // Quick check for performance - just check if it has text at all
                    if (content.contains("data-text=", ignoreCase = true)) {
                        val result = SvgSerializer.deserialize(content)
                        result.items.forEachIndexed { index, item ->
                            if (item is ImageData && item.pdfWords.isNotEmpty()) {
                                item.pdfWords.forEach { pdfWord ->
                                    if (pdfWord.text.contains(query, ignoreCase = true)) {
                                        matches.add(SearchMatch(pageIdx, pdfWord.text, index, pdfWord.bounds))
                                    }
                                }
                            } else {
                                val text = when (item) {
                                    is WordData -> item.text
                                    is ImageData -> item.text
                                    is PromptData -> item.prompt + " " + item.result
                                    is StrokeData -> ""
                                }.replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ")
                                
                                if (text.isNotBlank() && text.contains(query, ignoreCase = true)) {
                                    matches.add(SearchMatch(pageIdx, text, index))
                                }
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
                    drawingView.searchHighlightedItem = null
                }
                updateSearchUI()
            }
        }
    }

    private fun navigateToMatch(match: SearchMatch) {
        val isNotebook = currentNotebook?.type == NotebookType.NOTEBOOK
        val currentViewedPage = if (isNotebook) {
            drawingView.getCurrentPageIndex()
        } else {
            currentPageIndex
        }

        if (match.pageIndex != currentViewedPage) {
            flushPendingRecognition()
            performAutosave()
            currentPageIndex = match.pageIndex
            if (isNotebook) {
                drawingView.scrollToPage(currentPageIndex)
            } else {
                loadNotebookDrawing(currentPageIndex)
            }
        }
        
        val item = if (isNotebook) {
            drawingView.getItemsOnPage(match.pageIndex).getOrNull(match.itemIndex)
        } else {
            drawingView.getItemAtIndex(match.itemIndex)
        }

        if (item != null) {
            drawingView.searchHighlightedItem = item
            if (match.subRect != null) {
                drawingView.searchHighlightedSubRect = match.subRect
                drawingView.centerOnRect(item, match.subRect)
            } else {
                drawingView.searchHighlightedSubRect = null
                drawingView.centerOnItem(item)
            }
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
        isDeletingPage = true
        try {
            // Delete the page files
            deleteNotebookSvg(pageIndexToDelete)
            
            // Find remaining files to shift down
            val dir = File(filesDir, "notebooks")
            val prefix = getNotebookPagePrefix()
            val allFiles = dir.listFiles { _, name -> 
                name.startsWith(prefix) && name.endsWith(".svg") 
            } ?: emptyArray()
            
            val existingPages = allFiles.mapNotNull { 
                it.name.removePrefix(prefix).removeSuffix(".svg").toIntOrNull() 
            }.filter { it > pageIndexToDelete }.sorted()
            
            for (pageIdx in existingPages) {
                val oldSvg = getNotebookSvgFile(pageIdx)
                val newSvg = getNotebookSvgFile(pageIdx - 1)
                
                if (newSvg.exists()) newSvg.delete()
                oldSvg.renameTo(newSvg)
                
                val oldThumb = getNotebookThumbFile(pageIdx)
                val newThumb = getNotebookThumbFile(pageIdx - 1)
                if (oldThumb.exists()) {
                    if (newThumb.exists()) newThumb.delete()
                    oldThumb.renameTo(newThumb)
                }
            }
            
            // Update state
            drawingView.numPages = (drawingView.numPages - 1).coerceAtLeast(1)
            
            if (currentNotebook?.type == NotebookType.WHITEBOARD) {
                currentPageIndex = 0
                loadNotebookDrawing(0)
            } else {
                // In notebook mode, reload all pages to reflect shifts in the infinite scroll
                loadAllPages()
            }
            
            overviewDialog.dismiss()
            showOverviewDialog(skipAutosave = true)
        } finally {
            isDeletingPage = false
        }
    }
    
    private fun showOverviewDialog(skipAutosave: Boolean = false) {
        if (!skipAutosave) performAutosave() // ensure current page is up to date
        val dialogView = layoutInflater.inflate(R.layout.dialog_overview, null)
        val gridView = dialogView.findViewById<GridView>(R.id.gridOverview)
        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setView(dialogView).create()
            
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Find all pages by scanning the directory
        val dir = File(filesDir, "notebooks")
        val prefix = getNotebookPagePrefix()
        val allFiles = dir.listFiles { _, name -> 
            name.startsWith(prefix) && name.endsWith(".svg") 
        } ?: emptyArray()
        
        val existingPages = allFiles.mapNotNull { 
            it.name.removePrefix(prefix).removeSuffix(".svg").toIntOrNull() 
        }.toMutableSet()
        
        // Determine total pages to show: max of current numPages, highest file index, and current viewed page
        val currentViewedPage = drawingView.getCurrentPageIndex()
        val maxPageInFiles = existingPages.maxOrNull() ?: -1
        val totalPages = maxOf(drawingView.numPages, maxPageInFiles + 1, currentViewedPage + 1)
        
        val sortedPages = (0 until totalPages).toList()
        
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
                
                if (pageIdx == currentViewedPage) {
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
                    if (currentNotebook?.type == NotebookType.WHITEBOARD) {
                        currentPageIndex = pageIdx
                        loadNotebookDrawing(currentPageIndex)
                    } else {
                        drawingView.scrollToPage(pageIdx)
                    }
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
        
        val hasExistingText = imageItem.text.isNotEmpty()
        val prompt = if (hasExistingText) {
            "This image already has some digital text. Please find and transcribe any ADDITIONAL handwriting or describe any diagrams/images you see. Be concise."
        } else {
            "Transcribe all handwriting in this image and provide a short summary of any diagrams or images. Keep it to one line if possible."
        }

        recognizer.recognize(
            bitmap = bitmap,
            prompt = prompt,
            isBackground = true,
            onPartialResult = { partial ->
                if (imageItem.text.contains(partial.trim())) return@recognize
                imageItem.text = (imageItem.text + " " + partial).trim().replace(Regex("\\s+"), " ")
                drawingView.invalidate()
            },
            onDone = {
                imageItem.isAiRecognized = true
                scheduleAutosave()
                drawingView.invalidate()
            },
            onError = { _ -> }
        )
    }
    // ── Export ──────────────────────────────────────────────────────────

    private fun showExportDialog() {
        val options = arrayOf("PDF document (.pdf)", "SVG graphics (.svg)", "PNG image (.png)")
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Export Drawing")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startExport("pdf", "application/pdf")
                    1 -> startExport("svg", "image/svg+xml")
                    2 -> startExport("png", "image/png")
                }
            }
            .show()
    }

    private fun startExport(format: String, mimeType: String) {
        exportFormat = format
        val defaultName = "${notebookName.replace(" ", "_")}_export.$format"
        createDocumentLauncher.launch(defaultName)
    }

    private fun performPdfExport(uri: android.net.Uri) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val pdfDocument = android.graphics.pdf.PdfDocument()
                val isWhiteboard = currentNotebook?.type == NotebookType.WHITEBOARD
                
                if (isWhiteboard) {
                    val bounds = drawingView.getAllContentBounds()
                    if (bounds.isEmpty) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Canvas is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    // Add margin
                    bounds.inset(-40f, -40f)
                    
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(bounds.width().toInt(), bounds.height().toInt(), 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    drawingView.renderToExternalCanvas(page.canvas, bounds)
                    pdfDocument.finishPage(page)
                } else {
                    val pageIndices = drawingView.getNonEmptyPageIndices()
                    if (pageIndices.isEmpty()) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Notebook is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    
                    for ((i, idx) in pageIndices.withIndex()) {
                        val pageRect = drawingView.getPageRect(idx)
                        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageRect.width().toInt(), pageRect.height().toInt(), i + 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        drawingView.renderToExternalCanvas(page.canvas, pageRect, listOf(idx))
                        pdfDocument.finishPage(page)
                    }
                }

                contentResolver.openOutputStream(uri)?.use { out ->
                    pdfDocument.writeTo(out)
                }
                pdfDocument.close()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Exported PDF successfully", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun performSvgExport(uri: android.net.Uri) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val isWhiteboard = currentNotebook?.type == NotebookType.WHITEBOARD
                val svgString: String
                
                if (isWhiteboard) {
                    val bounds = drawingView.getAllContentBounds()
                    val svgData = drawingView.getSvgDataForExport()
                    svgString = SvgSerializer.serialize(
                        items = svgData,
                        width = bounds.width().toInt(),
                        height = bounds.height().toInt(),
                        backgroundColor = drawingView.canvasBackgroundColor,
                        backgroundType = "NONE",
                        contentBounds = bounds
                    )
                } else {
                    val pageIndices = drawingView.getNonEmptyPageIndices()
                    if (pageIndices.isEmpty()) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Notebook is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    
                    // For SVG notebook export, we'll create one long SVG containing all non-empty pages
                    val firstPage = drawingView.getPageRect(pageIndices.first())
                    val lastPage = drawingView.getPageRect(pageIndices.last())
                    val combinedBounds = RectF(0f, firstPage.top, drawingView.PAGE_WIDTH, lastPage.bottom)
                    
                    val svgData = drawingView.getSvgDataForExport(pageIndices)
                    svgString = SvgSerializer.serialize(
                        items = svgData,
                        width = combinedBounds.width().toInt(),
                        height = combinedBounds.height().toInt(),
                        backgroundColor = Color.WHITE,
                        backgroundType = drawingView.backgroundType.name,
                        contentBounds = combinedBounds
                    )
                }

                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(svgString.toByteArray())
                }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Exported SVG successfully", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun performPngExport(uri: android.net.Uri) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val isWhiteboard = currentNotebook?.type == NotebookType.WHITEBOARD
                val bitmap: Bitmap
                
                if (isWhiteboard) {
                    val bounds = drawingView.getAllContentBounds()
                    if (bounds.isEmpty) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Canvas is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    bounds.inset(-20f, -20f)
                    
                    bitmap = Bitmap.createBitmap(bounds.width().toInt(), bounds.height().toInt(), Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawingView.renderToExternalCanvas(canvas, bounds)
                } else {
                    val pageIndices = drawingView.getNonEmptyPageIndices()
                    if (pageIndices.isEmpty()) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Notebook is empty", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    
                    val firstPage = drawingView.getPageRect(pageIndices.first())
                    val lastPage = drawingView.getPageRect(pageIndices.last())
                    val combinedBounds = RectF(0f, firstPage.top, drawingView.PAGE_WIDTH, lastPage.bottom)
                    
                    bitmap = Bitmap.createBitmap(combinedBounds.width().toInt(), combinedBounds.height().toInt(), Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawingView.renderToExternalCanvas(canvas, combinedBounds, pageIndices)
                }

                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Exported PNG successfully", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showPromptEditDialog(item: DrawingView.PromptItem) {
        val editText = EditText(this)
        editText.setText(item.prompt)
        editText.setSelection(item.prompt.length)
        
        AlertDialog.Builder(this)
            .setTitle("Edit Prompt")
            .setView(editText)
            .setPositiveButton("Ask Gemma") { _, _ ->
                item.prompt = editText.text.toString()
                item.result = ""
                item.isShowingResult = true
                triggerPromptGemma(item)
                drawingView.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerPromptGemma(item: DrawingView.PromptItem) {
        if (!recognizer.isReady) {
            Toast.makeText(this, "Gemma is not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val contextText = collectAllNotebookText(excludeItem = item)
        val userPrompt = item.prompt
        
        val fullPrompt = """
            You are a helpful AI assistant called Gemma. 
            The user is working on a notebook. Below is the text from their notebook:
            ---
            $contextText
            ---
            The user's question or prompt is: $userPrompt
            
            Provide a helpful, concise response that fits in a small text box. 
            Keep it to 3-5 sentences maximum.
        """.trimIndent()

        item.result = "Gemma is thinking..."
        drawingView.invalidate()
        
        setRecognitionState(RecognitionState.RUNNING)
        var accumulated = ""
        
        // Use a bitmap from the current page to satisfy the multimodal requirement if needed, 
        // though we mainly care about the text context.
        val bitmap = drawingView.createFullPageBitmap(drawingView.getCurrentPageIndex()) ?: return

        recognizer.recognize(
            bitmap = bitmap,
            prompt = fullPrompt,
            onPartialResult = { partial ->
                accumulated += partial
                item.result = accumulated
                drawingView.invalidate()
            },
            onDone = {
                item.result = accumulated
                setRecognitionState(RecognitionState.DONE)
                drawingView.invalidate()
                scheduleAutosave()
            },
            onError = { msg ->
                item.result = "Error: $msg"
                setRecognitionState(RecognitionState.ERROR)
                drawingView.invalidate()
            }
        )
    }

    private fun collectAllNotebookText(excludeItem: DrawingView.CanvasItem? = null): String {
        val sb = StringBuilder()
        val isNotebook = currentNotebook?.type == NotebookType.NOTEBOOK
        
        if (isNotebook) {
            for (i in 0 until drawingView.numPages) {
                val pageItems = drawingView.getItemsOnPage(i)
                if (pageItems.isNotEmpty()) {
                    sb.appendLine("Page ${i + 1}:")
                    for (item in pageItems) {
                        if (item === excludeItem) continue
                        val text = when (item) {
                            is DrawingView.WordItem -> item.text
                            is DrawingView.ImageItem -> item.text
                            is DrawingView.PromptItem -> "AI Prompt: ${item.prompt}\nAI Result: ${item.result}"
                            is DrawingView.StrokeItem -> ""
                        }
                        if (text.isNotEmpty()) {
                            sb.appendLine("- $text")
                        }
                    }
                    sb.appendLine()
                }
            }
        } else {
            // Whiteboard mode: just collect all items
            val items = drawingView.getItemsOnPage(0)
            for (item in items) {
                if (item === excludeItem) continue
                val text = when (item) {
                    is DrawingView.WordItem -> item.text
                    is DrawingView.ImageItem -> item.text
                    is DrawingView.PromptItem -> "AI Prompt: ${item.prompt}\nAI Result: ${item.result}"
                    is DrawingView.StrokeItem -> ""
                }
                if (text.isNotEmpty()) {
                    sb.appendLine("- $text")
                }
            }
        }
        
        return sb.toString()
    }
}
