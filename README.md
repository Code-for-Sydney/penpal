# Penpal - Handwriting Recognition Drawing App

A feature-rich Android drawing and notebook application with on-device AI handwriting recognition powered by Google Gemma.

## Overview

Penpal transforms your device into a smart digital notebook with the following capabilities:

- **Handwriting Recognition**: On-device OCR using Gemma 4 E2B model via LiteRT-LM
- **Multi-page Notebooks**: Create notebooks with unlimited pages that scroll vertically
- **Whiteboard Mode**: Infinite canvas for brainstorming and sketching
- **PDF Import**: Import PDF documents and annotate them
- **Lasso Selection**: Select and manipulate groups of drawn elements
- **Search**: Full-text search across all notebook pages including digital text from PDFs
- **Export**: Export drawings to PDF, SVG, or PNG formats

## Screenshots

The app features a dark-themed UI with:
- Floating toolbar for drawing tools
- AI recognition status panel
- Page navigation and overview
- Color picker with custom HSV support
- Search functionality across all pages

## Build Configuration

| Component | Version |
|-----------|---------|
| AGP | 9.1.1 |
| Kotlin | 2.2.10 |
| Compile SDK | 34 |
| Min SDK | 24 |
| Target SDK | 34 |

## Key Dependencies

- **com.google.ai.edge.litertlm:litertlm-android** - On-device LLM inference (Gemma)
- **com.tom-roush:pdfbox-android:2.0.27.0** - PDF text extraction
- **org.jetbrains.kotlinx:kotlinx-coroutines** - Asynchronous programming
- **com.google.code.gson:gson** - JSON serialization

## Installation

### Prerequisites

1. Android Studio Hedgehog (2024.1.1) or later
2. Android SDK 34
3. Kotlin 2.2.10

### Build Steps

1. Clone the repository:
```bash
git clone https://github.com/your-username/penpal.git
cd penpal
```

2. Open in Android Studio

3. Sync Gradle files

4. Build and run on device or emulator

### Model Download

On first launch, the app will prompt you to download the Gemma model (~2.6 GB).
The model is hosted on HuggingFace and requires a token for download.

Alternatively, download from Kaggle with API credentials.

## Usage

### Creating Notebooks

1. Launch the app to see the notebook selection screen
2. Tap the **+** button to create a new notebook
3. Choose notebook type (Notebook with pages or Whiteboard)
4. Select a color for the notebook
5. Configure default background (Ruled, Graph, or None)

### Drawing

- **Brush**: Free-form drawing with adjustable size and color
- **Eraser**: Remove strokes
- **Lasso**: Select multiple items for manipulation
- **Select**: Single item selection and editing

### AI Recognition

1. Draw text on the canvas
2. The AI automatically analyzes strokes after a 2-second pause
3. Recognized text appears overlaid on the strokes
4. Tap the **T** button to toggle between stroke view and text view

### Lasso Operations

1. Use the Lasso tool to draw a selection around items
2. Selected items can be:
   - **Moved**: Drag to reposition
   - **Resized**: Use the bottom-right handle
   - **Rotated**: Use the top rotation handle
   - **Deleted**: Tap the red **X** button
   - **Grouped**: Lasso selects multiple items together

### Page Management

- **Scroll vertically** in notebook mode to navigate pages
- New pages are created automatically when scrolling past the last page
- Tap the **Overview** button to see all pages as thumbnails
- Delete pages from the overview dialog

### PDF Import

1. Tap the **Import PDF** button (floating action button)
2. Select a PDF file from your device
3. Choose which pages to import
4. The PDF pages are converted to locked image items

### PDF Snippet Insertion

1. In drawing mode, tap the **Add PDF** button
2. Select a PDF file
3. Use the selection tool to crop a region
4. The cropped region is inserted as an image with extracted text

### Exporting

1. Tap the **Export** button
2. Choose format: PDF, SVG, or PNG
3. Select save location via system file picker

### Search

1. Tap the **Search** button
2. Type your query
3. Results highlight matching items across all pages
4. Navigate between matches with prev/next buttons

### Marker Navigation

Draw a star (*) anywhere in your notes as a quick navigation marker.
Tap the marker button to jump between marked locations.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed system design documentation.

## File Storage

### Notebook Data

```
app_data/
└── files/
    └── notebooks/
        ├── My Notebook_page_0.svg
        ├── My Notebook_page_0_thumb.png
        ├── My Notebook_page_1.svg
        ├── My Notebook_page_1_thumb.png
        └── ...
```

- SVG files contain all drawing data (strokes, images, text)
- PNG thumbnails are generated for the overview screen
- Thumbnails are auto-generated during autosave

### Model Storage

The Gemma model is stored in the app's external files directory:
```
Android/data/com.drawapp/files/gemma-4-E2B-it.litertlm
```

### SharedPreferences

- `NotebookPrefs`: Notebook list and settings
- `penpal_prefs`: Model path and download state

## Troubleshooting

### Model Not Found
If the app shows "Model not found", ensure you:
1. Accepted the Gemma license on HuggingFace or Kaggle
2. Have a stable internet connection
3. Have sufficient storage space (~3 GB)

### Recognition Not Working
1. Check that the Gemma model is loaded (look for green AI icon)
2. Ensure strokes are clear and not too small
3. Try drawing larger, more distinct characters

### App Crashes
1. Clear app data and restart
2. Ensure sufficient memory is available
3. Check for corrupted notebook files in the file manager

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for development guidelines.

## License

This project is for educational and personal use. The Gemma model is subject to Google's terms of service.

## Acknowledgments

- **Google Gemma**: On-device AI model
- **Tom Roush**: PDFBox-Android library
- **Android Open Source Project**: Material Design components