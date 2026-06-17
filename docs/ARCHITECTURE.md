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
| Phase 3.5: Tab Wiring | ✅ Complete | ViewModels connected, MainScreen with 3 tabs (Notebooks, Think, Settings) + Chat FAB |
| Phase 4: Polish | ✅ Complete | WorkManager notifications, offline mode, network monitoring |
| Phase 4.5: Stacks | ✅ Complete | Think tab with block-based editor, GraphNodeCanvas, DrawingCanvas |
| Phase 4.6: Stacks Enhanced | ✅ Complete | Image picker, Coil integration, home navigation |
| Phase 5: Real Parsers & Chat Persistence | ✅ Complete | Document parsing, vector persistence, chat enhancements |
| Phase 5.5: Streaming Token Filter | ✅ Complete | Trie-based filter, Flow-based inference, 120s timeout |
| Phase 5.6: Text Structure Fix | ✅ Complete | Smart spacing, whitespace handling, lastEmittedChar tracking |
| Phase 5.7: Structured Message Parts | ✅ Complete | Opencode-inspired parts architecture with rich UI rendering |
| Phase 5.8: Chat Model Response Fix | ✅ Complete | getContents() fix, conversation history in prompts, FAB navigation |
| Phase 5.9: UI Polish & Chat Improvements | ✅ Complete | FAB positioning, pinned TopBar, AI message alignment |
| Phase 6.0: Agent Framework | ✅ Complete | ToolRegistry, ToolExecutor, BuiltinTools, execution loop for multi-step inference |

### Key Inference Components

| Component | Implementation | Description |
|-----------|----------------|-------------|
| **LiteRtInferenceBridge** | `LiteRtInferenceBridge` | LiteRT-LM Engine API pattern with `getContents()` for text extraction |
| **ChatViewModel** | `ChatViewModel` | Multi-turn conversation support with full message history in prompts |
| **Engine** | `com.google.ai.edge.litertlm.Engine` | GPU/CPU backend fallback via LiteRT-LM library |
| **Model** | Gemma 4 E2B-IT | Google's efficient on-device LLM |
| **API** | LiteRT-LM | Direct on-device inference via Engine class |
| **Streaming** | `Flow<String>` / `Flow<List<MessagePart>>` | Flow-based streaming (primary) + callback fallback |
| **Token Filter** | `StreamingTokenFilter` | Trie-based special token removal with mode transitions |
| **Special Tokens** | `GemmaSpecialTokens` | Definitions for turn, tool, thinking, media, sequence tokens |
| **Message Parts** | `MessagePart` sealed class | Structured parts: Text, Reasoning, ToolCall, ToolResponse, Image, Audio |
| **Part Aggregator** | `MessagePartAggregator` | Builds MessageParts from streaming token filter transitions |
| **Tool Registry** | `ToolRegistry` | ✅ Implemented - registers available tools with JSON schemas |
| **Tool Executor** | `ToolExecutor` | ✅ Implemented - executes tool calls and returns results |
| **Built-in Tools** | `BuiltinTools.kt` | ✅ Implemented - SearchKnowledge, ReadStack, GetHistory, ListStacks |
| **Web Search Tools** | `WebSearchTools.kt` | ✅ Implemented - web search tool integration |
| **Model Manager** | `ModelManager` | HuggingFace/Kaggle download management |
| **Text Embedder** | `OnnxMiniLmEmbedder` | ONNX Runtime with mean pooling + L2 normalization (fallback to `MiniLmEmbedder` mock) |
| **Model Source** | HuggingFace | `litert-community/gemma-4-E2B-it-litert-lm` (~2.6 GB) |
| **Timeout Guard** | `AtomicBoolean` + 120s | Prevents hung inference sessions |
| **Markdown Render** | `MarkdownText.kt` | Lightweight markdown renderer for chat messages |
| **Model Status** | `ModelStatusIndicator` | UI component showing: ON, Loading..., Unloading..., Downloading..., DL'd, ERR, OFF |

### AI Inference Architecture (LiteRT-LM Engine API)

The inference system uses the real LiteRT-LM Engine API for on-device LLM inference. The architecture supports **Flow-based streaming** with **trie-based token filtering**:

```
┌────────────────────────────────────────────────────────────────────┐
│                      Inference Layer (core:ai)                       │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                      InferenceBridge                          │  │
│  │  (Interface: runInference, runInferenceFlow, runInferenceFlowParts, │
│  │   runInferenceWithImageFlow, runInferenceWithImageFlowParts,  │  │
│  │   runInferenceWithAudio, runInferenceWithAudioFlow, etc.)    │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │                  LiteRtInferenceBridge                        │  │
│  │  • Engine/Conversation lifecycle                              │  │
│  │  • Flow<String> for streaming (primary)                       │  │
│  │  • MessageCallback for legacy streaming                       │  │
│  │  • StreamingTokenFilter (trie-based special token removal)    │  │
│  │  • 120s timeout with AtomicBoolean guards                     │  │
│  │  • Image/Audio content support (Content.ImageBytes/AudioBytes)│  │
│  │  • GPU/CPU backend fallback                                    │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │                    LmEngineManager                             │  │
│  │  • getEngine(modelPath, config, forceReload) - Mutex-guarded  │  │
│  │  • GPU/CPU backend fallback via Backend.GPU()/Backend.CPU()   │  │
│  │  • StateFlow-based: isInitialized, isLoading, error, modelPath│  │
│  │  • Engine lifecycle management (create/release)                │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │              com.google.ai.edge.litertlm.Engine               │  │
│  │  • EngineConfig(modelPath, backend, visionBackend, maxTokens) │  │
│  │  • createConversation(ConversationConfig) -> Conversation     │  │
│  │  • Conversation.sendMessageAsync() -> Flow<Message>           │  │
│  │  • renderMessageIntoString() for text extraction              │  │
│  └────────────────────────────┬─────────────────────────────────┘  │
│                               │                                     │
│  ┌────────────────────────────▼─────────────────────────────────┐  │
│  │                    ModelManager                                │  │
│  │  • startDownloadAsync() - HuggingFace/Kaggle download         │  │
│  │  • queryDownload() - Progress polling                          │  │
│  │  • findExistingModel(), saveModelPath(), listAvailableModels()│  │
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
│  │               StreamingTokenFilter                             │  │
│  │  • TokenTrie for O(m) special token matching                  │  │
│  │  • Character-by-character processing with boundary buffering  │  │
│  │  • Removes: turn, tool, thinking, media, sequence tokens      │  │
│  │  • Mode transition tracking for structured parts              │  │
│  │  • Smart spacing: lastEmittedChar, word-char detection        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │            MessagePart Architecture                             │  │
│  │  • MessagePart sealed class: Text, Reasoning, ToolCall, etc.  │  │
│  │  • MessagePartAggregator builds parts from stream transitions │  │
│  │  • InferenceBridge.runInferenceFlowParts(): Flow<List<...>>   │  │
│  │  • ChatViewModel collects parts, ChatScreen renders with UI   │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
```

#### Flow-Based Streaming (Primary)

```kotlin
// ChatViewModel uses Flow-based inference with structured parts
inferenceBridge.runInferenceFlowParts(prompt)
    .catch { error -> /* handle error */ }
    .onCompletion { /* save to DB, cleanup */ }
    .collect { parts ->
        updateLastAssistantMessage(parts)
    }
```

The Flow-based approach:
1. `conversation.sendMessageAsync(content)` returns `Flow<Message>`
2. Each `Message` is converted to text via `message.getContent()` reflection call
3. `StreamingTokenFilter.appendWithTransitions(chunk)` removes special tokens and emits mode transitions
4. `MessagePartAggregator` builds immutable `MessagePart` objects from transitions
5. `Flow<List<MessagePart>>` is collected by `ChatViewModel` and rendered by `ChatScreen`
6. `withTimeout(120_000)` prevents hung inference

**Two streaming APIs are available:**
- `runInferenceFlow(): Flow<String>` — Plain text accumulation (legacy compatibility)
- `runInferenceFlowParts(): Flow<List<MessagePart>>` — Structured parts for rich UI rendering

#### MessageCallback Interface (Legacy)

```kotlin
// com.google.ai.edge.litertlm.MessageCallback
// Callback-based streaming still available via runInference()
```

#### StreamingTokenFilter

```kotlin
class StreamingTokenFilter(
    specialTokens: Set<String> = GemmaSpecialTokens.ALL_USER_FACING
) {
    fun append(chunk: String): FilteredChunk
    fun appendWithTransitions(chunk: String): FilteredChunkWithTransitions
    fun flush(): FilteredChunk
    fun clear()
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
    const val STRING_DELIMITER         // <|"|>
    val ALL_USER_FACING: Set<String>   // Union of all above
}
```

### MessagePart Architecture

```
core:ai/messaging/
├── MessagePart.kt              # Sealed class: TextPart, ReasoningPart, ToolCallPart, ToolResponsePart, ImagePart, AudioPart
├── MessagePartAggregator.kt    # Builds MessageParts from streaming transitions
└── ContentMode.kt              # REGULAR, THINKING, TOOL_CALL, TOOL_RESPONSE, IMAGE, AUDIO, SYSTEM + FilteredChunk types
```

#### RAG Flow (Chat → VectorStore → Inference)

```
User Query in Chat
        │
        ▼
VectorStoreRepositoryImpl.similaritySearch(query, topK=6)
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
runInferenceFlowParts()  Show "Model not ready" message
    │
    ▼
Flow.collect() → StreamingTokenFilter → MessagePartAggregator → List<MessagePart>
```

### Build Configuration (Current)

| Component | Version |
|-----------|---------|
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Hilt (plugin only, not used in code) | 2.52 |
| Room | 2.6.1 |
| Compose BOM | 2024.06.00 |
| AGP | 9.1.1 |
| Coroutines | 1.8.1 |
| WorkManager | 2.9.1 |
| Gson | 2.11.0 |
| PdfBox (Android) | 2.0.27.0 |
| OkHttp | 4.12.0 |
| Jsoup | 1.17.2 |
| ML Kit Text Recognition | 16.0.1 |
| ONNX Runtime | 1.19.0 |
| Coil Compose | 2.5.0 |
| AndroidX WebKit | 1.10.0 |
| LiteRT-LM | latest.release |

### Application Architecture

**Note:** The project uses manual dependency injection via `PenpalApplication` lazy singletons, not Hilt. No `@HiltViewModel`, `@Module`, `@Singleton`, `@Inject`, or `@Qualifier` annotations exist in the codebase. ViewModels are instantiated manually in `MainScreen.kt` using `remember { ... }`.

```
PenpalApplication (Singleton)
├── lazy vectorStore: VectorStoreRepositoryImpl
│     (OnnxMiniLmEmbedder with MiniLmEmbedder fallback)
├── lazy workerLauncher: WorkerLauncher
├── lazy inferenceBridge: LiteRtInferenceBridge
├── lazy gson: Gson
├── gemmaServer: GemmaServerClient (lateinit)
└── notificationHelper: NotificationHelper (lateinit)

PenpalDatabase (Singleton via getInstance())
├── chunkDao()
├── extractionJobDao()
├── chatMessageDao()
├── chatConversationDao()
├── graphDao()
├── graphTokenDao()
├── stackDao()
├── notebookDao()
├── notebookSheetDao()
└── fallbackToDestructiveMigration()
```

### Tab Implementation

| Route | Icon | Screen | Bottom Nav | Backend |
|-------|------|--------|------------|---------|
| Notebooks | Book | NotebooksScreen | ✅ Tab | — |
| Think (Stacks) | AutoAwesome | StackListScreen → StackScreen | ✅ Tab | Room persistence + auto-processing |
| Settings | Settings | SettingsScreen | ✅ Tab | Model download, inference status |
| Chat | Chat | ChatScreen | ❌ (FAB) | RAG via InferenceBridge, structured MessageParts |

**Bottom navigation**: 3 tabs — **Notebooks**, **Think** (Stacks), **Settings**. **Chat** is a separate NavHost route accessible via a FAB shown on the Settings tab. The FAB is hidden on Stacks and Notebooks tabs (which have their own FABs).

**Navigation Behavior:**
- Chat FAB shows on the Settings tab
- FAB is hidden on Stacks/Notebooks tabs (they have their own FABs)
- FAB hides when user is in Chat screen
- FAB reappears after exiting Chat (via X button or tab click)
- Clicking a tab while in Chat triggers `popBackStack()` to close chat

---

## Module Architecture

### High-Level Structure

```
penpal/
├── app/                           # Shell application, NavHost, MainScreen
│   ├── MainScreen.kt              # Compose NavHost + BottomNavigation (Notebooks, Think, Settings) + Chat FAB
│   ├── MainComposeActivity.kt     # Compose-based Activity entry point (Launcher)
│   ├── PenpalApplication.kt       # Manual DI singleton
│   ├── NotebooksScreen.kt         # Notebook management screen
│   ├── NotebookAdapter.kt         # Notebook list adapter
│   ├── NotebookManager.kt         # Notebook CRUD operations
│   ├── Notebook.kt               # Notebook data model
│   └── (legacy activities: MainActivity, StackSelectionActivity, etc.)
├── core/
│   ├── ai/                        # ✅ Implemented (27 files in subdirectories)
│   │   ├── inference/
│   │   │   ├── InferenceBridge.kt           # ML inference interface
│   │   │   ├── model/ModelTypes.kt           # ModelStatus, DownloadProgress, InferenceConfig, DetectedItem
│   │   │   └── implementation/
│   │   │       ├── LiteRtInferenceBridge.kt  # LiteRT-LM Engine API implementation
│   │   │       ├── OllamaInferenceBridge.kt  # Remote inference fallback
│   │   │       └── LmEngineManager.kt        # Engine lifecycle, GPU/CPU fallback, mutex-guarded
│   │   ├── embedding/
│   │   │   ├── TextEmbedder.kt               # Text embedding interface
│   │   │   ├── OnnxMiniLmEmbedder.kt         # ONNX Runtime embedder with mean pooling + L2 norm
│   │   │   └── WordPieceTokenizer.kt         # BERT/MiniLM-compatible tokenizer
│   │   ├── vectorstore/
│   │   │   ├── VectorStoreRepository.kt      # Interface: embed(), similaritySearch()
│   │   │   ├── VectorStoreImpl.kt            # LRU cache + cosine similarity (MiniLmEmbedder mock included)
│   │   │   └── VectorStoreProvider.kt        # Cross-module singleton access
│   │   ├── model/
│   │   │   ├── ModelManager.kt               # HuggingFace/Kaggle download management
│   │   │   ├── ModelDownloadManager.kt       # WorkManager-based download orchestration
│   │   │   └── GgufConverter.kt              # GGUF to LiteRT-LM conversion utility
│   │   ├── messaging/
│   │   │   ├── MessagePart.kt                # Structured message parts (Text, Reasoning, ToolCall, ToolResponse, Image, Audio)
│   │   │   ├── MessagePartAggregator.kt      # Builds parts from streaming transitions
│   │   │   └── ContentMode.kt                # ContentMode enum + FilteredChunk types
│   │   ├── tokenization/
│   │   │   ├── StreamingTokenFilter.kt       # Trie-based special token filtering with mode transitions
│   │   │   └── GemmaSpecialTokens.kt         # Gemma 4 control token definitions
│   │   ├── tools/
│   │   │   ├── Tool.kt                       # Tool interface definitions
│   │   │   ├── ToolSchema.kt                 # Tool parameter schema definitions
│   │   │   ├── ToolRegistry.kt               # Registers available tools with JSON schemas
│   │   │   ├── ToolExecutor.kt               # Executes tool calls and returns results
│   │   │   ├── BuiltinTools.kt               # Built-in tools: SearchKnowledge, ReadStack, etc.
│   │   │   └── web/
│   │   │       ├── WebSearchTools.kt         # Web search tool integration
│   │   │       └── RawChunk.kt               # Raw chunk data class
│   │   └── ollama/
│   │       ├── OllamaApiService.kt           # REST API client for Ollama
│   │       └── OllamaModels.kt               # Data models for Ollama API responses
│   ├── data/                      # ✅ Implemented (22 files in subdirectories)
│   │   ├── PenpalDatabase.kt      # Room database v9 (singleton via getInstance())
│   │   ├── chat/
│   │   │   ├── ChatMessageEntity.kt
│   │   │   ├── ChatMessageDao.kt
│   │   │   ├── ChatConversationEntity.kt
│   │   │   └── ChatConversationDao.kt
│   │   ├── knowledge/
│   │   │   ├── ChunkEntity.kt
│   │   │   └── ChunkDao.kt
│   │   ├── processing/
│   │   │   ├── ExtractionJobEntity.kt
│   │   │   └── ExtractionJobDao.kt
│   │   ├── graph/
│   │   │   ├── GraphNodeEntity.kt
│   │   │   ├── GraphEdgeEntity.kt
│   │   │   ├── GraphTokenEntity.kt
│   │   │   ├── GraphDao.kt
│   │   │   └── GraphTokenDao.kt
│   │   ├── stack/
│   │   │   ├── StackEntity.kt
│   │   │   └── StackDao.kt
│   │   └── notebook/
│   │       ├── NotebookEntity.kt
│   │       ├── NotebookDao.kt
│   │       ├── NotebookType.kt
│   │       ├── NotebookMapper.kt
│   │       └── sheet/
│   │           ├── NotebookSheetEntity.kt
│   │           └── NotebookSheetDao.kt
│   ├── media/                     # ✅ Implemented (8 files)
│   │   ├── AudioRecorder.kt
│   │   ├── WavConstants.kt
│   │   ├── analysis/
│   │   │   ├── AudioAnalyzer.kt
│   │   │   └── AudioPlayer.kt
│   │   ├── capture/
│   │   │   └── AudioChunker.kt
│   │   └── serialization/
│   │       ├── SvgSerializer.kt
│   │       ├── SvgResult.kt
│   │       └── SvgData.kt
│   ├── processing/                # ✅ Implemented (8 files in subdirectories)
│   │   ├── document/
│   │   │   ├── DocumentParser.kt    # Parser interface
│   │   │   └── Parsers.kt           # Real parsers: PDF, Image OCR, Audio, URL, Code
│   │   ├── worker/
│   │   │   ├── ExtractionWorker.kt  # WorkManager worker with manual DI
│   │   │   └── WorkerLauncher.kt    # Job queue
│   │   ├── notification/
│   │   │   └── NotificationHelper.kt # WorkManager notifications
│   │   ├── network/
│   │   │   └── NetworkMonitor.kt    # Connectivity tracking
│   │   └── speech/
│   │       ├── WhisperTranscriber.kt
│   │       └── SpeechRecognizer.kt
│   └── ui/                        # ✅ Implemented (5 files)
│       ├── theme/Theme.kt          # Material 3 dark/light
│       ├── canvas/ActiveTool.kt    # Drawing tool types
│       ├── canvas/BackgroundType.kt
│       ├── component/StatusIndicator.kt  # Model status UI component
│       └── picker/PickerDialog.kt
├── feature/                       # ✅ Phase 3+ Complete
│   ├── chat/                      # 3 files: ChatScreen, ChatViewModel, MarkdownText
│   ├── process/                   # 2 files: ProcessScreen, ProcessViewModel
│   ├── inference/                 # 2 files: InferenceScreen, InferenceViewModel
│   ├── stacks/                    # 9 files: StackModels, StackScreen, StackListScreen, 2 ViewModels, 4 components
│   └── settings/                  # 3 files: SettingsScreen, SettingsViewModel, ModelDownloadBottomSheet
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
core:media ──> (standalone media utilities)
feature:chat ──> core:ai, core:data, core:processing, core:ui
feature:stacks ──> core:ai, core:data, core:media, core:processing, core:ui
feature:process ──> core:processing, core:ai, core:data, core:ui
feature:inference ──> core:ai, core:data, core:ui
feature:settings ──> core:ai, core:data, core:ui
```

**Key Architectural Principle**: `InferenceBridge` in `core:ai` is the central dependency. All AI-powered features flow through this interface to the Gemma 4 E2B-IT model via LiteRT-LM.

---

## Core Module Details

### core:ai

Handles AI inference and text embedding. **This is the central architectural module.**

#### Key Files (by subdirectory)

```
core:ai/src/main/java/com/penpal/core/ai/
├── inference/
│   ├── InferenceBridge.kt              # Interface: runInference(), runInferenceFlow(), runInferenceFlowParts(), etc.
│   ├── model/ModelTypes.kt             # ModelStatus, DownloadProgress, InferenceConfig, DetectedItem
│   └── implementation/
│       ├── LiteRtInferenceBridge.kt     # LiteRT-LM Engine API implementation (Context constructor)
│       ├── OllamaInferenceBridge.kt     # Remote inference via Ollama REST API
│       └── LmEngineManager.kt           # Mutex-guarded engine lifecycle, GPU/CPU fallback
├── embedding/
│   ├── TextEmbedder.kt                  # Interface: dimension, embed()
│   ├── OnnxMiniLmEmbedder.kt            # ONNX Runtime: mean pooling, L2 normalization
│   └── WordPieceTokenizer.kt            # BERT/MiniLM-compatible tokenizer
├── vectorstore/
│   ├── VectorStoreRepository.kt         # Interface: embed(), similaritySearch(), getChunksForSource(), etc.
│   ├── VectorStoreImpl.kt               # LRU cache (10,000), cosine similarity (MiniLmEmbedder mock included)
│   └── VectorStoreProvider.kt           # Static provider for cross-module access
├── model/
│   ├── ModelManager.kt                  # HuggingFace/Kaggle download management
│   ├── ModelDownloadManager.kt          # Download progress tracking
│   └── GgufConverter.kt                 # GGUF to LiteRT-LM conversion utility
├── messaging/
│   ├── MessagePart.kt                   # Sealed class: TextPart, ReasoningPart, ToolCallPart, ToolResponsePart, ImagePart, AudioPart
│   ├── MessagePartAggregator.kt         # Builds MessageParts from streaming transitions
│   └── ContentMode.kt                   # REGULAR, THINKING, TOOL_CALL, TOOL_RESPONSE, IMAGE, AUDIO, SYSTEM + FilteredChunk types
├── tokenization/
│   ├── StreamingTokenFilter.kt          # Trie-based special token filtering with mode transitions
│   └── GemmaSpecialTokens.kt            # Gemma 4 control token definitions (TURN, TOOL, THINKING, MEDIA, SEQUENCE, STRING_DELIMITER)
├── tools/
│   ├── Tool.kt                          # Tool interface definitions
│   ├── ToolSchema.kt                    # Tool parameter schema definitions
│   ├── ToolRegistry.kt                  # Tool registration and lookup
│   ├── ToolExecutor.kt                  # Tool execution orchestration
│   ├── BuiltinTools.kt                  # Built-in: SearchKnowledge, ReadStack, GetHistory, ListStacks
│   └── web/
│       ├── WebSearchTools.kt            # Web search tool integration
│       └── RawChunk.kt                  # Raw chunk data class (id, sourceId, text, position)
└── ollama/
    ├── OllamaApiService.kt              # REST API client for Ollama
    └── OllamaModels.kt                  # Data models for Ollama API responses
```

#### InferenceBridge Interface

```kotlin
interface InferenceBridge {
    val isReady: StateFlow<Boolean>
    val isProcessing: StateFlow<Boolean>
    val isDownloading: StateFlow<Boolean>
    val isUnloading: StateFlow<Boolean>
    val downloadProgress: StateFlow<DownloadProgress>
    val modelStatus: StateFlow<ModelStatus>

    // Lifecycle
    fun initialize(context: Context, modelName: String, backend: String?, onDone: (String) -> Unit)
    suspend fun isModelDownloaded(): Boolean
    fun downloadModel(context: Context, modelName: String, coroutineScope: CoroutineScope, ...)
    fun downloadModel(listener: DownloadProgressListener)
    fun deleteModel()
    suspend fun listAvailableModels(context: Context): List<ModelManager.ModelInfo>
    fun loadModel(context: Context, modelPath: String, backend: String?, onDone: (String) -> Unit)
    fun deleteModel(modelPath: String)

    // Callback-based inference
    fun runInference(input: String, resultListener, cleanUpListener, onError)
    fun runInferenceWithImage(input: String, image: Bitmap, resultListener, cleanUpListener, onError)
    fun runInferenceWithAudio(input: String, audioData: FloatArray, resultListener, cleanUpListener, onError)

    // Flow-based streaming (primary)
    fun runInferenceFlow(input: String): Flow<String>
    fun runInferenceFlowParts(input: String): Flow<List<MessagePart>>
    fun runInferenceWithImageFlow(input: String, image: Bitmap): Flow<String>
    fun runInferenceWithImageFlowParts(input: String, image: Bitmap): Flow<List<MessagePart>>
    fun runInferenceWithAudioFlow(input: String, audioData: FloatArray): Flow<String>
    fun runInferenceWithAudioFlowParts(input: String, audioData: FloatArray): Flow<List<MessagePart>>

    // Control
    fun resetConversation()
    fun stopInference()
    fun release()
    fun unloadModel()
}
```

#### ModelTypes (core:ai/inference/model/ModelTypes.kt)

```kotlin
enum class ModelStatus {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, LOADING, READY, ERROR
}

data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    val percentage: Int get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
}

data class InferenceConfig(
    val temperature: Float = 0.3f,
    val topK: Int = 16,
    val topP: Float = 0.95f,
    val maxTokens: Int = 4096,
    val prompt: String = "Analyze the handwriting in this image."
)

data class DetectedItem(
    val text: String,
    val boxYmin: Float, val boxXmin: Float,
    val boxYmax: Float, val boxXmax: Float
)
```

#### LiteRtInferenceBridge (LiteRT-LM Engine API)

The `LiteRtInferenceBridge` uses the LiteRT-LM Engine API with GPU/CPU backend fallback. It takes `Context` as a constructor parameter (no Hilt injection):

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtInferenceBridge(private val context: Context) : InferenceBridge {

    private var engine: com.google.ai.edge.litertlm.Engine? = null
    private var conversation: Conversation? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // StateFlows: isReady, isProcessing, isDownloading, isUnloading, downloadProgress, modelStatus

    fun initialize(context, modelName, backend, onDone) {
        // Finds model file, calls initializeEngine()
    }

    private suspend fun initializeEngine(modelPath: String, preferredBackend: String? = null): Boolean {
        // GPU/CPU backend fallback via Backend.GPU()/Backend.CPU()
        // EngineConfig(modelPath, backend, visionBackend, maxNumTokens=8192)
        // Engine(engineConfig).initialize()
        // createConversation(ConversationConfig(samplerConfig(topK=64, topP=0.95, temp=0.7)))
    }

    fun runInference(input, resultListener, cleanUpListener, onError) {
        // Uses AtomicBoolean for 120s timeout guard
        // conv.sendMessageAsync(Contents.of(Content.Text(input)), MessageCallback)
    }

    fun runInferenceFlow(input: String): Flow<String> = flow {
        // conv.sendMessageAsync(content).collect { message -> ... }
        // withTimeout(120_000) { ... }
    }.flowOn(Dispatchers.IO)

    fun runInferenceFlowParts(input: String): Flow<List<MessagePart>> = flow {
        // StreamingTokenFilter.appendWithTransitions()
        // MessagePartAggregator.processChunk() / finalize()
    }.flowOn(Dispatchers.IO)
}
```

Engine management uses `Engine(EngineConfig).initialize()` directly (not `Engine.create()`). The `LmEngineManager` class exists as a standalone utility but `LiteRtInferenceBridge` manages its own engine lifecycle inline.

#### LmEngineManager

```kotlin
class LmEngineManager(private val context: Context) {
    private val mutex = Mutex()

    data class Config(
        val temperature: Float = 0.7f,
        val topK: Int = 64,
        val topP: Float = 0.95f,
        val maxTokens: Int = 4096,
        val useGpu: Boolean = true
    )

    suspend fun getEngine(modelPath: String, config: Config, forceReload: Boolean): Boolean
    fun getEngine(): Engine?
    fun getCurrentModelPath(): String?
    suspend fun releaseEngine()
}
```

#### VectorStoreRepository

```kotlin
interface VectorStoreRepository {
    suspend fun embed(chunks: List<RawChunk>)
    suspend fun similaritySearch(query: String, topK: Int): List<ChunkEntity>
    suspend fun getChunksForSource(sourceId: String): List<ChunkEntity>
    suspend fun deleteChunksForSource(sourceId: String)
    suspend fun getAllSourceIds(): List<String>
    suspend fun getCachedChunkCount(): Int
    suspend fun hasCachedData(): Boolean
}

// Implementation in VectorStoreImpl.kt:
// VectorStoreRepositoryImpl(chunkDao, textEmbedder, gson)
// - LRU cache (LinkedHashMap, max 10,000)
// - Cosine similarity for vector comparison
// - Embeddings stored as JSON in Room
// - MiniLmEmbedder (mock, 384-dim) included as fallback
```

---

### core:data

Handles persistence via Room. 22 files organized in entity/DAO pairs by domain.

#### PenpalDatabase (Singleton)

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
        GraphTokenEntity::class,
        NotebookEntity::class,
        NotebookSheetEntity::class,
    ],
    version = 9,
    exportSchema = false
)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun extractionJobDao(): ExtractionJobDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatConversationDao(): ChatConversationDao
    abstract fun graphDao(): GraphDao
    abstract fun graphTokenDao(): GraphTokenDao
    abstract fun stackDao(): StackDao
    abstract fun notebookDao(): NotebookDao
    abstract fun notebookSheetDao(): NotebookSheetDao

    companion object {
        @Volatile private var INSTANCE: PenpalDatabase? = null

        fun getInstance(context: Context): PenpalDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, PenpalDatabase::class.java, "penpal_database")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
```

---

### core:processing

Handles document parsing and background extraction. 8 files in organized subdirectories.

```
core:processing/src/main/java/com/penpal/core/processing/
├── document/
│   ├── DocumentParser.kt    # Interface: parse(uri, rule) -> List<RawChunk>
│   └── Parsers.kt           # Real implementations:
│                             #   PdfDocumentParser (PdfBox text extraction)
│                             #   ImageParser (ML Kit Text Recognition OCR)
│                             #   AudioParser (metadata, placeholder for transcription)
│                             #   UrlParser (Jsoup HTML parsing)
│                             #   CodeParser (language-aware: Kotlin, Java, Python, JS/TS, Go, Rust)
│                             #   ParserFactory (MIME type routing)
├── worker/
│   ├── ExtractionWorker.kt  # WorkManager worker with real parsing + vector persistence
│   └── WorkerLauncher.kt    # Job queue management
├── notification/
│   └── NotificationHelper.kt
├── network/
│   └── NetworkMonitor.kt
└── speech/
    ├── WhisperTranscriber.kt
    └── SpeechRecognizer.kt
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
```

#### ExtractionWorker (Manual DI)

```kotlin
class ExtractionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val extractionJobDao = PenpalDatabase.getInstance(context).extractionJobDao()
    private val notificationHelper = NotificationHelper(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val agentPrompt = inputData.getString(KEY_AGENT_PROMPT)?.takeIf { it.isNotBlank() }

        val job = extractionJobDao.getJob(jobId) ?: return@withContext Result.failure()

        // Parse document, embed chunks, update status
        parserFactory.createParser(mimeType).parse(uri, rule)
        VectorStoreProvider.instance?.embed(chunks)
        extractionJobDao.updateStatus(jobId, "DONE")
    }
}
```

No Hilt annotations — uses `PenpalDatabase.getInstance()` and `VectorStoreProvider.instance` for dependency access.

---

## feature:stacks Module

The Stacks module provides a block-based editor for creating rich documents with text, images, drawings, graphs, LaTeX, and media processing. This implements the "Think" tab in the bottom navigation.

### Module Structure

```
feature:stacks/src/main/java/com/penpal/feature/stacks/
├── model/
│   └── StackModels.kt         # Block sealed class, GraphNode, GraphEdge, StackEvent, StackScreenEvent
├── viewmodel/
│   ├── StackEditorViewModel.kt # Editor state management, block CRUD, AI processing
│   └── StackListViewModel.kt   # Stack list management
├── screen/
│   ├── StackScreen.kt          # Main editor composable
│   └── StackListScreen.kt      # Stack list with creation/selection
└── component/
    ├── BlockRenderer.kt        # Block type rendering
    ├── GraphNodeCanvas.kt      # Node-based graph editor
    ├── DrawingCanvas.kt        # Touch-based drawing
    └── StackPicker.kt          # Stack selection picker component
```

**Dependencies:**
- `io.coil-kt:coil-compose:2.5.0` for async image loading in ImageBlockContent
- `androidx.webkit:webkit:1.10.0` for WebView-based LaTeX rendering
- `com.google.code.gson:gson` for JSON serialization of blocks

### Block Model (StackModels.kt)

```kotlin
sealed class Block {
    abstract val id: String

    data class TextBlock(id, content, isEditing) : Block()
    data class ImageBlock(id, uri, caption, isEditing) : Block()
    data class DrawingBlock(id, pathData, width, height) : Block()
    data class LatexBlock(id, expression) : Block()
    data class GraphBlock(id, graphId, nodes, edges) : Block()
    data class EmbedBlock(id, sourceId, preview, type) : Block()
    data class ProcessBlock(id, sourceUri, mediaType, status, progress, extractedText, errorMessage, showParsedContent) : Block()
}

enum class EmbedType { LINK, AUDIO, VIDEO, FILE }
enum class MediaType { IMAGE, AUDIO, VIDEO, TEXT }
enum class ProcessStatus { PENDING, QUEUED, RUNNING, DONE, ERROR }
```

### StackEvent (StackModels.kt)

```kotlin
sealed class StackEvent {
    data class AddBlock(val block: Block, val afterBlockId: String? = null)
    data class RemoveBlock(val blockId: String)
    data class MoveBlock(val blockId: String, val newIndex: Int)
    data class UpdateBlock(val block: Block)
    data class SelectBlock(val blockId: String?)
    data class UpdateGraphNode(val node: GraphNode)
    data class AddGraphEdge(val edge: GraphEdge)
    data class UpdateDocumentTitle(val title: String)
    data class UpdateSystemPrompt(val prompt: String)
    data class UpdateAgentPrompt(val prompt: String)
    data class ToggleProcessView(val blockId: String)
    object SaveDocument
    object LoadDocument
    object DeleteDocument
    data class SetImageUri(val blockId: String, val uri: Uri)
    data class AddProcessBlock(val mediaType: MediaType, val afterBlockId: String? = null)
    data class UpdateProcessBlockStatus(val blockId: String, val status: ProcessStatus, ...)
    data class ReprocessBlock(val blockId: String)
    data class ProcessBlockWithAI(val blockId: String)
}
```

### GraphNodeCanvas

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
)
```

**Interactions:** Drag (detectDragGestures), Pan (detectTransformGestures with two fingers), Zoom (0.25x - 4x), Double-tap (creates node), Long-press (context menu), Edge creation.

**Rendering:** Grid in canvas space, edges as curved Path with arrow heads, nodes as colored circles with labels. Color-coded by node type (DEFAULT=indigo, CONCEPT=emerald, TOOL=amber, DATA=blue, STARRED=red).

### DrawingCanvas

```kotlin
@Composable
fun DrawingCanvas(
    pathData: String,
    onPathDataChanged: (String) -> Unit,
    strokeColor: Color = Color.Black,
    strokeWidth: Float = 4f,
    backgroundColor: Color = Color.White
)
```

**Features:** 8-color palette, eraser mode (3x stroke, white), undo (paths.dropLast(1)), clear, auto-hiding toolbar (5s).

**Path Serialization:** `"isEraser:colorHex:strokeWidth:points..."` format.

### StackEditorViewModel

```kotlin
class StackEditorViewModel(
    private val context: Context,
    private val stackDao: StackDao,
    private val workerLauncher: WorkerLauncher,
    private val inferenceBridge: InferenceBridge,
    private val onLoadModel: () -> Unit = {}
) : ViewModel() {

    private val _uiState = MutableStateFlow(StackEditorState())
    val uiState: StateFlow<StackEditorState> = _uiState.asStateFlow()

    fun onEvent(event: StackEvent) { /* ... */ }
    fun loadFromDatabase(stackId: String) { /* ... */ }
}
```

No Hilt injection — ViewModel is created manually in `MainScreen.kt` with `remember { StackEditorViewModel(...) }`.

---

## Threading Model

```
Main Thread (UI) ──suspend/StateFlow──> IO Dispatcher (Room, files, network)
                                       ──> Default Dispatcher (embeddings, FFT)
                                       ──> WorkManager (persisted extraction)
```

### Dispatcher Assignments

| Operation | Dispatcher |
|-----------|------------|
| UI StateFlow | Main (auto via viewModelScope) |
| Room reads/writes | Dispatchers.IO |
| File I/O | Dispatchers.IO |
| ONNX/LiteRT inference | Dispatchers.IO (via flowOn) |
| Embeddings computation | Default |
| Graph layout | Default |
| WorkManager workers | withContext inside doWork() |

---

## Data Flow

### Document Ingestion → Vector Storage

```
1. User adds document
           │
           ▼
2. viewModelScope.launch(Dispatchers.IO)
           │
           ▼
3. PenpalDatabase.getInstance().extractionJobDao() → writes to Room
           │
           ▼
4. WorkerLauncher.enqueue(jobId) → enqueues ExtractionWorker
           │
           ▼
5. ExtractionWorker.doWork():
     ParserFactory.create(mimeType).parse(uri)  ← IO dispatcher (real parsing)
           │
           ▼
     chunks = [RawChunk, ...]
           │
           ▼
     VectorStoreProvider.instance.embed(chunks)   ← Default dispatcher (embedding)
           │
           ▼
     chunkDao.insert(chunks)                      ← IO dispatcher (persistent storage)
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
       viewModelScope.launch
             │
             ▼
3. VectorStoreRepositoryImpl.similaritySearch(query, topK=6)
       cosine similarity over cached embeddings
             │
             ▼
       chunks = [ChunkEntity, ...] ← top-K relevant text
             │
             ▼
4. Check isModelReady state
             │
       ┌─────┴─────┐
       │           │
     Ready     Not Ready
       │           │
       ▼           ▼
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
```

### Agent Framework Flow

```
User Query in Chat
        │
        ▼
ChatViewModel.sendMessage(query)
        │
        ▼
InferenceBridge.runInferenceFlowParts(prompt)
        │  (model streams response with potential <|tool_call|> tokens)
        ▼
StreamingTokenFilter → ContentMode.TOOL_CALL detected
        │
        ▼
MessagePartAggregator builds ToolCallPart(name, callId, args)
        │
        ▼
ChatViewModel receives ToolCallPart in Flow<List<MessagePart>>
        │
        ├── ToolCall detected → pause streaming accumulation
        │   │
        │   ▼
        │   ToolExecutor.execute(toolCall)
        │   │
        │   ├── search_knowledge(query)     → VectorStoreRepositoryImpl.similaritySearch()
        │   ├── process_image(uri, prompt)   → InferenceBridge.runInferenceWithImageFlowParts()
        │   ├── read_stack(stackId)          → StackRepository.getBlocks()
        │   └── get_conversation_history()   → ChatRepository.getMessages()
        │   │
        │   ▼
        │   ToolResponsePart(name, callId, output)
        │   │
        │   ▼
        │   Append tool response to prompt context
        │   │
        │   ▼
        │   Resume inference with enriched prompt (agent loop)
        │
        └── No tool call → render as TextPart/ReasoningPart
```

### Tool Registry & Schema (✅ Implemented)

**Files**: `core/ai/tools/Tool.kt`, `ToolSchema.kt`, `ToolRegistry.kt`, `ToolExecutor.kt`, `BuiltinTools.kt`, `web/WebSearchTools.kt`

```kotlin
// core/ai/tools/Tool.kt
interface Tool {
    val name: String
    val description: String
    val schema: ToolSchema
    suspend fun execute(context: ToolExecutionContext): ToolResult
}

// core/ai/tools/ToolRegistry.kt
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    fun register(tool: Tool)
    fun get(name: String): Tool?
    fun getAll(): List<Tool>
    fun getToolsJson(): String  // Returns JSON schema for model prompt
    suspend fun execute(name: String, context: ToolExecutionContext): ToolResult
}
```

---

## Known Issues

### Text Splitting After Special Character Filtering ✅

**Status**: Resolved

**Problem**: After implementing the `StreamingTokenFilter`, chat text was being split into separate lines after each special character occurrence.

**Solution**:
- Implemented smart spacing logic in `StreamingTokenFilter` with `lastEmittedChar` tracking
- `appendWithTransitions()` emits mode transition events for structured parsing
- Context-aware space insertion: only before word characters, not punctuation/symbols
- Prevents `\n\n` spam while preserving intentional paragraph breaks

**Files Involved**:
- `core/ai/tokenization/StreamingTokenFilter.kt`
- `core/ai/tokenization/GemmaSpecialTokens.kt`
- `core/ai/inference/implementation/LiteRtInferenceBridge.kt`
- `feature/chat/viewmodel/ChatViewModel.kt`
- `feature/chat/screen/ChatScreen.kt`

---

## Build Configuration

### Root build.gradle.kts

```kotlin
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
}
```

### Version Catalog (select entries)

```toml
[versions]
agp = "9.1.1"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
hilt = "2.51.1"
composeBom = "2024.06.00"
room = "2.6.1"
workManager = "2.9.1"
coroutines = "1.8.1"
litertlm = "latest.release"
onnxruntime = "1.19.0"
coil = "2.5.0"
```

### Module Plugins (Actual)

| Module | Plugins |
|--------|---------|
| app | android-application, kotlin-android, kotlin-compose, ksp, secrets |
| core:ai | android-library, kotlin-android |
| core:data | android-library, kotlin-android, ksp |
| core:media | android-library, kotlin-android |
| core:processing | android-library, kotlin-android, ksp |
| core:ui | android-library, kotlin-android, kotlin-compose |
| feature:chat | android-library, kotlin-android, kotlin-compose |
| feature:process | android-library, kotlin-android, kotlin-compose |
| feature:inference | android-library, kotlin-android, kotlin-compose |
| feature:stacks | android-library, kotlin-android, kotlin-compose |
| feature:settings | android-library, kotlin-android, kotlin-compose |

---

## Memory Management

| Resource | Strategy |
|----------|----------|
| Embedding cache | LRU (LinkedHashMap) with max 10,000 chunks |
| Bitmap | compress to JPEG stream, no explicit recycle |
| Room pagination | chunkDao.getAllPaged(offset, limit) |
| Engine | close() on release, unload, or model switch |

---

## Thread Safety Checklist

- [x] Every Room call in `withContext(Dispatchers.IO)`
- [x] Every embedding in Default dispatcher
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
| [REFACTOR0001.md](./REFACTOR0001.md) | Architecture doc sync changelog |

---

## Glossary

| Term | Definition |
|------|------------|
| **RAG** | Retrieval-Augmented Generation — combining vector search with LLM inference |
| **LRU** | Least Recently Used — cache eviction strategy |
| **Embedding** | Vector representation of text for semantic similarity |
| **Chunk** | Parsed text segment from a document with position metadata |
| **Manual DI** | Dependency injection via Application singleton lazies (no framework) |

---

*Last updated: 2026-05-12 — Full documentation sync with actual codebase (REFACTOR0001). Corrected versions, tab structure, file listings, removal of fabricated Hilt annotations, and duplicate sections.*

---

## Legacy v1.x Architecture

> The following describes the legacy production architecture (v1.x single-Activity with Views).

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
