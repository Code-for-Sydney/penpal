# Penpal Architecture

This document provides an in-depth look at the system architecture, component relationships, and data flow in the Penpal application.

## High-Level Overview

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
│  │  ┌───────────┐ ┌─────────────────────────────────────────┐ ││
│  │  │ TextItem  │ │         Undo/Redo System                │ ││
│  │  └───────────┘ └─────────────────────────────────────────┘ ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              HandwritingRecognizer (Gemma)                  ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                      PdfSelectionActivity                       │
│                      (Snippet Extractor)                        │
└─────────────────────────────────────────────────────────────────┘
```

## Activity Flow

### 1. NotebookSelectionActivity (Entry Point)

**Purpose**: Display list of notebooks and manage notebook CRUD operations.

**Key Responsibilities**:
- Display all notebooks in a grid
- Create/edit/delete notebooks
- Handle PDF import to create new notebooks
- Manage Gemma model download and initialization

**Navigation**:
- Tap notebook → Launch `MainActivity`
- Tap "+" → Create new notebook
- Tap "Import PDF" → Launch `PdfImportActivity`

### 2. MainActivity (Drawing Canvas)

**Purpose**: Main drawing interface with AI recognition and audio evaluation.

**Key Responsibilities**:
- Render `DrawingView` canvas
- Handle toolbar interactions
- Manage recognition state
- Coordinate autosave
- Handle page navigation
- Orchestrate audio evaluation sessions

**State Management**:
- `activeColor`: Current brush color
- `currentPageIndex`: Currently displayed page
- `notebookId/notebookName`: Current notebook identifier
- `allMatches`: Search results cache
- `evaluationManager`: Audio evaluation session manager
- `isEvaluationActive`: Evaluation session state flag

### 3. PdfImportActivity (PDF Page Selection)

**Purpose**: Allow users to select specific PDF pages for import.

**Key Responsibilities**:
- Display PDF pages as thumbnails
- Allow multi-select of pages
- Return selected page indices

### 4. PdfSelectionActivity (Region Cropping)

**Purpose**: Crop a region from a PDF page to insert as an image.

**Key Responsibilities**:
- Render PDF page at high resolution
- Provide selection frame interface
- Extract digital text from selected region
- Return cropped image with text data

## Core Components

### DrawingView

The central component handling all canvas operations.

```
┌─────────────────────────────────────────────────────────────────┐
│                         DrawingView                              │
├─────────────────────────────────────────────────────────────────┤
│ Data Structures                                                  │
│  - drawItems: List<CanvasItem>        // All canvas items       │
│  - undoStack/redoStack: List<UndoAction> // Command pattern      │
│  - selectedItems: List<CanvasItem>   // Current selection       │
│  - lassoPoints: List<PointF>         // Lasso path points        │
├─────────────────────────────────────────────────────────────────┤
│ Canvas Items (Sealed Class Hierarchy)                            │
│                                                                  │
│  sealed class CanvasItem {                                       │
│      data class StrokeItem(...)  // Raw drawing strokes          │
│      data class WordItem(...)    // Recognized word (strokes+text)│
│      data class ImageItem(...)   // Bitmaps (PDF, photos)        │
│      data class PromptItem(...)  // AI prompt/response boxes    │
│      data class TextItem(...)    // Plain text elements          │
│  }                                                            │
├─────────────────────────────────────────────────────────────────┤
│ Touch Handling                                                   │
│  - Single finger: Drawing or item manipulation                   │
│  - Two fingers: Pan/zoom canvas                                 │
│  - Lasso tool: Freeform selection                               │
│  - Selection handles: Resize, rotate, delete                    │
├─────────────────────────────────────────────────────────────────┤
│ Rendering Pipeline                                               │
│  1. Clear background                                             │
│  2. Draw paper pages (for notebooks)                            │
│  3. Draw paper lines (ruled/graph)                              │
│  4. Apply view transform (zoom/pan)                             │
│  5. Draw canvas items (with culling)                            │
│  6. Draw in-progress stroke                                     │
│  7. Draw lasso path                                             │
│  8. Draw selection UI                                           │
└─────────────────────────────────────────────────────────────────┘
```

#### Canvas Transformation

DrawingView uses a matrix-based transformation system for infinite canvas:

```
viewMatrix = Scale(scaleFactor) + Translate(translateX, translateY)
inverseMatrix = inverse(viewMatrix)

screenToCanvas(x, y):
    pts = [x, y]
    inverseMatrix.mapPoints(pts)
    return pts
```

#### Zoom Constraints

```kotlin
MIN_ZOOM:
    - NOTEBOOK: min(fitWidth, fitHeight, 0.1)
    - WHITEBOARD: 0.25

MAX_ZOOM:
    - NOTEBOOK: 5.0
    - WHITEBOARD: 100.0
```

### HandwritingRecognizer

Singleton wrapper for LiteRT-LM (Gemma) inference engine.

```
┌─────────────────────────────────────────────────────────────────┐
│                    HandwritingRecognizer                         │
├─────────────────────────────────────────────────────────────────┤
│ State                                                            │
│  - engine: Engine?            // LiteRT-LM engine instance       │
│  - isReady: Boolean          // Model loaded and ready          │
│  - requestChannel: Channel   // Queue for recognition requests   │
├─────────────────────────────────────────────────────────────────┤
│ Public API                                                       │
│  - load(modelPath, onReady, onError)                             │
│  - recognize(bitmap, prompt, callbacks)                          │
│  - recognizeSuspend(bitmap, prompt): String                     │
├─────────────────────────────────────────────────────────────────┤
│ Internal Flow                                                    │
│  1. Receive RecognitionRequest via channel                       │
│  2. Convert Bitmap to JPEG bytes                                 │
│  3. Create multimodal Content with image + prompt                │
│  4. Stream tokens back via onPartialResult                      │
│  5. Call onDone when complete                                   │
└─────────────────────────────────────────────────────────────────┘
```

#### Recognition Prompts

**Full Page Analysis**:
```
Detect all handwriting in this image. For each word, number, or star (*), 
provide its text and its bounding box in JSON format: 
[{"text": "...", "box_2d": [ymin, xmin, ymax, xmax]}, ...]. 
Coordinates are 0-1000 relative to the image. Output ONLY the JSON.
```

**Word Recognition**:
```
Analyze the handwriting in this image. What word, letter, number, or 
text is drawn? Detect symbols like stars (*) or asterisks as well. 
Reply with ONLY the recognized text.
```

### SvgSerializer

Handles SVG persistence for notebook pages.

```
┌─────────────────────────────────────────────────────────────────┐
│                        SvgSerializer                             │
├─────────────────────────────────────────────────────────────────┤
│ Serialized Data Types                                            │
│                                                                  │
│  sealed class SvgData                                           │
│      data class StrokeData(...)    // Path + styling            │
│      data class ImageData(...)      // Base64 + transform        │
│      data class WordData(...)       // Strokes + text overlay     │
│      data class PromptData(...)     // AI prompt box              │
│      data class TextData(...)       // Plain text                │
├─────────────────────────────────────────────────────────────────┤
│ Path Commands (for SVG path d attribute)                        │
│                                                                  │
│  sealed class PathCommand                                       │
│      MoveTo(x, y)                                               │
│      LineTo(x, y)                                               │
│      QuadTo(x1, y1, x2, y2)                                     │
│      CubicTo(x1, y1, x2, y2, x3, y3)                           │
└─────────────────────────────────────────────────────────────────┘
```

### ModelManager

Handles Gemma model download and discovery.

```
┌─────────────────────────────────────────────────────────────────┐
│                         ModelManager                             │
├─────────────────────────────────────────────────────────────────┤
│ Model Discovery Order                                           │
│  1. App's external files directory                              │
│  2. SharedPreferences saved path                                 │
│  3. Common download locations                                    │
├─────────────────────────────────────────────────────────────────┤
│ Download Sources                                                │
│  - HuggingFace: litert-community/gemma-4-E2B-it-litert-lm        │
│  - Kaggle: google/gemma-4/tfLite/gemma4-e2b-it-web/1             │
├─────────────────────────────────────────────────────────────────┤
│ Key Files                                                        │
│  - MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"                   │
│  - MODEL_SIZE_DISPLAY = "~2.6 GB"                               │
└─────────────────────────────────────────────────────────────────┘
```

### AudioEvaluationManager

Manages audio evaluation sessions with speech-to-text and similarity scoring.

```
┌─────────────────────────────────────────────────────────────────┐
│                  AudioEvaluationManager                          │
├─────────────────────────────────────────────────────────────────┤
│ Dependencies                                                    │
│  - AudioRecorder: Audio capture with amplitude monitoring      │
│  - SpeechToText: Transcription via Gemma                       │
│  - EvaluationEngine: Similarity scoring                         │
├─────────────────────────────────────────────────────────────────┤
│ State                                                           │
│  - currentSession: EvaluationSession?                           │
│  - isActive: Boolean                                           │
├─────────────────────────────────────────────────────────────────┤
│ Session Lifecycle                                               │
│  1. createSession(questions, notebookId)                        │
│  2. startRecording() → AudioRecorder.start()                   │
│  3. stopRecording() → SpeechToText.transcribe()                 │
│  4. evaluateAnswer() → EvaluationEngine.evaluate()             │
│  5. moveToNextQuestion() / completeSession()                    │
├─────────────────────────────────────────────────────────────────┤
│ Export Formats                                                  │
│  - JSON: Full session data for reloading                        │
│  - CSV: Results for spreadsheet analysis                        │
└─────────────────────────────────────────────────────────────────┘
```

### EvaluationEngine

Evaluates transcribed answers against expected answers using similarity metrics.

```
┌─────────────────────────────────────────────────────────────────┐
│                      EvaluationEngine                            │
├─────────────────────────────────────────────────────────────────┤
│ Scoring Components                                              │
│  - Word Match: Intersection of expected vs transcribed words    │
│  - Speaking Pace: Words per minute metric                       │
│  - Silence Percentage: Recording quality indicator             │
├─────────────────────────────────────────────────────────────────┤
│ Similarity Score                                                │
│  - Weighted combination of word match and pronunciation         │
│  - Passing threshold: configurable per question (default 0.7)    │
└─────────────────────────────────────────────────────────────────┘
```

### SpeechToText

Transcribes audio recordings using the Gemma model.

```
┌─────────────────────────────────────────────────────────────────┐
│                        SpeechToText                              │
├─────────────────────────────────────────────────────────────────┤
│ Transcription Methods                                           │
│  - transcribe(audioFile): Suspend function for coroutines      │
│  - transcribeAsync(): Callback-based async transcription       │
│  - transcribeStreaming(): Process audio chunks sequentially     │
├─────────────────────────────────────────────────────────────────┤
│ Transcription Prompts                                           │
│  - DEFAULT_TRANSCRIPTION: General speech capture                │
│  - PRONUNCIATION_EVALUATION: Phonetic accuracy focus           │
│  - READING_EVALUATION: Reading passage analysis                │
│  - FREE_RESPONSE: Open-ended response capture                  │
└─────────────────────────────────────────────────────────────────┘
```

### Question Types

| Type | Purpose | Scoring Focus |
|------|---------|-------------|
| SHORT_ANSWER | Free-form written answer evaluation | Word match |
| PRONUNCIATION | Speech pronunciation practice | Phonetic accuracy |
| READING | Reading passage fluency | Word sequence match |
| LISTENING | Comprehension response | Content understanding |

## Data Flow

### Drawing Session Flow

```
User Input (Touch)
       │
       ▼
┌──────────────────┐
│ onTouchEvent()   │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐     ┌──────────────────┐
│ Identify Action  │────▶│ DrawingView      │
│ (draw/pan/select)│     │ State Update    │
└────────┬─────────┘     └────────┬─────────┘
         │                        │
         ▼                        ▼
┌──────────────────┐     ┌──────────────────┐
│ Transform Item   │     │ invalidate()     │
│ Update Matrix    │     │ Request redraw  │
└────────┬─────────┘     └────────┬─────────┘
         │                        │
         ▼                        ▼
┌──────────────────┐     ┌──────────────────┐
│ Push UndoAction  │     │ onDraw()         │
│ (Command Pattern)│     │ Render items     │
└────────────────┬─┘     └──────────────────┘
                 │
                 ▼
        ┌──────────────────┐
        │ onStateChanged() │
        └────────┬─────────┘
                 │
         ┌───────┴───────┐
         ▼               ▼
┌─────────────┐   ┌─────────────┐
│ Autosave    │   │ Recognition │
│ (2s debounce)│   │ (2s debounce)│
└─────────────┘   └──────┬──────┘
                         │
                         ▼
                ┌─────────────────┐
                │ Gemma Inference │
                │ & Text Overlay  │
                └─────────────────┘
```

### Save/Load Flow

```
SAVE (Autosave every 2s)
─────────────────────────
DrawingView.getSvgDataList()
         │
         ▼
SvgSerializer.serialize(items, dimensions, bgType)
         │
         ▼
File("${notebookName}_page_${index}.svg").writeText(svg)
         │
         ▼
DrawingView.createPageThumbnail()
         │
         ▼
File("${notebookName}_page_${index}_thumb.png").writeBytes(png)

LOAD (On Activity Start)
─────────────────────────
NotebookManager.getNotebooks() → Load metadata from SharedPrefs
         │
         ▼
For each page file:
    file.readText() → SvgSerializer.deserialize()
         │
         ▼
DrawingView.loadFromSvgData(items)
         │
         ▼
Background: Trigger page analysis with Gemma
```

### Recognition Flow

```
STROKE COMPLETE (User lifts finger)
         │
         ▼
scheduleRecognition() [2s debounce]
         │
         ▼
triggerRecognition() [after delay]
         │
         ▼
DrawingView.createFullPageBitmap(pageIndex)
         │
         ▼
HandwritingRecognizer.recognize(bitmap, prompt)
         │
         ├── onPartialResult → Update text display
         │
         ▼
onDone → Parse JSON → List<DetectedBox>
         │
         ▼
DrawingView.groupStrokesByBoxes(detectedBoxes)
         │
         ▼
For each box:
    - Find strokes inside bounds
    - Create WordItem with strokes + text
    - Remove original strokes
         │
         ▼
scheduleAutosave()
```

### Evaluation Flow

```
START EVALUATION (User taps mic button)
         │
         ▼
 requestAudioPermissionAndStartEvaluation()
         │
         ▼
 AudioEvaluationManager.createSession(questions)
         │
         ▼
 showQuestion(question, index) → Display in evaluation panel
         │
         ▼
 START RECORDING (User taps record)
         │
         ▼
 AudioRecorder.start() → Record to temp file
         │
         ├── onAmplitudeUpdate → Update amplitude bar
         │
         ▼
 onSilenceTimeout → Auto-stop on silence
         │
         ▼
 AudioRecorder.stop() → Return audio file
         │
         ▼
 SpeechToText.transcribe(audioFile) → Gemma inference
         │
         ▼
 EvaluationEngine.evaluate(transcription, question)
         │
         ▼
 onEvaluationComplete(result) → Show score + feedback
         │
         ▼
 MOVE TO NEXT (User taps next or session ends)
         │
         ▼
 completeSession() → Create SessionSummary
         │
         ▼
 saveSession() + onSessionComplete(summary) → Show summary dialog
```

## Undo/Redo System

Uses the Command Pattern with action composition:

```kotlin
interface UndoAction {
    fun undo()
    fun redo()
}

// Single actions
AddItemAction(item)
RemoveItemAction(item, index)
TransformAction(item, oldState, newState)
StyleAction(item, property, oldValue, newValue)
GroupAction(strokes, words, newWord)

// Composable actions (for grouped operations)
FusedAutoGroupAction(lastStroke, groupAction)
GroupTransformAction(items, oldStates, newStates)
```

**Push/Pop Flow**:
```kotlin
fun pushAction(action: UndoAction, executeNow: Boolean = true) {
    if (executeNow) action.redo()
    undoStack.add(action)
    redoStack.clear()
    onStateChanged?.invoke()
}
```

## Memory Management

### Bitmap Handling

1. **PDF Import**: Render at 2x scale, cap at 2048px max dimension
2. **Thumbnails**: 20% scale of original
3. **Full Page Bitmap**: Created on-demand for recognition, released after use
4. **ImageItem Display**: Use `displayBitmap` property with background removal cache

### Cache Invalidation

```kotlin
// Invalidate when transform changes
ImageItem.invalidateCache() → _processedBitmap = null
WordItem.invalidateCache() → cachedBounds = null
```

## Navigation

### Page Creation (Notebook Mode)

```kotlin
updateMatrix(allowPageCreation = true)
         │
         ▼
if (translateY <= trigger) {
    numPages++
    onPageAdded?.invoke()
}
```

### Page Deletion

```kotlin
deletePageAndShift(pageIndex)
         │
         ▼
For each page > deletedIndex:
    Rename page_n to page_(n-1)
    Rename thumb_n to thumb_(n-1)
         │
         ▼
DrawingView.numPages--
loadAllPages() // Reload with updated indices
```

## Search Architecture

```kotlin
class SearchMatch(
    val pageIndex: Int,
    val text: String,
    val itemIndex: Int,
    val subRect: RectF? = null  // PDF word bounds
)

performGlobalSearch(query):
    For each page file:
        If contains "data-text=":
            Deserialize SVG
            For each item:
                If text contains query:
                    Add SearchMatch
    Return all matches sorted by page
```

## Key Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| PAGE_WIDTH | 2800 | Canvas width in pixels |
| PAGE_HEIGHT | 3960 | Canvas height in pixels |
| PAGE_MARGIN | 168 | Vertical gap between pages |
| TOUCH_TOLERANCE | 4 | Path smoothing threshold |
| SELECTION_BUFFER | 20 | Selection box padding |
| AUTOSAVE_DEBOUNCE | 2000ms | Autosave delay |
| RECOGNITION_DEBOUNCE | 2000ms | Recognition trigger delay |

## Extension Points

### Adding New Canvas Items

1. Add data class extending `CanvasItem` in `DrawingView.kt`
2. Implement `draw(canvas: Canvas)` extension
3. Implement `toSvgData(): SvgData` serialization
4. Add handling in `SvgSerializer.deserialize()`
5. Add touch handling in `onTouchEvent()`
6. Add selection UI in `drawSelectionBox()`

### Adding New Tools

1. Add entry to `ActiveTool` enum
2. Set `activeTool` in toolbar handlers
3. Add handling in `onTouchEvent()` based on tool
4. Update `updateToolState()` for button highlighting

### Adding Export Formats

1. Add format handler in `showExportDialog()`
2. Implement `performXxxExport(uri)` method
3. Use `DrawingView.renderToExternalCanvas()` for rendering

### Adding Evaluation Question Types

1. Add new entry to `QuestionType` enum in `EvaluationModels.kt`
2. Update `displayName` property with human-readable name
3. Add corresponding transcription prompt in `TranscriptionPrompts`
4. Implement scoring logic in `EvaluationEngine`
5. Add test cases for new question type

### Adding AI Tools (Chat, Math, Search)

1. Create tool class extending base tool interface
2. Implement tool execution logic
3. Add tool registration in `ToolSet`
4. Create UI panel for tool input/output
5. Add tool-specific callbacks in `MainActivity`