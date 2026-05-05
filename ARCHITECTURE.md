# Penpal Architecture

This document provides an in-depth look at the system architecture, component relationships, and data flow in the Penpal application.

> **Note**: This document describes the **v2.x Compose-based architecture** under development. For the current production architecture (v1.x), see legacy references below.

---

## Architecture Overview

### Central Architectural Concept: Inference

**Inference is the central architectural component** in Penpal v2.x. All other features (Process, Chat, Notebooks) depend on the inference layer for AI capabilities:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        PENPAL ARCHITECTURE                               │
│                                                                          │
│                           ┌──────────────────┐                          │
│                           │  Inference Layer │  ← CENTRAL COMPONENT     │
│                           │  (ML Kit GenAI)   │                          │
│                           └────────┬─────────┘                          │
│                                    │                                     │
│              ┌─────────────────────┼─────────────────────┐               │
│              │                     │                     │               │
│              ▼                     ▼                     ▼               │
│    ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐       │
│    │   feature:chat │  │ feature:notebooks│  │ feature:process │       │
│    │  (RAG queries)  │  │  (recognition)   │  │ (extraction)    │       │
│    └────────┬───────┘  └────────┬─────────┘  └────────┬────────┘       │
│             │                    │                     │                 │
│             └────────────────────┼─────────────────────┘                 │
│                                  ▼                                       │
│                    ┌─────────────────────────┐                          │
│                    │    InferenceBridge      │                          │
│                    │  (LiteRtInferenceBridge)│                          │
│                    └────────────┬────────────┘                          │
│                                 │                                        │
│                    ┌────────────▼────────────┐                          │
│                    │     Gemma 4 E2B-IT       │                          │
│                    │   (via ML Kit GenAI API) │                          │
│                    └─────────────────────────┘                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### Current Status

| Phase | Status | Description |
|-------|--------|-------------|
| Phase 1: Foundation | ✅ Complete | Gradle multi-module, Kotlin DSL, core modules |
| Phase 2: Core AI | ✅ Complete | AI interfaces, VectorStore, processing pipeline |
| Phase 3: Feature Modules | ✅ Complete | Chat, Process, Inference tabs with navigation |
| Phase 3.5: Tab Wiring | ✅ Complete | Real ViewModels connected, UI functional |
| Phase 4: Polish | ✅ Complete | WorkManager notifications, offline mode, network monitoring |
| Phase 4.5: Notebooks | ✅ Complete | Think tab with block-based editor, GraphNodeCanvas, DrawingCanvas |
| Phase 4.6: Notebooks Enhanced | ✅ Complete | Image picker, Coil integration, home navigation |

### Key Inference Components

| Component | Implementation | Description |
|-----------|----------------|-------------|
| **InferenceBridge** | `LiteRtInferenceBridge` | ML Kit GenAI pattern (AI Edge Gallery style) |
| **Model** | Gemma 4 E2B-IT | Google's efficient on-device LLM |
| **API** | ML Kit GenAI | LiteRT-based inference on Android |
| **Streaming** | Flow-based | StateFlow for progress, Channel for streaming tokens |

### Build Configuration (Current)

| Component | Version |
|-----------|---------|
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Hilt | 2.51.1 |
| Room | 2.6.1 |
| AGP | 9.0.0 |

### Application Architecture

```
PenpalApplication (Singleton)
├── lazy vectorStore: VectorStoreRepository
├── lazy workerLauncher: WorkerLauncher
├── lazy inferenceBridge: InferenceBridge
└── lazy gson: Gson

PenpalDatabase (Singleton via getInstance())
├── notebookDao()
├── chunkDao()
├── jobDao()
└── graphDao()
```

### Tab Implementation Status

| Tab | ViewModel | UI Status | Backend Status |
|-----|-----------|-----------|----------------|
| Chat | ChatViewModel | ✅ Functional | ✅ RAG via InferenceBridge |
| Think | NotebookEditorViewModel | ✅ Functional | ✅ Room persistence |
| Process | ProcessViewModel | ✅ Functional | ✅ Connected to VectorStore |
| Inference | InferenceViewModel | ✅ Functional | ✅ ML Kit GenAI / Gemma 4 |
| Settings | SettingsViewModel | ✅ Functional | ✅ SharedPreferences / DataStore |

---

## Module Architecture

### High-Level Structure

```
penpal/
├── app/                           # Shell application, NavHost, MainScreen
│   ├── MainScreen.kt              # Compose NavHost + BottomNavigation (Process, Chat, Inference)
│   ├── MainComposeActivity.kt     # Compose-based Activity entry point
│   └── MainViewModel.kt           # ViewModel for MainScreen
├── core/
│   ├── ai/                        # ✅ Implemented
│   │   ├── AiModule.kt            # Hilt bindings
│   │   ├── DispatcherModule.kt    # Coroutine dispatchers
│   │   ├── InferenceBridge.kt     # ML inference interface
│   │   ├── LiteRtInferenceBridge.kt # ML Kit GenAI implementation (AI Edge Gallery pattern)
│   │   ├── InferenceModule.kt     # Hilt inference bindings
│   │   ├── TextEmbedder.kt        # Text embedding interface
│   │   ├── MiniLmEmbedder.kt      # Mock embedder (384-dim)
│   │   └── VectorStoreRepository.kt # LRU cache + similarity
│   ├── data/                      # ✅ Implemented
│   │   ├── PenpalDatabase.kt      # Room database
│   │   ├── Entities.kt            # 5 entities
│   │   ├── Daos.kt               # 4 DAOs
│   │   ├── DatabaseModule.kt      # Hilt DI
│   │   ├── NetworkModule.kt       # OkHttpClient
│   │   └── TypeConverters.kt     # Enum/type converters
│   ├── media/                     # ✅ Stub (empty shell)
│   ├── processing/                # ✅ Implemented
│   │   ├── DocumentParser.kt      # Parser interface
│   │   ├── Parsers.kt             # 5 parser stubs
│   │   ├── ExtractionWorker.kt     # WorkManager worker
│   │   ├── WorkerLauncher.kt      # Job queue
│   │   └── ProcessingModule.kt    # Hilt DI
│   └── ui/                        # ✅ Partial
│       └── Theme.kt               # Material 3 dark/light
├── feature/                       # ✅ Phase 3 & 4 Complete
│   ├── chat/                      # ✅ RAG chat interface
│   ├── process/                   # ✅ Document extraction UI
│   ├── inference/                 # ✅ Model management UI
│   ├── notebooks/                 # ✅ Think tab - block editor
│   └── settings/                  # ✅ App settings and configuration
├── build.gradle.kts              # Root with plugins
├── settings.gradle.kts           # Module includes
└── gradle/libs.versions.toml     # Version catalog
```

### Module Dependencies

```
app ──> all core modules, feature:chat, feature:process, feature:inference
core:processing ──> core:ai, core:data
core:ai ──> core:data              ← InferenceBridge is the core AI dependency
core:media ──> core:ai, core:data
feature:chat ──> core:ai, core:data, core:ui   ← Depends on InferenceBridge
feature:process ──> core:processing, core:ai, core:data, core:ui
feature:inference ──> core:ai, core:data, core:ui ← Direct inference access
```

**Key Architectural Principle**: `InferenceBridge` in `core:ai` is the central dependency. All AI-powered features flow through this interface to the Gemma 4 E2B-IT model via ML Kit GenAI.

---

## Core Module Details

### core:ai

Handles AI inference and text embedding. **This is the central architectural module.**

```
core:ai/
├── AiModule.kt              # Hilt bindings
├── DispatcherModule.kt      # @IoDispatcher, @DefaultDispatcher, @InferenceDispatcher
├── InferenceBridge.kt       # Interface: initialize(), generate(), stream(), detectItems(), recognizeText()
├── LiteRtInferenceBridge.kt # ML Kit GenAI implementation (AI Edge Gallery pattern)
├── InferenceModule.kt       # Hilt bindings for inference
├── TextEmbedder.kt          # Interface: embed(text), dimension
├── MiniLmEmbedder.kt        # Mock: 384-dim embeddings
└── VectorStoreRepository.kt # LRU cache + cosine similarity search
```

#### DispatcherModule

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class InferenceDispatcher

// @InferenceDispatcher limited to 2 parallel tasks
```

#### InferenceBridge (ML Kit GenAI Pattern)

```kotlin
interface InferenceBridge {
    val isReady: Boolean
    val isReadyFlow: StateFlow<Boolean>
    val isProcessingFlow: StateFlow<Boolean>
    val modelInfoFlow: StateFlow<ModelInfo>
    val downloadProgressFlow: StateFlow<DownloadProgress>

    // Lifecycle
    suspend fun initialize(context: Context, config: InferenceConfig): Boolean
    suspend fun downloadModel(modelId: String): Flow<DownloadProgress>
    fun release()
    fun close()

    // Generation with streaming support
    suspend fun generate(prompt: String, config: GenerationConfig): String
    fun streamGenerate(prompt: String, config: GenerationConfig): Flow<String>

    // Task-specific inference
    suspend fun detectItems(bitmap: Bitmap, prompt: String): List<DetectedItem>
    suspend fun recognizeText(bitmap: Bitmap, prompt: String): String
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String?): String
}

data class DetectedItem(
    val text: String,
    val boxYmin: Float,  // 0-1000 normalized
    val boxXmin: Float,
    val boxYmax: Float,
    val boxXmax: Float,
)

data class ModelInfo(
    val modelId: String,
    val modelName: String,
    val sizeBytes: Long,
    val isDownloaded: Boolean
)

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val status: DownloadStatus
)

enum class DownloadStatus { NOT_STARTED, DOWNLOADING, COMPLETED, FAILED }
enum class ModelBackend { ON_DEVICE, REMOTE_API }
```

#### LiteRtInferenceBridge (AI Edge Gallery Pattern)

The `LiteRtInferenceBridge` follows the AI Edge Gallery pattern for ML Kit GenAI integration:

```kotlin
@Singleton
class LiteRtInferenceBridge @Inject constructor(
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher,
) : InferenceBridge {

    private var generativeModel: GenerativeModel? = null
    private var downloadTask: Task<Void>? = null

    override val isReadyFlow = MutableStateFlow(false)
    override val isProcessingFlow = MutableStateFlow(false)
    override val modelInfoFlow = MutableStateFlow(ModelInfo(...))
    override val downloadProgressFlow = MutableStateFlow(DownloadProgress(...))

    override suspend fun initialize(context: Context, config: InferenceConfig): Boolean {
        return withContext(inferenceDispatcher) {
            // ML Kit GenAI API pattern
            val model = GenerativeModel.Builder()
                .setModelName(config.modelName)  // "gemma-4-e2b-it"
                .setApiKey(config.apiKey)        // Optional API key
                .build()
            generativeModel = model
            isReadyFlow.value = true
            true
        }
    }

    override suspend fun downloadModel(modelId: String): Flow<DownloadProgress> = flow {
        // Download via ModelDownloadHelper with progress reporting
        emit(DownloadProgress(0, totalSize, DOWNLOADING))
        // ... download logic
        emit(DownloadProgress(totalSize, totalSize, COMPLETED))
    }

    override fun streamGenerate(prompt: String, config: GenerationConfig): Flow<String> = flow {
        val model = generativeModel ?: throw IllegalStateException("Model not initialized")
        val input = ContentBuilder.makeContent { text(prompt) }

        model.generateContentStream(input).collect { chunk ->
            emit(chunk.text)
        }
    }
}
```

#### VectorStoreRepository

```kotlin
interface VectorStoreRepository {
    suspend fun embed(chunks: List<RawChunk>)
    suspend fun similaritySearch(query: String, topK: Int): List<ChunkEntity>
    suspend fun getChunksForSource(sourceId: String): List<ChunkEntity>
    suspend fun deleteChunksForSource(sourceId: String)
}

// Implementation details:
// - LRU cache (configurable max size, default 10,000)
// - Cosine similarity for vector comparison
// - Embeddings stored as JSON in Room
```

---

### core:data

Handles persistence and networking.

```
core:data/
├── PenpalDatabase.kt      # Room database (singleton via getInstance())
├── Entities.kt            # 5 entities (enum fields as String for KSP)
├── Daos.kt               # 4 DAOs defined
├── DatabaseModule.kt     # Hilt DI for Room
├── NetworkModule.kt       # OkHttpClient provider
└── TypeConverters.kt     # Enum/type converters
```

#### Room Database Singleton

```kotlin
// Thread-safe singleton for WorkManager compatibility
object PenpalDatabase {
    @Volatile
    private var instance: PenpalDatabase_Impl? = null

    fun getInstance(context: Context): PenpalDatabase_Impl {
        return instance ?: synchronized(this) {
            instance ?: buildDatabase(context).also { instance = it }
        }
    }
}

// ExtractionWorker uses this pattern instead of Hilt injection
class ExtractionWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    private val database = PenpalDatabase.getInstance(ctx)
    // ...
}
```

#### Room Schema

```kotlin
@Database(
    entities = [
        NotebookEntity::class,
        ChunkEntity::class,
        ExtractionJobEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun chunkDao(): ChunkDao
    abstract fun jobDao(): ExtractionJobDao
    abstract fun graphDao(): GraphDao
}
```

#### Enums

```kotlin
enum class ExtractionRule { 
    FFT_PEAKS, DICOM_METADATA, FULL_TEXT, 
    TRANSCRIPT, IMAGE_OCR, URL_CONTENT, CODE 
}

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED }

enum class NodeType { PAPER, CONCEPT, TOOL, DATA_MODEL }
```

---

### core:processing

Handles document parsing and background extraction.

```
core:processing/
├── DocumentParser.kt      # Interface: parse(uri, rule) -> List<RawChunk>
├── Parsers.kt            # PDF, Audio, Image, URL, Code (stubs)
├── ExtractionWorker.kt   # WorkManager worker with Hilt
├── WorkerLauncher.kt     # Job queue management
└── ProcessingModule.kt   # Hilt DI
```

#### DocumentParser

```kotlin
interface DocumentParser {
    val supportedMimeTypes: Set<String>
    suspend fun parse(uri: Uri, rule: ExtractionRule): List<RawChunk>
}

data class RawChunk(
    val id: String,
    val sourceId: String,
    val text: String,
    val position: Int  // page number or timestamp ms
)

// Implementations (stubs):
// - PdfDocumentParser (iText / PDFBox)
// - AudioParser (Whisper via JNI)
// - ImageParser (ML Kit Text Recognition)
// - UrlParser (Jsoup HTML → text)
// - CodeParser (syntax-aware chunking)
```

#### ExtractionWorker

```kotlin
@HiltWorker
class ExtractionWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val parser: DocumentParser,
    private val vectorStore: VectorStoreRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(io) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()

        setProgress(workDataOf(KEY_PROGRESS to 0))

        val job = extractionRepo.getJob(jobId)
        val chunks = parser.parse(job.uri, job.rule)

        setProgress(workDataOf(KEY_PROGRESS to 50))

        withContext(Dispatchers.Default) {
            vectorStore.embed(chunks)
        }

        setProgress(workDataOf(KEY_PROGRESS to 100))
        extractionRepo.updateJobStatus(jobId, JobStatus.DONE)
        Result.success(workDataOf(KEY_JOB_ID to jobId))
    }
}
```

---

## feature:notebooks Module

The Notebooks module provides a block-based editor for creating rich documents with text, images, drawings, graphs, and LaTeX. This implements the "Think" tab in the bottom navigation.

### Module Structure

```
feature:notebooks/
├── NotebookModels.kt        # Block sealed class, GraphNode, GraphEdge, NotebookEvent
├── NotebookEditorViewModel.kt # Editor state management, setImageUri()
├── NotebookScreen.kt        # Main screen composable, image picker, Coil integration
├── BlockRenderer.kt         # Block type rendering
├── GraphNodeCanvas.kt       # Node-based graph editor
└── DrawingCanvas.kt         # Touch-based drawing
```

**Dependencies:**
- `io.coil-kt:coil-compose:2.5.0` for async image loading in ImageBlockContent

### Block Model (NotebookModels.kt)

```kotlin
sealed class Block {
    abstract val id: String

    data class TextBlock(
        override val id: String,
        val content: String = "",
        val isEditing: Boolean = false
    ) : Block()

    data class ImageBlock(
        override val id: String,
        val uri: Uri? = null,
        val caption: String = "",
        val isEditing: Boolean = false
    ) : Block()

    data class DrawingBlock(
        override val id: String,
        val pathData: String = "",
        val width: Float = 800f,
        val height: Float = 600f
    ) : Block()

    data class LatexBlock(
        override val id: String,
        val expression: String = ""
    ) : Block()

    data class GraphBlock(
        override val id: String,
        val graphId: String,
        val nodes: List<GraphNode> = emptyList(),
        val edges: List<GraphEdge> = emptyList()
    ) : Block()

    data class EmbedBlock(
        override val id: String,
        val sourceId: String,
        val preview: String = "",
        val type: EmbedType = EmbedType.LINK
    ) : Block()
}

enum class EmbedType { LINK, AUDIO, VIDEO, FILE }
```

### NotebookEvent (NotebookModels.kt)

```kotlin
sealed class NotebookEvent {
    data class AddBlock(val block: Block, val afterBlockId: String? = null)
    data class RemoveBlock(val blockId: String)
    data class UpdateTextBlock(val blockId: String, val content: String)
    data class SetImageUri(val blockId: String, val uri: Uri)  // Image picker integration
    data class UpdateGraphNode(val node: GraphNode)
    data class AddGraphEdge(val edge: GraphEdge)
    data class AddDrawingPath(val pathData: String)
    // ...
}
```

data class GraphNode(
    val id: String,
    val label: String,
    var posX: Float,
    var posY: Float,
    val type: NodeType = NodeType.DEFAULT
)

enum class NodeType { DEFAULT, CONCEPT, TOOL, DATA, STARRED }

data class GraphEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String = "",
    val type: EdgeType = EdgeType.DEFAULT
)

enum class EdgeType { DEFAULT, LABELLED, BIDIRECTIONAL, HIGHLIGHTED }
```

### GraphNodeCanvas

The `GraphNodeCanvas` is a custom Canvas composable for visualizing and editing node-based graphs:

```kotlin
@Composable
fun GraphNodeCanvas(
    nodes: List<GraphNode>,
    edges: List<GraphEdge>,
    selectedNodeId: String?,
    isAddingEdge: Boolean,
    edgeStartNodeId: String?,
    onNodePositionChanged: (String, Float, Float) -> Unit,
    onNodeDragEnded: (GraphNode) -> Unit,
    onNodeSelected: (String?) -> Unit,
    onNodeDoubleTap: (Float, Float) -> Unit,
    onNodeLongPress: (String, Offset) -> Unit,
    onEdgeStart: (String) -> Unit,
    onEdgeComplete: (String) -> Unit,
    onCanvasTap: (Offset) -> Unit,
    onCanvasPan: (Offset) -> Unit,
    onCanvasScale: (Float) -> Unit,
    // ...
)
```

**Interactions:**
- **Drag**: `detectDragGestures` → updates node `posX/posY` → callback to ViewModel
- **Pan**: `detectTransformGestures` with two fingers
- **Zoom**: Pinch gesture with scale bounds (0.25x - 4x)
- **Double-tap**: Creates new node at tap position
- **Long-press**: Shows context menu for existing node
- **Edge creation**: Tap start node → tap end node → edge created

**Rendering:**
- Grid drawn in canvas space
- Edges rendered as curved `Path` with arrow heads
- Nodes rendered as colored circles with labels
- Color-coded by node type (DEFAULT=indigo, CONCEPT=emerald, TOOL=amber, DATA=blue, STARRED=red)

### DrawingCanvas

The `DrawingCanvas` provides freehand drawing with a floating toolbar:

```kotlin
@Composable
fun DrawingCanvas(
    pathData: String,
    onPathDataChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color.Black,
    strokeWidth: Float = 4f,
    backgroundColor: Color = Color.White
)
```

**Features:**
- **Color palette**: 8 colors (black, gray, red, orange, blue, green, purple, pink)
- **Eraser mode**: 3x stroke width, draws white
- **Undo**: `paths.dropLast(1)` removes last path
- **Clear**: Resets to empty path list
- **Toolbar**: Auto-hides after 5 seconds

**Path Serialization:**
```kotlin
// Format: "isEraser:colorHex:strokeWidth:points..."
// Example: "0:FF000000:4:100,200,150,250;0:FF000000:4:300,400,350,450"
```

### NotebookEditorViewModel

```kotlin
@HiltViewModel
class NotebookEditorViewModel @Inject constructor(
    // ...
) : ViewModel() {

    val uiState: StateFlow<NotebookEditorState> = MutableStateFlow(NotebookEditorState())

    fun onEvent(event: NotebookEvent) {
        when (event) {
            is NotebookEvent.AddBlock -> { /* ... */ }
            is NotebookEvent.RemoveBlock -> { /* ... */ }
            is NotebookEvent.SetImageUri -> updateBlock(blockId) { /* set uri */ }
            is NotebookEvent.UpdateGraphNode -> { /* ... */ }
            is NotebookEvent.AddGraphEdge -> { /* ... */ }
            // ...
        }
    }

    fun setImageUri(blockId: String, uri: Uri) {
        // Updates ImageBlock with selected gallery image URI
    }
}
```

### NotebookScreen (Image Picker + Navigation)

```kotlin
@Composable
fun NotebookScreen(
    onNavigateToHome: () -> Unit = {},  // Navigate to Process tab
    // ...
) {
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // Handle selected image URI
    }

    // Toolbar home button triggers onNavigateToHome()
}
```

**Coil Integration for ImageBlockContent:**
```kotlin
ImageBlockContent(
    uri = block.uri,
    caption = block.caption,
    onPickImage = { imagePickerLauncher.launch("image/*") },
    onCaptionChanged = { /* ... */ }
)

// Uses AsyncImage from coil-compose to display selected images
```

---

## Threading Model

```
Main Thread (UI) ──suspend/StateFlow──> IO Dispatcher (Room, files, network)
                                      ──> Default Dispatcher (embeddings, FFT)
                                      ──> Inference Dispatcher (limited parallelism 2)
                                      ──> WorkManager (persisted extraction)
```

### Dispatcher Assignments

| Operation | Dispatcher |
|-----------|------------|
| UI StateFlow | Main (auto via viewModelScope) |
| Room reads/writes | @IoDispatcher |
| File I/O | @IoDispatcher |
| ONNX/LiteRT inference | @InferenceDispatcher (limited 2) |
| Embeddings computation | @DefaultDispatcher |
| Graph layout/FFT | @DefaultDispatcher |
| WorkManager workers | withContext inside doWork() |

---

## Data Flow

### Document Ingestion → Vector Storage

```
1. User adds document via bottom sheet
           │
           ▼
2. viewModelScope.launch(ioDispatcher)
           │
           ▼
3. extractionRepo.createJob()  ← writes to Room
           │
           ▼
4. WorkerLauncher.enqueue(jobId)   ← enqueues ExtractionWorker
           │
           ▼
5. ExtractionWorker.doWork():
    parser.parse(uri)                ← IO dispatcher (file read)
           │
           ▼
    chunks = [RawChunk, ...]
           │
           ▼
    vectorStore.embed(chunks)        ← Default dispatcher (embedding)
           │
           ▼
    Room.insert(chunks)             ← IO dispatcher
           │
           ▼
    Result.success()
```

### Query → RAG Response

```
1. User sends query in Chat
           │
           ▼
2. ChatViewModel.sendQuery("What about X?")
    viewModelScope.launch(Default)
           │
           ▼
3. VectorStoreRepository.similaritySearch(query, topK=6)
    Default dispatcher (embedding + cosine sim)
           │
           ▼
    chunks = [ChunkEntity, ...]    ← top-K relevant text
           │
           ▼
4. InferenceBridge.generate(prompt + context)
     Inference dispatcher (ML Kit GenAI / Gemma 4 E2B-IT)
           │
           ▼
    result = InferenceResult(text, sources)
           │
           ▼
5. ChatViewModel._messages.update { it + Message(result.text) }
           │
           ▼
6. Compose recomposes ChatScreen
```

---

## Build Configuration

### Version Catalog (libs.versions.toml)

```toml
[versions]
kotlin = "2.1.0"
compose-compiler = "2.1.0"
hilt = "2.54"
room = "2.7.0"
coroutines = "1.8.1"
okhttp = "4.12.0"
work = "2.9.1"
```

### Module build.gradle.kts

```kotlin
// core:ai/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    implementation(libs.room.runtime)
    implementation(libs.okhttp)
}

// core:processing/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    implementation(libs.work.runtime)
}
```

---

## Channel Bridge Pattern

```kotlin
class ProcessViewModel @Inject constructor(
    private val extractionRepo: ExtractionRepository,
    private val workerLauncher: WorkerLauncher,
    @Inject @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val ingestionChannel = Channel<IngestionRequest>(capacity = Channel.BUFFERED)

    init {
        viewModelScope.launch(ioDispatcher) {
            ingestionChannel.consumeEach { request ->
                val jobId = extractionRepo.createJob(request)
                WorkerLauncher.enqueue(jobId)
                observeJobProgress(jobId)
            }
        }
    }

    fun submitIngestion(request: IngestionRequest) {
        viewModelScope.launch {
            ingestionChannel.send(request)
        }
    }
}
```

---

## Bottom Navigation (Implemented)

| Tab | Route | Icon | Screen |
|-----|-------|------|--------|
| Chat | `chat` | AutoMirrored.Chat | ChatScreen |
| Think | `notebooks` | AutoAwesome | NotebookListScreen → NotebookScreen |
| Process | `process` | CloudUpload | ProcessScreen |
| Inference | `inference` | Psychology | InferenceScreen |
| Settings | `settings` | Settings | SettingsScreen |

### Tab Implementation Status

| Tab | ViewModel | UI Status | Backend Status |
|-----|-----------|-----------|----------------|
| Chat | ChatViewModel | ✅ Functional | ✅ RAG via InferenceBridge |
| Think | NotebookEditorViewModel | ✅ Functional | ✅ Room persistence |
| Process | ProcessViewModel | ✅ Functional | ✅ Connected to VectorStore |
| Inference | InferenceViewModel | ✅ Functional | ✅ ML Kit GenAI / Gemma 4 |
| Settings | SettingsViewModel | ✅ Functional | ✅ SharedPreferences / DataStore |

### Module Dependencies

```
app ──> all core modules, all feature modules
core:processing ──> core:ai, core:data
core:ai ──> core:data              ← InferenceBridge is the core AI dependency
core:media ──> core:ai, core:data
feature:chat ──> core:ai, core:data, core:ui   ← Depends on InferenceBridge
feature:process ──> core:processing, core:ai, core:data, core:ui
feature:inference ──> core:ai, core:data, core:ui ← Direct inference access
feature:notebooks ──> core:data, core:ui, core:ai
feature:settings ──> core:data, core:ui
```

**Key Architectural Principle**: `InferenceBridge` in `core:ai` is the central dependency. All AI-powered features flow through this interface to the Gemma 4 E2B-IT model via ML Kit GenAI. `ModelStatus` enum is defined in `core:ai` and imported by features that need to check model state.

---

## Memory Management

| Resource | Strategy |
|----------|----------|
| Embedding cache | LRU with max 10,000 chunks in memory |
| Bitmap | `recycle()` in finally block |
| Room pagination | `chunkDao.getAllPaged(offset, limit)` |

---

## Thread Safety Checklist

- [ ] Every Room call in `withContext(Dispatchers.IO)`
- [ ] Every ONNX/embedding in `withContext(Dispatchers.Default)`
- [ ] StateFlow updates via `.update {}` (lock-free)
- [ ] CoroutineWorker, not Worker
- [ ] No `runBlocking` anywhere
- [ ] No GlobalScope — viewModelScope or worker scope only

---

## Related Documentation

| Document | Description |
|----------|-------------|
| [MIGRATION.md](./MIGRATION.md) | v1.x → v2.x migration guide |
| [CHANGELOG.md](./CHANGELOG.md) | Version history |
| [DEVELOPMENT.md](./DEVELOPMENT.md) | Development guidelines |
| [testingground/ARCHITECTURE.md](./testingground/ARCHITECTURE.md) | Detailed planning docs |

---

## Glossary

| Term | Definition |
|------|------------|
| **RAG** | Retrieval-Augmented Generation — combining vector search with LLM inference |
| **LRU** | Least Recently Used — cache eviction strategy |
| **Embedding** | Vector representation of text for semantic similarity |
| **Chunk** | Parsed text segment from a document with position metadata |
| **Hilt** | Google's dependency injection framework for Android |

---

*Last updated: Build Fix & Settings Module Integration (May 2026)*

---

## Legacy v1.x Architecture

> The following describes the current production architecture (v1.x single-Activity with Views).

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      NotebookSelectionActivity                   │
│                           (Launcher)                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │ Notebooks   │  │ Model       │  │ PDF Import              │  │
│  │ RecyclerView│  │ Manager     │  │ Activity                │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                      │                    │
                      ▼                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                          MainActivity                            │
│                           (Drawing)                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                   DrawingView (Custom View)                  ││
│  │  ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌─────────────┐ ││
│  │  │ StrokeItem│ │ WordItem   │ │ ImageItem │ │ PromptItem  │ ││
│  │  └───────────┘ └───────────┘ └───────────┘ └─────────────┘ ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Description |
|-----------|-------------|
| **DrawingView** | Custom View handling canvas operations |
| **HandwritingRecognizer** | LiteRT-LM (Gemma) wrapper |
| **GemmaServerClient** | Remote inference via HTTP |
| **AudioRecorder/Player** | Audio capture and playback |
| **SvgSerializer** | SVG persistence |

---

*End of Architecture Documentation*