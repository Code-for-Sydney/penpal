package com.drawapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
    private lateinit var colorSwatch: View
    private lateinit var recognitionProgress: ProgressBar
    private lateinit var recognitionIcon: TextView
    private lateinit var recognitionText: TextView

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
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Misc ───────────────────────────────────────────────────────────────
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

        // Set notebook title from intent
        notebookId = intent.getStringExtra("NOTEBOOK_ID") ?: ""
        notebookName = intent.getStringExtra("NOTEBOOK_NAME") ?: "My Notebook"
        findViewById<TextView>(R.id.tvNotebookTitle).text = notebookName

        setupListeners()
        drawingView.brushColor = activeColor
        updateColorSwatch()
        setupRecognizer()
        loadNotebookDrawing()
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
