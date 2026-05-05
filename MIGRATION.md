# Migration Guide: PenPal v1.x to v2.x

This document serves as the definitive reference for migrating PenPal from the current single-Activity architecture (v1.x) to a Compose-based multi-module architecture (v2.x).

---

## Migration Status

| Phase | Status | Description |
|-------|--------|-------------|
| **Phase 1: Foundation** | ✅ COMPLETE | Gradle multi-module, Kotlin DSL, core modules |
| **Phase 2: Core AI** | ✅ COMPLETE | AI interfaces, VectorStore, processing pipeline |
| **Phase 3: Feature Modules** | ✅ COMPLETE | Chat, Process, Inference tabs with navigation |
| **Phase 3.5: Tab Wiring** | ✅ COMPLETE | Real ViewModels connected, UI functional |
| Phase 4: Polish | 📋 Planned | WorkManager notifications, offline mode |

---

## Table of Contents

1. [Completed Work Summary](#completed-work-summary)
2. [Module Structure](#module-structure)
3. [Build Configuration](#build-configuration)
4. [Key Interfaces Created](#key-interfaces-created)
5. [Dispatcher Model](#dispatcher-model)
6. [Extraction Pipeline](#extraction-pipeline)
7. [Dependency Direction](#dependency-direction)
8. [Threading Model](#threading-model)
9. [Room Schema](#room-schema)
10. [Data Flow](#data-flow)
11. [Channel Bridge Pattern](#channel-bridge-pattern)
12. [Migration Phases](#migration-phases)
13. [Edge Cases](#edge-cases)
14. [Thread Safety Checklist](#thread-safety-checklist)
15. [Verification Checklist](#verification-checklist)
16. [Related Documentation](#related-documentation)

---

## Completed Work Summary

### Phase 1: Foundation ✅ COMPLETE

| Component | Implementation |
|-----------|----------------|
| **Gradle Structure** | Multi-module with 5 core modules (core:ai, core:data, core:media, core:processing, core:ui) |
| **Build System** | Kotlin DSL throughout (`build.gradle.kts`), `settings.gradle.kts` |
| **Kotlin Version** | Kotlin 2.1.0 with Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose:2.1.0`) |
| **DI Framework** | Hilt 2.54 with kapt for annotation processing |
| **Database** | Room 2.7.0 |
| **Build Tools** | AGP 9.0.0 |
| **Stubbed LiteRT** | Removed unavailable `litertlm-android:0.1.0` dependency, created stub implementations |

### Phase 2: Core AI ✅ COMPLETE

| Component | Implementation |
|-----------|----------------|
| **core:ai module** | DispatcherModule, InferenceBridge, TextEmbedder, VectorStoreRepository, MiniLmEmbedder |
| **core:data module** | NetworkModule (OkHttpClient), Room schema (5 entities, 4 DAOs), ExtractionRule enums |
| **core:processing module** | DocumentParser, ExtractionWorker, WorkerLauncher, ProcessingModule |

### Phase 3: Feature Modules ✅ COMPLETE

| Component | Implementation |
|-----------|----------------|
| **app module** | MainScreen.kt (BottomNavigation + NavHost), MainComposeActivity.kt, MainViewModel.kt, PenpalApplication.kt (lazy DI) |
| **feature:chat module** | ChatViewModel.kt (RAG flow), ChatScreen.kt, ChatState.kt, ChatModule.kt |
| **feature:process module** | ProcessViewModel.kt, ProcessScreen.kt, ProcessModule.kt |
| **feature:inference module** | InferenceViewModel.kt, InferenceScreen.kt, InferenceModule.kt |
| **Build config** | Added compose plugin to core:ai, InferenceBridge.release() method added, Kotlin 2.0.21, KSP 2.0.21-1.0.28 |

---

## Module Structure

```
penpal/
├── app/                           # Shell app, navigation, MainScreen with BottomNav
│   ├── MainScreen.kt              # Compose NavHost + BottomNavigation (Process, Chat, Inference)
│   ├── MainComposeActivity.kt     # Compose-based Activity entry point
│   └── MainViewModel.kt           # ViewModel for MainScreen
├── core/
│   ├── ai/                        # ✅ IMPLEMENTED
│   │   ├── AiModule.kt            # Hilt bindings
│   │   ├── DispatcherModule.kt    # @IoDispatcher, @DefaultDispatcher, @InferenceDispatcher
│   │   ├── InferenceBridge.kt     # Interface for ML inference
│   │   ├── LiteRtInferenceBridge.kt  # Stub implementation (LiteRT unavailable)
│   │   ├── InferenceModule.kt     # Hilt bindings for inference
│   │   ├── TextEmbedder.kt        # Interface for text embeddings
│   │   ├── MiniLmEmbedder.kt      # Mock embedder (384-dim)
│   │   └── VectorStoreRepository.kt # LRU cache, cosine similarity
│   ├── data/                      # ✅ IMPLEMENTED
│   │   ├── PenpalDatabase.kt      # Room database (singleton via getInstance() for WorkManager)
│   │   ├── Entities.kt            # 5 entities (enum fields as String for KSP)
│   │   ├── Daos.kt               # 4 DAOs defined
│   │   ├── DatabaseModule.kt      # Hilt DI
│   │   ├── NetworkModule.kt       # OkHttpClient provider
│   │   └── TypeConverters.kt      # Enum/type converters
│   ├── media/                     # ✅ STUB (empty shell)
│   ├── processing/                # ✅ IMPLEMENTED
│   │   ├── DocumentParser.kt      # Interface
│   │   ├── Parsers.kt             # PDF, Audio, Image, URL, Code (stubs)
│   │   ├── ExtractionWorker.kt     # WorkManager worker
│   │   ├── WorkerLauncher.kt      # Job queue management
│   │   └── ProcessingModule.kt    # Hilt DI
│   └── ui/                        # ✅ PARTIAL
│       └── Theme.kt               # Material 3 theme (dark/light)
├── feature/                       # ✅ Phase 3 Complete
│   ├── chat/                      # ✅ Chat screen with RAG flow
│   ├── process/                   # ✅ Process screen with source type selector, job list
│   ├── inference/                 # ✅ Inference screen with model status, action buttons
│   ├── notebooks/                 # 📋 Planned
│   ├── organize/                  # 📋 Planned
│   └── settings/                  # 📋 Planned
├── build.gradle.kts              # Root with plugins
├── settings.gradle.kts           # Module includes (app, core:*, feature:*)
└── gradle/libs.versions.toml     # Version catalog
```

### Module Dependencies (Implemented)

```
app ──> core:ai, core:data, core:processing, core:media, core:ui
core:processing ──> core:ai, core:data
core:ai ──> core:data
feature:chat ──> core:ai, core:data, core:ui
feature:process ──> core:processing, core:data, core:ui
feature:inference ──> core:ai, core:data, core:ui
```

---

## Build Configuration

### Core Module Dependencies (build.gradle.kts)

```kotlin
// core:ai/build.gradle.kts
dependencies {
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    implementation(libs.room.runtime)
    implementation(libs.okhttp)
}

// core:processing/build.gradle.kts
dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:data"))
    implementation(libs.hilt.android)
    implementation(libs.work.runtime)
}
```

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

[libraries]
kotlinx-coroutines = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
```

---

## Key Interfaces Created

### 1. InferenceBridge (core:ai)

```kotlin
interface InferenceBridge {
    val isReady: Boolean
    val isReadyFlow: StateFlow<Boolean>
    val isProcessingFlow: StateFlow<Boolean>

    suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean
    suspend fun detectItems(bitmap: Bitmap, prompt: String): List<DetectedItem>
    suspend fun recognizeText(bitmap: Bitmap, prompt: String): String
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String?): String
    fun release()
    fun close()
}

data class DetectedItem(
    val text: String,
    val boxYmin: Float,
    val boxXmin: Float,
    val boxYmax: Float,
    val boxXmax: Float,
)

enum class ModelBackend { ON_DEVICE, REMOTE_API }
```

### 2. TextEmbedder (core:ai)

```kotlin
interface TextEmbedder {
    suspend fun embed(text: String): FloatArray
    val dimension: Int
}

// MiniLmEmbedder - Mock implementation with 384-dim embeddings
class MiniLmEmbedder : TextEmbedder {
    override val dimension: Int = 384
    override suspend fun embed(text: String): FloatArray {
        // Generate mock 384-dimensional embedding
        return FloatArray(384) { Random.nextFloat() }
    }
}
```

### 3. VectorStoreRepository (core:ai)

```kotlin
interface VectorStoreRepository {
    suspend fun embed(chunks: List<RawChunk>)
    suspend fun similaritySearch(query: String, topK: Int): List<ChunkEntity>
    suspend fun getChunksForSource(sourceId: String): List<ChunkEntity>
    suspend fun deleteChunksForSource(sourceId: String)
}

// LRU Cache Implementation
// - Max size: configurable (default 10,000 chunks)
// - Cosine similarity for search
// - JSON-serialized embeddings in Room
```

### 4. DocumentParser (core:processing)

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
// - PdfDocumentParser
// - AudioParser
// - ImageParser
// - UrlParser
// - CodeParser
```

### 5. ExtractionWorker (core:processing)

```kotlin
@HiltWorker
class ExtractionWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val parser: DocumentParser,
    private val vectorStore: VectorStoreRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(ctx, params) {
    // Parses document, embeds chunks, stores in Room
}
```

---

## Dispatcher Model

### DispatcherModule (core:ai)

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier @Retention(AnnotationRetention.BINARY)
annotation class InferenceDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides @DefaultDispatcher
    fun provideDefault(): CoroutineDispatcher = Dispatchers.Default

    @Provides @IoDispatcher
    fun provideIo(): CoroutineDispatcher = Dispatchers.IO

    @Provides @InferenceDispatcher
    fun provideInference(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)
}
```

### Dispatcher Assignments

| Operation | Dispatcher |
|-----------|------------|
| UI StateFlow | Main (auto via viewModelScope) |
| Room reads/writes | @IoDispatcher |
| File I/O | @IoDispatcher |
| ONNX/LiteRT inference | @InferenceDispatcher (limited parallelism 2) |
| Embeddings computation | @DefaultDispatcher |
| Graph layout/FFT | @DefaultDispatcher |
| WorkManager workers | withContext inside doWork() |

---

## Extraction Pipeline

### Data Flow

```
User Input (PDF, URL, Audio, Image)
           │
           ▼
WorkerLauncher.enqueue(jobId)
           │
           ▼
ExtractionWorker.doWork():
    parser.parse(uri)                    ← IO dispatcher
           │
           ▼
    chunks = [RawChunk, ...]
           │
           ▼
    vectorStore.embed(chunks)             ← Default dispatcher
           │
           ▼
    Room.insert(chunks)                   ← IO dispatcher
           │
           ▼
    Result.success()
```

### ExtractionRule Enum (core:data)

```kotlin
enum class ExtractionRule {
    FFT_PEAKS,      // Audio frequency analysis
    DICOM_METADATA, // Medical imaging metadata
    FULL_TEXT,      // Complete text extraction
    TRANSCRIPT,     // Speech-to-text
    IMAGE_OCR,      // OCR for images
    URL_CONTENT,    // Web page content extraction
    CODE            // Source code parsing
}
```

---

## Dependency Direction

```
app ──> core:ai, core:data, core:processing, core:media, core:ui
                              │
                              ▼
                    core:processing ──> core:ai, core:data
                              │
                              ▼
                    core:ai ──> core:data
```

**Key Constraint:** Dependencies flow downward only — feature modules never import each other. Cross-feature communication occurs through core modules or shared state.

---

## Threading Model

```
Main Thread (UI) ──suspend/StateFlow──> IO Dispatcher (Room, files, network)
                                      ──> Default Dispatcher (embeddings, FFT)
                                      ──> Inference Dispatcher (limited parallelism)
                                      ──> WorkManager (persisted extraction)
```

### Thread Safety Checklist

- [ ] Every Room call in `withContext(Dispatchers.IO)`
- [ ] Every ONNX/embedding in `withContext(Dispatchers.Default)`
- [ ] StateFlow updates via `.update {}` (lock-free)
- [ ] CoroutineWorker, not Worker
- [ ] No `runBlocking` anywhere
- [ ] No GlobalScope — viewModelScope or worker scope only

---

## Room Schema

### Entities (core:data)

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

### Enums (core:data)

```kotlin
enum class ExtractionRule { 
    FFT_PEAKS, DICOM_METADATA, FULL_TEXT, 
    TRANSCRIPT, IMAGE_OCR, URL_CONTENT, CODE 
}

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED }

enum class NodeType { PAPER, CONCEPT, TOOL, DATA_MODEL }
```

---

## Data Flow

### PDF Ingestion → Vector Search

```
1. User adds PDF via bottom sheet
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
    parser.parse(uri)                ← IO dispatcher (PDFBox reads file)
           │
           ▼
    chunks = [RawChunk, ...]
           │
           ▼
    vectorStore.embed(chunks)        ← Default dispatcher (embedding)
           │
           ▼
    Room.insert(chunks)             ← IO dispatcher (Room insert)
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

## Migration Phases

### Phase 1: Foundation ✅ (COMPLETED: May 2026)

| Task | Status | Implementation |
|------|--------|-----------------|
| 1 | ✅ | Created Gradle multi-module structure |
| 2 | ✅ | Created `settings.gradle.kts` with Kotlin DSL |
| 3 | ✅ | Created core:data with Room schema + basic DAOs |
| 4 | ✅ | Created DispatcherModule in core:ai |
| 5 | ✅ | Created InferenceBridge interface |
| 6 | ✅ | Created core:ui with shared Material 3 Theme |
| 7 | ✅ | Created NetworkModule with OkHttpClient |
| 8 | ✅ | Added URL_CONTENT and CODE to ExtractionRule enum |
| 9 | ✅ | Created TextEmbedder interface + MiniLmEmbedder (384-dim) |
| 10 | ✅ | Created VectorStoreRepository with LRU cache |
| 11 | ✅ | Stubbed LiteRT dependencies (litertlm-android unavailable) |

### Phase 2: Core AI ✅ (COMPLETED: May 2026)

| Task | Status | Implementation |
|------|--------|-----------------|
| 1 | ✅ | Implemented VectorStoreRepository with LRU cache |
| 2 | ✅ | Built TextEmbedder interface + MiniLmEmbedder (384-dim) |
| 3 | ✅ | Wired InferenceBridge into Room-backed extraction pipeline |
| 4 | ✅ | Created core:processing with DocumentParser interface |
| 5 | ✅ | Created Parsers.kt with 5 stub implementations (PDF, Audio, Image, URL, Code) |
| 6 | ✅ | Created ExtractionWorker with Hilt |
| 7 | ✅ | Created WorkerLauncher for job queue |
| 8 | ✅ | Created ProcessingModule for Hilt DI |
| 9 | ✅ | Documented extraction pipeline |

### Phase 3: Feature Modules ✅ (COMPLETED: May 2026)

| Task | Status | Implementation |
|------|--------|----------------|
| 1 | ✅ | Created MainScreen.kt with BottomNavigation and NavHost |
| 2 | ✅ | Created MainComposeActivity.kt - Compose-based Activity entry point |
| 3 | ✅ | Created MainViewModel.kt - ViewModel for MainScreen |
| 4 | ✅ | Created feature:chat module with ChatViewModel (RAG flow), ChatScreen, ChatModule |
| 5 | ✅ | Created feature:process module with ProcessViewModel, ProcessScreen, ProcessModule |
| 6 | ✅ | Created feature:inference module with InferenceViewModel, InferenceScreen, InferenceModule |
| 7 | ✅ | Added hilt-navigation-compose and navigation-compose dependencies to app |
| 8 | ✅ | Added compose plugin to core:ai module build.gradle.kts |
| 9 | ✅ | Added InferenceBridge.release() method to interface and implementation |
| 10 | ✅ | Added feature:process and feature:inference to settings.gradle.kts |
| 11 | ✅ | Added MainComposeActivity to AndroidManifest.xml |

### Phase 3.5: Tab Wiring ✅ (COMPLETED: May 2026)

| Task | Status | Implementation |
|------|--------|----------------|
| 1 | ✅ | PenpalDatabase.kt - Added getInstance() singleton for WorkManager compatibility |
| 2 | ✅ | PenpalApplication.kt - Lazy initialization for dependencies |
| 3 | ✅ | MainScreen.kt - Wired up all 3 tabs with real ViewModels |
| 4 | ✅ | ExtractionWorker.kt - Uses PenpalDatabase.getInstance() |
| 5 | ✅ | Room entity simplification - Changed enums to String for KSP compatibility |
| 6 | ✅ | Build configuration - Kotlin 2.0.21, KSP 2.0.21-1.0.28 |

### Phase 4: Polish (📋 Planned)

| Task | Description |
|------|-------------|
| 1 | Connect MainComposeActivity as launcher or navigate from NotebookSelectionActivity |
| 2 | Implement real document parsing (PDFBox, Audio transcription) |
| 3 | Implement real LLM inference integration |
| 4 | WorkManager notifications for long-running extractions |
| 5 | Offline mode banner |
| 6 | End-to-end flow testing (PDF → Chat → Organize) |

---

## Edge Cases

### Offline Mode

| Scenario | Behavior |
|----------|----------|
| RAG queries | Fall back to `ModelBackend.ON_DEVICE` |
| No network | Show banner, disable chat, allow notebook drawing |
| NetworkMonitor | `StateFlow<Boolean>` updated via ConnectivityManager |

### Large Files

| Size | Strategy |
|------|----------|
| < 10 MB | Inline processing in ViewModel coroutine |
| 10-100 MB | WorkManager with `Constraints.RequiresBatteryNotLow` |
| > 100 MB | Pre-chunked upload with progress, background notification |

---

## Verification Checklist

### Module Structure

| Check | Requirement |
|-------|-------------|
| [x] | Feature modules have no inter-feature imports |
| [x] | Convention plugins eliminate build.gradle.kts duplication |
| [x] | libs.versions.toml used everywhere |
| [x] | Each core module has single responsibility |

### Data Flow

| Check | Requirement |
|-------|-------------|
| [x] | Ingestion creates Room entity before enqueueing WorkManager |
| [x] | Worker reads from Room, writes to Room |
| [x] | UI observes Room via Flow (not polling) |
| [ ] | Cross-tab state via @ActivityRetainedScoped ViewModel |

---

## Related Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Architecture details |
| [CHANGELOG.md](./CHANGELOG.md) | Version history |
| [DEVELOPMENT.md](./DEVELOPMENT.md) | Development guidelines |
| [testingground/ARCHITECTURE.md](./testingground/ARCHITECTURE.md) | Compose architecture details |
| [testingground/MODULES.md](./testingground/MODULES.md) | Gradle multi-module setup |

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

*Last updated: Phase 3 Complete (May 2026)*