package com.drawapp

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Matrix
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


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
    private lateinit var btnAddText: ImageButton
    private lateinit var optionsMenu: LinearLayout
    private lateinit var btnOptions: ImageButton
    private lateinit var btnToggleTouchAreas: ImageButton
    private lateinit var btnToggleGemma: ImageButton

    private lateinit var etInlineEdit: EditText
    private lateinit var inlineEditContainer: LinearLayout
    private lateinit var btnConfirmInlineEdit: ImageButton
    private lateinit var recognitionProgress: ProgressBar

    private var editingTextItem: DrawingView.TextItem? = null
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

    // ── Audio Recording ─────────────────────────────────────────────────
    private lateinit var btnRecordAudio: ImageButton
    private lateinit var btnRecordingsList: ImageButton
    private lateinit var recordingPanel: LinearLayout
    private lateinit var recordingIndicator: View
    private lateinit var tvRecordingDuration: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var btnStopRecording: ImageButton

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var gemmaServer: GemmaServerClient? = null
    private var gemmaTranscriber: GemmaTranscriber? = null
    private var processingQueue: ProcessingQueueManager? = null
    private var isRecording = false
    private var recordingStartTime: Long = 0
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val updateDurationRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                tvRecordingDuration.text = String.format("%d:%02d", minutes, seconds)
                recordingHandler.postDelayed(this, 1000)
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission required for recording", Toast.LENGTH_LONG).show()
        }
    }

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
        btnAddText          = findViewById(R.id.btnAddText)
        recognitionProgress = findViewById(R.id.recognitionProgress)
        recognitionIcon     = findViewById(R.id.recognitionIcon)
        recognitionText     = findViewById(R.id.recognitionText)
        btnBack             = findViewById(R.id.btnBack)
        btnPageUp           = findViewById(R.id.btnPageUp)
        btnPageDown         = findViewById(R.id.btnPageDown)
        btnOverview         = findViewById(R.id.btnOverview)
        btnSearch           = findViewById(R.id.btnSearch)
        btnJumpMarkers      = findViewById(R.id.btnJumpMarkers)
        etInlineEdit        = findViewById(R.id.etInlineEdit)
        inlineEditContainer = findViewById(R.id.inlineEditContainer)
        btnConfirmInlineEdit = findViewById(R.id.btnConfirmInlineEdit)
        btnBackground       = findViewById(R.id.btnBackground)
        tvPageIndicator     = findViewById(R.id.tvPageIndicator)
        btnExport           = findViewById(R.id.btnExport)
        btnAddPrompt        = findViewById(R.id.btnAddPrompt)
        optionsMenu         = findViewById(R.id.optionsMenu)
        btnOptions          = findViewById(R.id.btnOptions)
        btnToggleTouchAreas = findViewById(R.id.btnToggleTouchAreas)
        btnToggleGemma      = findViewById(R.id.btnToggleGemma)

        searchBarContainer  = findViewById(R.id.searchBarContainer)
        etSearch            = findViewById(R.id.etSearch)
        btnCloseSearch      = findViewById(R.id.btnCloseSearch)
        btnNextMatch        = findViewById(R.id.btnNextMatch)
        btnPrevMatch        = findViewById(R.id.btnPrevMatch)
        tvSearchCount       = findViewById(R.id.tvSearchCount)

        // Audio Recording
        btnRecordAudio      = findViewById(R.id.btnRecordAudio)
        btnRecordingsList   = findViewById(R.id.btnRecordingsList)
        recordingPanel      = findViewById(R.id.recordingPanel)
        recordingIndicator  = findViewById(R.id.recordingIndicator)
        tvRecordingDuration = findViewById(R.id.tvRecordingDuration)
        tvRecordingStatus   = findViewById(R.id.tvRecordingStatus)
        btnStopRecording    = findViewById(R.id.btnStopRecording)

        // Initialize audio recorder and use shared Gemma server from application
        audioRecorder = AudioRecorder(this)
        audioPlayer = AudioPlayer(this)
        gemmaServer = (application as PenpalApplication).gemmaServer
        gemmaTranscriber = GemmaTranscriber.getInstance(this)
        processingQueue = ProcessingQueueManager.getInstance(this)

        // Start processing queue when app starts
        processingQueue?.startProcessing()

        // Set up audio recorder callbacks
        audioRecorder?.onAmplitudeUpdate = { amplitude ->
            Log.d("MainActivity", "Recording amplitude: ${amplitude.toInt()} dB")
        }

        audioRecorder?.onError = { error ->
            Log.e("MainActivity", "Audio recording error: $error")
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        }


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
        drawingView.onTextEditRequested = { textItem ->
            startInlineTextEdit(textItem)
        }

        btnConfirmInlineEdit.setOnClickListener {
            finishInlineTextEdit()
        }

        updateColorSwatch()
        updateToolState()
        updateOptionsUI()
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
        // Cleanup audio
        audioPlayer?.cleanup()
        stopPlayback()
        // No longer closing the recognizer here as it is shared
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recognizer / model setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupRecognizer() {
        recognizer = HandwritingRecognizer.getInstance(this)

        // Wire stroke callback → debounced recognition & autosave
        drawingView.onStrokeCompleted = {
            scheduleRecognition()
            scheduleAutosave()
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
            config = HandwritingRecognizer.InferenceConfig(),
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
            config = HandwritingRecognizer.InferenceConfig(prompt = prompt),
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
            onPartialResult = { partial: String ->
                word.text += partial
                drawingView.invalidate()
            },
            onDone = {
                scheduleAutosave()
            },
            onError = { _: String ->
                // Ignore errors on background update
            }
        )
    }

    private fun handleLassoRecognition(items: List<DrawingView.CanvasItem>) {
        if (items.isEmpty()) return

        // 1. Check if all items are already "recognized" types (Word, Image, Text, or Prompt)
        val allAreGrouped = items.all { it is DrawingView.WordItem || it is DrawingView.ImageItem || it is DrawingView.TextItem || it is DrawingView.PromptItem }
        val hasRawStrokes = items.any { it is DrawingView.StrokeItem }

        // 2. Fast path: All items already grouped and no raw strokes -> Toggle mode
        if (allAreGrouped && !hasRawStrokes) {
            val anyShowingStrokes = items.any { 
                (it is DrawingView.WordItem && !it.isShowingText) || 
                (it is DrawingView.ImageItem && !it.isShowingText) ||
                (it is DrawingView.PromptItem && !it.isShowingResult)
            }
            val target = anyShowingStrokes
            for (item in items) {
                drawingView.setItemStyle(item, "isShowingText", target)
            }
            drawingView.invalidate()
            return
        }

        // Partition items into those with text (recognized) and raw strokes
        val itemsWithText = items.filter { it is DrawingView.WordItem || it is DrawingView.ImageItem || it is DrawingView.TextItem || it is DrawingView.PromptItem }
        val itemsWithoutText = items.filter { it is DrawingView.StrokeItem }

        // 3. Consolidate text from existing recognized items
        val parts = mutableListOf<Pair<RectF, String>>()
        for (item in itemsWithText) {
            val txt = when(item) {
                is DrawingView.WordItem -> item.text
                is DrawingView.ImageItem -> item.text
                is DrawingView.TextItem -> item.text
                is DrawingView.PromptItem -> item.result
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
        btnAddText.setOnClickListener {
            val item = drawingView.addTextItem("")
            startInlineTextEdit(item)
        }

        // ── Audio Recording ──────────────────────────────────────────────
        btnRecordAudio.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                requestAudioPermissionAndRecord()
            }
        }

        btnStopRecording.setOnClickListener {
            if (isRecording) {
                stopRecording()
            }
        }

        btnRecordingsList.setOnClickListener {
            showRecordingsListDialog()
        }
        // ─────────────────────────────────────────────────────────────────

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
        
        btnOptions.setOnClickListener {
            optionsMenu.visibility = if (optionsMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateOptionsUI()
        }
        btnToggleTouchAreas.setOnClickListener {
            drawingView.showTouchAreas = !drawingView.showTouchAreas
            drawingView.invalidate()
            updateOptionsUI()
        }
        btnToggleGemma.setOnClickListener {
            val panel = findViewById<View>(R.id.recognitionPanel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            updateOptionsUI()
        }

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

    private fun updateOptionsUI() {
        btnToggleTouchAreas.setColorFilter(if (drawingView.showTouchAreas) Color.parseColor("#7C4DFF") else Color.parseColor("#CCCCCC"))
        val panel = findViewById<View>(R.id.recognitionPanel)
        btnToggleGemma.setColorFilter(if (panel.visibility == View.VISIBLE) Color.parseColor("#7C4DFF") else Color.parseColor("#CCCCCC"))
        
        btnOptions.setColorFilter(if (optionsMenu.visibility == View.VISIBLE) Color.WHITE else Color.parseColor("#CCCCCC"))
        btnOptions.alpha = if (optionsMenu.visibility == View.VISIBLE) 1.0f else 0.8f
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
            is DrawingView.TextItem -> {
                drawingView.setItemStyle(item, "color", color)
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
                fullRecognizedText = ""
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
        fullRecognizedText = ""
        recognitionText.text = "Draw something to recognize…"
        recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
        
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
                is TextData -> {
                    val matrix = android.graphics.Matrix()
                    matrix.setValues(item.matrix)
                    canvas.save()
                    canvas.concat(matrix)
                    
                    val paint = android.graphics.Paint().apply {
                        color = item.color
                        textSize = item.fontSize
                        isAntiAlias = true
                    }
                    
                    // Simple single line for thumbnail
                    canvas.drawText(item.text.take(20), 10f, item.fontSize, paint)
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
                                    is TextData -> item.text
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
            config = HandwritingRecognizer.InferenceConfig(prompt = prompt),
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val editText = EditText(this).apply {
            setTextColor(Color.WHITE)
            setText(item.prompt)
            setSelection(item.prompt.length)
            hint = "Ask a question or describe what you want..."
        }
        
        val audioButton = Button(this).apply {
            text = if (item.hasAudio()) "Attached: ${File(item.audioFilePath!!).name}\nTap to change" else "Attach Recording"
            setOnClickListener {
                showRecordingsListForAttachment(item) { selectedFile ->
                    item.audioFilePath = selectedFile.absolutePath
                    item.audioTranscription = null  // Clear cached transcription
                    text = "Attached: ${selectedFile.name}\nTap to change"
                }
            }
        }
        
        var clearButton: Button? = null
        if (item.hasAudio()) {
            clearButton = Button(this).apply {
                text = "Remove Audio"
                setOnClickListener {
                    item.audioFilePath = null
                    item.audioTranscription = null
                    audioButton.text = "Attach Recording"
                    clearButton?.let { layout.removeView(it) }
                }
            }
            layout.addView(clearButton)
        }
        
        layout.addView(editText)
        layout.addView(audioButton)
        
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Edit Prompt")
            .setView(layout)
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

    private fun showTextEditDialog(item: DrawingView.TextItem) {
        val et = EditText(this).apply {
            setText(item.text)
            hint = "Type here..."
            setTextColor(Color.WHITE)
            setSelection(item.text.length)
        }
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Edit Text")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val newText = et.text.toString()
                if (newText != item.text) {
                    drawingView.pushAction(drawingView.StyleAction(item, "text", item.text, newText))
                    item.text = newText
                    drawingView.invalidate()
                    scheduleAutosave()
                    scheduleRecognition()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerPromptGemma(item: DrawingView.PromptItem) {
        Log.d("MainActivity", "═══════════════════════════════════════════")
        Log.d("MainActivity", "triggerPromptGemma() called")
        Log.d("MainActivity", "  item.prompt: '${item.prompt}'")
        Log.d("MainActivity", "  item.hasAudio(): ${item.hasAudio()}")
        Log.d("MainActivity", "  item.audioFilePath: ${item.audioFilePath}")
        Log.d("MainActivity", "  recognizer.isReady: ${recognizer.isReady}")
        
        if (!recognizer.isReady) {
            Log.e("MainActivity", "  ERROR: Gemma is not ready")
            Toast.makeText(this, "Gemma is not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val contextText = collectAllNotebookText(excludeItem = item)
        val userPrompt = item.prompt
        
        // If item has audio, transcribe it first
        var fullPrompt = ""
        
        if (item.hasAudio()) {
            Log.d("MainActivity", "  Processing audio file: ${item.audioFilePath}")
            val audioFile: File? = item.audioFilePath?.let { File(it) }
            if (audioFile != null && audioFile.exists()) {
                Log.d("MainActivity", "  Audio file exists, size: ${audioFile.length()} bytes")
                // Use GemmaTranscriber to process audio
                runBlocking {
                    try {
                        val transcriber = GemmaTranscriber.getInstance(this@MainActivity)
                        Log.d("MainActivity", "  Using GemmaTranscriber for audio transcription")
                        
                        // Check if we can use local engine or need server
                        if (transcriber.isLocalEngineReady()) {
                            Log.d("MainActivity", "  Using local inference engine")
                            val audioResult = transcriber.transcribe(audioFile, useLocalInference = true)
                            Log.d("MainActivity", "  Audio transcription result: success=${audioResult.success}")
                            Log.d("MainActivity", "  Transcription: '${audioResult.transcription}'")
                            if (audioResult.success) {
                                item.audioTranscription = audioResult.transcription
                            } else {
                                Log.e("MainActivity", "  Audio transcription failed: ${audioResult.errorMessage}")
                            }
                        } else if (transcriber.isConfigured()) {
                            Log.d("MainActivity", "  Using remote server: ${transcriber.getServerUrl()}")
                            val audioResult = transcriber.transcribe(audioFile, useLocalInference = false)
                            Log.d("MainActivity", "  Server transcription result: success=${audioResult.success}")
                            if (audioResult.success) {
                                item.audioTranscription = audioResult.transcription
                            } else {
                                Log.e("MainActivity", "  Server transcription failed: ${audioResult.errorMessage}")
                            }
                        } else {
                            Log.e("MainActivity", "  No inference backend available (neither local engine ready nor server configured)")
                            item.result = "Error: No inference backend available.\nPlease configure Gemma server in settings or download a local model."
                            drawingView.invalidate()
                            return@runBlocking
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "  Exception during audio processing: ${e.message}", e)
                        item.result = "Error processing audio: ${e.message}"
                        drawingView.invalidate()
                        return@runBlocking
                    }
                }
            } else {
                Log.e("MainActivity", "  Audio file does not exist!")
            }
        }
        
        // Build the full prompt with audio transcription if available
        val audioContext = if (item.audioTranscription != null) {
            "\n\nThe user also provided audio which was transcribed as:\n\"${item.audioTranscription}\""
        } else ""
        
        fullPrompt = """
            You are a helpful AI assistant called Gemma. 
            The user is working on a notebook. Below is the text from their notebook:
            ---
            $contextText
            ---$audioContext
            The user's question or prompt is: $userPrompt
            
            Provide a helpful, concise response that fits in a small text box. 
            Keep it to 3-5 sentences maximum.
        """.trimIndent()

        Log.d("MainActivity", "  Full prompt length: ${fullPrompt.length} chars")
        
        item.result = "Gemma is thinking..."
        drawingView.invalidate()
        
        setRecognitionState(RecognitionState.RUNNING)
        var accumulated = ""
        
        // Use a bitmap from the current page to satisfy the multimodal requirement if needed, 
        // though we mainly care about the text context.
        val bitmap = drawingView.createFullPageBitmap(drawingView.getCurrentPageIndex()) ?: return

        recognizer.recognize(
            bitmap = bitmap,
            config = HandwritingRecognizer.InferenceConfig(prompt = fullPrompt),
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
                            is DrawingView.TextItem -> item.text
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
                    is DrawingView.TextItem -> item.text
                    is DrawingView.StrokeItem -> ""
                }
                if (text.isNotEmpty()) {
                    sb.appendLine("- $text")
                }
            }
        }
        
        return sb.toString()
    }

    private fun startInlineTextEdit(item: DrawingView.TextItem) {
        editingTextItem = item
        drawingView.hiddenItem = item
        drawingView.invalidate()

        inlineEditContainer.visibility = View.VISIBLE
        etInlineEdit.setText(item.text)
        etInlineEdit.setSelection(item.text.length)
        etInlineEdit.setTextColor(Color.WHITE)
        
        // Calculate screen position
        val fullMatrix = Matrix(drawingView.viewMatrix)
        fullMatrix.preConcat(item.matrix)
        
        val pts = floatArrayOf(10f, 0f)
        fullMatrix.mapPoints(pts)
        
        val rotation = drawingView.getItemRotation(item)
        val scale = drawingView.scaleFactor
        
        // Match font size exactly
        etInlineEdit.textSize = (item.fontSize * scale) / resources.displayMetrics.scaledDensity
        
        inlineEditContainer.translationX = pts[0]
        inlineEditContainer.translationY = pts[1]
        inlineEditContainer.rotation = rotation
        
        etInlineEdit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etInlineEdit, InputMethodManager.SHOW_IMPLICIT)
        
        etInlineEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                finishInlineTextEdit()
                true
            } else false
        }
        
        etInlineEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && editingTextItem != null) {
                finishInlineTextEdit()
            }
        }
    }

    private fun finishInlineTextEdit() {
        val item = editingTextItem ?: return
        val newText = etInlineEdit.text.toString()
        
        if (newText != item.text) {
            drawingView.pushAction(drawingView.UpdateTextAction(item, item.text, newText))
            item.text = newText
            scheduleAutosave()
            scheduleRecognition()
        }
        
        inlineEditContainer.visibility = View.GONE
        drawingView.hiddenItem = null
        editingTextItem = null
        drawingView.invalidate()
        
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInlineEdit.windowToken, 0)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Audio Recording Functions
    // ══════════════════════════════════════════════════════════════════════

    private fun requestAudioPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        Log.d("MainActivity", "startRecording called")

        val fileName = "recording_${System.currentTimeMillis()}"
        val success = audioRecorder?.startRecording(fileName) ?: false

        if (success) {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()

            // Show recording panel
            recordingPanel.visibility = View.VISIBLE

            // Change button color to indicate recording
            btnRecordAudio.setColorFilter(Color.parseColor("#FF5252"))

            // Start duration timer
            recordingHandler.post(updateDurationRunnable)

            Log.d("MainActivity", "Recording started: $fileName")
        } else {
            Log.e("MainActivity", "Failed to start recording")
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        Log.d("MainActivity", "stopRecording called")

        // Stop duration timer
        recordingHandler.removeCallbacks(updateDurationRunnable)

        val recordedFile = audioRecorder?.stopRecording()

        isRecording = false

        // Hide recording panel
        recordingPanel.visibility = View.GONE

        // Reset button color
        btnRecordAudio.clearColorFilter()

        if (recordedFile != null && recordedFile.exists()) {
            val durationMs = audioRecorder?.getDurationMs(recordedFile) ?: 0
            val durationSec = durationMs / 1000

            Log.d("MainActivity", "Recording saved: ${recordedFile.absolutePath} (${durationSec}s)")

            // Show confirmation with file info
            AlertDialog.Builder(this)
                .setTitle("Recording Saved")
                .setMessage("Duration: ${durationSec}s\nFile: ${recordedFile.name}")
                .setPositiveButton("OK", null)
                .setNeutralButton("View in Logs") { _, _ ->
                    Log.d("MainActivity", "Recorded file: ${recordedFile.absolutePath}")
                }
                .show()
        } else {
            Log.e("MainActivity", "Recording file is null or doesn't exist")
            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recordings List Dialog
    // ══════════════════════════════════════════════════════════════════════

    private var recordingsDialog: AlertDialog? = null
    private var recordingsAdapter: RecordingsAdapter? = null
    private var updateProgressRunnable: Runnable? = null

    private fun showRecordingsListDialog() {
        val recordings = audioRecorder?.getRecordings()?.toMutableList() ?: mutableListOf()

        if (recordings.isEmpty()) {
            Toast.makeText(this, "No recordings yet", Toast.LENGTH_SHORT).show()
            return
        }

        // Create dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_recordings_list, null)

        val rvRecordings: RecyclerView = dialogView.findViewById(R.id.rvRecordings)
        val tvEmptyState: TextView = dialogView.findViewById(R.id.tvEmptyState)
        val tvRecordingCount: TextView = dialogView.findViewById(R.id.tvRecordingCount)
        val playbackControls: LinearLayout = dialogView.findViewById(R.id.playbackControls)
        val tvNowPlaying: TextView = dialogView.findViewById(R.id.tvNowPlaying)
        val tvCurrentTime: TextView = dialogView.findViewById(R.id.tvCurrentTime)
        val tvTotalTime: TextView = dialogView.findViewById(R.id.tvTotalTime)
        val seekBar: SeekBar = dialogView.findViewById(R.id.seekBarPlayback)
        val btnPlayPause: ImageButton = dialogView.findViewById(R.id.btnPlayPause)
        val btnRewind: ImageButton = dialogView.findViewById(R.id.btnRewind)
        val btnForward: ImageButton = dialogView.findViewById(R.id.btnForward)
        val btnDeleteAll: Button = dialogView.findViewById(R.id.btnDeleteAll)
        val btnClose: Button = dialogView.findViewById(R.id.btnClose)
        val btnTranscribe: Button = dialogView.findViewById(R.id.btnTranscribe)
        val btnSettings: ImageButton = dialogView.findViewById(R.id.btnSettings)
        val btnYouTube: ImageButton = dialogView.findViewById(R.id.btnYouTube)
        val etPrompt: EditText = dialogView.findViewById(R.id.etPrompt)
        val tvServerUrl: TextView = dialogView.findViewById(R.id.tvServerUrl)
        val processingQueueStatus: LinearLayout = dialogView.findViewById(R.id.processingQueueStatus)
        val tvQueueStatus: TextView = dialogView.findViewById(R.id.tvQueueStatus)
        val progressQueue: ProgressBar = dialogView.findViewById(R.id.progressQueue)
        val btnQueueForLater: Button = dialogView.findViewById(R.id.btnQueueForLater)
        val rbNow: RadioButton = dialogView.findViewById(R.id.rbNow)
        val rbQueue: RadioButton = dialogView.findViewById(R.id.rbQueue)
        val rgProcessingMode: RadioGroup = dialogView.findViewById(R.id.rgProcessingMode)

        tvRecordingCount.text = "${recordings.size} files"

        // Show server URL and inference mode status
        val currentUrl = gemmaServer?.getBaseUrl() ?: ""
        val engineMode = if (gemmaTranscriber?.isUsingLocalEngine() == true) "Local" else "Server"
        if (currentUrl.isNotBlank()) {
            tvServerUrl.visibility = View.VISIBLE
            tvServerUrl.text = "$engineMode | $currentUrl"
        } else {
            tvServerUrl.text = "$engineMode inference"
tvServerUrl.visibility = View.VISIBLE
        }

        // Setup model status
        val modelStatusArea: LinearLayout = dialogView.findViewById(R.id.modelStatusArea)
        val tvModelStatus: TextView = dialogView.findViewById(R.id.tvModelStatus)
        val modelProgressBar: ProgressBar = dialogView.findViewById(R.id.modelProgressBar)
        val btnDownloadModel: Button = dialogView.findViewById(R.id.btnDownloadModel)
        setupModelStatus(modelStatusArea, tvModelStatus, modelProgressBar, btnDownloadModel)

        // Setup processing queue status
        updateQueueStatus(processingQueueStatus, tvQueueStatus, progressQueue, btnQueueForLater)

        // Watch queue changes
        processingQueue?.queueItemsFlow?.let { flow ->
            activityScope.launch {
                flow.collect { items ->
                    updateQueueStatus(processingQueueStatus, tvQueueStatus, progressQueue, btnQueueForLater)
                }
            }
        }

        // Setup RecyclerView
        recordingsAdapter = RecordingsAdapter(
            recordings = recordings,
            audioRecorder = audioRecorder!!,
            onPlayClicked = { file -> playRecording(file, playbackControls, tvNowPlaying, tvCurrentTime, tvTotalTime, seekBar, btnPlayPause) },
            onDeleteClicked = { file -> deleteRecording(file, recordingsAdapter!!) },
            onTranscribeClicked = { file ->
                val customPrompt = etPrompt.text.toString().takeIf { it.isNotBlank() }
                transcribeRecording(file, btnTranscribe, customPrompt, rbQueue)
            }
        )
        rvRecordings.layoutManager = LinearLayoutManager(this)
        rvRecordings.adapter = recordingsAdapter

        // Setup playback controls
        btnPlayPause.setOnClickListener {
            audioPlayer?.togglePlayPause()
            updatePlayPauseButton(btnPlayPause)
        }

        btnRewind.setOnClickListener {
            val current = audioPlayer?.currentPosition ?: 0
            audioPlayer?.seekTo(maxOf(0, current - 10000))
        }

        btnForward.setOnClickListener {
            val current = audioPlayer?.currentPosition ?: 0
            val total = audioPlayer?.duration ?: 0
            audioPlayer?.seekTo(minOf(total, current + 10000))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioPlayer?.seekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Delete all button
        btnDeleteAll.visibility = if (recordings.isNotEmpty()) View.VISIBLE else View.GONE
        btnTranscribe.visibility = if (recordings.isNotEmpty()) View.VISIBLE else View.GONE
        btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete All Recordings?")
                .setMessage("This will permanently delete all ${recordings.size} recordings.")
                .setPositiveButton("Delete All") { _, _ ->
                    deleteAllRecordings()
                    recordingsAdapter?.notifyDataSetChanged()
                    recordingsDialog?.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Settings button - show server URL configuration
        btnSettings.setOnClickListener {
            showServerSettingsDialog(tvServerUrl)
        }

        // YouTube button - show YouTube transcription dialog
        btnYouTube.setOnClickListener {
            showYouTubeTranscriptionDialog()
        }

        // Close button
        btnClose.setOnClickListener {
            stopPlayback()
            recordingsDialog?.dismiss()
        }

        // Setup audio player callbacks
        audioPlayer?.onCompletion = {
            runOnUiThread {
                playbackControls.visibility = View.GONE
                recordingsAdapter?.setPlayingFile(null)
            }
        }

        audioPlayer?.onError = { error ->
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                playbackControls.visibility = View.GONE
            }
        }

        // Create and show dialog
        recordingsDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                stopPlayback()
            }
            .create()

        recordingsDialog?.show()
    }

    private fun showRecordingsListForAttachment(
        item: DrawingView.PromptItem,
        onSelected: (File) -> Unit
    ) {
        val recordings = audioRecorder?.getRecordings()?.toMutableList() ?: mutableListOf()

        if (recordings.isEmpty()) {
            Toast.makeText(this, "No recordings yet. Record audio first.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = recordings.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Select Recording")
            .setItems(items) { _, which ->
                onSelected(recordings[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playRecording(
        file: File,
        playbackControls: LinearLayout,
        tvNowPlaying: TextView,
        tvCurrentTime: TextView,
        tvTotalTime: TextView,
        seekBar: SeekBar,
        btnPlayPause: ImageButton
    ) {
        // Stop current playback if any
        stopPlayback()

        // Start new playback
        val success = audioPlayer?.play(file) ?: false
        if (success) {
            recordingsAdapter?.setPlayingFile(file)

            // Show playback controls
            playbackControls.visibility = View.VISIBLE
            tvNowPlaying.text = file.name

            val totalMs = audioPlayer?.duration ?: 0
            tvTotalTime.text = audioPlayer?.formatTime(totalMs) ?: "0:00"
            tvCurrentTime.text = "0:00"
            seekBar.max = totalMs
            seekBar.progress = 0

            updatePlayPauseButton(btnPlayPause)

            // Start progress updates
            startProgressUpdates(tvCurrentTime, seekBar)
        }
    }

    private fun updatePlayPauseButton(btnPlayPause: ImageButton) {
        val isPlaying = audioPlayer?.isPlaying ?: false
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun startProgressUpdates(tvCurrentTime: TextView, seekBar: SeekBar) {
        updateProgressRunnable?.let { recordingHandler.removeCallbacks(it) }

        updateProgressRunnable = object : Runnable {
            override fun run() {
                val player = audioPlayer ?: return

                if (player.isPlaying) {
                    val (current, total) = player.getProgress()
                    tvCurrentTime.text = player.formatTime(current)
                    seekBar.progress = current

                    recordingHandler.postDelayed(this, 200)
                } else {
                    val (current, total) = player.getProgress()
                    tvCurrentTime.text = player.formatTime(current)
                    seekBar.progress = current
                }
            }
        }

        recordingHandler.post(updateProgressRunnable!!)
    }

    private fun stopPlayback() {
        updateProgressRunnable?.let { recordingHandler.removeCallbacks(it) }
        audioPlayer?.stop()
        recordingsAdapter?.setPlayingFile(null)
    }

    private fun deleteRecording(file: File, adapter: RecordingsAdapter) {
        // Stop playback if this file is playing
        if (audioPlayer?.isPlaying == true) {
            val (current, _) = audioPlayer?.getProgress() ?: Pair(0, 0)
            val playerFile = getCurrentPlayingFile()
            if (playerFile == file) {
                stopPlayback()
            }
        }

        // Delete file
        if (audioRecorder?.deleteRecording(file) == true) {
            adapter.removeItem(file)
            Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteAllRecordings() {
        audioPlayer?.stop()
        audioRecorder?.getRecordings()?.forEach { file ->
            audioRecorder?.deleteRecording(file)
        }
    }

    private fun transcribeRecording(file: File, btnTranscribe: Button, customPrompt: String? = null, rbQueue: RadioButton? = null) {
        // Check if user wants to queue instead of transcribe now
        if (rbQueue?.isChecked == true) {
            enqueueForLaterProcessing(file, customPrompt)
            Toast.makeText(this, "Added to processing queue", Toast.LENGTH_SHORT).show()
            return
        }

        // Stop any playback
        stopPlayback()

        // Show loading state
        btnTranscribe.isEnabled = false
        btnTranscribe.text = "Transcribing..."

        activityScope.launch {
            try {
                // Priority: Local model if ready, otherwise server if configured
                val recognizer = HandwritingRecognizer.getInstance(this@MainActivity)
                val useLocal = recognizer.isReady
                
                val result = if (useLocal) {
                    // Use local model for transcription
                    try {
                        val audioData = file.readBytes()
                        val transcript = withContext(Dispatchers.IO) {
                            recognizer.transcribeAudio(audioData, customPrompt)
                        }
                        GemmaServerClient.TranscriptionResult(
                            success = true,
                            transcription = transcript,
                            errorMessage = null
                        )
                    } catch (e: Exception) {
                        GemmaServerClient.TranscriptionResult(
                            success = false,
                            transcription = "",
                            errorMessage = "Local transcription failed: ${e.message}"
                        )
                    }
                } else if (gemmaServer?.isConfigured() == true) {
                    // Fallback to server
                    gemmaServer?.transcribeWithAutoChunking(file, customPrompt)
                } else {
                    // No inference available
                    GemmaServerClient.TranscriptionResult(
                        success = false,
                        transcription = "",
                        errorMessage = "No inference available. Download the local model or configure a server."
                    )
                }

                withContext(Dispatchers.Main) {
                    btnTranscribe.isEnabled = true
                    btnTranscribe.text = "Transcribe"

                    if (result?.success == true && result.transcription.isNotBlank()) {
                        showTranscriptionResult(
                            result.transcription,
                            file.name,
                            result.webSearchResults
                        )
                    } else {
                        val errorMsg = result?.errorMessage ?: "Transcription failed"
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnTranscribe.isEnabled = true
                    btnTranscribe.text = "Transcribe"
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showServerSettingsDialog(tvServerUrl: TextView) {
        val currentUrl = gemmaServer?.getBaseUrl() ?: GemmaServerClient.DEFAULT_BASE_URL

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        val editText = EditText(this).apply {
            setText(currentUrl)
            hint = "http://localhost:8080/transcribe"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setSelection(currentUrl.length)
        }
        container.addView(TextView(this).apply {
            text = "Gemma Server URL"
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        container.addView(editText)

        container.addView(TextView(this).apply {
            text = "\nInference Mode"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 16, 0, 8)
        })

        val swLocalInference = Switch(this).apply {
            text = "Use Local Inference (on-device)"
            isChecked = gemmaTranscriber?.isUsingLocalEngine() ?: false
        }
        container.addView(swLocalInference)

        container.addView(TextView(this).apply {
            text = if (gemmaTranscriber?.isLocalEngineReady() == true) "Local engine: Ready" else "Local engine: Not initialized"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 12f
        })

        scrollView.addView(container)

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Inference Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotBlank()) {
                    gemmaServer?.setServerUrl(url)
                    tvServerUrl.visibility = View.VISIBLE
                    tvServerUrl.text = "Server: $url"
                }
                gemmaTranscriber?.setUseLocalEngine(swLocalInference.isChecked)
                val mode = if (swLocalInference.isChecked) "local" else "server"
                Toast.makeText(this, "Using $mode inference", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTranscriptionResult(
        transcription: String,
        fileName: String,
        webSearchResults: List<GemmaServerClient.SearchResult>? = null
    ) {
        // Create scrollable view for content
        val scrollView = ScrollView(this)

        // Create vertical layout for all content
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // Transcription text
        val textView = TextView(this).apply {
            text = transcription
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        contentLayout.addView(textView)

        // Add web search results section if available
        if (!webSearchResults.isNullOrEmpty()) {
            contentLayout.addView(TextView(this).apply {
                text = "\n--- Web Search Results ---"
                setTextColor(Color.parseColor("#7C4DFF"))
                textSize = 16f
                setPadding(0, 16, 0, 8)
            })

            webSearchResults.forEachIndexed { index, result ->
                val resultButton = Button(this).apply {
                    text = "${index + 1}. ${result.title}"
                    setBackgroundColor(Color.parseColor("#333333"))
                    setTextColor(Color.WHITE)
                    setPadding(12, 8, 12, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                    setOnClickListener {
                        // Open URL in browser
                        openUrlInBrowser(result.url)
                    }
                }
                contentLayout.addView(resultButton)

                // Add snippet
                val snippetView = TextView(this).apply {
                    text = result.snippet.take(150) + if (result.snippet.length > 150) "..." else ""
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    textSize = 12f
                    setPadding(8, 0, 8, 8)
                }
                contentLayout.addView(snippetView)
            }
        }

        scrollView.addView(contentLayout)

        // Build dialog buttons
        val builder = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Transcription: $fileName")
            .setView(scrollView)

        // Add web search button if results available
        if (!webSearchResults.isNullOrEmpty()) {
            builder.setNeutralButton("Capture Results") { _, _ ->
                val captured = WebSearchCapture.captureSearch(
                    context = this,
                    query = "Transcription context",
                    transcriptionContext = transcription.take(500),
                    results = webSearchResults,
                    sourceFile = fileName
                )
                Toast.makeText(this, "Captured ${webSearchResults.size} results", Toast.LENGTH_SHORT).show()
                showCapturedSearchesDialog()
            }
        }

        builder
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Transcription", transcription)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showWebSearchResults(results: List<GemmaServerClient.SearchResult>) {
        val context = this
        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        results.forEach { result ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#222222"))
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }

            val titleView = TextView(context).apply {
                text = result.title
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, 4)
            }
            card.addView(titleView)

            val sourceView = TextView(context).apply {
                text = result.source
                setTextColor(Color.parseColor("#7C4DFF"))
                textSize = 11f
            }
            card.addView(sourceView)

            val snippetView = TextView(context).apply {
                text = result.snippet
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 12f
                setPadding(0, 8, 0, 8)
            }
            card.addView(snippetView)

            val openButton = Button(context).apply {
                text = "Open in Browser"
                setBackgroundColor(Color.parseColor("#7C4DFF"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    openUrlInBrowser(result.url)
                }
            }
            card.addView(openButton)

            layout.addView(card)
        }

        scrollView.addView(layout)

AlertDialog.Builder(context, R.style.DarkDialogTheme)
            .setTitle("Web Search Results")
            .setView(scrollView)
            .setPositiveButton("Open All") { _, _ ->
                results.firstOrNull()?.let { openUrlInBrowser(it.url) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCapturedSearchesDialog() {
        val captures = WebSearchCapture.getCaptures(this)
        if (captures.isEmpty()) {
            Toast.makeText(this, "No captured searches", Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        captures.take(20).forEach { capture ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#222222"))
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }

            card.addView(TextView(this).apply {
                text = capture.query
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            card.addView(TextView(this).apply {
                text = "${capture.results.size} results - ${formatTimestamp(capture.timestamp)}"
                setTextColor(Color.parseColor("#888888"))
                textSize = 11f
            })

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val shareBtn = Button(this).apply {
                text = "Share"
                setBackgroundColor(Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    startActivity(WebSearchCapture.shareAsMarkdown(capture))
                }
            }

            val exportBtn = Button(this).apply {
                text = "Export"
                setBackgroundColor(Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val file = WebSearchCapture.exportToJson(this@MainActivity, capture)
                    Toast.makeText(this@MainActivity, "Saved to ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }

            val deleteBtn = Button(this).apply {
                text = "Delete"
                setBackgroundColor(Color.parseColor("#FF4444"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    WebSearchCapture.deleteCapture(this@MainActivity, capture.id)
                    showCapturedSearchesDialog()
                }
            }

            buttonRow.addView(shareBtn)
            buttonRow.addView(exportBtn)
            buttonRow.addView(deleteBtn)
            card.addView(buttonRow)

            layout.addView(card)
        }

        scrollView.addView(layout)

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Captured Searches (${captures.size})")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear All") { _, _ ->
                WebSearchCapture.clearAllCaptures(this)
                Toast.makeText(this, "Cleared all captures", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    private fun showYouTubeTranscriptionDialog() {
        val editText = EditText(this).apply {
            hint = "Paste YouTube URL here"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 8)
            }
        }

        val progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 16, 0, 0)
            }
        }

        val statusText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 12f
            visibility = View.GONE
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("YouTube Transcription")
            .setView(editText)
            .setPositiveButton("Transcribe") { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotBlank()) {
                    processYouTubeUrl(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processYouTubeUrl(url: String) {
        val videoId = extractYouTubeVideoId(url)
        if (videoId == null) {
            Toast.makeText(this, "Invalid YouTube URL", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Starting YouTube transcription for: $videoId", Toast.LENGTH_LONG).show()

        activityScope.launch {
            try {
                val audioFile = downloadYouTubeAudio(videoId)
                if (audioFile != null) {
                    transcribeAudioFile(audioFile)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to download audio", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("youtube\\.com/watch\\?v=([^&]+)"),
            Regex("youtu\\.be/([^?]+)"),
            Regex("youtube\\.com/embed/([^?]+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private suspend fun downloadYouTubeAudio(videoId: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val outputDir = File(cacheDir, "youtube")
                if (!outputDir.exists()) outputDir.mkdirs()

                val outputFile = File(outputDir, "${videoId}.m4a")

                val audioUrl = fetchYouTubeAudioUrl(videoId)
                if (audioUrl == null) {
                    android.util.Log.e("YouTube", "Could not get audio URL")
                    return@withContext null
                }

                android.util.Log.d("YouTube", "Downloading audio from: ${audioUrl.take(80)}")
                downloadFile(audioUrl, outputFile)
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("YouTube", "Failed: ${e.message}")
                null
            }
        }
    }

    private fun fetchYouTubeAudioUrl(videoId: String): String? {
        val instances = listOf(
            "https://inv.nopsled.me" to "/api/v1/videos/$videoId",
            "https://invidious.fdn.fr" to "/api/v1/videos/$videoId",
            "https://invidious.moomoo.me" to "/api/v1/videos/$videoId",
            "https://invidious.poopy.ml" to "/api/v1/videos/$videoId",
            "https://vid.incogdews.net" to "/api/v1/videos/$videoId"
        )

        for ((base, path) in instances) {
            try {
                val apiUrl = base + path
                android.util.Log.d("YouTube", "Trying: $apiUrl")

                val connection = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 20000
                connection.readTimeout = 25000

                val responseCode = connection.responseCode
                android.util.Log.d("YouTube", "Response: $responseCode")

                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    if (response.trim().startsWith("{")) {
                        val json = org.json.JSONObject(response)

                        if (json.has("adaptiveFormats")) {
                            val adaptiveFormats = json.getJSONArray("adaptiveFormats")
                            for (i in 0 until adaptiveFormats.length()) {
                                val format = adaptiveFormats.getJSONObject(i)
                                val type = format.optString("type", "")
                                val bitrate = format.optInt("bitrate", 0)
                                val url = format.optString("url", "")

                                if (url.isNotEmpty() && type.contains("audio")) {
                                    android.util.Log.d("YouTube", "Found audio: $type @ ${bitrate}kbps")
                                    return url
                                }
                            }
                        }

                        if (json.has("hlsManifestUrl")) {
                            val hlsUrl = json.getString("hlsManifestUrl")
                            android.util.Log.d("YouTube", "Found HLS: $hlsUrl")
                            return hlsUrl
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("YouTube", "Failed $base: ${e.message}")
            }
        }

        android.util.Log.e("YouTube", "All instances failed - try VPN or different network")
        return null
    }

    private fun downloadFile(url: String, outputFile: File): File? {
        try {
            android.util.Log.d("YouTube", "Downloading: ${url.take(80)}")

            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 60000
            connection.readTimeout = 120000

            val inputStream = connection.inputStream
            val outStream = java.io.FileOutputStream(outputFile)
            val buffer = ByteArray(8192)
            var totalBytes = 0L
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }

            outStream.close()
            inputStream.close()

            android.util.Log.d("YouTube", "Downloaded $totalBytes bytes")

if (outputFile.exists() && outputFile.length() > 1024) {
                return outputFile
            } else {
                android.util.Log.e("YouTube", "File too small")
                return null
            }
        } catch (e: Exception) {
            android.util.Log.e("YouTube", "Download error: ${e.message}")
            return null
        }
    }

    private fun transcribeAudioFile(audioFile: File) {
        activityScope.launch {
            try {
                val result = gemmaServer?.transcribe(audioFile)
                    ?: GemmaServerClient.TranscriptionResult(false, "", "No server inference available")

                withContext(Dispatchers.Main) {
                    if (result.success && result.transcription.isNotBlank()) {
                        showTranscriptionResult(
                            result.transcription,
                            audioFile.name,
                            result.webSearchResults
                        )
                    } else {
                        Toast.makeText(this@MainActivity, result.errorMessage ?: "Transcription failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openUrlInBrowser(url: String): Boolean {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open browser", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun getCurrentPlayingFile(): File? {
        // This would need to be tracked - for now return null
        return null
    }

    // ═══════════════════════════════════════════════════════════════════
    // Processing Queue Functions
    // ═══════════════════════════════════════════════════════════════════

    private fun updateQueueStatus(
        queueStatus: LinearLayout,
        tvQueueStatus: TextView,
        progressQueue: ProgressBar,
        btnQueueForLater: Button
    ) {
        val stats = processingQueue?.getStats()
        if (stats == null || stats.totalQueued == 0 && stats.inProgress == 0) {
            queueStatus.visibility = View.GONE
            return
        }

        queueStatus.visibility = View.VISIBLE

        when {
            stats.inProgress > 0 -> {
                progressQueue.visibility = View.VISIBLE
                tvQueueStatus.text = "Processing ${stats.inProgress} item(s)..."
            }
            stats.totalQueued > 0 -> {
                progressQueue.visibility = View.GONE
                tvQueueStatus.text = "${stats.totalQueued} queued for processing"
            }
            stats.completed > 0 -> {
                progressQueue.visibility = View.GONE
                tvQueueStatus.text = "${stats.completed} completed"
            }
        }

        btnQueueForLater.setOnClickListener {
            // Toggle queue for later processing
            Toast.makeText(this, "Add to queue", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueForLaterProcessing(file: File, customPrompt: String?) {
        processingQueue?.enqueue(file, customPrompt, ProcessingQueueManager.PRIORITY_NORMAL)
        Toast.makeText(this, "Added to processing queue", Toast.LENGTH_SHORT).show()
    }

    private fun setupModelStatus(
        modelStatusArea: LinearLayout,
        tvModelStatus: TextView,
        modelProgressBar: ProgressBar,
        btnDownloadModel: Button
    ) {
        val recognizer = HandwritingRecognizer.getInstance(this)

        fun updateStatus() {
            when {
                recognizer.isReady -> {
                    tvModelStatus.text = "Ready"
                    tvModelStatus.setTextColor(Color.parseColor("#64FFDA"))
                    btnDownloadModel.visibility = View.GONE
                    modelProgressBar.visibility = View.GONE
                }
                recognizer.errorMessageFlow.value != null -> {
                    tvModelStatus.text = "Error: ${recognizer.errorMessageFlow.value}"
                    tvModelStatus.setTextColor(Color.parseColor("#FF5252"))
                    btnDownloadModel.visibility = View.VISIBLE
                    btnDownloadModel.text = "Retry Download"
                }
                else -> {
                    tvModelStatus.text = "Not downloaded"
                    tvModelStatus.setTextColor(Color.parseColor("#88FFFFFF"))
                    btnDownloadModel.visibility = View.VISIBLE
                    btnDownloadModel.text = "Download Model (~1.5 GB)"
                    modelProgressBar.visibility = View.GONE
                }
            }
        }

        updateStatus()

        recognizer.isReadyFlow.let { flow ->
            activityScope.launch {
                flow.collect {
                    runOnUiThread { updateStatus() }
                }
            }
        }

        recognizer.errorMessageFlow.let { flow ->
            activityScope.launch {
                flow.collect {
                    if (it != null) {
                        runOnUiThread { updateStatus() }
                    }
                }
            }
        }

        btnDownloadModel.setOnClickListener {
            showModelDownloadDialog()
        }
    }

    private fun showModelDownloadDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_download, null)
        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val etHfToken: EditText = dialogView.findViewById(R.id.etHfToken)
        val etHfToken2: EditText? = null
        val btnStartDownload: Button = dialogView.findViewById(R.id.btnStartDownload)
        val btnSkipDownload: Button = dialogView.findViewById(R.id.btnSkipDownload)
        val btnAcceptLicense: TextView = dialogView.findViewById(R.id.btnAcceptLicense)
        val btnGetToken: TextView = dialogView.findViewById(R.id.btnGetToken)
        val dlProgressArea: LinearLayout = dialogView.findViewById(R.id.dlProgressArea)
        val dlReadyArea: LinearLayout = dialogView.findViewById(R.id.dlReadyArea)
        val dlProgressBar: ProgressBar = dialogView.findViewById(R.id.dlProgressBar)
        val dlProgressLabel: TextView = dialogView.findViewById(R.id.dlProgressLabel)
        val dlProgressPercent: TextView = dialogView.findViewById(R.id.dlProgressPercent)
        val dlErrorText: TextView = dialogView.findViewById(R.id.dlErrorText)
        val dlSubtitle: TextView = dialogView.findViewById(R.id.dlSubtitle)

        etHfToken.setText(ModelManager.savedHfToken(this))

        btnAcceptLicense.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://ai.google.dev/gemma/terms"))
            startActivity(intent)
        }

        btnGetToken.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://huggingface.co/settings/tokens"))
            startActivity(intent)
        }

        var pendingDownloadId: Long = -1L

        btnStartDownload.setOnClickListener {
            val token = etHfToken.text.toString().trim()
            if (token.isEmpty()) {
                dlErrorText.text = "Please enter your HuggingFace token"
                dlErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            dlErrorText.visibility = View.GONE
            dlReadyArea.visibility = View.GONE
            dlProgressArea.visibility = View.VISIBLE
            dlSubtitle.text = "Downloading Gemma model..."

            ModelManager.startDownloadHFAsync(this, token,
                onSuccess = { downloadId ->
                    pendingDownloadId = downloadId
                    dlProgressLabel.text = "Starting download..."
                },
                onError = { error ->
                    dlErrorText.text = error
                    dlErrorText.visibility = View.VISIBLE
                    dlReadyArea.visibility = View.VISIBLE
                    dlProgressArea.visibility = View.GONE
                }
            )
        }

        btnSkipDownload.setOnClickListener {
            val intent = android.content.Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, "Select Gemma model file")
            }
            modelFilePickerLauncher.launch(intent)
        }

        // Poll download progress
        val progressHandler = android.os.Handler(Looper.getMainLooper())
        val progressRunnable = object : Runnable {
            override fun run() {
                if (pendingDownloadId > 0) {
                    val status = ModelManager.queryDownload(this@MainActivity, pendingDownloadId)
                    when (status.state) {
                        ModelManager.DownloadState.RUNNING, ModelManager.DownloadState.PAUSED -> {
                            dlProgressBar.visibility = View.VISIBLE
                            dlProgressBar.progress = status.progressPercent
                            dlProgressLabel.text = status.progressDisplay
                            dlProgressPercent.text = "${status.progressPercent}%"
                            progressHandler.postDelayed(this, 500)
                        }
                        ModelManager.DownloadState.DONE -> {
                            dlProgressBar.visibility = View.GONE
                            dlProgressLabel.text = "Download complete!"
                            dlProgressPercent.text = "100%"
                            val modelPath = ModelManager.modelFile(this@MainActivity).absolutePath
                            loadModelAndClose(modelPath, dialog)
                        }
                        ModelManager.DownloadState.FAILED -> {
                            dlErrorText.text = "Download failed (reason: ${status.reason}). Check storage space."
                            dlErrorText.visibility = View.VISIBLE
                            dlReadyArea.visibility = View.VISIBLE
                            dlProgressArea.visibility = View.GONE
                        }
                        else -> {}
                    }
                } else {
                    progressHandler.postDelayed(this, 500)
                }
            }
        }
        progressHandler.post(progressRunnable)

        dialog.setOnDismissListener {
            progressHandler.removeCallbacks(progressRunnable)
        }

        dialog.show()
    }

    private val modelFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val inputStream = contentResolver.openInputStream(uri)
                val modelFile = ModelManager.modelFile(this)
                inputStream?.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                loadModelAndClose(modelFile.absolutePath, null)
            }
        }
    }

    private fun loadModelAndClose(modelPath: String, dialog: AlertDialog?) {
        Toast.makeText(this, "Loading model...", Toast.LENGTH_SHORT).show()
        val recognizer = HandwritingRecognizer.getInstance(this)
        recognizer.load(modelPath, HandwritingRecognizer.InferenceConfig(),
            onReady = {
                runOnUiThread {
                    Toast.makeText(this, "Model ready!", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Model load failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showQueueDialog() {
        val ctx = this
        val queueItems = processingQueue?.queueItemsFlow?.value ?: emptyList()

        val scrollView = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        if (queueItems.isEmpty()) {
            val emptyText = TextView(ctx).apply {
                text = "Queue is empty"
                setTextColor(Color.parseColor("#88FFFFFF"))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
            }
            layout.addView(emptyText)
        } else {
            queueItems.forEach { item ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#333333"))
                    setPadding(12, 12, 12, 12)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 8)
                    }
                }

                val titleView = TextView(ctx).apply {
                    text = item.audioFile.name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
                card.addView(titleView)

                val statusView = TextView(ctx).apply {
                    text = "Status: ${item.status.name} (${(item.progress * 100).toInt()}%)"
                    setTextColor(Color.parseColor("#88FFFFFF"))
                    textSize = 12f
                }
                card.addView(statusView)

                // Progress bar
                val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    progress = (item.progress * 100).toInt()
                    max = 100
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 0)
                    }
                }
                card.addView(progressBar)

                // Result if completed
                item.result?.let { result ->
                    val resultView = TextView(ctx).apply {
                        text = result.take(100) + if (result.length > 100) "..." else ""
                        setTextColor(Color.parseColor("#7C4DFF"))
                        textSize = 12f
                        setPadding(0, 8, 0, 0)
                    }
                    card.addView(resultView)
                }

                // Action buttons
                val buttonLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                if (item.status == ProcessingQueueManager.ProcessingStatus.QUEUED) {
                    val boostBtn = Button(ctx).apply {
                        text = "Process Now"
                        setOnClickListener {
                            processingQueue?.boostPriority(item.id)
                            Toast.makeText(ctx, "Moved to front of queue", Toast.LENGTH_SHORT).show()
                        }
                    }
                    buttonLayout.addView(boostBtn)
                }

                if (item.status == ProcessingQueueManager.ProcessingStatus.FAILED) {
                    val retryBtn = Button(ctx).apply {
                        text = "Retry"
                        setOnClickListener {
                            processingQueue?.retry(item.id)
                        }
                    }
                    buttonLayout.addView(retryBtn)
                }

                val removeBtn = Button(ctx).apply {
                    text = "Remove"
                    setOnClickListener {
                        processingQueue?.remove(item.id)
                    }
                }
                buttonLayout.addView(removeBtn)

                card.addView(buttonLayout)
                layout.addView(card)
            }
        }

        scrollView.addView(layout)

        AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Processing Queue (${queueItems.size})")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear Completed") { _, _ ->
                processingQueue?.clearCompleted()
            }
            .show()
    }
}
