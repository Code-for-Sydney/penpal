# Feature Notes — Tab by Tab

## Chat `:feature:chat`

**Purpose:** Conversational RAG interface. Queries go through `InferenceBridge` with vector-retrieved context. Uses Flow-based streaming with trie-based special token filtering.

**Key classes:**
- `ChatViewModel` — manages conversation history as `List<ChatMessage>` in `StateFlow`, uses `runInferenceFlow()` with full message history for context-aware prompts
- `ChatScreen` — lazy column of message bubbles; input field with context panel
- `StreamingTokenFilter` — removes Gemma 4 control tokens from model output (in `core:ai`)
- `GemmaSpecialTokens` — definitions for all control tokens (in `core:ai`)

**Flow:**
```
User types → ChatViewModel.sendMessage() → launch(Default)
  → VectorStore.similaritySearch()           // retrieve top-K chunks
  → buildPrompt(userMessage, chunks, history) // RAG context + conversation history
  → inferenceBridge.runInferenceFlow(prompt) // Flow-based streaming
       → conversation.sendMessageAsync()     // LiteRT-LM Engine API
       → renderMessageIntoString(message)     // Extract text from Message via getContents()
       → StreamingTokenFilter.append(chunk)  // Remove special tokens
  → Flow.collect { partialResult ->
       updateLastAssistantMessage(partialResult)
     }
  → _uiState.update()                        // StateFlow emits
  → Compose recompose                        // UI updates
```

**Navigation:**
- Chat FAB appears on Stacks/Settings tabs, hides when in Chat
- Clicking tabs while in Chat closes it via `popBackStack()`
- Consistent exit behavior via X button or tab selection

**Current Issue — Text Splitting After Special Characters:**
After implementing `StreamingTokenFilter`, chat text is split into separate lines after each special token occurrence. The Gemma 4 chat template includes structural newlines around turn tokens (e.g., `<|turn>model\n...\n<turn|>`), which remain after token removal.

**Investigation:**
- `renderMessageIntoString()` output format needs analysis
- Potential fix: newline coalescing in `StreamingTokenFilter`
- Potential fix: boundary whitespace trimming around removed tokens
- Long-term: track token types as annotated spans in the data model

**Structured output types (planned):**
```kotlin
sealed class SynthesisOutput {
    data class RawAnswer(val text: String, val citations: List<String>) : SynthesisOutput()
    data class Quizlet(val cards: List<FlashCard>) : SynthesisOutput()
    data class ConceptMap(val nodes: List<ConceptNode>, val edges: List<ConceptEdge>) : SynthesisOutput()
    data class Summary(val bullets: List<String>, val tldr: String) : SynthesisOutput()
}
```

---

## Stacks `:feature:stacks` — ✅ Implemented (Renamed from Notebooks)

**Purpose:** Rich note editor with embedded AI-generated content — graphs (rendered as node canvases similar to Blender/Nuke node editors), LaTeX blocks, drawings, audio recording, and integration maps. This is the "Think" tab.

**Migration (May 2026):**
- Renamed `feature/notebooks` → `feature/stacks`
- Model status indicator added to `NotebookListScreen` TopBar
- Block parsing: stacks process each block according to its type (PDF, Image, Audio, URL, Code)
- Per-block toggle: switch between original and parsed content

**Key classes:**
- `StackEditorViewModel` — manages `StackDocument` (blocks list as `StateFlow`)
- `StackModels.kt` — Block sealed class with all block types, `StackEvent` sealed class
- `BlockRenderer` — renders each `Block` type in Compose (text, image, graph, LaTeX, drawing, embed)
- `GraphNodeCanvas` — custom `Canvas`-based composable; nodes are draggable, pannable, zoomable
- `DrawingCanvas` — touch-based drawing with color palette, eraser, undo

**Block model (implemented):**
```kotlin
sealed class Block {
    data class TextBlock(val id: String, val content: String, val isEditing: Boolean) : Block()
    data class ImageBlock(val id: String, val uri: Uri?, val caption: String, val isEditing: Boolean) : Block()
    data class GraphBlock(val id: String, val graphId: String, val nodes: List<GraphNode>, val edges: List<GraphEdge>) : Block()
    data class LatexBlock(val id: String, val expression: String) : Block()
    data class DrawingBlock(val id: String, val pathData: String, val width: Float, val height: Float) : Block()
    data class EmbedBlock(val id: String, val sourceId: String, val preview: String, val type: EmbedType) : Block()
    data class ProcessBlock(val id: String, val sourceUri: String, val mediaType: MediaType, val parsedContent: String, val showParsedContent: Boolean) : Block()
}

enum class MediaType { TEXT, IMAGE, AUDIO, VIDEO, PDF, URL, CODE }
```

// Supporting types
data class GraphNode(val id: String, val label: String, var posX: Float, var posY: Float, val type: NodeType)
data class GraphEdge(val id: String, val fromNodeId: String, val toNodeId: String, val label: String, val type: EdgeType)
enum class NodeType { DEFAULT, CONCEPT, TOOL, DATA, STARRED }
enum class EdgeType { DEFAULT, LABELLED, BIDIRECTIONAL, HIGHLIGHTED }
```

**StackEvent (for image picker + navigation):**
```kotlin
sealed class StackEvent {
    data class AddBlock(val block: Block, val afterBlockId: String? = null)
    data class RemoveBlock(val blockId: String)
    data class UpdateTextBlock(val blockId: String, val content: String)
    data class SetImageUri(val blockId: String, val uri: Uri)  // Image picker event
    data class UpdateGraphNode(val node: GraphNode)
    data class AddGraphEdge(val edge: GraphEdge)
    data class AddDrawingPath(val pathData: String)
    data class ToggleProcessView(val blockId: String)
    data class UpdateSystemPrompt(val prompt: String)
    data class UpdateAgentPrompt(val prompt: String)
    // ...
}
```

**Image Picker (implemented):**
- Activity result launcher with `ActivityResultContracts.GetContent()` for gallery access
- `setImageUri()` method in `StackEditorViewModel` handles URI updates
- `onPickImage: (String) -> Unit` callback through `BlockCard` → `ImageBlockContent`
- `AsyncImage` from Coil library (`io.coil-kt:coil-compose:2.5.0`) for image display

**GraphNodeCanvas interaction (implemented):**
- Drag: `detectDragGestures` → update node `posX/posY` → callback to ViewModel
- Pan: `detectTransformGestures` with two fingers
- Zoom: pinch gesture with scale bounds (0.25x - 4x)
- Double-tap: creates new node at canvas position
- Long-press: shows context menu for node
- Edge rendering: `Canvas.drawPath` between connected node centers with arrow heads

**DrawingCanvas (implemented):**
- 8-color palette: black, gray, red, orange, blue, green, purple, pink
- Eraser mode (3x stroke width, draws white)
- Undo: `paths.dropLast(1)`
- Auto-hiding toolbar (5-second timeout)
- Path serialization: `"isEraser:colorHex:strokeWidth:points..."`

**LaTeX:** Rendered via MathJax in a `WebView` bridge. Isolated in a `@Composable fun LatexView(expression: String)` wrapper with `AndroidView`.

### Audio Recording in Stacks ✅ (May 2026)

**Source files:**
- `core/media/src/main/java/.../AudioRecorder.kt` — AudioRecord-based recorder
- `core/media/src/main/java/.../AudioAnalyzer.kt` — Real-time FFT spectrum analyzer

**AudioRecorder features:**
- 16 kHz sample rate, mono, 16-bit PCM, WAV format
- Real-time streaming to disk (`context.filesDir/recordings/`) — crash-safe
- Permission checking via `ActivityCompat.checkSelfPermission()` for `RECORD_AUDIO`
- Callbacks (all on main thread):
  - `onAmplitudeUpdate: ((Float) -> Unit)?` — RMS amplitude in dB
  - `onPcmBuffer: ((ShortArray, Int) -> Unit)?` — Raw PCM samples for analyzer
  - `onRecordingStarted`, `onRecordingStopped`, `onError`
- WAV header written at start, updated on stop via `RandomAccessFile`
- Helper: `getDurationMs(file)`, `getRecordings()`, `deleteRecording()`, `cancelRecording()`

**AudioAnalyzer features:**
- Real-time FFT using Cooley-Tukey radix-2 algorithm
- Hanning window applied before FFT
- 12 log-spaced frequency bins (bass → treble), normalized 0..1f
- Thread-safe: `feedPcmData()` uses `synchronized(lock)`, analysis on separate `Thread`
- `onSpectrumUpdate: ((FloatArray) -> Unit)?` — posted to main handler
- ~12.5 FPS spectrum update rate (80ms sleep between iterations)

**StackScreen integration:**
- **Scrollable toolbar** — Toolbar `Row` wrapped in `Modifier.horizontalScroll(rememberScrollState())` so all 14+ icon buttons scroll horizontally
- **Audio submenu** — Audio toolbar button shows a `DropdownMenu` with two options:
  - "Record Audio" — opens recording dialog
  - "Pick from Files" — opens file picker for existing audio files
- **Recording permission** — `audioPermissionLauncher` using `ActivityResultContracts.RequestPermission()` for `RECORD_AUDIO`
- **AudioRecordingDialog** — `AlertDialog` composable with 3 states:
  - `IDLE`: Shows "Start Recording" button
  - `RECORDING`: Shows elapsed timer (MM:SS), real-time FFT spectrum analyzer `Canvas` (12 green bars of varying height), and "Stop Recording" button
  - `DONE`: Shows file name + duration, "Use Recording" and "Discard" buttons
- **Auto-add on "Use Recording"**: Creates `Block.ProcessBlock(MediaType.AUDIO)` with `sourceUri = file.toURI().toString()` and adds via `StackEvent.AddBlock`

**Audio recording flow:**
```
User taps audio button → DropdownMenu → "Record Audio"
  → Permission check → dialog opens (IDLE)
  → User taps "Start Recording" → AudioRecorder.startRecording()
  → AudioAnalyzer.startAnalyzing()
  → Real-time FFT bars + timer update
  → User taps "Stop Recording" → AudioRecorder.stopRecording()
  → Dialog shows DONE state with file info
  → "Use Recording" → auto-adds ProcessBlock to stack
```

---

## Process + Add Data `:feature:process`

**Purpose:** Extraction queue management and data ingestion. This module exists but is not a primary tab in MainScreen. Processing functionality is integrated into stack auto-processing and chat file attachment flows.

**Entry points for ingestion:**
```kotlin
sealed class IngestionSource {
    data class FileUri(val uri: Uri, val mimeType: String) : IngestionSource()
    data class YouTubeUrl(val url: String) : IngestionSource()
    data class RecordedAudio(val file: File) : IngestionSource()
    data class LinkUrl(val url: String) : IngestionSource()
    data class CodeSnippet(val language: String, val code: String) : IngestionSource()
}
```

**ProcessViewModel responsibilities:**
1. Receive `IngestionSource` from bottom sheet
2. Create `ExtractionJobEntity` in Room
3. Enqueue `ExtractionWorker` via WorkManager
4. Expose `queue: Flow<List<ExtractionJob>>` to UI (Room query → Flow, no polling)
5. Expose `workInfoMap: Flow<Map<String, ExtractionStatus>>` per job

**Extraction protocol selection:** User picks a rule from `ExtractionConfig` (loaded from DataStore). Default rules are seeded on first launch:
```kotlin
val DEFAULT_RULES = listOf(
    ExtractionRule("full_text",   "Full text",          RuleTarget.FULL_TEXT),
    ExtractionRule("fft_peaks",   "Extract FFT peaks",  RuleTarget.FFT_PEAKS,
        params = mapOf("window" to "hanning", "resolution" to "1hz")),
    ExtractionRule("dicom_meta",  "Parse DICOM metadata", RuleTarget.DICOM_METADATA,
        params = mapOf("targets" to "SQ,DS,IS")),
    ExtractionRule("transcript",  "Audio transcript",   RuleTarget.WHISPER_TRANSCRIPT),
    ExtractionRule("ocr",         "Image OCR",          RuleTarget.IMAGE_OCR),
)
```

---

## Organize `:feature:organize`

**Purpose:** 2D/3D knowledge graph. Nodes are research papers, concepts, tools, data models. Edges are typed relations. Users can pan, zoom, drag nodes, and tap to expand.

**Rendering:**
- 2D mode: custom `Canvas` composable with Force-directed layout (Fruchterman–Reingold, run on `Dispatchers.Default`)
- 3D mode: SceneView (`io.github.sceneview:sceneview`) or simple `OpenGL ES` surface with billboarded sprites for nodes

**Force layout (runs off main thread):**
```kotlin
suspend fun computeLayout(
    nodes: List<GraphNodeEntity>,
    edges: List<GraphEdgeEntity>,
    iterations: Int = 100,
): List<GraphNodeEntity> = withContext(Dispatchers.Default) {
    val positions = nodes.associate { it.id to MutableVector2(it.posX, it.posY) }.toMutableMap()
    repeat(iterations) {
        applyRepulsion(positions, nodes)
        applyAttraction(positions, edges)
        applyCooling(it, iterations)
    }
    nodes.map { it.copy(posX = positions[it.id]!!.x, posY = positions[it.id]!!.y) }
}
```

**Node tap → Synthesis Hub:** Tapping a node opens a bottom sheet that queries `InferenceEngine` with the node's connected context as the prompt seed. This is the bridge between Organize and Chat.

---

## Settings `:feature:settings`

**Purpose:** Technical configuration exposed to power users. Reads/writes `AppSettings` proto via `SettingsRepository`.

**Sections:**
- AI Infrastructure: model selection (NEURAL-3 / TITAN-X / LEGACY-S), viz engine (2D/3D)
- Data & Protocol: WAV extraction toggle, HIPAA cloud sync toggle, custom rule editor
- Resource Allocation: buffer persistence slider (256MB–4GB), thread count slider (1–16)
- Danger zone: "Purge local vector cache" → `VectorStoreRepository.clearAll()` + Room nuke

**WorkManager parallelism from settings:**
```kotlin
// Applied at app startup in PenpalApp.onCreate()
val config = Configuration.Builder()
    .setMaxSchedulerLimit(settings.threadCount.coerceIn(1, 16))
    .setWorkerFactory(hiltWorkerFactory)
    .build()
WorkManager.initialize(context, config)
```

---

## Bottom navigation

**Note:** The actual `MainScreen.kt` has 2 tabs in bottom navigation: Think (Stacks), Settings. Chat is accessible via the FAB in the bottom-right corner.

```kotlin
sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    data object Stacks : Screen("stacks", "Think", Icons.Default.AutoAwesome)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}
```

Tab state survives configuration changes via `rememberNavController()` + `saveState = true` on `popBackStack`. ViewModels are instantiated manually with `remember { ... }` in `MainScreen.kt`.
