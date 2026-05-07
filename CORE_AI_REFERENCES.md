# Core AI Module References

## Module Dependency Graph

```
:core:ai
├── depends on: :core:data
├── used by:   :app
├── used by:   :feature:chat
├── used by:   :feature:inference
├── used by:   :feature:notebooks
├── used by:   :feature:process
├── used by:   :feature:settings
└── used by:   :core:processing
```

## Gradle Dependencies

### Direct Dependencies on `:core:ai`

| Module | File | Type |
|--------|------|------|
| `:app` | `app/build.gradle.kts:105` | `api(project(":core:ai"))` |
| `:feature:chat` | `feature/chat/build.gradle.kts:22` | `api(project(":core:ai"))` |
| `:feature:inference` | `feature/inference/build.gradle.kts:22` | `api(project(":core:ai"))` |
| `:feature:notebooks` | `feature/notebooks/build.gradle.kts:22` | `api(project(":core:ai"))` |
| `:feature:process` | `feature/process/build.gradle.kts:22` | `api(project(":core:ai"))` |
| `:feature:settings` | `feature/settings/build.gradle.kts:22` | `api(project(":core:ai"))` |
| `:core:processing` | `core/processing/build.gradle.kts:29` | `implementation(project(":core:ai"))` |

### Project Inclusion
- `settings.gradle.kts:23` — `include(":core:ai")`

## Design References

### Opencode Library Parts Architecture

The structured message parsing in Penpal is inspired by the [opencode](https://github.com/anomalyco/opencode) library's use of the Vercel AI SDK.

**Key Differences:**

| Aspect | Opencode (Vercel AI SDK) | Penpal (LiteRT-LM) |
|--------|--------------------------|-------------------|
| **Events** | `reasoning-start/delta/end`, `text-start/delta/end` | Raw Gemma 4 tokens (`<|channel>`, `<|tool_call>`) |
| **Parsing** | SDK handles provider-specific tokens | Custom `StreamingTokenFilter` with trie matching |
| **Parts** | `TextPart`, `ReasoningPart`, `ToolPart` | `TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResponsePart` |
| **Streaming** | Event-based state machine | `Flow<List<MessagePart>>` with mode transitions |
| **Tool Lifecycle** | `pending → running → completed/error` | `ToolStatus` enum with same states |

**Why We Built It Differently:**
- LiteRT-LM doesn't emit structured events like Vercel AI SDK
- We must parse raw tokens ourselves using `StreamingTokenFilter`
- The `MessagePartAggregator` serves as our equivalent to opencode's processor.ts state machine

**Reference Files:**
- `opencode/src/lib/ai/message-v2.ts` — Part type definitions (TextPart, ReasoningPart, ToolPart)
- `opencode/src/lib/ai/processor.ts` — Event processor that builds parts from streaming events

## Source Code References

### App Module (`:app`)

#### `app/src/main/java/com/drawapp/PenpalApplication.kt`
- **Line 6-11** — Imports `InferenceBridge`, `LiteRtInferenceBridge`, `MiniLmEmbedder`, `ModelManager`, `OnnxMiniLmEmbedder`, `VectorStoreRepositoryImpl`
- **Line 61-62** — Creates `WordPieceTokenizer` from assets with fallback
- **Line 75** — Sets `VectorStoreProvider.instance` for global access

**Usage:** Application-wide initialization of AI components (tokenizer, embedder, vector store)

#### `app/src/main/java/com/drawapp/MainActivity.kt`
- **Line 20** — Imports `ModelManager`

**Usage:** Likely checks model status or triggers model operations in main activity

#### `app/src/main/java/com/drawapp/NotebookSelectionActivity.kt`
- **Line 22** — Imports `ModelManager`

**Usage:** Model management in notebook selection UI

#### `app/src/main/java/com/drawapp/ModelDownloadReceiver.kt`
- **Line 7** — Imports `ModelManager`

**Usage:** Handles download completion broadcasts for model files

#### `app/src/main/java/com/drawapp/ModelDownloadHelper.kt`
- **Line 15** — Imports `ModelManager`

**Usage:** Helper utilities for model download operations

### Feature: Chat (`:feature:chat`)

#### `feature/chat/src/main/java/com/penpal/feature/chat/ChatViewModel.kt`
- **Line 8** — Imports `InferenceBridge`
- **Line 9** — Imports `VectorStoreRepository`

**Usage:** 
- `InferenceBridge` — Powers chat message generation (streaming responses)
- `VectorStoreRepository` — Retrieves relevant context for RAG (Retrieval-Augmented Generation)

### Feature: Inference (`:feature:inference`)

#### `feature/inference/src/main/java/com/penpal/feature/inference/InferenceViewModel.kt`
- **Line 6** — Imports `DownloadProgress`
- **Line 7** — Imports `InferenceBridge`
- **Line 8** — Imports `ModelManager`

**Usage:**
- `DownloadProgress` — UI progress bar for model downloads
- `InferenceBridge` — Run inference operations
- `ModelManager` — Check model availability and download status

#### `feature/inference/src/main/java/com/penpal/feature/inference/InferenceScreen.kt`
- **Line 20** — Imports `ModelManager`

**Usage:** UI screen showing inference controls and model status

### Feature: Settings (`:feature:settings`)

#### `feature/settings/src/main/java/com/penpal/feature/settings/SettingsViewModel.kt`
- **Line 7** — Imports `InferenceBridge`
- **Line 8** — Imports `ModelManager`
- **Line 9** — Imports `ModelStatus`

**Usage:**
- `InferenceBridge` — Model initialization and status
- `ModelManager` — Model path management, token configuration
- `ModelStatus` — Display download/installation state in settings UI

#### `feature/settings/src/main/java/com/penpal/feature/settings/SettingsScreen.kt`
- **Line 16** — Imports `ModelManager`
- **Line 17** — Imports `ModelStatus`

**Usage:** Compose UI for settings screen showing model download progress and status

### Core: Processing (`:core:processing`)

#### `core/processing/src/main/java/com/penpal/core/processing/Parsers.kt`
- **Line 11** — Imports `RawChunk`

**Usage:** Document parsing that produces `RawChunk` objects for embedding and storage

#### `core/processing/src/main/java/com/penpal/core/processing/DocumentParser.kt`
- **Line 4** — Imports `RawChunk`

**Usage:** Parses documents into chunks that will be embedded by `VectorStoreRepository`

#### `core/processing/src/main/java/com/penpal/core/processing/ExtractionWorker.kt`
- **Line 8** — Imports `VectorStoreRepository`
- **Line 80** — Uses `VectorStoreProvider.instance` to access repository

**Usage:** Background WorkManager worker that extracts text and stores embeddings

## Reference Summary by Class

| Class | Used In | Purpose |
|-------|---------|---------|
| `InferenceBridge` | `ChatViewModel`, `SettingsViewModel`, `InferenceViewModel`, `PenpalApplication` | Main LLM inference contract |
| `LiteRtInferenceBridge` | `PenpalApplication` | On-device inference implementation |
| `ModelManager` | `MainActivity`, `NotebookSelectionActivity`, `ModelDownloadReceiver`, `ModelDownloadHelper`, `InferenceViewModel`, `SettingsViewModel`, `SettingsScreen`, `InferenceScreen` | Model file management and downloads |
| `ModelStatus` | `SettingsViewModel`, `SettingsScreen` | UI state for model installation |
| `DownloadProgress` | `InferenceViewModel` | Download progress tracking |
| `VectorStoreRepository` | `ChatViewModel`, `ExtractionWorker` | Semantic search and storage |
| `VectorStoreProvider` | `PenpalApplication`, `ExtractionWorker` | Global access to vector store |
| `VectorStoreRepositoryImpl` | `PenpalApplication` | Repository implementation |
| `OnnxMiniLmEmbedder` | `PenpalApplication` | Text embedding generation |
| `MiniLmEmbedder` | `PenpalApplication` | Fallback/mock embedder |
| `WordPieceTokenizer` | `PenpalApplication` | Text tokenization |
| `RawChunk` | `Parsers`, `DocumentParser` | Chunk data structure |
| `MessagePart` | `ChatViewModel`, `ChatScreen` | Structured message parts (Text, Reasoning, ToolCall) |
| `StreamingTokenFilter` | `LiteRtInferenceBridge`, `OllamaInferenceBridge` | Real-time special token filtering with mode transitions |
| `MessagePartAggregator` | `LiteRtInferenceBridge`, `OllamaInferenceBridge` | Builds MessageParts from streaming chunks |
| `MarkdownText` | `ChatScreen` | Lightweight markdown renderer for TextPart content |

## Usage Patterns

### 1. Application Initialization Pattern
**Location:** `PenpalApplication.kt`
**Pattern:** Initialize all AI components once at app startup and expose via singleton provider

### 2. ViewModel Injection Pattern
**Locations:** `ChatViewModel`, `InferenceViewModel`, `SettingsViewModel`
**Pattern:** ViewModels receive `InferenceBridge` and other AI components via constructor or factory

### 3. Background Worker Pattern
**Location:** `ExtractionWorker.kt`
**Pattern:** WorkManager workers access `VectorStoreRepository` via static `VectorStoreProvider` (avoids DI in workers)

### 4. UI Binding Pattern
**Locations:** `SettingsScreen.kt`, `InferenceScreen.kt`
**Pattern:** Compose UI collects StateFlow from ViewModels that wrap AI module state

### 5. Model Download Pattern
**Locations:** `ModelDownloadReceiver.kt`, `ModelDownloadHelper.kt`, `InferenceViewModel`
**Pattern:** DownloadManager + BroadcastReceiver for model file downloads with progress callbacks

### 6. Structured Message Parts Pattern
**Locations:** `ChatViewModel.kt`, `ChatScreen.kt`, `LiteRtInferenceBridge.kt`
**Pattern:** 
1. `StreamingTokenFilter.appendWithTransitions()` detects mode changes from Gemma 4 tokens
2. `MessagePartAggregator.processChunk()` builds immutable `MessagePart` objects
3. `ChatViewModel` collects `Flow<List<MessagePart>>` and stores on `ChatMessage`
4. `ChatScreen` renders different part types with distinct UI (collapsible thinking, tool call cards)

## Impact Analysis

### If `:core:ai` is modified, these modules need testing:
1. **`:app`** — Application initialization may fail
2. **`:feature:chat`** — Chat functionality broken (especially MessagePart rendering)
3. **`:feature:inference`** — Model inference broken
4. **`:feature:settings`** — Model management UI broken
5. **`:core:processing`** — Document extraction and embedding broken
6. **`:feature:notebooks`** — Notebook features may use AI components

### MessagePart Architecture Impact
Changes to `MessagePart` sealed class affect:
- **UI**: `ChatScreen.kt` must handle all part subtypes
- **ViewModel**: `ChatViewModel.kt` stores parts on `ChatMessage`
- **Bridge**: `LiteRtInferenceBridge.kt` and `OllamaInferenceBridge.kt` must produce correct parts
- **Filter**: `StreamingTokenFilter.kt` mode transitions map directly to part boundaries
7. **`:feature:process`** — Process features may use AI components

### Public API Surface:
- `InferenceBridge` interface and all implementations
- `VectorStoreRepository` interface
- `ModelManager` object (singleton)
- `ModelStatus` enum
- `DownloadProgress` data class
- `RawChunk` data class
- `VectorStoreProvider` object
- `WordPieceTokenizer` class
- `StreamingTokenFilter` class
- All Ollama data classes

## External References

### LiteRT-LM Documentation
- [Overview](https://ai.google.dev/edge/litert-lm/overview)
- [Android Guide](https://ai.google.dev/edge/litert-lm/android)
- [GitHub Repository](https://github.com/google-ai-edge/LiteRT-LM)

### Models
- [HuggingFace LiteRT Community](https://huggingface.co/litert-community)
- [Gemma-4-E2B-it](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)

### Gemma Documentation
- [Gemma Prompt Formatting](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4)
- [Run Gemma](https://ai.google.dev/gemma/docs/run)

### Related Frameworks (Not Used)
- MediaPipe LLM Inference API — Deprecated for Android/iOS, migrate to LiteRT-LM
- Ollama — Used as alternative/development backend
