# Development Guide

This guide provides instructions for setting up a development environment and understanding the codebase for contributing to Penpal.

## AI Inference Setup

Penpal v2.x uses **Google Gemma 4 E2B-IT** as the primary inference model via **ML Kit GenAI API**. This section covers setup and configuration.

### ML Kit GenAI API Setup

The inference layer follows the **AI Edge Gallery pattern** for ML Kit GenAI integration:

#### 1. Add Dependencies

```kotlin
// In core:ai/build.gradle.kts
dependencies {
    // ML Kit GenAI (LiteRT-based inference)
    implementation("com.google.ai.edge.litert:genai-android:0.1.0")
    implementation("com.google.ai.edge.litert:api:0.1.0")
    
    // Google Play Services (required for model download)
    implementation("com.google.android.gms:play-services-base:18.3.0")
}
```

#### 2. Configure API Key (Optional)

ML Kit GenAI supports two modes:
- **Local inference only**: No API key required (limited model selection)
- **With API key**: Access to latest models, quota management

```kotlin
// Create InferenceConfig
val config = InferenceConfig(
    modelName = "gemma-4-e2b-it",  // Model identifier
    apiKey = null,  // Optional: null for local-only inference
    maxTokens = 1024,
    temperature = 0.7f,
)
```

#### 3. Initialize InferenceBridge

```kotlin
// In Application or ViewModel
val inferenceBridge: InferenceBridge = LiteRtInferenceBridge()

lifecycleScope.launch {
    val success = inferenceBridge.initialize(context, config)
    if (success) {
        Log.d("Penpal", "Inference ready")
    }
}
```

### Gemma 4 E2B-IT Model Configuration

| Property | Value |
|----------|-------|
| **Model ID** | `gemma-4-e2b-it` |
| **Name** | Gemma 4 Efficient 2B Instruction-Tuned |
| **Size** | ~2.6 GB |
| **Parameters** | 2B |
| **Context Window** | 8K tokens |
| **Use Case** | Instruction following, RAG, text generation |

#### GenerationConfig

```kotlin
data class GenerationConfig(
    val maxTokens: Int = 1024,        // Max output tokens
    val temperature: Float = 0.7f,    // Creativity (0 = deterministic)
    val topP: Float = 0.9f,           // Nucleus sampling
    val topK: Int = 40,               // Top-k sampling
    val stopSequences: List<String> = emptyList()
)
```

#### InferenceConfig

```kotlin
data class InferenceConfig(
    val modelName: String = "gemma-4-e2b-it",
    val apiKey: String? = null,       // Optional API key
    val maxConcurrentRequests: Int = 2,
    val cacheDir: File? = context.cacheDir
)
```

### Model Download Flow

The app supports downloading models on-demand:

```
1. User opens Inference tab
           │
           ▼
2. Check modelInfoFlow.state.isDownloaded
           │
    ┌──────┴──────┐
    │             │
    ▼             ▼
Downloaded    Not Downloaded
    │             │
    ▼             ▼
Initialize    Show "Download Model" button
           │
           ▼
User taps "Download"
           │
           ▼
inferenceBridge.downloadModel(modelId)
           │
           ▼
Observe downloadProgressFlow:
  - bytesDownloaded / totalBytes
  - status: DOWNLOADING → COMPLETED
           │
           ▼
On complete: Initialize and use model
```

#### ModelDownloadHelper

```kotlin
class ModelDownloadHelper(private val context: Context) {
    
    fun downloadModel(
        modelId: String,
        onProgress: (DownloadProgress) -> Unit
    ): Task<Void> {
        // Use DownloadManager or custom download logic
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Downloading Gemma 4")
            .setDescription("AI model (~2.6 GB)")
        
        return downloadManager.enqueue(request)
    }
}

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: DownloadStatus
)
```

### Inference Testing Guidelines

#### Unit Testing InferenceBridge

```kotlin
@Test
fun `generate returns response from Gemma`() = runTest {
    // Given
    val bridge = LiteRtInferenceBridge()
    bridge.initialize(context, testConfig)
    
    // When
    val response = bridge.generate("What is 2+2?", GenerationConfig())
    
    // Then
    assertTrue(response.isNotBlank())
    assertFalse(bridge.isProcessingFlow.value)
}

@Test
fun `streamGenerate emits tokens incrementally`() = runTest {
    // Given
    val bridge = LiteRtInferenceBridge()
    bridge.initialize(context, testConfig)
    
    // When
    val tokens = mutableListOf<String>()
    bridge.streamGenerate("Count to 3", GenerationConfig())
        .collect { tokens.add(it) }
    
    // Then
    assertTrue(tokens.isNotEmpty())
    assertTrue(tokens.joinToString("").isNotBlank())
}

@Test
fun `downloadModel emits progress updates`() = runTest {
    // Given
    val bridge = LiteRtInferenceBridge()
    
    // When
    val progressUpdates = mutableListOf<DownloadProgress>()
    bridge.downloadModel("gemma-4-e2b-it")
        .collect { progressUpdates.add(it) }
    
    // Then
    assertTrue(progressUpdates.isNotEmpty())
    val final = progressUpdates.last()
    assertEquals(DownloadStatus.COMPLETED, final.status)
}
```

#### Integration Testing RAG Flow

```kotlin
@Test
fun `RAG flow retrieves context and generates response`() = runTest {
    // Given
    val vectorStore = VectorStoreRepository(database, MiniLmEmbedder())
    val bridge = LiteRtInferenceBridge()
    
    // Embed test chunks
    val chunks = listOf(
        RawChunk("1", "source", "Kotlin is a programming language", 0),
        RawChunk("2", "source", "Android is a mobile OS", 1),
    )
    vectorStore.embed(chunks)
    
    bridge.initialize(context, testConfig)
    
    // When
    val context = vectorStore.similaritySearch("Tell me about programming", 2)
    val prompt = buildPrompt("Tell me about programming", context)
    val response = bridge.generate(prompt, GenerationConfig())
    
    // Then
    assertTrue(response.contains("Kotlin") || response.contains("programming"))
}
```

#### Mocking for Tests

```kotlin
@Test
fun `chat shows streaming response`() = runTest {
    // Given
    val mockBridge = mock<InferenceBridge> {
        on { isReadyFlow } doReturn MutableStateFlow(true)
        on { streamGenerate(anyString(), any()) } doReturn flow {
            emit("Th")
            emit("ank")
            emit(" you")
        }
    }
    
    val viewModel = ChatViewModel(
        inferenceBridge = mockBridge,
        vectorStore = mockVectorStore
    )
    
    // When
    viewModel.sendMessage("Thanks")
    delay(100)
    
    // Then
    assertTrue(viewModel.uiState.value.messages.any { 
        it.content.contains("Thank") 
    })
}
```

### Testing Model Initialization

```kotlin
@Test
fun `inference ready after initialization`() = runTest {
    // Given
    val bridge = LiteRtInferenceBridge()
    val readyFlow = bridge.isReadyFlow
    
    // When
    launch { bridge.initialize(context, config) }
    
    // Then
    assertTrue(readyFlow.first { it })
}
```

---

## Environment Setup

### Required Tools

1. **Android Studio Hedgehog (2024.1.1)** or later
2. **Android SDK 34**
3. **JDK 17** or later
4. **Git**

### Initial Setup

```bash
# Clone repository
git clone https://github.com/your-username/penpal.git
cd penpal

# Open in Android Studio
# File → Open → Select penpal directory

# Sync Gradle
# File → Sync Project with Gradle Files

# Build
# Build → Make Project (Ctrl+F9)
```

### Running on Device/Emulator

1. Connect device or start emulator
2. Select device from run configuration dropdown
3. Click Run (Shift+F10)

## Project Structure

```
penpal/
├── app/
│   └── src/
│       └── main/
│           ├── java/com/drawapp/
│           │   ├── MainActivity.kt           # Main drawing screen
│           │   ├── DrawingView.kt            # Canvas custom view
│           │   ├── NotebookSelectionActivity.kt # Home screen
│           │   ├── NotebookManager.kt        # Notebook persistence
│           │   ├── Notebook.kt               # Notebook data model
│           │   ├── HandwritingRecognizer.kt   # Gemma AI wrapper (local)
│           │   ├── GemmaServerClient.kt     # Remote Gemma server client
│           │   ├── GemmaTranscriber.kt      # Transcription via remote Gemma
│           │   ├── InferenceService.kt      # Background inference service
│           │   ├── InferenceEngineManager.kt # Multi-engine inference manager
│           │   ├── LlmInferenceEngine.kt    # Local inference engine
│           │   ├── ProcessingQueueManager.kt  # Batch processing queue
│           │   ├── AudioRecorder.kt          # Audio recording with amplitude
│           │   ├── AudioPlayer.kt            # Audio playback with seek
│           │   ├── AudioChunker.kt           # Audio chunking for streaming
│           │   ├── RecordingsAdapter.kt      # Audio recordings list adapter
│           │   ├── ModelManager.kt            # Model download management
│           │   ├── ModelDownloadHelper.kt     # Download UI helpers
│           │   ├── ModelDownloadReceiver.kt  # Download broadcast receiver
│           │   ├── SvgSerializer.kt           # SVG persistence
│           │   ├── PdfHelper.kt               # PDF text extraction
│           │   ├── PdfSelectionActivity.kt    # PDF region cropping
│           │   ├── PdfImportActivity.kt       # PDF page selection
│           │   ├── SelectionFrameView.kt      # Crop selection view
│           │   ├── NotebookAdapter.kt         # RecyclerView adapter
│           │   ├── PenpalApplication.kt       # Application class
│           │   └── TestReflection.kt          # Testing utilities
│           │
│           └── res/
│               ├── layout/                    # Activity and dialog layouts
│               ├── drawable/                   # Icons and shapes
│               ├── values/                     # Strings, colors, themes
│               └── mipmap/                     # App icons
│
├── docs/                              # Documentation files
├── build.gradle                     # Root build config
├── settings.gradle                  # Project settings
├── gradle.properties               # Gradle configuration
└── gradle/
    └── wrapper/                     # Gradle wrapper files
```

## Code Style Guidelines

### Kotlin Conventions

1. **Naming**
   - Classes: PascalCase (e.g., `DrawingView`)
   - Functions: camelCase (e.g., `performAutosave`)
   - Properties: camelCase (e.g., `activeColor`)
   - Constants: SCREAMING_SNAKE_CASE (e.g., `PAGE_WIDTH`)

2. **Visibility**
   - Use `private` for internal implementation details
   - Use `internal` for module-internal APIs
   - Use `public` only for intended public APIs

3. **Null Safety**
   - Prefer `?.` and `?:` operators over null checks
   - Use `lateinit` for views initialized in `onCreate`
   - Use `nullable` types for optional return values

### View Handling

```kotlin
// DO: Use lateinit for views
private lateinit var drawingView: DrawingView

// DO: Initialize in onCreate
drawingView = findViewById(R.id.drawingView)

// DON'T: Make views nullable unless necessary
// private var drawingView: DrawingView? = null
```

### Coroutine Usage

```kotlin
// DO: Use CoroutineScope for structured concurrency
private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

// DO: Clean up in onDestroy
override fun onDestroy() {
    activityScope.cancel()
}

// DON'T: Use GlobalScope
// activityScope.launch vs GlobalScope.launch
```

### Custom Views

```kotlin
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Properties
    var activeTool: ActiveTool = ActiveTool.SELECT
    
    // Data classes as nested types
    sealed class CanvasItem { ... }
    data class StrokeItem(...) : CanvasItem() { ... }
    
    // Undo actions as inner classes
    inner class AddItemAction(...) : UndoAction { ... }
}
```

## Testing Strategy

### Manual Testing Checklist

1. **Drawing**
   - [ ] Brush draws smooth strokes
   - [ ] Eraser removes strokes
   - [ ] Undo/Redo work correctly
   - [ ] Clear clears all items

2. **Selection**
   - [ ] Lasso selects multiple items
   - [ ] Selection handles work
   - [ ] Group transformation works
   - [ ] Delete removes selected items

3. **AI Recognition**
   - [ ] Model loads on startup
   - [ ] Strokes trigger recognition
   - [ ] Text overlay displays
   - [ ] Toggle text/stroke view

4. **Pages**
   - [ ] New pages created on scroll
   - [ ] Pages persist on restart
   - [ ] Delete page works
   - [ ] Overview shows all pages

5. **PDF**
   - [ ] Import creates new notebook
   - [ ] Page selection works
   - [ ] Snippet insertion works
   - [ ] Text extraction works

6. **Export**
   - [ ] PDF export works
   - [ ] SVG export works
   - [ ] PNG export works

## Debugging

### Enable Touch Area Visualization

In `MainActivity`, tap the options menu (⋮) and enable "Touch Areas" to visualize:
- Blue circles: Selection handle hit areas
- Green areas: Item hit boxes
- Red areas: Touch detection zones

### Log Messages

The app uses standard Android logging:
```kotlin
android.util.Log.d("Penpal", "Message")
android.util.Log.e("Penpal", "Error: $e")
```

View logs in Android Studio:
```
View → Tool Windows → Logcat
Filter: "Penpal"
```

### Common Issues

**Recognition not working**
1. Check model file exists: `ModelManager.modelFile(context).exists()`
2. Verify `isReady` state in logs
3. Check network permissions in manifest

**Canvas not rendering**
1. Check `onSizeChanged()` is called
2. Verify `updateMatrix()` runs
3. Check `invalidate()` is called after state changes

**Pages not saving**
1. Check file permissions
2. Verify `autosaveRunnable` triggers
3. Check SVG serialization completes

## Adding Features

### Adding a New Canvas Item Type

1. **Define Data Class**
```kotlin
data class ShapeItem(
    val shapeType: ShapeType,
    val matrix: Matrix,
    var color: Int,
    var strokeWidth: Float
) : CanvasItem() {
    override val bounds: RectF ...
    override fun toSvgData(): SvgData ...
}
```

2. **Add Touch Handling**
```kotlin
// In onTouchEvent ACTION_DOWN:
if (activeTool == ActiveTool.SHAPE) {
    isCreatingShape = true
    startShape(event)
}

// In onTouchEvent ACTION_MOVE:
if (isCreatingShape) {
    updateShape(event)
}

// In onTouchEvent ACTION_UP:
if (isCreatingShape) {
    finishShape(event)
    pushAction(AddItemAction(shapeItem))
}
```

3. **Add Drawing Code**
```kotlin
// In CanvasItem.draw() extension:
is ShapeItem -> {
    canvas.save()
    canvas.concat(matrix)
    when (shapeType) {
        ShapeType.RECTANGLE -> canvas.drawRect(rect, paint)
        ShapeType.OVAL -> canvas.drawOval(rect, paint)
    }
    canvas.restore()
}
```

4. **Add Serialization**
```kotlin
// In SvgSerializer
is ShapeData -> {
    // Serialize to SVG <rect> or <ellipse>
}

// In deserialize():
else if (parser.name == "rect" && ...) {
    // Parse shape data
}
```

### Adding a New Tool

1. **Add to ActiveTool Enum**
```kotlin
enum class ActiveTool { BRUSH, ERASER, LASSO, SELECT, SHAPE }
```

2. **Add Toolbar Button** (in activity_main.xml)
```xml
<ImageButton
    android:id="@+id/btnShape"
    android:src="@drawable/ic_shape" />
```

3. **Wire Up Handler**
```kotlin
btnShape = findViewById(R.id.btnShape)
btnShape.setOnClickListener {
    drawingView.activeTool = DrawingView.ActiveTool.SHAPE
    updateToolState()
}
```

## Performance Considerations

### Bitmap Management

1. **Thumbnail Generation**: Use 20% scale
2. **Recognition Bitmaps**: Release after use
3. **ImageItem Cache**: Invalidate on transform

### Canvas Rendering

1. **Viewport Culling**: Skip items outside visible area
2. **Path Optimization**: Simplify paths on save
3. **Background Lines**: Draw at correct scale

### Memory Management

1. **Recycle Bitmaps**: Call `bitmap.recycle()` when done
2. **Clear Lists**: Remove unused items from memory
3. **Cancel Coroutines**: Clean up in `onDestroy()`

## Release Process

1. **Version Bump**
   ```kotlin
   // In app/build.gradle
   versionCode 2  // increment
   versionName "1.1.0"  // semantic version
   ```

2. **Build Release APK**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Test on Multiple Devices**
   - Different screen sizes
   - Different Android versions
   - Different GPU families (for Gemma)

4. **ProGuard/R8**
   - Keep model-related classes
   - Keep serialization classes
   - Test thoroughly (rules may break reflection)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests (if any)
5. Submit a pull request

### Pull Request Guidelines

- Reference the issue number
- Describe what changed
- Include screenshots for UI changes
- Test on real device