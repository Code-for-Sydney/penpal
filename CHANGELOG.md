# Changelog

All notable changes to the Penpal project.

## [Unreleased]

### Added
- Audio Evaluation System with speech-to-text transcription
- Evaluation panel UI with recording controls and score display
- Question types: SHORT_ANSWER, PRONUNCIATION, READING, LISTENING
- Session management with JSON/CSV export
- Audio amplitude visualization during recording
- Color-coded feedback scores (green ≥70%, orange ≥50%, red <50%)

### Planned
- Chat system with context injection
- Math tools (WebSearchTool, MathTool, ToolSet)
- Math Lab UI with symbol keyboard
- C++ Math engine integration

### Components Added
- `AudioEvaluationManager.kt` - Session orchestration
- `EvaluationEngine.kt` - Similarity scoring
- `EvaluationModels.kt` - Data classes
- `AudioRecorder.kt` - Recording with amplitude monitoring
- `SpeechToText.kt` - Transcription via Gemma

## Version History

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