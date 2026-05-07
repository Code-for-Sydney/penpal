# Development Guide

This guide provides instructions for setting up a development environment and understanding the codebase for contributing to Penpal.

## AI Inference Setup

Penpal v2.x uses **Google Gemma 4 E2B-IT** as the primary inference model via **LiteRT-LM Engine API**. This section covers setup and configuration.

### LiteRT-LM Engine API Setup

The inference layer uses the **LiteRT-LM Engine API** with GPU/CPU backend fallback. The app has migrated from MediaPipe LLM Inference API (deprecated) to LiteRT-LM.

#### 1. Add Dependencies

```kotlin
// In core:ai/build.gradle.kts
dependencies {
    // LiteRT-LM for Gemma inference
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
    
    // Google Play Services (required for model download)
    implementation("com.google.android.gms:play-services-base:18.3.0")
}
```

#### 2. Configure Model Download

LiteRT-LM supports model downloads from HuggingFace and Kaggle:

```kotlin
// ModelManager for download management
val modelManager = ModelManager(context)

// Download from HuggingFace
val downloadId = modelManager.startDownloadHFAsync(
    token = "your_hf_token",
    repoId = "google/gemma-4-2b-it",
    filePath = context.filesDir.resolve("models/gemma-4-2b-it.bin")
)

// Poll for progress
val progress = modelManager.queryDownload(downloadId)
val percentage = (progress.bytesDownloaded * 100) / progress.totalBytes
```

#### 3. Initialize InferenceBridge

```kotlin
// In Application or ViewModel
val inferenceBridge: InferenceBridge = LiteRtInferenceBridge(context)

inferenceBridge.initialize(
    context = context,
    modelName = "google/gemma-4-e2b-it",
    backend = null,  // null = auto (GPU first, CPU fallback)
    onDone = { message -> Log.d("Penpal", message) }
)
```

### LmEngineManager with GPU/CPU Backend Fallback

```kotlin
class LmEngineManager(private val context: Context) {
    private var engine: Engine? = null
    var backend: ModelBackend = ModelBackend.ON_DEVICE

    fun createEngine(): Engine? {
        // Try GPU first
        val gpuSpec = GpuBackendSpec.create()
        if (gpuSpec != null) {
            engine = Engine.create(gpuSpec)
            if (engine != null) {
                backend = ModelBackend.GPU
                return engine
            }
        }
        // Fallback to CPU
        val cpuSpec = CpuBackendSpec.create()
        engine = Engine.create(cpuSpec)
        backend = ModelBackend.CPU
        return engine
    }

    fun release() { engine?.close(); engine = null }
}
```

### Streaming via Flow (Primary)

The recommended approach for streaming inference uses Kotlin Flow:

```kotlin
// Flow-based streaming (primary)
inferenceBridge.runInferenceFlow(prompt)
    .catch { error ->
        // Handle timeout, cancellation, or model errors
        Log.e("Penpal", "Inference error", error)
    }
    .onCompletion {
        // Save to database, cleanup
    }
    .collect { partialResult ->
        // Update UI with accumulated cleaned text
        viewModel.updateLastAssistantMessage(partialResult)
    }
```

The Flow-based pipeline:
1. `conversation.sendMessageAsync(content)` returns `Flow<Message>`
2. Each message is rendered to text via `conv.renderMessageIntoString(message)`
3. `StreamingTokenFilter.append(chunk)` removes special tokens incrementally
4. Clean text is accumulated and emitted
5. `withTimeout(120_000)` prevents hung inference sessions

### Streaming via MessageCallback (Legacy)

```kotlin
interface MessageCallback {
    fun onMessage(message: Message)
    fun onDone()
    fun onError(throwable: Throwable)
}

// Usage
inferenceBridge.runInference(
    input = prompt,
    resultListener = { partial, done ->
        viewModel.updateLastAssistantMessage(partial)
    },
    cleanUpListener = { /* cleanup */ },
    onError = { error -> viewModel.showError(error) }
)
```

### StreamingTokenFilter

The `StreamingTokenFilter` removes Gemma 4 control tokens from the model output using a trie (prefix tree) for efficient character-by-character matching:

```kotlin
val filter = StreamingTokenFilter(GemmaSpecialTokens.ALL_USER_FACING)

// Process streaming chunks
val emitted1 = filter.append("Hello <|tur")   // Returns "Hello " (partial buffered)
val emitted2 = filter.append("n> world")       // Returns "world" (token removed)
val remaining = filter.flush()                 // Returns "" (buffer was only token)
```

**Why trie-based?**
- Regex would re-scan the entire accumulated text on each chunk (O(n) per chunk)
- Trie matches in O(m) where m = token length, regardless of input size
- Correctly handles partial tokens at chunk boundaries via internal buffering

**Token Categories Filtered:**
- **Turn tokens**: `<|turn>`, `<turn|>`, `<|turn>model`, `<|turn>user`, `<|turn>system`
- **Tool tokens**: `<|tool>`, `<tool_call|>`, `<|tool_response>`, etc.
- **Thinking tokens**: `<|think|>`, `<|channel>`, `<channel|>`
- **Media tokens**: `<|image>`, `<image|>`, `<|audio>`, `<audio|>`
- **Sequence tokens**: `<bos>`, `<eos>`, `<|endoftext|>`, `<|im_start|>`, `<|im_end|>`

### Gemma 4 E2B-IT Model Configuration

| Property | Value |
|----------|-------|
| **Model ID** | `gemma-4-e2b-it` |
| **Name** | Gemma 4 Efficient 2B Instruction-Tuned |
| **Size** | ~2.6 GB |
| **Parameters** | 2B |
| **Context Window** | 8K tokens |
| **Use Case** | Instruction following, RAG, text generation |
| **API** | LiteRT-LM Engine API |

#### ConversationConfig

```kotlin
ConversationConfig(
    samplerConfig = SamplerConfig(
        topK = 64,
        topP = 0.95,
        temperature = 0.7
    )
)
```

#### EngineConfig

```kotlin
EngineConfig(
    modelPath = modelPath,
    backend = Backend.GPU(),        // or Backend.CPU()
    visionBackend = Backend.GPU(),  // for image support
    audioBackend = Backend.CPU(),   // for audio support
    maxNumImages = 1,
    maxNumTokens = 4096
)
```

### Model Download Flow

The app downloads models via Settings using ModelManager:

```
1. User opens Settings tab
            │
            ▼
2. Enter HuggingFace token (if not already saved)
            │
            ▼
3. Tap "Download Model" button
            │
            ▼
4. ModelManager.startDownloadHFAsync() initiates download
            │
            ▼
5. SettingsViewModel polls ModelManager.queryDownload() for progress
            │
            ▼
6. Progress shown in ModelDownloadBottomSheet:
   - bytesDownloaded / totalBytes
   - percentage
   - status: DOWNLOADING → COMPLETED
            │
            ▼
7. On complete: LiteRtInferenceBridge initializes with model
```

#### ModelManager

```kotlin
class ModelManager(private val context: Context) {
    
    fun startDownloadHFAsync(
        token: String,
        repoId: String,  // e.g., "google/gemma-4-2b-it"
        filePath: File
    ): Long  // Returns downloadId
    
    fun startDownloadKaggleAsync(
        modelUri: String,
        filePath: File
    ): Long
    
    fun queryDownload(downloadId: Long): DownloadProgress
}

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: DownloadStatus  // NOT_STARTED, DOWNLOADING, COMPLETED, FAILED
)
```

### Known Issue: Text Splitting After Special Characters

**Status**: In Progress

After implementing `StreamingTokenFilter`, chat responses exhibit spurious line breaks after special token occurrences.

**Symptoms**:
- Model outputs text with structural newlines around turn tokens
- After filtering `<|turn>model\n...\n<turn|>`, extra `\n` characters remain
- Text appears fragmented in the chat UI

**Debugging Tips**:

```kotlin
// Add logging to trace token boundaries
Log.d("TokenFilter", "Raw chunk: ${text.take(50).replace("\n", "\\n")}")
Log.d("TokenFilter", "Cleaned chunk: ${cleaned.take(50).replace("\n", "\\n")}")
```

**Potential Fixes**:
1. **Newline coalescing**: Collapse multiple consecutive `\n` into a single `\n` after token removal
2. **Boundary trimming**: Trim whitespace around removed token boundaries
3. **Annotated spans**: Track token types as metadata rather than filtering from raw text
4. **Template-aware filtering**: Understand Gemma 4 chat template structure to remove associated whitespace

**Files to Modify**:
- `core/ai/StreamingTokenFilter.kt` — Add newline coalescing/trimming
- `core/ai/LiteRtInferenceBridge.kt` — Verify chunk accumulation logic
- `feature/chat/ChatViewModel.kt` — Post-process received text

---

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
fun `chat shows streaming response via Flow`() = runTest {
    // Given
    val mockBridge = mock<InferenceBridge> {
        on { isReady } doReturn MutableStateFlow(true)
        on { runInferenceFlow(anyString()) } doReturn flow {
            emit("Th")
            emit("Thank")
            emit("Thank you")
        }
    }
    
    val viewModel = ChatViewModel(
        inferenceBridge = mockBridge,
        vectorStore = mockVectorStore
    )
    
    // When
    viewModel.onEvent(ChatEvent.SendMessage)
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