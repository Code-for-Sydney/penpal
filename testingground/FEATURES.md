# Feature Notes — Tab by Tab

## Chat `:feature:chat`

**Purpose:** Conversational RAG interface. Queries go through `InferenceEngine` with vector-retrieved context. Supports quizlet generation, concept maps, and paper analysis as structured outputs.

**Key classes:**
- `ChatViewModel` — manages conversation history as `List<Message>` in `StateFlow`
- `SynthesisHub` — wraps `InferenceEngine`, formats structured outputs (quizlet, summary, concept map)
- `ChatScreen` — lazy column of `MessageBubble` composables; input field with mic toggle

**Flow:**
```
User types → ChatViewModel.send() → launch(defaultDispatcher)
  → VectorStore.similaritySearch()     // retrieve top-K chunks
  → InferenceEngine.query()            // prompt + context → response
  → _messages.update()                 // StateFlow emits
  → Compose recompose                  // UI updates
```

**Structured output types:**
```kotlin
sealed class SynthesisOutput {
    data class RawAnswer(val text: String, val citations: List<String>) : SynthesisOutput()
    data class Quizlet(val cards: List<FlashCard>) : SynthesisOutput()
    data class ConceptMap(val nodes: List<ConceptNode>, val edges: List<ConceptEdge>) : SynthesisOutput()
    data class Summary(val bullets: List<String>, val tldr: String) : SynthesisOutput()
}
```

---

## Notebooks `:feature:notebooks` — ✅ Implemented

**Purpose:** Rich note editor with embedded AI-generated content — graphs (rendered as node canvases similar to Blender/Nuke node editors), LaTeX blocks, drawings, and integration maps. This is the "Think" tab.

**Key classes:**
- `NotebookEditorViewModel` — manages `NotebookDocument` (blocks list as `StateFlow`)
- `NotebookModels.kt` — Block sealed class with all block types, `NotebookEvent` sealed class
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
}

// Supporting types
data class GraphNode(val id: String, val label: String, var posX: Float, var posY: Float, val type: NodeType)
data class GraphEdge(val id: String, val fromNodeId: String, val toNodeId: String, val label: String, val type: EdgeType)
enum class NodeType { DEFAULT, CONCEPT, TOOL, DATA, STARRED }
enum class EdgeType { DEFAULT, LABELLED, BIDIRECTIONAL, HIGHLIGHTED }
```

**NotebookEvent (for image picker + navigation):**
```kotlin
sealed class NotebookEvent {
    data class AddBlock(val block: Block, val afterBlockId: String? = null)
    data class RemoveBlock(val blockId: String)
    data class UpdateTextBlock(val blockId: String, val content: String)
    data class SetImageUri(val blockId: String, val uri: Uri)  // Image picker event
    data class UpdateGraphNode(val node: GraphNode)
    data class AddGraphEdge(val edge: GraphEdge)
    data class AddDrawingPath(val pathData: String)
    // ...
}
```

**Image Picker (implemented):**
- Activity result launcher with `ActivityResultContracts.GetContent()` for gallery access
- `setImageUri()` method in `NotebookEditorViewModel` handles URI updates
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

---

## Process + Add Data `:feature:process`

**Purpose:** Extraction queue management and data ingestion. "Add Data" is a `ModalBottomSheet` within this screen — not a separate tab. Keeps the bottom nav clean.

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

```kotlin
enum class PenpalTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    CHAT      ("chat",      R.string.tab_chat,      Icons.Outlined.Chat),
    NOTEBOOKS ("notebooks", R.string.tab_notebooks, Icons.Outlined.MenuBook),
    PROCESS   ("process",   R.string.tab_process,   Icons.Outlined.AccountTree),
    ORGANIZE  ("organize",  R.string.tab_organize,  Icons.Outlined.Hub),
    SETTINGS  ("settings",  R.string.tab_settings,  Icons.Outlined.Settings),
}
```

Tab state survives configuration changes via `rememberNavController()` + `saveState = true` on `popBackStack`. Each tab gets its own `NavBackStackEntry`-scoped ViewModel so switching tabs doesn't destroy state.
