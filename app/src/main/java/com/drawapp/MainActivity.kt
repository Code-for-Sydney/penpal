package com.drawapp

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

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
    private lateinit var colorSwatch: View
    private lateinit var recognitionProgress: ProgressBar
    private lateinit var recognitionIcon: TextView
    private lateinit var recognitionText: TextView

    // ── State ──────────────────────────────────────────────────────────────
    private var activeColor: Int = Color.WHITE
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

    // ── Download tracking ──────────────────────────────────────────────────
    private var downloadId: Long = -1L
    private var downloadDialog: Dialog? = null
    private val downloadPollHandler = Handler(Looper.getMainLooper())
    private lateinit var downloadPollRunnable: Runnable

    /** Receives MODEL_READY broadcast when DownloadManager completes. */
    private val modelReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ModelDownloadReceiver.ACTION_MODEL_READY) {
                val path = intent.getStringExtra(ModelDownloadReceiver.EXTRA_MODEL_PATH) ?: return
                downloadPollHandler.removeCallbacks(downloadPollRunnable)
                downloadDialog?.dismiss()
                Toast.makeText(this@MainActivity, "Model downloaded!", Toast.LENGTH_SHORT).show()
                loadModel(path)
            }
        }
    }

    // ── Misc ───────────────────────────────────────────────────────────────
    private val STORAGE_PERMISSION_CODE = 101
    private val colors = listOf(
        "#FFFFFF", "#000000", "#FF4081", "#F44336", "#FF9800",
        "#FFEB3B", "#4CAF50", "#00BCD4", "#2196F3", "#9C27B0",
        "#795548", "#607D8B", "#E91E63", "#8BC34A", "#FF5722",
        "#00E5FF", "#76FF03", "#EA80FC", "#FF6D00", "#18FFFF"
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

        // Set notebook title from intent
        notebookId = intent.getStringExtra("NOTEBOOK_ID") ?: ""
        notebookName = intent.getStringExtra("NOTEBOOK_NAME") ?: "My Notebook"
        findViewById<TextView>(R.id.tvNotebookTitle).text = notebookName

        setupListeners()
        updateColorSwatch()
        setupRecognizer()
        loadNotebookDrawing()

        // Register for MODEL_READY broadcast
        val filter = IntentFilter(ModelDownloadReceiver.ACTION_MODEL_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(modelReadyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(modelReadyReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognitionHandler.removeCallbacks(recognitionRunnable)
        recognitionHandler.removeCallbacks(autosaveRunnable)
        if (::downloadPollRunnable.isInitialized) {
            downloadPollHandler.removeCallbacks(downloadPollRunnable)
        }
        unregisterReceiver(modelReadyReceiver)
        recognizer.close()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recognizer / model setup
    // ══════════════════════════════════════════════════════════════════════

    private fun setupRecognizer() {
        recognizer = HandwritingRecognizer(this)

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

        // 1. Check if model already exists anywhere on device
        val existing = ModelManager.findExistingModel(this)
        if (existing != null) {
            loadModel(existing)
            return
        }

        // 2. Check if a download is already in progress from a previous session
        val savedId = ModelManager.savedDownloadId(this)
        if (savedId != -1L) {
            val status = ModelManager.queryDownload(this, savedId)
            when (status.state) {
                ModelManager.DownloadState.RUNNING,
                ModelManager.DownloadState.PAUSED -> {
                    // Resume showing progress for existing download
                    downloadId = savedId
                    showDownloadDialog(alreadyDownloading = true)
                    return
                }
                ModelManager.DownloadState.DONE -> {
                    val path = ModelManager.modelFile(this).absolutePath
                    if (File(path).exists()) {
                        ModelManager.saveModelPath(this, path)
                        loadModel(path)
                        return
                    }
                }
                else -> { /* fall through to show dialog */ }
            }
        }

        // 3. Nothing found — show download dialog automatically
        showDownloadDialog(alreadyDownloading = false)
    }

    private fun loadModel(path: String) {
        setRecognitionState(RecognitionState.LOADING)
        recognizer.load(
            modelPath = path,
            onReady = {
                setRecognitionState(RecognitionState.IDLE)
                Toast.makeText(this, "Gemma ready ✓", Toast.LENGTH_SHORT).show()
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
    // Download dialog
    // ══════════════════════════════════════════════════════════════════════

    private fun showDownloadDialog(alreadyDownloading: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_model_download, null)

        val progressArea  = dialogView.findViewById<View>(R.id.dlProgressArea)
        val readyArea     = dialogView.findViewById<View>(R.id.dlReadyArea)
        val progressBar   = dialogView.findViewById<ProgressBar>(R.id.dlProgressBar)
        val progressLabel = dialogView.findViewById<TextView>(R.id.dlProgressLabel)
        val progressPct   = dialogView.findViewById<TextView>(R.id.dlProgressPercent)
        val errorText     = dialogView.findViewById<TextView>(R.id.dlErrorText)
        val btnStart      = dialogView.findViewById<Button>(R.id.btnStartDownload)
        val btnSkip       = dialogView.findViewById<Button>(R.id.btnSkipDownload)
        val etToken       = dialogView.findViewById<android.widget.EditText>(R.id.etHfToken)
        val btnAcceptLic  = dialogView.findViewById<TextView>(R.id.btnAcceptLicense)
        val btnGetToken   = dialogView.findViewById<TextView>(R.id.btnGetToken)

        // Web links
        btnAcceptLic.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/google/gemma-4-E2B-it"))
            startActivity(intent)
        }
        btnGetToken.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/settings/tokens"))
            startActivity(intent)
        }

        // Pre-fill any previously saved token
        val savedToken = ModelManager.savedHfToken(this)
        if (savedToken.isNotBlank()) etToken.setText(savedToken)

        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        downloadDialog = dialog

        // ── Polling runnable ────────────────────────────────────────────
        downloadPollRunnable = Runnable {
            if (downloadId == -1L) return@Runnable
            val status = ModelManager.queryDownload(this, downloadId)

            when (status.state) {
                ModelManager.DownloadState.RUNNING,
                ModelManager.DownloadState.PAUSED -> {
                    progressBar.progress = status.progressPercent
                    progressLabel.text   = status.progressDisplay
                    progressPct.text     = "${status.progressPercent}%"
                    // Poll again in 1s
                    downloadPollHandler.postDelayed(downloadPollRunnable, 1000)
                }
                ModelManager.DownloadState.DONE -> {
                    val path = ModelManager.modelFile(this).absolutePath
                    if (File(path).exists()) {
                        ModelManager.saveModelPath(this, path)
                        dialog.dismiss()
                        loadModel(path)
                    }
                }
                ModelManager.DownloadState.FAILED -> {
                    progressArea.visibility = View.GONE
                    readyArea.visibility    = View.VISIBLE
                    errorText.visibility    = View.VISIBLE
                    errorText.text          =
                        "Download failed (reason ${status.reason}). Check your internet connection and try again."
                }
                else -> {}
            }
        }

        fun showProgress() {
            readyArea.visibility    = View.GONE
            progressArea.visibility = View.VISIBLE
            progressBar.progress    = 0
            progressLabel.text      = "Connecting…"
            progressPct.text        = "0%"
            downloadPollHandler.postDelayed(downloadPollRunnable, 1000)
        }

        // ── If already downloading, jump straight to progress view ──────
        if (alreadyDownloading) {
            showProgress()
        } else {
            readyArea.visibility    = View.VISIBLE
            progressArea.visibility = View.GONE
        }

        // ── Buttons ─────────────────────────────────────────────────────
        btnStart.setOnClickListener {
            val token = etToken.text.toString().trim()
            if (token.isBlank()) {
                errorText.visibility = View.VISIBLE
                errorText.text = "Please enter your HuggingFace access token first."
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            btnStart.isEnabled = false
            btnStart.text = "Connecting..."
            
            ModelManager.startDownloadHFAsync(
                context = this,
                hfToken = token,
                onSuccess = { id ->
                    downloadId = id
                    btnStart.isEnabled = true
                    btnStart.text = "⬇  Download Model"
                    showProgress()
                },
                onError = { msg ->
                    btnStart.isEnabled = true
                    btnStart.text = "⬇  Download Model"
                    errorText.visibility = View.VISIBLE
                    errorText.text = msg
                }
            )
        }

        btnSkip.setOnClickListener {
            dialog.dismiss()
            showManualPathDialog()
        }

        dialog.show()
        recognitionText.text = "Waiting for Gemma model…"
        recognitionText.setTextColor(Color.parseColor("#88FFFFFF"))
    }

    /** Fallback: user points to an existing .task file path. */
    private fun showManualPathDialog() {
        val input = EditText(this).apply {
            hint = "/sdcard/Download/gemma-4-E2B-it.litertlm"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Model File Path")
            .setMessage("Enter the full path to a Gemma .litertlm file already on this device:")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotBlank() && File(path).exists()) {
                    ModelManager.saveModelPath(this, path)
                    loadModel(path)
                } else {
                    Toast.makeText(this, "File not found at that path", Toast.LENGTH_LONG).show()
                    showDownloadDialog(alreadyDownloading = false)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showDownloadDialog(alreadyDownloading = false)
            }
            .show()
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
        }
        btnRedo.setOnClickListener {
            drawingView.redo(); updateButtonStates()
            scheduleRecognition()
        }
        btnClear.setOnClickListener { showClearConfirmDialog() }
        btnEraser.setOnClickListener {
            isEraserActive = !isEraserActive
            drawingView.isEraser = isEraserActive
            updateEraserButton()
        }
        btnBrushSize.setOnClickListener   { showBrushSizeDialog() }
        btnColorPicker.setOnClickListener { showColorPickerDialog() }

        // Long-press ✦ to re-trigger model setup
        recognitionIcon.setOnLongClickListener {
            ModelManager.clearModelPath(this)
            recognizer.close()
            recognizer = HandwritingRecognizer(this)
            showDownloadDialog(alreadyDownloading = false)
            true
        }
    }

    private fun scheduleRecognition() {
        if (recognizer.isReady && !isRecognizing) {
            recognitionHandler.removeCallbacks(recognitionRunnable)
            recognitionHandler.postDelayed(recognitionRunnable, DEBOUNCE_MS)
        }
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
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Save drawing ──────────────────────────────────────────────────────

    private fun loadNotebookDrawing() {
        val fileName = "${notebookName}.png"
        val relativePath = Environment.DIRECTORY_PICTURES + "/Penpal"
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                // Try both with and without trailing slash
                val paths = arrayOf(relativePath + "/", relativePath)
                var found = false
                
                for (path in paths) {
                    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                    val selectionArgs = arrayOf(fileName, path)
                    
                    contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                            contentResolver.openInputStream(uri)?.use { input ->
                                val bitmap = BitmapFactory.decodeStream(input)
                                if (bitmap != null) {
                                    drawingView.initializeWithBitmap(bitmap)
                                    Toast.makeText(this, "Loaded \"$notebookName\"", Toast.LENGTH_SHORT).show()
                                    found = true
                                }
                            }
                        }
                    }
                    if (found) break
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Penpal")
                val file = File(dir, fileName)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        drawingView.initializeWithBitmap(bitmap)
                        Toast.makeText(this, "Loaded \"$notebookName\"", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun performAutosave() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val bitmap = drawingView.getBitmap() ?: return
        val fileName = "${notebookName}.png"
        val relativePath = Environment.DIRECTORY_PICTURES + "/Penpal"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(fileName, relativePath + "/")
                
                var uri: Uri? = null
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    }
                }

                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath + "/")
                    }
                    uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                }

                uri?.let {
                    contentResolver.openOutputStream(it, "wt")?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Penpal")
                dir.mkdirs()
                val file = File(dir, fileName)
                file.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
