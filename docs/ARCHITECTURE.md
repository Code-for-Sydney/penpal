# Penpal Architecture

This document provides an in-depth look at the system architecture, component relationships, and data flow in the Penpal application.

> **Note**: This document describes the **v2.x Compose-based architecture** under development. For the current production architecture (v1.x), see legacy references below.

---

## Architecture Overview

### Central Architectural Concept: Inference

**Inference is the central architectural component** in Penpal v2.x. All other features (Process, Chat, Stacks) depend on the inference layer for AI capabilities:

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
│    │   feature:chat │  │ feature:stacks│  │ feature:process │       │
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
| Phase 3: Feature Modules | ✅ Complete | Chat, Process, Inference modules created |
| Phase 3.5: Tab Wiring | ✅ Complete | ViewModels connected, MainScreen with 2 tabs (Think, Settings) + Chat FAB |
| Phase 4: Polish | ✅ Complete | WorkManager notifications, offline mode, network monitoring |
| Phase 4.5: Stacks | ✅ Complete | Think tab with block-based editor, GraphNodeCanvas, DrawingCanvas |
| Phase 4.6: Stacks Enhanced | ✅ Complete | Image picker, Coil integration, home navigation |
| Phase 5: Real Parsers & Chat Persistence | ✅ Complete | Document parsing, vector persistence, chat enhancements |
| Phase 5.5: Streaming Token Filter | ✅ Complete | Trie-based filter, Flow-based inference, 120s timeout |
| Phase 5.6: Text Structure Fix | ✅ Complete | Smart spacing, whitespace handling, lastEmittedChar tracking |
| Phase 5.7: Structured Message Parts | ✅ Complete | Opencode-inspired parts architecture with rich UI rendering |
| Phase 5.8: Chat Model Response Fix | ✅ Complete | getContents() fix, conversation history in prompts, FAB navigation |

### Key Inference Components

| Component | Implementation | Description |
|-----------|----------------|-------------|
| **LiteRtInferenceBridge** | `LiteRtInferenceBridge` | LiteRT-LM Engine API pattern with `getContents()` for text extraction |
| **ChatViewModel** | `ChatViewModel` | Multi-turn conversation support with full message history in prompts |
| **Engine** | `LmEngineManager` | GPU/CPU backend fallback, Engine lifecycle |
| **Model** | Gemma 4 E2B-IT | Google's efficient on-device LLM |
| **API** | LiteRT-LM | Direct on-device inference via Engine class |
| **Streaming** | `Flow<String>` / `Flow<List<MessagePart>>` | Flow-based streaming (primary) + callback fallback |
| **Token Filter** | `StreamingTokenFilter` | Trie-based special token removal with mode transitions |
| **Special Tokens** | `GemmaSpecialTokens` | Definitions for turn, tool, thinking, media, sequence tokens |
| **Message Parts** | `MessagePart` sealed class | Structured parts: Text, Reasoning, ToolCall, ToolResponse, Image, Audio |
| **Part Aggregator** | `MessagePartAggregator` | Builds MessageParts from streaming token filter transitions |
| **Model Manager** | `ModelManager` | HuggingFace/Kaggle download management |
| **Text Embedder** | `OnnxMiniLmEmbedder` | ONNX Runtime with mean pooling + L2 normalization (fallback to mock) |
| **Model Source** | HuggingFace | `litert-community/gemma-4-E2B-it-litert-lm` (~2.6 GB) |
| **Timeout Guard** | `AtomicBoolean` + 120s | Prevents hung inference sessions |
| **Markdown Render** | `MarkdownText.kt` | Lightweight markdown renderer for chat messages |
| **Model Status** | `ModelStatusIndicator` | UI component showing: ON, Loading..., Unloading..., Downloading..., DL'd, ERR, OFF |

### AI Inference Architecture (LiteRT-LM Engine API)

The inference system uses the real LiteRT-LM Engine API for on-device LLM inference. The architecture has been updated to support **Flow-based streaming** with **trie-based token filtering**:

```
┌────────────────────────────────────────────────────────────────────┐
│                      Inference Layer (core:ai)                       │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                      InferenceBridge                          │  │
│  │  (Interface: initialize, generate, streamGenerate, detectItems)│  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │                  LiteRtInferenceBridge                        │  │
│  │  • Engine/Conversation lifecycle                              │  │
│  │  • Flow<String> for streaming (primary)                       │  │
│  │  • MessageCallback for legacy streaming                       │  │
│  │  • StreamingTokenFilter (trie-based special token removal)    │  │
│  │  • 120s timeout with AtomicBoolean guards                     │  │
│  │  • Image/Audio content support (Content.ImageBytes)           │  │
│  │  • GPU/CPU backend fallback                                    │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │                    LmEngineManager                             │  │
│  │  • Creates Engine with GpuBackendSpec or CpuBackendSpec       │  │
│  │  • Tracks backend state (GPU/CPU)                              │  │
│  │  • Engine lifecycle management (create/release)                │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │              com.google.ai.edge.litertlm.Engine               │  │
│  │  • createConversation() -> Conversation                       │  │
│  │  • sendMessageAsync() -> Flow<Message> / MessageCallback      │  │
│  │  • renderMessageIntoString() for text extraction              │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │                    ModelManager                                │  │
│  │  • startDownloadHFAsync() - HuggingFace download              │  │
│  │  • startDownloadKaggleAsync() - Kaggle download                │  │
│  │  • queryDownload() - Progress polling                          │  │
│  │  • Uses Android DownloadManager for reliable downloads         │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                  Gemma 4 E2B-IT Model                         │  │
│  │  • Model: gemma-4-E2B-it.litertlm (~2.6 GB)                   │  │
│  │  • Source: huggingface.co/litert-community/...               │  │
│  │  • Outputs control tokens: <|turn>, <|think|>, <bos>, <eos>  │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │               StreamingTokenFilter (NEW)                      │  │
│  │  • TokenTrie for O(m) special token matching                  │  │
│  │  • Character-by-character processing with boundary buffering  │  │
│  │  • Removes: turn, tool, thinking, media, sequence tokens      │  │
│  │  • Mode transition tracking for structured parts              │  │
│  │  • Smart spacing: lastEmittedChar, word-char detection        │  │
│  └──────────────────────────────────────────────────────────────┘
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │            MessagePart Architecture (NEW)                     │  │
│  │  • MessagePart sealed class: Text, Reasoning, ToolCall, etc.  │  │
│  │  • MessagePartAggregator builds parts from stream transitions │  │
│  │  • InferenceBridge.runInferenceFlowParts(): Flow<List<...>>   │  │
│  │  • ChatViewModel collects parts, ChatScreen renders with UI   │  │
│  └──────────────────────────────────────────────────────────────┘
└────────────────────────────────────────────────────────────────────┘
```

#### Flow-Based Streaming (Primary)

```kotlin
// ChatViewModel uses Flow-based inference with structured parts
inferenceBridge.runInferenceFlowParts(contextPrompt)
    .catch { error -> /* handle error */ }
    .onCompletion { /* save to DB, cleanup */ }
    .collect { parts ->
        updateLastAssistantMessage(parts)
    }
```

The Flow-based approach:
1. `conversation.sendMessageAsync(content)` returns `Flow<Message>`
2. Each `Message` is converted to text via `conv.renderMessageIntoString(message)`
3. `StreamingTokenFilter.appendWithTransitions(chunk)` removes special tokens and emits mode transitions
4. `MessagePartAggregator` builds immutable `MessagePart` objects from transitions
5. `Flow<List<MessagePart>>` is collected by `ChatViewModel` and rendered by `ChatScreen`
6. `withTimeout(120_000)` prevents hung inference

**Two streaming APIs are available:**
- `runInferenceFlow(): Flow<String>` — Plain text accumulation (legacy compatibility)
- `runInferenceFlowParts(): Flow<List<MessagePart>>` — Structured parts for rich UI rendering

#### MessageCallback Interface (Legacy)

```kotlin
interface MessageCallback {
    fun onMessage(message: Message)
    fun onDone()
    fun onError(throwable: Throwable)
}
```

Callback-based streaming is still available via `runInference()` but `ChatViewModel` now prefers `runInferenceFlow()`.

#### StreamingTokenFilter

```kotlin
class StreamingTokenFilter(
    specialTokens: Set<String> = GemmaSpecialTokens.ALL_USER_FACING
) {
    fun append(chunk: String): String  // Returns safe prefix, buffers partial tokens
    fun appendWithTransitions(chunk: String): FilteredChunkWithTransitions
    fun flush(): String                // Emit remaining safe text at stream end
    fun clear()                        // Reset buffer
}
```

The filter uses a `TokenTrie` (prefix tree) to match special tokens character-by-character. This ensures partial tokens at chunk boundaries are correctly buffered until the complete token arrives.

**Smart Spacing Logic:**
- `lastEmittedChar` tracking prevents `\n\n` spam between words
- Only adds space before word characters, not punctuation or symbols
- Handles mode transitions (REGULAR → THINKING → TOOL_CALL → REGULAR) via `appendWithTransitions()`

#### GemmaSpecialTokens

```kotlin
object GemmaSpecialTokens {
    val TURN_TOKENS: Set<String>       // <|turn>, <turn|>, <|turn>model, etc.
    val TOOL_TOKENS: Set<String>       // <|tool>, <tool_call|>, etc.
    val THINKING_TOKENS: Set<String>   // <|think|>, <|channel>, etc.
    val MEDIA_TOKENS: Set<String>      // <|image>, <audio|>, etc.
    val SEQUENCE_TOKENS: Set<String>   // <bos>, <eos>, <|endoftext|>
    val ALL_USER_FACING: Set<String>   // Union of all above
}
```
```

#### RAG Flow (Chat → VectorStore → Inference)

```
User Query in Chat
        │
        ▼
VectorStoreRepository.similaritySearch(query, topK=6)
        │  (retrieves relevant chunks from processed documents)
        ▼
ChatViewModel builds prompt with document context
        │
        ▼
Check isModelReady state
        │
    ┌───┴───┐
    │       │
 Ready   Not Ready
    │       │
    ▼       ▼
runInference()  Show "Model not ready" message
        │
        ▼
MessageCallback.onContent() → streaming tokens
        │
        ▼
UI updates as response streams in
```

### Build Configuration (Current)

| Component | Version |
|-----------|---------|
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Hilt | 2.52 (plugin only, not actively used) |
| Room | 2.6.1 (app/processing), 2.7.0-beta01 (core:data) |
| Compose BOM | 2024.06.00 |
| AGP | 9.1.1 |

### Application Architecture

**Note:** The project uses manual dependency injection via `PenpalApplication` lazy singletons, not Hilt. ViewModels are instantiated manually in `MainScreen.kt` using `remember { ... }`.

```
PenpalApplication (Singleton)
├── lazy vectorStore: VectorStoreRepositoryImpl
├── lazy workerLauncher: WorkerLauncher
├── lazy inferenceBridge: InferenceBridge (LiteRtInferenceBridge)
├── lazy gson: Gson
└── gemmaServer: GemmaServerClient

PenpalDatabase (Singleton via getInstance())
├── stackDao()
├── chunkDao()
├── extractionJobDao()
├── chatMessageDao()
├── chatConversationDao()
├── graphDao()
└── fallbackToDestructiveMigration()
```

### Tab Implementation Status

| Tab | ViewModel | UI Status | Backend Status |
|-----|-----------|-----------|----------------|
| Chat | ChatViewModel | ✅ Functional | ✅ RAG via InferenceBridge, structured MessageParts (via FAB) |
| Think | StackEditorViewModel | ✅ Functional | ✅ Room persistence + auto-processing |
| Settings | SettingsViewModel | ✅ Functional | ✅ Model download, inference status |

**Note:** MainScreen currently shows 2 tabs in bottom navigation: Think, Settings. Chat is accessible via the FAB in the bottom-right corner.

---

## Module Architecture

### High-Level Structure

```
penpal/
├── app/                           # Shell application, NavHost, MainScreen
│   ├── MainScreen.kt              # Compose NavHost + BottomNavigation (Chat, Think, Settings)
│   ├── MainComposeActivity.kt     # Compose-based Activity entry point (Launcher)
│   ├── PenpalApplication.kt       # Manual DI singleton
│   └── (legacy activities: MainActivity, StackSelectionActivity, etc.)
├── core/
│   ├── ai/                        # ✅ Implemented
│   │   ├── InferenceBridge.kt     # ML inference interface
│   │   ├── LiteRtInferenceBridge.kt # LiteRT-LM Engine API implementation
│   │   ├── OllamaInferenceBridge.kt # Remote inference fallback
│   │   ├── LmEngineManager.kt     # Engine lifecycle, GPU/CPU fallback
│   │   ├── TextEmbedder.kt        # Text embedding interface
│   │   ├── MiniLmEmbedder.kt      # Mock embedder (384-dim, fallback)
│   │   ├── OnnxMiniLmEmbedder.kt  # ONNX Runtime embedder with mean pooling + L2 norm
│   │   ├── VectorStoreRepository.kt # LRU cache + similarity
│   │   ├── VectorStoreProvider.kt # Cross-module singleton access
│   │   ├── ModelManager.kt        # HuggingFace/Kaggle download management
│   │   ├── MessagePart.kt         # Structured message parts (Text, Reasoning, ToolCall)
│   │   ├── MessagePartAggregator.kt # Builds parts from streaming transitions
│   │   ├── StreamingTokenFilter.kt # Trie-based special token filtering with mode transitions
│   │   ├── GemmaSpecialTokens.kt  # Gemma 4 control token definitions
│   │   └── WordPieceTokenizer.kt  # BERT/MiniLM-compatible tokenizer
│   ├── data/                      # ✅ Implemented
│   │   ├── PenpalDatabase.kt      # Room database v3 (singleton via getInstance())
│   │   ├── Entities.kt            # 7 entities
│   │   └── Daos.kt               # 6 DAOs
│   ├── media/                     # ✅ Stub (empty shell, no source files)
│   ├── processing/                # ✅ Implemented
│   │   ├── DocumentParser.kt      # Parser interface
│   │   ├── Parsers.kt             # Real parsers: PDF, Image OCR, Audio, URL, Code
│   │   ├── ExtractionWorker.kt    # WorkManager worker with real parsing
│   │   ├── WorkerLauncher.kt      # Job queue
│   │   ├── NotificationHelper.kt  # WorkManager notifications
│   │   └── NetworkMonitor.kt      # Connectivity tracking
│   └── ui/                        # ✅ Implemented
│       └── Theme.kt               # Material 3 dark/light
├── feature/                       # ✅ Phase 3+ Complete
│   ├── chat/                      # ✅ RAG chat with structured MessageParts
│   ├── process/                   # ✅ Document extraction UI
│   ├── inference/                 # ✅ Model management UI
│   ├── stacks/                 # ✅ Think tab - block editor
│   └── settings/                  # ✅ App settings and configuration
├── build.gradle.kts              # Root with plugins
├── settings.gradle.kts           # Module includes
└── gradle/libs.versions.toml     # Version catalog
```

### Module Dependencies

**Note:** Dependency injection is manual via `PenpalApplication`, not Hilt. No `@HiltViewModel`, `@Module`, or `@Inject` annotations exist in the codebase.

```
app ──> all core modules, all feature modules
core:processing ──> core:ai, core:data
core:ai ──> core:data              ← InferenceBridge is the core AI dependency
core:media ──> (empty, no source files)
feature:chat ──> core:ai, core:data, core:processing, core:ui, feature:stacks
feature:stacks ──> core:ai, core:data, core:processing, core:ui
feature:process ──> core:processing, core:ai, core:data, core:ui
feature:inference ──> core:ai, core:data, core:ui ← Direct inference access
feature:settings ──> core:ai, core:data, core:ui
```

**Key Architectural Principle**: `InferenceBridge` in `core:ai` is the central dependency. All AI-powered features flow through this interface to the Gemma 4 E2B-IT model via LiteRT-LM.

---

## Core Module Details

### core:ai

Handles AI inference and text embedding. **This is the central architectural module.**

```
core:ai/
├── InferenceBridge.kt       # Interface: initialize(), runInference(), runInferenceFlow(), runInferenceFlowParts()
├── LiteRtInferenceBridge.kt # LiteRT-LM Engine API implementation
├── OllamaInferenceBridge.kt # Remote inference via Ollama REST API
├── LmEngineManager.kt       # Engine lifecycle, GPU/CPU backend fallback
├── ModelManager.kt          # HuggingFace/Kaggle download management
├── ModelDownloadManager.kt  # WorkManager-based download orchestration
├── ModelDownloadWorker.kt   # Background download worker
├── TextEmbedder.kt          # Text embedding interface
├── MiniLmEmbedder.kt        # Mock: 384-dim embeddings (fallback)
├── OnnxMiniLmEmbedder.kt    # ONNX Runtime: mean pooling, L2 normalization
├── VectorStoreRepository.kt # Interface: embed(), similaritySearch()
├── VectorStoreProvider.kt   # Static provider for cross-module access
├── MessagePart.kt           # Structured message parts (Text, Reasoning, ToolCall, ToolResponse, Image, Audio)
├── MessagePartAggregator.kt # Builds MessageParts from streaming transitions
├── StreamingTokenFilter.kt  # Trie-based special token filtering with mode transitions
├── GemmaSpecialTokens.kt    # Gemma 4 control token definitions
├── WordPieceTokenizer.kt    # BERT/MiniLM-compatible tokenizer
├── OllamaApiService.kt      # REST API client for Ollama
└── OllamaModel.kt           # Data models for Ollama API responses
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

#### InferenceBridge (LiteRT-LM Engine API)

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
    fun runInferenceFlow(input: String): Flow<String>
    fun runInferenceFlowParts(input: String): Flow<List<MessagePart>>
    fun runInferenceWithImageFlow(input: String, image: Bitmap): Flow<String>
    fun runInferenceWithImageFlowParts(input: String, image: Bitmap): Flow<List<MessagePart>>

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
enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, LOADING, READY, ERROR }
```

#### LiteRtInferenceBridge (LiteRT-LM Engine API)

The `LiteRtInferenceBridge` uses the LiteRT-LM Engine API with GPU/CPU backend fallback:

```kotlin
@Singleton
class LiteRtInferenceBridge @Inject constructor(
    @InferenceDispatcher private val inferenceDispatcher: CoroutineDispatcher,
) : InferenceBridge {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var lmEngineManager: LmEngineManager? = null

    override val isReadyFlow = MutableStateFlow(false)
    override val isProcessingFlow = MutableStateFlow(false)
    override val modelInfoFlow = MutableStateFlow(ModelInfo(...))
    override val downloadProgressFlow = MutableStateFlow(DownloadProgress(...))

    override suspend fun initialize(context: Context, config: InferenceConfig): Boolean {
        return withContext(inferenceDispatcher) {
            // LiteRT-LM Engine API pattern
            lmEngineManager = LmEngineManager(context)
            engine = lmEngineManager?.createEngine()
            isReadyFlow.value = engine != null
            engine != null
        }
    }

    override fun runInference(prompt: String, callback: MessageCallback) {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")
        val conv = eng.startConversation(callback)
        conversation = conv
        conv.send(prompt)
    }
}

// LmEngineManager with GPU/CPU fallback
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
├── PenpalDatabase.kt      # Room database v3 (singleton via getInstance())
├── Entities.kt            # 7 entities
└── Daos.kt               # 6 DAOs
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
        ChunkEntity::class,
        ExtractionJobEntity::class,
        ChatMessageEntity::class,
        ChatConversationEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
        StackEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun extractionJobDao(): ExtractionJobDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatConversationDao(): ChatConversationDao
    abstract fun graphDao(): GraphDao
    abstract fun stackDao(): StackDao
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
├── Parsers.kt            # Real implementations:
│                         #   • PdfDocumentParser (PdfBox text extraction)
│                         #   • ImageParser (ML Kit Text Recognition OCR)
│                         #   • AudioParser (metadata, placeholder for transcription)
│                         #   • UrlParser (Jsoup HTML parsing)
│                         #   • CodeParser (language-aware: Kotlin, Java, Python, JS/TS, Go, Rust)
│                         #   • ParserFactory (MIME type routing)
├── ExtractionWorker.kt   # WorkManager worker with real parsing + vector persistence
├── WorkerLauncher.kt     # Job queue management
├── NotificationHelper.kt # WorkManager progress notifications
├── NetworkMonitor.kt     # Connectivity tracking for offline mode
├── WhisperTranscriber.kt # Audio transcription utilities
└── SpeechRecognizer.kt   # Speech recognition interface
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

// Implementations:
// - PdfDocumentParser (PdfBox: text extraction + overlapping chunking)
// - AudioParser (metadata reading, transcription placeholder)
// - ImageParser (ML Kit Text Recognition with coroutine suspension)
// - UrlParser (Jsoup: HTML → clean text extraction)
// - CodeParser (language-aware parsing for Kotlin, Java, Python, JS/TS, Go, Rust)
// - ParserFactory (creates parser by MIME type)
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

## feature:stacks Module

The Stacks module provides a block-based editor for creating rich documents with text, images, drawings, graphs, and LaTeX. This implements the "Think" tab in the bottom navigation.

### Module Structure

```
feature:stacks/
├── StackModels.kt        # Block sealed class, GraphNode, GraphEdge, StackEvent
├── StackEditorViewModel.kt # Editor state management, setImageUri()
├── StackScreen.kt        # Main screen composable, image picker, Coil integration
├── BlockRenderer.kt         # Block type rendering
├── GraphNodeCanvas.kt       # Node-based graph editor
└── DrawingCanvas.kt         # Touch-based drawing
```

**Dependencies:**
- `io.coil-kt:coil-compose:2.5.0` for async image loading in ImageBlockContent

### Block Model (StackModels.kt)

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

### StackEvent (StackModels.kt)

```kotlin
sealed class StackEvent {
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

### StackEditorViewModel

```kotlin
@HiltViewModel
class StackEditorViewModel @Inject constructor(
    // ...
) : ViewModel() {

    val uiState: StateFlow<StackEditorState> = MutableStateFlow(StackEditorState())

    fun onEvent(event: StackEvent) {
        when (event) {
            is StackEvent.AddBlock -> { /* ... */ }
            is StackEvent.RemoveBlock -> { /* ... */ }
            is StackEvent.SetImageUri -> updateBlock(blockId) { /* set uri */ }
            is StackEvent.UpdateGraphNode -> { /* ... */ }
            is StackEvent.AddGraphEdge -> { /* ... */ }
            // ...
        }
    }

    fun setImageUri(blockId: String, uri: Uri) {
        // Updates ImageBlock with selected gallery image URI
    }
}
```

### StackScreen (Image Picker + Navigation)

```kotlin
@Composable
fun StackScreen(
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
    ParserFactory.create(mimeType).parse(uri)  ← IO dispatcher (real parsing)
           │
           ▼
    chunks = [RawChunk, ...]        ← smart overlap for RAG context
           │
           ▼
    vectorStore.embed(chunks)        ← Default dispatcher (ONNX embedding)
           │
           ▼
    Room.insert(chunks)             ← IO dispatcher (persistent storage)
           │
           ▼
    Result.success()
```

### Query → RAG Response (LiteRT-LM Engine API)

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
4. Check isModelReady state
             │
      ┌──────┴──────┐
      │             │
      ▼             ▼
Ready         Not Ready
      │             │
      ▼             ▼
runInferenceFlowParts()  Show "Model not ready" message
      │
      ▼
Flow.collect() → StreamingTokenFilter → MessagePartAggregator → List<MessagePart>
             │
             ▼
      ChatViewModel updates message with parts
             │
             ▼
      ChatScreen renders TextPart, ReasoningBlock, ToolCallBlock
             │
             ▼
5. Compose recomposes ChatScreen with streaming response
```

---

## Known Issues

### Text Splitting After Special Character Filtering ✅

**Status**: Resolved

**Problem**: After implementing the `StreamingTokenFilter`, chat text was being split into separate lines after each special character occurrence. The model output contains structural newlines around control tokens (e.g., `<|turn>model\n...content...\n<turn|>`), and when tokens were removed, spurious line breaks remained in the user-facing text.

**Solution**:
- Implemented smart spacing logic in `StreamingTokenFilter` with `lastEmittedChar` tracking
- Added `appendWithTransitions()` method that emits mode transition events for structured parsing
- Space insertion is now context-aware: only before word characters, not punctuation/symbols
- Prevents `\n\n` spam between words while preserving natural paragraph structure

**Result**: Chat responses now render with proper text structure. Excessive line breaks have been eliminated while preserving intentional paragraph breaks.

**Files Involved**:
- `core/ai/StreamingTokenFilter.kt`
- `core/ai/GemmaSpecialTokens.kt`
- `core/ai/LiteRtInferenceBridge.kt` (Flow accumulation logic)
- `feature/chat/ChatViewModel.kt` (message update logic)
- `feature/chat/ChatScreen.kt` (text rendering)

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
| Think | `stacks` | AutoAwesome | StackListScreen → StackScreen |
| Settings | `settings` | Settings | SettingsScreen |
| Chat | `chat` | AutoMirrored.Chat | ChatScreen (via FAB) |

**Navigation Behavior:**
- Chat FAB shows on Stacks and Settings tabs
- FAB hides when user is in Chat screen
- FAB reappears after exiting Chat (via X button or tab click)
- Clicking a tab while in Chat triggers `popBackStack()` to close chat
- Consistent navigation flow between tabs and Chat screen

### Tab Implementation Status

| Tab | ViewModel | UI Status | Backend Status |
|-----|-----------|-----------|----------------|
| Chat | ChatViewModel | ✅ Functional | ✅ RAG via InferenceBridge, structured MessageParts (via FAB) |
| Think | StackEditorViewModel | ✅ Functional | ✅ Room persistence + auto-processing |
| Settings | SettingsViewModel | ✅ Functional | ✅ Model download, inference status |

**Note:** MainScreen shows 2 tabs (Think, Settings) in bottom nav. Chat is accessible via FAB in bottom-right corner.

### Module Dependencies

```
app ──> all core modules, all feature modules
core:processing ──> core:ai, core:data
core:ai ──> core:data              ← InferenceBridge is the core AI dependency
core:media ──> (empty shell, no source files)
feature:chat ──> core:ai, core:data, core:processing, core:ui, feature:stacks
feature:stacks ──> core:ai, core:data, core:processing, core:ui
feature:process ──> core:processing, core:ai, core:data, core:ui
feature:inference ──> core:ai, core:data, core:ui
feature:settings ──> core:ai, core:data, core:ui
```

**Key Architectural Principle**: `InferenceBridge` in `core:ai` is the central dependency. All AI-powered features flow through this interface to the Gemma 4 E2B-IT model via LiteRT-LM. `ModelStatus` enum is defined in `core:ai` and imported by features that need to check model state.

---

## Memory Management

| Resource | Strategy |
|----------|----------|
| Embedding cache | LRU with max 10,000 chunks in memory |
| Bitmap | `recycle()` in finally block |
| Room pagination | `chunkDao.getAllPaged(offset, limit)` |
| Engine | `close()` on release, conversation cleanup |

---

## Thread Safety Checklist

- [x] Every Room call in `withContext(Dispatchers.IO)`
- [x] Every ONNX/embedding in `withContext(Dispatchers.Default)`
- [x] StateFlow updates via `.update {}` (lock-free)
- [x] CoroutineWorker, not Worker
- [x] No `runBlocking` anywhere
- [x] No GlobalScope — viewModelScope or worker scope only

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

*Last updated: Documentation synced — tab structure (2 tabs + Chat FAB), navigation refactor to popBackStack(), StackEditorViewModel block update fix (May 2026)*

---

## Legacy v1.x Architecture

> The following describes the current production architecture (v1.x single-Activity with Views).

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      StackSelectionActivity                   │
│                           (Launcher)                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │ Stacks   │  │ Model       │  │ PDF Import              │  │
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