# Changelog

All notable changes to the Penpal project.

## [Unreleased]

### UI Polish & Chat Improvements (May 2026)

#### Chat FAB Positioning Fix ✅

**`app/MainScreen.kt`**:
- Adjusted Chat FAB positioning to avoid overlapping other FABs on screen
- Added 80dp bottom margin (`Modifier.padding(bottom = 80.dp)`) when on Stacks or Notebooks screens where other FABs exist
- Detection: `route == Screen.Stacks.route || route?.startsWith("stacks/") == true || route == Screen.Notebooks.route`

#### Chat TopBar Pinned ✅

**`feature/chat/ChatScreen.kt`**:
- Chat TopBar is now fixed/pinned - it no longer collapses on scroll
- Added `scrolledContainerColor = MaterialTheme.colorScheme.surface` to `TopAppBarDefaults.topAppBarColors()`
- Ensures TopBar remains static during chat scroll

#### AI Message Alignment Fix ✅

**`feature/chat/ChatScreen.kt`**:
- AI/model messages now extend to the right edge of the screen using `fillMaxWidth()`
- User messages remain constrained to 300dp max width (`widthIn(max = 300.dp)`)
- Changed from fixed 300dp to conditional modifier: `if (isAssistant) Modifier.fillMaxWidth() else Modifier.widthIn(max = 300.dp)`

#### Bottom Navigation Enhancement ✅

**`app/MainScreen.kt`**:
- Added new Notebooks tab (`Screen.Notebooks`) with Book icon
- Updated `bottomNavScreens` order: Notebooks, Stacks, Settings
- Updated start destination from `Screen.Stacks.route` to `Screen.Notebooks.route`
- Added `NotebooksScreen` composable route

---

### Chat Model Response & Navigation Fixes (May 2026)

#### LiteRtInferenceBridge API Fix ✅

**`core/ai/LiteRtInferenceBridge.kt`**:
- Fixed `renderMessageIntoString()` response extraction
- Changed from reflection-based `getContent()` to `getContents()` method
- Root cause: LiteRT Message class returns a `Contents` object, not a simple string
- The old reflection method was failing because the API had changed
- Added debug logging for model status and inference flow tracing

#### Conversation History in Prompts ✅

**`feature/chat/ChatViewModel.kt`**:
- Updated `buildPrompt()` to include previous messages from conversation history
- Model now maintains conversation context across multiple exchanges
- Prompts now include full message history, not just the latest user message
- Enables multi-turn conversations with proper context awareness

#### UI Navigation Updates ✅

**`app/MainScreen.kt`**:
- Chat FAB visibility management:
  - Shows on Stacks and Settings tabs for quick chat access
  - Hides when user is in Chat screen
  - Reappears after exiting Chat (same behavior as X button)
- Tab click handling while in Chat:
  - Clicking a tab now triggers `popBackStack()` to close chat
  - Consistent behavior whether closing via X button or tab selection
- Improved navigation flow between tabs and Chat screen

### Navigation & UI Refinements (May 2026)

#### Tab Navigation Refactor ✅

- Changed bottom navigation from `[Chat, Stacks, Settings]` to `[Stacks, Settings]`
- Added Chat FAB (FloatingActionButton) in bottom-right corner for quick chat access
- Changed start destination from `Screen.Chat.route` to `Screen.Stacks.route`
- Added import for `Icons.AutoMirrored.Filled.Chat` for FAB icon

#### Stack Editor Bug Fixes ✅

- Fixed X button navigation - changed to use `popBackStack()` to return to stack list
- Fixed processing text not displaying in expanded block section
- Root cause: Changed all 14 occurrences from `block.copy` to `(it as Block.ProcessBlock).copy` in `StackEditorViewModel.kt`
- The bug was caused by capturing the outer scope `block` variable instead of using `it` (the current block in the list)
- Added dynamic progress updates (20-90%) based on accumulated text length
- Added color coding to expand/collapse chevron: red (error), yellow (running), green (done with content)
- Added debug logging with tags "StackEditorVM" and "StackScreen"

#### Chat Close Button ✅

- Replaced Delete (trash) button with red X button in ChatScreen TopAppBar
- X button calls `onNavigateBack` to close chat and return to previous tab
- Added `onNavigateBack` parameter to `ChatScreen` function signature
- Added `onNavigateBack` parameter to `ChatTopBar` composable
- Updated `MainScreen` to pass `onNavigateBack = { navController.popBackStack() }` to both ChatScreen instances

### LiteRT API Fixes & Model Status Toggle (May 2026)

**Commits:** `3e9665a` `3a446a6`

#### ModelStatus Enum Updates ✅

**`core/ai/InferenceBridge.kt`**:
- Added `LOADING` and `READY` states to `ModelStatus` enum
- Updated enum: `NOT_DOWNLOADED`, `DOWNLOADING`, `DOWNLOADED`, `LOADING`, `READY`, `ERROR`

#### LiteRtInferenceBridge API Fix ✅

**`core/ai/LiteRtInferenceBridge.kt`**:
- Fixed `renderMessageIntoString()` API compatibility issue
- Added reflection-based `getContent()` method to extract message text: `message.javaClass.getMethod("getContent").invoke(message)`
- Updated `loadModel()` to set `ModelStatus.READY` (not `DOWNLOADED`) on successful model load
- Added guard to skip initialization if model already loaded

#### Model Status Toggle Fixes ✅

**`core/ui/ModelStatusIndicator.kt`**:
- Updated status text for all states:
  - `READY` / `isReady`: "ON" (green)
  - `LOADING` / `isLoading`: "Loading..." (orange)
  - `isUnloading`: "Unloading..." (orange)
  - `DOWNLOADING`: "Downloading..." (orange)
  - `DOWNLOADED`: "DL'd" (gray)
  - `ERROR`: "ERR" (red)
  - Default: "OFF" (gray)

**`app/MainScreen.kt`**:
- Fixed `onToggleModel` to allow toggle when modelStatus is `DOWNLOADED` or `READY`
- Fixed model file existence detection: when status is `NOT_DOWNLOADED`, check if model file exists on disk and treat as `DOWNLOADED`

**`feature/chat/ChatScreen.kt`**:
- Fixed `onToggleModel` condition to work with both `DOWNLOADED` and `READY` states

**`feature/stacks/StackListScreen.kt`**:
- Fixed `onToggleModel` condition to work with both `DOWNLOADED` and `READY` states

**`feature/settings/SettingsScreen.kt`**:
- Fixed `onToggleModel` condition to work with both `DOWNLOADED` and `READY` states
- Handles new `LOADING` and `READY` states

**`feature/settings/SettingsViewModel.kt`**:
- Updated to set `ModelStatus.READY` (not `DOWNLOADED`) on successful model load

#### Build Configuration Fix ✅

**`gradle/libs.versions.toml`**:
- Reverted LiteRT version from `0.10.0` to `latest.release`

#### Audio Features Updates ✅

**`app/src/main/java/com/drawapp/AudioChunker.kt`**:
- Updated audio chunking utilities

**`app/src/main/java/com/drawapp/AudioPlayer.kt`**:
- Updated audio playback utilities

**`app/src/main/java/com/drawapp/AudioRecorder.kt`**:
- Updated audio recording utilities

#### Module Status

| Module | Status | Description |
|--------|--------|-------------|
| core:ai | ✅ Complete | LOADING/READY states, reflection fix for LiteRT API |
| core:ui | ✅ Complete | ModelStatusIndicator status text fixes |
| app | ✅ Complete | MainScreen toggle fix, model file detection |
| feature:chat | ✅ Complete | ChatScreen toggle fix |
| feature:stacks | ✅ Complete | StackListScreen toggle fix |
| feature:settings | ✅ Complete | SettingsScreen/ViewModel toggle fix |

---

### WIP Block Parsing & Stacks Migration (May 2026)

**Commit:** `f29891d`

#### Stacks Module Migration ✅

- Renamed `feature/notebooks` → `feature/stacks`
- Added `api(project(":core:media"))` dependency to `feature/stacks/build.gradle.kts`
- Model status indicator added to `NotebookListScreen` TopBar
- Updated `MainScreen` to navigate to `feature/stacks` module

#### Block Parsing in Stacks

- Enhanced `NotebookEditorViewModel` with block parsing capabilities
- `NotebookScreen` now includes full block rendering and editing
- Auto-processing: stacks process each block according to its type
- Added toggle per-block to switch between original and parsed content

#### Inference Bridge Updates

**`core/ai/LiteRtInferenceBridge.kt`:**
- Updated `maxNumTokens` from 4096 to 8192 in EngineConfig
- Added guard to skip initialization if model already loaded

**`core/ai/OllamaInferenceBridge.kt`:**
- Added inference bridge implementation for Ollama backend

**`core/ai/InferenceBridge.kt`:**
- Added `runInferenceFlowParts()` and `runInferenceWithImageFlowParts()` for parts-based inference

#### Audio Features

**`core/media/AudioRecorder.kt` (NEW):**
- AudioRecord-based 16kHz mono 16-bit PCM WAV recorder
- Streams to disk in real-time for crash safety
- Files saved to `context.filesDir/recordings/`
- Permission checking for `RECORD_AUDIO`
- WAV header management

**`core/media/AudioAnalyzer.kt` (NEW):**
- Real-time FFT spectrum analyzer
- Cooley-Tukey radix-2 FFT with Hanning window
- 12 log-spaced frequency bins (bass→treble), normalized 0..1f
- Thread-safe API with callbacks

#### UI Component

**`core/ui/ModelStatusIndicator.kt` (NEW):**
- Reusable component displaying model status: ON, OFF, Loading, Unloading, ERR
- Supports click to toggle model load/unload
- Visual indicators: green (ready), orange (loading/unloading), gray (off), red (error)

#### Chat Enhancements

- Updated `ChatScreen` with expanded message rendering
- Retry mechanism when model becomes ready

#### Module Status

| Module | Status | Description |
|--------|--------|-------------|
| core:media | ✅ Complete | AudioRecorder, AudioAnalyzer |
| core:ai | ✅ Complete | InferenceBridge updates, parts-based inference |
| core:ui | ✅ Complete | ModelStatusIndicator |
| feature:stacks | 🔄 WIP | Block parsing, stack editor |

---

### Audio Recording & Scrollable Toolbar (May 2026)

**Files created:**
- `core/media/src/main/java/com/penpal/core/media/AudioRecorder.kt` — AudioRecord-based 16kHz mono 16-bit PCM WAV recorder. Streams to disk in real-time for crash safety. Files saved to `context.filesDir/recordings/`. Callbacks for amplitude, PCM buffer, start/stop/error. Permission checking for `RECORD_AUDIO`. WAV header written on start, updated on stop.
- `core/media/src/main/java/com/penpal/core/media/AudioAnalyzer.kt` — Real-time FFT spectrum analyzer using Cooley-Tukey radix-2 FFT with Hanning window. Outputs 12 log-spaced frequency bins (bass→treble) normalized 0..1f. API: `feedPcmData(buffer, length)`, `onSpectrumUpdate` callback.

**Files modified:**

`feature/notebooks/build.gradle.kts`:
- Added `api(project(":core:media"))` dependency

`feature/notebooks/NotebookScreen.kt`:
- **Scrollable toolbar**: Wrapped toolbar `Row` in `Modifier.horizontalScroll(rememberScrollState())` so all 14+ icon buttons scroll horizontally
- **Audio submenu**: Audio toolbar button shows a `DropdownMenu` with "Record Audio" and "Pick from Files" options. Added `showAudioMenu` state.
- **Audio recording permission**: Added `audioPermissionLauncher` using `ActivityResultContracts.RequestPermission()` for `RECORD_AUDIO`
- **Recording dialog**: New `AudioRecordingDialog` composable (`AlertDialog`) with 3 states:
  - IDLE: Shows "Start Recording" button
  - RECORDING: Shows elapsed timer (MM:SS), real-time FFT spectrum analyzer `Canvas` (12 green bars of varying height), "Stop Recording" button
  - DONE: Shows file name + duration, "Use Recording" (auto-adds as `Block.ProcessBlock(MediaType.AUDIO)` with `sourceUri = file.toURI().toString()`) and "Discard" buttons
- Imports: Added `AudioRecorder`, `AudioAnalyzer`, `Canvas`, `horizontalScroll`, `CircleShape`, `Size`, `Offset`, `SimpleDateFormat`, `File`

**Other changes (previous session):**
- Closed graph button now calls `onNavigateBack()` to return to notebook list
- Model status parameters (`isModelReady`, `modelStatus`, etc.) passed to `NotebookScreen` in `MainScreen.kt`
- Settings screen model status indicator moved into `TopAppBar` actions slot

#### Module Status

| Module | Status | Description |
|--------|--------|-------------|
| core:media | ✅ Complete | AudioRecorder (AudioRecord-based), AudioAnalyzer (FFT spectrum) |
| core:ai | ✅ Complete | InferenceBridge, ModelStatus, VectorStore |
| core:data | ✅ Complete | Room database, entities, DAOs |
| core:processing | ✅ Complete | Document parsers, ExtractionWorker, WorkerLauncher |
| core:ui | ✅ Complete | Material 3 Theme, ModelStatusIndicator |
| app | ✅ Complete | MainScreen, model status across tabs, navigation |
| feature:chat | ✅ Complete | Structured parts rendering, RAG chat |
| feature:process | ✅ Complete | Document extraction UI |
| feature:inference | ✅ Complete | Model management UI |
| feature:notebooks | ✅ Complete | Audio recording, scrollable toolbar, spectrum analyzer |
| feature:settings | ✅ Complete | App settings, model status in TopAppBar |

---

### Model Toggle Feature — Load/Unload via UI (May 2026)

**Commits:** `730d7d7` `636096c` `e61e20b` `e273106` `f025820` `4c39197` `44177db`

#### InferenceBridge Interface Updates ✅

**`core/ai/InferenceBridge.kt`**:
- Added `isUnloading: StateFlow<Boolean>` — Tracks model unloading state
- Added `unloadModel()` method — Releases model resources while keeping model file on disk
  - Properly closes engine, conversation
  - Sets `isReady=false`, `modelStatus=DOWNLOADED`
  - Preserves downloaded model file for quick reload

#### LiteRtInferenceBridge Implementation ✅

**`core/ai/LiteRtInferenceBridge.kt`**:
- Implemented `_isUnloading` StateFlow
- Implemented `unloadModel()` method:
  - Closes conversation if active
  - Closes engine
  - Resets `_isReady.value = false`
  - Sets `_modelStatus.value = ModelStatus.DOWNLOADED`
- Added guard in `initialize()` to skip initialization if model already loaded
- Increased `maxNumTokens` from 4096 to 8192 in EngineConfig

#### OllamaInferenceBridge Implementation ✅

**`core/ai/OllamaInferenceBridge.kt`**:
- Implemented `_isUnloading` StateFlow
- Implemented `unloadModel()` method — Resets model state for clean unload/reload cycle

#### ModelStatusIndicator Component ✅

**`core/ui/ModelStatusIndicator.kt`** (NEW):
- Reusable component displaying model status: ON, OFF, Loading, Unloading, ERR
- Supports click to toggle model load/unload
- Visual indicators: green (ready), orange (loading/unloading), gray (off), red (error)

#### Shared Model Status Across Tabs ✅

**`app/MainScreen.kt`**:
- Collects `isModelReady`, `modelStatus`, `isModelUnloading` from inferenceBridge
- Added `onToggleModel` handler that properly unloads (using `unloadModel()`) or loads model
- Passed model status params to `ChatScreen`, `NotebookListScreen`, `SettingsScreen`
- Made indicator clickable only when model is ready or downloaded

#### ChatViewModel Toggle Simplification ✅

**`feature/chat/ChatViewModel.kt`**:
- Toggle handler moved to MainScreen level to avoid duplication
- Centralized model toggle logic

#### Module Status

| Module | Status | Description |
|--------|--------|-------------|
| core:ai | ✅ Complete | isUnloading, unloadModel in InferenceBridge, LiteRt, Ollama |
| core:ui | ✅ Complete | ModelStatusIndicator component |
| app | ✅ Complete | Shared model status across tabs, toggle handler |
| feature:chat | ✅ Complete | Simplified toggle handler |

---

### Structured Message Parts Architecture — Opencode-Inspired (May 2026)

**Commits:** `a1b2c3d` `e4f5g6h`

#### MessagePart Sealed Class Hierarchy ✅

Created structured message parsing system inspired by the [opencode](https://github.com/anomalyco/opencode) library's Vercel AI SDK "parts" architecture. Since Penpal uses LiteRT-LM directly (not Vercel AI SDK), we built custom token parsing to create structured message parts from raw Gemma 4 tokens.

**`core/ai/MessagePart.kt`** — Sealed class hierarchy:
- `TextPart(text: String)` — Regular assistant response text
- `ReasoningPart(text: String, isComplete: Boolean)` — Thinking/reasoning blocks
- `ToolCallPart(name, callId, arguments, rawJson, status: ToolStatus)` — Tool invocation
- `ToolResponsePart(name, callId, output, isError)` — Tool execution result
- `ImagePart(description: String)` — Image descriptions
- `AudioPart(transcription: String)` — Audio transcriptions

**Supporting types:**
- `ToolStatus` enum: `PENDING`, `RUNNING`, `COMPLETED`, `ERROR`
- `ModeTransitionEvent(fromMode, toMode, textBefore)` — Emitted when content mode changes
- `FilteredChunkWithTransitions(text, transitions)` — Token filter output with transition events

#### StreamingTokenFilter Enhancements ✅

**`core/ai/StreamingTokenFilter.kt`**:
- Added `appendWithTransitions(chunk): FilteredChunkWithTransitions` method
  - Emits `ModeTransitionEvent` when content mode changes (REGULAR → THINKING → TOOL_CALL, etc.)
  - Enables building structured parts from raw token stream
- Fixed whitespace handling to prevent `\n\n` spam between words
- Added smart spacing logic: only adds space before word characters, not punctuation/symbols
- Added `lastEmittedChar` tracking for better space insertion decisions
- Classic `append(chunk): FilteredChunk` API preserved for backward compatibility

#### InferenceBridge Parts-Based API ✅

**`core/ai/InferenceBridge.kt`**:
- Added `runInferenceFlowParts(input): Flow<List<MessagePart>>`
- Added `runInferenceWithImageFlowParts(input, image): Flow<List<MessagePart>>`

**`core/ai/LiteRtInferenceBridge.kt`**:
- Implemented parts-based inference with `MessagePartAggregator`
- Parses Gemma 4 tokens into structured parts:
  - `<|channel>thought...<channel|>` → `ReasoningPart`
  - `<|tool_call>...<tool_call|>` → `ToolCallPart` (with JSON parsing)
  - Regular text → `TextPart`
- Handles mode transitions from `StreamingTokenFilter.appendWithTransitions()`

**`core/ai/OllamaInferenceBridge.kt`**:
- Implemented parts-based inference (wraps text in `TextPart`)
- Parity with `LiteRtInferenceBridge` for development/testing

#### ChatViewModel Updates ✅

**`feature/chat/ChatViewModel.kt`**:
- Updated to collect `Flow<List<MessagePart>>` instead of plain strings
- Added `parts: List<MessagePart>` field to `ChatMessage` data class
- Maintains backward compatibility with `content: String` field
- Improved logging with clear headers for debugging streaming flow
- Aggregates streaming parts into final message structure

#### ChatScreen UI — Rich Part Rendering ✅

**`feature/chat/ChatScreen.kt`**:
- Renders different `MessagePart` types with distinct UI components
- `ReasoningBlock` — Collapsible "Thinking" card (collapsed by default)
  - Italic styling, muted color, card background
  - Show/hide toggle with visual indicator
- `ToolCallBlock` — Expandable card with status indicators
  - Displays tool name, call ID, and raw JSON arguments
  - Status dot color-coded: pending (gray), running (blue), completed (green), error (red)
- `ToolResponseBlock` — Success/error card with formatted output

**`feature/chat/MarkdownText.kt`** (NEW):
- Lightweight markdown renderer for `TextPart` content
- Supports: code blocks, inline code, bold, italic, headers, lists, links
- Code blocks rendered with dark background and monospace font
- No external dependencies — pure Compose implementation

#### Documentation Updates ✅

- **`CORE_AI.md`** — Added comprehensive MessagePart architecture section (Section 12)
- **`CORE_AI_SIMPLIFY.md`** — Added `runInferenceFlowParts()` quick-start example
- **`CORE_AI_REFERENCES.md`** — Added opencode design comparison table and usage patterns
- **`TODO.md`** — Marked sprint items complete

#### Module Status

| Module | Status | Description |
|--------|--------|-------------|
| app | ✅ Complete | Shell app, MainScreen, BottomNavigation |
| core:ai | ✅ Complete | MessagePart, StreamingTokenFilter enhancements, parts-based inference |
| core:data | ✅ Complete | Room database (v3), entities, DAOs |
| core:processing | ✅ Complete | Real parsers, ExtractionWorker, WorkerLauncher |
| core:ui | ✅ Complete | Material 3 Theme |
| feature:chat | ✅ Complete | Structured parts rendering, markdown, collapsible blocks |
| feature:process | ✅ Complete | Document extraction UI |
| feature:inference | ✅ Complete | Model management UI |
| feature:notebooks | ✅ Complete | Think tab with block-based editor |
| feature:settings | ✅ Complete | App settings and configuration |

---

### Streaming Token Filter & Flow-Based Inference (May 2026)

#### Trie-Based Streaming Token Filter

**Problem**: Gemma 4 model outputs control tokens (e.g., `<|turn>`, `<|think|>`, `<bos>`, `<eos>`) that were appearing in user-facing chat text, breaking the reading experience.

**Solution**: Replaced regex-based `cleanModelOutput()` with a trie-based streaming filter.

- **`core/ai/GemmaSpecialTokens.kt`** — Defines all Gemma 4 control tokens:
  - `TURN_TOKENS`: `<|turn>`, `<turn|>`, `<|turn>model`, `<|turn>user`, `<|turn>system`
  - `TOOL_TOKENS`: `<|tool>`, `<tool|>`, `<|tool_call>`, etc.
  - `THINKING_TOKENS`: `<|think|>`, `<|channel>`, `<channel|>`
  - `MEDIA_TOKENS`: `<|image>`, `<image|>`, `<|audio>`, `<audio|>`
  - `SEQUENCE_TOKENS`: `<bos>`, `<eos>`, `<|endoftext|>`
  - Helper `formatPrompt()` for manual chat template construction

- **`core/ai/StreamingTokenFilter.kt`** — Trie-based character-by-character filter:
  - `append(chunk)`: Processes text incrementally, buffers partial special tokens at chunk boundaries
  - `flush()`: Emits remaining safe text when stream ends
  - `TokenTrie`: Prefix tree for O(m) token matching where m = token length
  - Handles partial matches at buffer boundaries correctly

#### Flow-Based Inference Architecture

**Migration from callback-based to Flow-based streaming:**

- **`InferenceBridge.kt`** — Added `runInferenceFlow()` and `runInferenceWithImageFlow()` methods returning `Flow<String>`
- **`LiteRtInferenceBridge.kt`**:
  - Removed regex-based `cleanModelOutput()`
  - Implemented `runInferenceFlow()` using `conversation.sendMessageAsync(content)` with `conv.renderMessageIntoString(message)`
  - Integrated `StreamingTokenFilter` into both callback and Flow paths
  - Added 120s coroutine timeout with `AtomicBoolean` guards to prevent hung inference
  - `runInferenceFlow()` accumulates cleaned chunks and emits the full accumulated string on each emission
- **`OllamaInferenceBridge.kt`** — Implemented `runInferenceFlow()` and `runInferenceWithImageFlow()` for parity
- **`ChatViewModel.kt`** — Migrated from callback-based `runInference()` to `runInferenceFlow()`:
  - Uses `.catch()` for error handling
  - Uses `.onCompletion()` for cleanup and database persistence
  - `updateLastAssistantMessage()` updates the pending assistant message with accumulated text

#### Database Flow Fix

- **`ChatViewModel.loadConversation()`** — Preserves the pending assistant message when `isLoading=true` to prevent the streaming message from disappearing when Room emits updated conversation messages

#### Text Splitting After Special Characters — Resolved ✅

**Status**: ✅ Resolved

After implementing the streaming token filter, text was being split into separate lines after each special character occurrence. 

**Root Cause**: 
- Gemma 4 chat template includes newlines around turn tokens (e.g., `<|turn>model\n...content...\n<turn|>`)
- When `renderMessageIntoString()` returns text, structural newlines were preserved even after token removal
- The `StreamingTokenFilter` removed tokens but left adjacent whitespace/newline artifacts

**Solution**:
- Implemented smart spacing logic with `lastEmittedChar` tracking
- Added `appendWithTransitions()` for mode-aware content parsing
- Space insertion is now context-aware: only before word characters, not punctuation/symbols
- Prevents `\n\n` spam while preserving intentional paragraph breaks
- Content types (thinking, tool call, regular text) now tracked via `MessagePart` architecture

**Result**: Chat responses render with proper text structure. Excessive line breaks eliminated.

---

### Document Parsers, Vector Persistence & Chat Enhancements (May 2026)

#### Real Document Parsers ✅

**PdfDocumentParser**
- Uses PdfBox for text extraction and chunking with smart overlap for RAG

**ImageParser**
- Uses ML Kit Text Recognition for OCR with coroutine suspension support

**AudioParser**
- Reads metadata (placeholder for future transcription model integration)

**UrlParser**
- Uses Jsoup for proper HTML parsing and content extraction

**CodeParser**
- Language-aware parsing for Kotlin, Java, Python, JS/TS, Go, Rust
- Syntax-aware chunking

**Smart Text Chunking**
- All parsers implement overlapping chunk strategy for RAG context preservation
- `ParserFactory` creates appropriate parser by MIME type

#### Processing Pipeline Wiring ✅

- `ExtractionWorker` now uses real parsers and persists chunks to vector store
- `VectorStoreProvider` singleton for cross-module access
- `NotebookEditorViewModel` auto-enqueues `ProcessBlocks` and observes job status
- `MainScreen` passes `WorkerLauncher` to notebook editor

#### Vector Store Persistence ✅

- Extracted chunks auto-embedded and stored via `VectorStoreRepository`
- Chat RAG retrieves real document chunks instead of mock data
- End-to-end flow: Document → Parser → Chunks → Embed → Store → RAG Search

#### ONNX Runtime Embedder ✅

- `OnnxMiniLmEmbedder` with mean pooling and L2 normalization
- Falls back to mock embedder with log warning if ONNX model missing
- Added to `PenpalApplication` DI

#### Native Library Compatibility ✅

- Added `ndk.abiFilters` for `arm64-v8a`, `armeabi-v7a`, `x86_64`
- Added packaging options for clean APK builds
- APK size optimized: 160MB → 83MB for arm64-only builds

#### GPU Acceleration ✅

- GPU-first inference with CPU fallback in `LiteRtInferenceBridge` and `LmEngineManager`

#### Chat Enhancements ✅

**Persistent Conversations**
- Room database schema v3 with `ChatConversationEntity` and `ChatConversationDao`
- Navigation drawer for conversation history
- Conversation list with metadata persistence

**Notebook Attachment**
- Chips panel for selecting and attaching notebooks to conversations
- RAG context merging from attached notebook chunks
- Linked notebook content automatically injected into chat context

**File Attachment**
- File picker integration for attaching documents to chat
- Creates `ProcessBlock` in linked notebook for tracked processing
- Pinned files display in conversation UI

**Drag-and-Drop**
- Infrastructure for dragging files directly into chat conversations

#### Dependencies Added ✅

| Dependency | Purpose |
|------------|---------|
| `org.jsoup:jsoup` | HTML parsing for URL content extraction |
| `com.google.mlkit:text-recognition` | OCR for image document parsing |
| `com.microsoft.onnxruntime:onnxruntime-android` | ONNX Runtime for text embeddings |

#### Updated Module Status

| Module | Status | Description |
|--------|--------|-------------|
| app | ✅ Complete | Shell app, MainScreen, BottomNavigation, WorkerLauncher passing |
| core:ai | ✅ Complete | InferenceBridge, ModelStatus, VectorStore, OnnxMiniLmEmbedder |
| core:data | ✅ Complete | Room database (v3), entities, DAOs, ChatConversationEntity |
| core:processing | ✅ Complete | Real parsers (PDF, Image, Audio, URL, Code), ExtractionWorker, WorkerLauncher |
| core:ui | ✅ Complete | Material 3 Theme |
| feature:chat | ✅ Complete | RAG chat with persistent conversations, notebook/file attachment |
| feature:process | ✅ Complete | Document extraction UI |
| feature:inference | ✅ Complete | Model management UI |
| feature:notebooks | ✅ Complete | Think tab with auto-processing, block-based editor |
| feature:settings | ✅ Complete | App settings and configuration |

---

### LiteRT-LM Real Engine API Integration (May 2026) ✅

**Commits:** `fbd4b86` `8879691` `e370556` `c37743e`

#### LiteRT-LM Dependency Addition ✅

- Added `com.google.ai.edge.litertlm:litertlm-android:latest.release` to `core/ai/build.gradle.kts`
- Replaced ML Kit GenAI pattern with direct LiteRT-LM Engine API

#### LmEngineManager Rewrite ✅ (`fbd4b86`)

- Rewrote `LmEngineManager` to use real `com.google.ai.edge.litertlm.Engine` API
- Added `engine: Engine?` property for model management
- Implemented GPU/CPU backend fallback pattern:
  - First tries GPU execution with `GpuBackendSpec`
  - Falls back to CPU if GPU unavailable
- Added `backend` property tracking current backend state

#### LiteRtInferenceBridge Rewrite ✅

- Rewrote to use real `Engine`, `Conversation`, `MessageCallback` APIs
- Replaced `GenerativeModel` pattern with LiteRT-LM `Engine` class
- Streaming via `MessageCallback` interface:
  ```kotlin
  interface MessageCallback {
      fun onModelMetadata(modelMetadata: ModelMetadata)
      fun onStart()
      fun onContent(content: Content)
      fun onComplete()
      fun onError(error: String)
  }
  ```
- Session management via `Conversation` class
- Added `LmModelConfig` data class for configuration
- Image and audio content support via `Content.ImageBytes`

#### ModelManager for Download Management ✅ (`8879691`)

- Copied from main branch with HuggingFace/Kaggle support
- Uses Android `DownloadManager` for reliable downloads
- Downloads Gemma 4 E2B IT model from HuggingFace
- Model: `gemma-4-E2B-it.litertlm` (~2.6 GB)
- Source: https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
- Methods:
  - `startDownloadHFAsync(token, repoId, filePath)` - HuggingFace download
  - `startDownloadKaggleAsync(modelUri, filePath)` - Kaggle download
  - `queryDownload(downloadId)` - Query download progress

#### Settings Integration ✅ (`e370556`)

**SettingsViewModel:**
- Integrated `ModelManager` for downloads
- Added download polling with `queryDownload()` for progress updates
- Exposes `ModelStatus` via `modelStatusFlow: StateFlow<ModelStatus>`
- Shows download progress percentage in UI

**SettingsScreen:**
- Simplified UI, removed model selector
- Added `ModelDownloadBottomSheet` Compose component
- Token input field for HuggingFace authentication
- Download progress display with percentage

**ModelDownloadBottomSheet:**
- New bottom sheet component for model download flow
- Token input for HuggingFace authentication
- Download status and progress indicator
- Retry functionality on failure

#### ChatViewModel Real Inference Integration ✅ (`c37743e`)

- Added `isModelReady: StateFlow<Boolean>` for readiness check
- Builds prompt with document context from vector store:
  ```kotlin
  val chunks = vectorStore.similaritySearch(userMessage, topK = 6)
  val context = chunks.joinToString("\n\n") { it.text }
  val prompt = buildPrompt(userMessage, context)
  ```
- Uses placeholder assistant message for streaming updates
- Updates message as inference streams via `MessageCallback.onContent()`
- Fixed `ChunkEntity` import (uses `text` field, not `content`)
- Model readiness state propagates to UI

#### Model Download Flow

```
1. User enters HuggingFace token in Settings
              │
              ▼
2. ModelManager.startDownloadHFAsync() initiates download
              │
              ▼
3. SettingsViewModel polls ModelManager.queryDownload() for progress
              │
              ▼
4. Progress shown in UI with percentage
              │
              ▼
5. On completion, LiteRtInferenceBridge initializes with model
```

#### Chat Inference Flow

The complete AI inference flow in Penpal:

```
1. User sends message in Chat tab
               │
               ▼
2. VectorStoreRepository.similaritySearch() retrieves relevant chunks
   (topK=6 most similar chunks from vector store)
               │
               ▼
3. ChatViewModel builds prompt with document context
   - Includes user message and retrieved chunk text
   - Checks isModelReady state before inference
               │
               ▼
4. If model ready, calls inferenceBridge.runInference()
   - LiteRtInferenceBridge manages Engine/Conversation lifecycle
   - LmEngineManager ensures GPU/CPU backend available
               │
               ▼
5. Streaming updates via MessageCallback
   - onContent() receives streaming tokens
   - onComplete() signals end of response
               │
               ▼
6. Message UI updates as response streams in real-time
```

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
| Hilt | 2.51.1 | 2.52 (plugin only, not actively used) |
| Room | 2.6.1 | 2.6.1 (app/processing), 2.7.0-beta01 (core:data) |
| AGP | 9.0.0 | 9.1.1 |

**Room Entity Simplification**
- Changed enum fields to String in Room entities for KSP compatibility
- Added ChatMessageEntity and ChatConversationEntity for persistent conversations
- Schema version bumped to v3

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