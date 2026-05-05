# Changelog

All notable changes to the Penpal project.

## [Unreleased]

### Build Fix & Settings Module Integration (May 2026)

#### Build Error Fixes ✅

**Fixed Duplicate `ModelStatus` Enum**
- Removed local `ModelStatus` enum from `SettingsViewModel.kt` (lines 30-35)
- The ViewModel now imports `ModelStatus` from `com.penpal.core.ai.ModelStatus`
- This eliminates the duplicate definition that was causing build conflicts

**Added Missing Import to SettingsScreen**
- Added `import com.penpal.core.ai.ModelStatus` to `SettingsScreen.kt`
- Resolves unresolved reference error for `ModelStatus` usage

**Fixed NotebookRoutes Reference**
- Changed `NotebookRoutes.editor` to `NotebookRoutes.EDITOR` in `MainScreen.kt`
- Uses const companion object value instead of property accessor

**Build Status**: ✅ BUILD SUCCESSFUL
- App compiles without errors
- Only deprecation warnings remain (non-blocking)

#### Settings Module Integration ✅

The Settings module is now fully integrated with the main app:

| File | Changes |
|------|---------|
| `feature/settings/SettingsViewModel.kt` | Removed duplicate enum, imports from core.ai |
| `feature/settings/SettingsScreen.kt` | Added ModelStatus import |
| `app/MainScreen.kt` | Fixed NotebookRoutes reference, Settings tab connected |

#### Current Module Status

| Module | Status | Description |
|--------|--------|-------------|
| app | ✅ Complete | Shell app, MainScreen, BottomNavigation |
| core:ai | ✅ Complete | InferenceBridge, ModelStatus enum, VectorStore |
| core:data | ✅ Complete | Room database (v2), entities, DAOs |
| core:processing | ✅ Complete | DocumentParser, ExtractionWorker, WorkerLauncher |
| core:ui | ✅ Complete | Material 3 Theme |
| feature:chat | ✅ Complete | RAG chat interface |
| feature:process | ✅ Complete | Document extraction UI |
| feature:inference | ✅ Complete | Model management UI |
| feature:notebooks | ✅ Complete | Think tab - block-based editor |
| feature:settings | ✅ Complete | App settings and configuration |

---

### v2.x Notebooks Feature (Think Tab) - ✅ Complete (May 2026)

#### Notebooks v1.1: Image Picker & Home Navigation (May 2026)

**New Features:**

- **Image Picker Integration**
  - `SetImageUri` event added to `NotebookEvent` sealed class in `NotebookModels.kt`
  - `setImageUri()` method in `NotebookEditorViewModel.kt` handles URI updates
  - Activity result launcher using `ActivityResultContracts.GetContent()` for gallery access
  - Image block now displays selected images via `AsyncImage` from Coil library

- **Home Navigation**
  - Toolbar home button navigates to Process tab
  - `onNavigateToHome` callback wired through `NotebookScreen` composable

**New Dependency:**
- `io.coil-kt:coil-compose:2.5.0` added to `feature/notebooks/build.gradle.kts`

**Files Modified:**
| File | Changes |
|------|---------|
| `feature/notebooks/NotebookModels.kt` | Added `SetImageUri` event to `NotebookEvent` |
| `feature/notebooks/NotebookEditorViewModel.kt` | Added Uri import + `setImageUri()` method |
| `feature/notebooks/NotebookScreen.kt` | Image picker + navigation + Coil integration |
| `app/src/main/java/com/drawapp/MainScreen.kt` | Connected home navigation to Process tab |
| `feature/notebooks/build.gradle.kts` | Added Coil dependency |

**Working Features:**
| Feature | Status |
|---------|--------|
| Think tab navigation | ✅ Works |
| Image picker (tap Image block → gallery) | ✅ Works |
| Display selected images with Coil | ✅ Works |
| Home button navigates to Process tab | ✅ Works |

#### Phase 4.5: Notebooks Initial Implementation (✅ Complete)

| File | Changes |
|------|---------|
| `feature/notebooks/NotebookModels.kt` | Block sealed class, GraphNode, GraphEdge models |
| `feature/notebooks/NotebookEditorViewModel.kt` | Editor state management, block operations |
| `feature/notebooks/GraphNodeCanvas.kt` | Canvas composable with gestures |
| `feature/notebooks/DrawingCanvas.kt` | Drawing with colors/eraser/undo |
| `feature/notebooks/BlockRenderer.kt` | Block type rendering |
| `feature/notebooks/NotebookScreen.kt` | Main notebook editor screen |

**Phase 4.5 Dependencies:**
- `io.coil-kt:coil-compose:2.5.0` for async image loading in ImageBlockContent |

#### What's Now Working ✅

- Notebooks tab ("Think") with block-based editor
- GraphNodeCanvas for node-based graph editing
- DrawingCanvas with color picker, eraser, undo support
- LaTeX rendering via MathJax WebView
- Minimal floating toolbar design

---

### v2.x Phase 4: Polish Complete (May 2026)

#### Inference Module Overhaul

**ML Kit GenAI Integration**
- `InferenceBridge` updated with streaming support (`streamGenerate()`)
- `LiteRtInferenceBridge` implemented using ML Kit GenAI API (AI Edge Gallery pattern)
- Added `GenerationConfig` for inference parameters (temperature, token limits)
- Added model download progress tracking via `downloadProgressFlow`

**Gemma 4 E2B-IT Model**
- **Model**: Google Gemma 4 E2B-IT (Efficient 2B Instruction-Tuned)
- **Size**: ~2.6 GB
- **API**: ML Kit GenAI (LiteRT-based on-device inference)
- **Features**: Streaming token generation, instruction following, RAG support

**InferenceBridge Interface Updates**
```kotlin
interface InferenceBridge {
    val isReady: Boolean
    val isReadyFlow: StateFlow<Boolean>
    val isProcessingFlow: StateFlow<Boolean>
    val modelInfoFlow: StateFlow<ModelInfo>
    val downloadProgressFlow: StateFlow<DownloadProgress>

    suspend fun initialize(context: Context, config: InferenceConfig): Boolean
    suspend fun downloadModel(modelId: String): Flow<DownloadProgress>

    // Generation with streaming support
    suspend fun generate(prompt: String, config: GenerationConfig): String
    fun streamGenerate(prompt: String, config: GenerationConfig): Flow<String>

    // Task-specific inference
    suspend fun detectItems(bitmap: Bitmap, prompt: String): List<DetectedItem>
    suspend fun recognizeText(bitmap: Bitmap, prompt: String): String
    suspend fun transcribeAudio(audioData: ByteArray, prompt: String?): String

    fun release()
    fun close()
}

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
```

#### WorkManager Notifications
- `ExtractionWorker` now sends progress updates to WorkManager
- Notifications shown for long-running document extractions
- Progress percentage displayed in notification

#### Offline Mode & Network Monitoring
- `NetworkMonitor` tracks connectivity state
- UI indicators for offline/online status
- Graceful degradation when network unavailable
- `NetworkState` sealed class: `Available`, `Unavailable`, `Checking`

#### Phase 4 Files Modified

| File | Changes |
|------|---------|
| `InferenceBridge.kt` | Added streaming support, download progress, model info |
| `LiteRtInferenceBridge.kt` | ML Kit GenAI implementation (AI Edge Gallery pattern) |
| `InferenceViewModel.kt` | Integrated model download, streaming generation |
| `InferenceScreen.kt` | Download progress UI, model status display |
| `NetworkMonitor.kt` | Connectivity tracking for offline mode |
| `ExtractionWorker.kt` | WorkManager progress notifications |
| `ChatViewModel.kt` | RAG flow with real inference via InferenceBridge |

#### RAG Flow (VectorStore → InferenceBridge → RAG)

```
User Query
    │
    ▼
VectorStoreRepository.similaritySearch(query, topK=6)
    │  (finds relevant chunks from processed documents)
    ▼
Context Building (prompt + chunks)
    │
    ▼
InferenceBridge.streamGenerate(prompt, config)
    │  (Gemma 4 E2B-IT via ML Kit GenAI)
    ▼
Streaming Tokens → UI
    │
    ▼
Complete Response
```

#### What's Now Working ✅

- RAG chat with real Gemma 4 E2B-IT inference
- Streaming token display in Chat tab
- Model download with progress UI
- WorkManager notifications for extraction jobs
- Offline mode detection and banner

---

### v2.x Migration - Tab Implementation Complete (May 2026)

#### Architecture Updates

**Singleton Pattern for Database Access**
- `PenpalDatabase.kt` - Added `getInstance()` method for thread-safe database access
  - WorkManager compatibility via singleton pattern
  - Removed dependency on Hilt injection for workers

**Application-Level Dependency Management**
- `PenpalApplication.kt` - Refactored to use lazy initialization for dependencies:
  - `vectorStore: VectorStoreRepository`
  - `workerLauncher: WorkerLauncher`
  - `inferenceBridge: InferenceBridge`
  - `gson: Gson`
- Removed `AppDependencies` object in favor of Application singleton pattern

**MainScreen Integration**
- `MainScreen.kt` - Wired up all 3 tab screens with real ViewModels:
  - ProcessScreen → ProcessViewModel
  - ChatScreen → ChatViewModel
  - InferenceScreen → InferenceViewModel
- All tabs now show functional UI (no "coming soon" placeholders)

**ExtractionWorker Compatibility**
- `ExtractionWorker.kt` - Updated to use `PenpalDatabase.getInstance()` for WorkManager compatibility
- No longer relies on Hilt injection for database access

#### Build Configuration Updates

| Dependency | Old Version | New Version |
|------------|-------------|-------------|
| Kotlin | 2.0.10 | 2.0.21 |
| KSP | 2.0.10-1.0.24 | 2.0.21-1.0.28 |
| Hilt | 2.51.1 | 2.51.1 |
| Room | 2.6.1 | 2.6.1 |

**Room Entity Simplification**
- Changed enum fields to String in Room entities for KSP compatibility
- Simplified type converters

#### Current State (May 2026)

| Component | Status | Description |
|-----------|--------|-------------|
| BottomNavigation | ✅ Working | 3 tabs with proper navigation |
| Process Tab | ✅ Functional | Add URLs/files to extraction queue (stubbed parsing) |
| Chat Tab | ✅ Functional | RAG-enabled AI chat UI (stubbed responses) |
| Inference Tab | ✅ Functional | Load/unload Gemma model status display (stubbed) |
| Build | ✅ Passing | No compilation errors |
| App Launch | ✅ Stable | No crashes on startup |
| Manual DI | ✅ Implemented | Lazy properties on PenpalApplication |

#### Files Modified in This Update

| File | Changes |
|------|---------|
| `PenpalApplication.kt` | Added lazy dependencies, removed AppDependencies |
| `MainScreen.kt` | Wired up ViewModels for all tabs |
| `PenpalDatabase.kt` | Added singleton pattern with getInstance() |
| `ExtractionWorker.kt` | Uses PenpalDatabase.getInstance() |
| `WorkerLauncher.kt` | Job queue management |
| `Parsers.kt` | Stub implementations for all parser types |
| `DocumentParser.kt` | Parser interface |
| `ProcessViewModel.kt` | Job queue state management |
| `ProcessScreen.kt` | Functional UI |
| `libs.versions.toml` | Kotlin/KSP version updates |
| `build.gradle.kts` | Build configuration updates |

#### What's Working ✅

- BottomNavigation with 3 tabs (Process, Chat, Inference)
- Process tab: Add URLs/files to extraction queue
- Chat tab: Chat UI with RAG-enabled AI (stubbed responses)
- Inference tab: Load/unload Gemma model status display

#### What's Still Stubbed 🔧

- Chat responds with placeholder text (not real LLM)
- Process tab creates placeholder chunks (not real parsing)
- Inference tab shows stub status (not real LiteRT)

#### Phase 1: Foundation (✅ Complete: May 2026)
- Created Gradle multi-module structure with 5 core modules (core:ai, core:data, core:media, core:processing, core:ui)
- Converted from `settings.gradle` → `settings.gradle.kts` with Kotlin DSL throughout
- Kotlin 2.1.0 with Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose:2.1.0`)
- Hilt 2.54 with kapt for annotation processing
- Room 2.7.0
- AGP 9.0.0, Coroutines 1.8.1, WorkManager 2.9.1
- Stubbed unavailable LiteRT dependencies (`litertlm-android:0.1.0` removed)

#### Phase 2: Core AI (✅ Complete: May 2026)
- **core:ai module** created with:
  - `DispatcherModule.kt` (@IoDispatcher, @DefaultDispatcher, @InferenceDispatcher)
  - `InferenceBridge.kt` (interface for ML inference)
  - `LiteRtInferenceBridge.kt` (stub implementation - LiteRT unavailable)
  - `TextEmbedder.kt` (interface for text embeddings)
  - `MiniLmEmbedder.kt` (mock implementation with 384-dim embeddings)
  - `VectorStoreRepository.kt` (LRU cache, cosine similarity)
  - `AiModule.kt` (Hilt bindings)
  - `InferenceModule.kt` (Hilt bindings)
- **core:data module** updated with:
  - `NetworkModule.kt` (OkHttpClient provider)
  - Added `URL_CONTENT` and `CODE` to `ExtractionRule` enum
  - Room schema with 5 entities, 4 DAOs, type converters
- **core:processing module** created with:
  - `DocumentParser.kt` (interface)
  - `Parsers.kt` (PdfDocumentParser, AudioParser, ImageParser, UrlParser, CodeParser - stubs)
  - `ExtractionWorker.kt` (WorkManager worker with Hilt)
  - `WorkerLauncher.kt` (job queue management)
  - `ProcessingModule.kt` (Hilt DI)
- **core:ui module** partially implemented:
  - `Theme.kt` (Material 3 dark/light color schemes)

#### Phase 3: Feature Modules (✅ Complete: May 2026)

**MainScreen Navigation (app module)**
- Created `MainScreen.kt` with BottomNavigation and NavHost
- Created `MainComposeActivity.kt` - Compose-based Activity entry point
- Created `MainViewModel.kt` - ViewModel for MainScreen
- Added three tabs: Process, Chat, Inference
- Added hilt-navigation-compose dependency

**feature:process Module (✅ Complete)**
- `ProcessViewModel.kt` - ViewModel with job queue management
- `ProcessScreen.kt` - Composable UI with source type selector, input section, job list
- `ProcessModule.kt` - Hilt DI module
- Supports: PDF, AUDIO, IMAGE, URL, CODE source types
- Integrates with WorkerLauncher for background processing

**feature:inference Module (✅ Complete)**
- `InferenceViewModel.kt` - ViewModel for model loading/unloading
- `InferenceScreen.kt` - Composable UI with model status, action buttons
- `InferenceModule.kt` - Hilt DI module
- Integrates with InferenceBridge (LiteRtInferenceBridge)

**Build Configuration Updates**
- `settings.gradle.kts` - Added feature:process and feature:inference modules
- `app/build.gradle.kts` - Added navigation-compose, hilt-navigation-compose dependencies
- `AndroidManifest.xml` - Added MainComposeActivity
- core:ai module - Added compose plugin
- InferenceBridge - Added `release()` method to interface and implementation

**Chat Tab (✅ Complete)**
- Created `feature:chat` module with:
  - `ChatViewModel.kt` - RAG flow implementation (similarity search → context build → prompt → inference)
  - `ChatScreen.kt` - Material 3 UI with message bubbles, input field, context panel
  - `ChatState.kt` - UI state models (ChatMessage, ChatUiState, ChatEvent)
  - `ChatModule.kt` - Hilt DI bindings
- Dependencies: `core:ai` (VectorStoreRepository, InferenceBridge), `core:data` (ChunkEntity), `core:ui` (PenpalTheme)
- Features: Auto-scroll, collapsible context panel, loading indicator, error handling

| Tab | Status | Implementation |
|-----|--------|----------------|
| **Chat** | ✅ Complete | ChatViewModel with RAG flow, ChatScreen composable |
| **Process** | ✅ Complete | ProcessViewModel with Channel bridge pattern, ProcessScreen UI |
| **Inference** | ✅ Complete | InferenceViewModel, InferenceScreen with model status |
| **Notebooks** | 📋 Planned | Migrate MainActivity drawing to NotebookViewModel |
| **Organize** | 📋 Planned | Graph layout on DefaultDispatcher |
| **Settings** | 📋 Planned | DataStore Proto wiring |

#### Phase 4: Polish (📋 Planned)
- Connect MainComposeActivity as launcher or create navigation from NotebookSelectionActivity
- Implement real document parsing (PDFBox, Audio transcription)
- Implement real LLM inference integration
- WorkManager notifications for long-running extractions
- Offline mode banner
- Memory pressure handling tuning
- End-to-end flow testing (PDF → Chat → Organize)

## Version History

### v1.1.0 - Audio Processing & Server Infrastructure

#### New Components
- **GemmaServerClient** - Communication with Gemma server running on secondary device
- **InferenceService** - Background service for inference operations
- **InferenceEngineManager** - Manages multiple inference engine types
- **LlmInferenceEngine** - Local Gemma inference engine
- **ProcessingQueueManager** - Queue-based batch processing system
- **AudioPlayer** - Audio playback with seek functionality
- **AudioRecorder** - Recording with amplitude monitoring
- **AudioChunker** - Audio chunking for streaming transcription
- **GemmaTranscriber** - Transcription via remote Gemma server
- **RecordingsAdapter** - RecyclerView adapter for audio recordings list

#### Audio Evaluation System
- Session management with question types: SHORT_ANSWER, PRONUNCIATION, READING, LISTENING
- Evaluation panel UI with recording controls and score display
- Audio amplitude visualization during recording
- Color-coded feedback scores (green ≥70%, orange ≥50%, red <50%)
- Session export to JSON/CSV formats

#### UI Improvements
- Toolbar visible by default with clear draw/select mode switching
- Start in selection mode rather than brush mode
- Added text boxes for explicit text insertion
- Debug options (hidden by default): touch areas visualization
- Updated tool icons (hammer, lasso, etc.)

#### Bug Fixes
- Fixed zooming out issue (zoom constraints)
- Text button working better for images and PDFs

### v1.0.0 - Initial Release

#### Core Features
- **Handwriting Recognition**: On-device OCR using Gemma 4 E2B model via LiteRT-LM
- **Multi-page Notebooks**: Create notebooks with unlimited pages that scroll vertically
- **Whiteboard Mode**: Infinite canvas for brainstorming and sketching
- **Drawing Tools**: Brush, eraser, lasso selection, and item selection
- **Color Picker**: 20-color palette plus custom HSV picker with opacity control
- **Brush Size**: Adjustable from 5px to 100px

#### AI Features
- **Automatic Page Analysis**: Debounced recognition after 2 seconds of inactivity
- **Stroke Grouping**: AI groups strokes into words based on detected text
- **Text Overlay Toggle**: Switch between stroke view and recognized text view
- **Lasso Recognition**: Select items and trigger recognition on selection

#### Page Management
- **Auto Page Creation**: New pages created when scrolling past last page
- **Page Overview**: Grid view of all page thumbnails
- **Page Deletion**: Remove pages with automatic index shifting
- **Marker Navigation**: Jump between pages with star (*) markers

#### PDF Integration
- **PDF Import**: Create notebooks from PDF documents
- **Page Selection**: Choose specific pages to import
- **Text Extraction**: Import digital text from PDFs
- **Snippet Insertion**: Crop and insert PDF regions as images

#### Search
- **Global Search**: Search across all notebook pages
- **PDF Word Search**: Search individual words in imported PDFs
- **Result Navigation**: Navigate between matches with prev/next buttons

#### Export
- **PDF Export**: Export notebook as multi-page PDF
- **SVG Export**: Export as vector graphics
- **PNG Export**: Export as bitmap image
- **Whiteboard Export**: Export with content bounding box

#### Data Persistence
- **SVG Storage**: All drawings saved as SVG files
- **Thumbnail Generation**: Auto-generated PNG thumbnails
- **Notebook Metadata**: SharedPreferences-based notebook list
- **Autosave**: Automatic save after 2 seconds of inactivity

#### UI/UX
- **Dark Theme**: Full-screen immersive dark UI
- **Fullscreen Mode**: Hide navigation and status bars
- **Floating Toolbar**: Collapsible tool palette
- **Recognition Panel**: AI status and feedback display
- **Selection UI**: Touch-friendly handles for manipulation

### Technical Details

#### Dependencies
- LiteRT-LM (Gemma inference): com.google.ai.edge.litertlm:litertlm-android
- PDFBox-Android: com.tom-roush:pdfbox-android:2.0.27.0
- Coroutines: org.jetbrains.kotlinx:kotlinx-coroutines:1.7.3
- Gson: com.google.code.gson:gson:2.10.1
- Material Design: com.google.android.material:material:1.11.0

#### Build Configuration
- AGP: 9.1.1
- Kotlin: 2.2.10
- Compile SDK: 34
- Min SDK: 24
- Target SDK: 34

#### Architecture
- Activities: NotebookSelectionActivity, MainActivity, PdfSelectionActivity, PdfImportActivity
- Custom Views: DrawingView, SelectionFrameView
- Singleton: HandwritingRecognizer (shared across activities)
- Object Managers: NotebookManager, ModelManager, PdfHelper, SvgSerializer

#### Storage Structure
```
app_data/files/notebooks/
├── {NotebookName}_page_{n}.svg      # Drawing data
├── {NotebookName}_page_{n}_thumb.png # Thumbnail
└── ...
```

#### Model Management
- Auto-discovery of existing model files
- HuggingFace and Kaggle download sources
- Redirect handling for authentication
- Download progress tracking via DownloadManager