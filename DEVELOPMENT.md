# Development Guide

This guide provides instructions for setting up a development environment and understanding the codebase for contributing to Penpal.

## Environment Setup

### Required Tools

1. **Android Studio Hedgehog (2024.1.1)** or later
2. **Android SDK 34**
3. **JDK 17** or later
4. **Git**

### Initial Setup

```bash
# Clone repository
git clone https://github.com/your-username/penpal.git
cd penpal

# Open in Android Studio
# File → Open → Select penpal directory

# Sync Gradle
# File → Sync Project with Gradle Files

# Build
# Build → Make Project (Ctrl+F9)
```

### Running on Device/Emulator

1. Connect device or start emulator
2. Select device from run configuration dropdown
3. Click Run (Shift+F10)

## Project Structure

```
penpal/
├── app/
│   └── src/
│       └── main/
│           ├── java/com/drawapp/
│           │   ├── MainActivity.kt           # Main drawing screen
│           │   ├── DrawingView.kt            # Canvas custom view
│           │   ├── NotebookSelectionActivity.kt # Home screen
│           │   ├── NotebookManager.kt        # Notebook persistence
│           │   ├── Notebook.kt               # Notebook data model
│           │   ├── HandwritingRecognizer.kt   # Gemma AI wrapper
│           │   ├── ModelManager.kt            # Model download management
│           │   ├── ModelDownloadHelper.kt     # Download UI helpers
│           │   ├── ModelDownloadReceiver.kt  # Download broadcast receiver
│           │   ├── SvgSerializer.kt           # SVG persistence
│           │   ├── PdfHelper.kt               # PDF text extraction
│           │   ├── PdfSelectionActivity.kt    # PDF region cropping
│           │   ├── PdfImportActivity.kt       # PDF page selection
│           │   ├── SelectionFrameView.kt      # Crop selection view
│           │   ├── NotebookAdapter.kt         # RecyclerView adapter
│           │   ├── PenpalApplication.kt       # Application class
│           │   └── TestReflection.kt          # Testing utilities
│           │
│           └── res/
│               ├── layout/                    # Activity and dialog layouts
│               ├── drawable/                   # Icons and shapes
│               ├── values/                     # Strings, colors, themes
│               └── mipmap/                     # App icons
│
├── build.gradle                     # Root build config
├── settings.gradle                  # Project settings
├── gradle.properties               # Gradle configuration
└── gradle/
    └── wrapper/                     # Gradle wrapper files
```

## Code Style Guidelines

### Kotlin Conventions

1. **Naming**
   - Classes: PascalCase (e.g., `DrawingView`)
   - Functions: camelCase (e.g., `performAutosave`)
   - Properties: camelCase (e.g., `activeColor`)
   - Constants: SCREAMING_SNAKE_CASE (e.g., `PAGE_WIDTH`)

2. **Visibility**
   - Use `private` for internal implementation details
   - Use `internal` for module-internal APIs
   - Use `public` only for intended public APIs

3. **Null Safety**
   - Prefer `?.` and `?:` operators over null checks
   - Use `lateinit` for views initialized in `onCreate`
   - Use `nullable` types for optional return values

### View Handling

```kotlin
// DO: Use lateinit for views
private lateinit var drawingView: DrawingView

// DO: Initialize in onCreate
drawingView = findViewById(R.id.drawingView)

// DON'T: Make views nullable unless necessary
// private var drawingView: DrawingView? = null
```

### Coroutine Usage

```kotlin
// DO: Use CoroutineScope for structured concurrency
private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

// DO: Clean up in onDestroy
override fun onDestroy() {
    activityScope.cancel()
}

// DON'T: Use GlobalScope
// activityScope.launch vs GlobalScope.launch
```

### Custom Views

```kotlin
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Properties
    var activeTool: ActiveTool = ActiveTool.SELECT
    
    // Data classes as nested types
    sealed class CanvasItem { ... }
    data class StrokeItem(...) : CanvasItem() { ... }
    
    // Undo actions as inner classes
    inner class AddItemAction(...) : UndoAction { ... }
}
```

## Testing Strategy

### Manual Testing Checklist

1. **Drawing**
   - [ ] Brush draws smooth strokes
   - [ ] Eraser removes strokes
   - [ ] Undo/Redo work correctly
   - [ ] Clear clears all items

2. **Selection**
   - [ ] Lasso selects multiple items
   - [ ] Selection handles work
   - [ ] Group transformation works
   - [ ] Delete removes selected items

3. **AI Recognition**
   - [ ] Model loads on startup
   - [ ] Strokes trigger recognition
   - [ ] Text overlay displays
   - [ ] Toggle text/stroke view

4. **Pages**
   - [ ] New pages created on scroll
   - [ ] Pages persist on restart
   - [ ] Delete page works
   - [ ] Overview shows all pages

5. **PDF**
   - [ ] Import creates new notebook
   - [ ] Page selection works
   - [ ] Snippet insertion works
   - [ ] Text extraction works

6. **Export**
   - [ ] PDF export works
   - [ ] SVG export works
   - [ ] PNG export works

## Debugging

### Enable Touch Area Visualization

In `MainActivity`, tap the options menu (⋮) and enable "Touch Areas" to visualize:
- Blue circles: Selection handle hit areas
- Green areas: Item hit boxes
- Red areas: Touch detection zones

### Log Messages

The app uses standard Android logging:
```kotlin
android.util.Log.d("Penpal", "Message")
android.util.Log.e("Penpal", "Error: $e")
```

View logs in Android Studio:
```
View → Tool Windows → Logcat
Filter: "Penpal"
```

### Common Issues

**Recognition not working**
1. Check model file exists: `ModelManager.modelFile(context).exists()`
2. Verify `isReady` state in logs
3. Check network permissions in manifest

**Canvas not rendering**
1. Check `onSizeChanged()` is called
2. Verify `updateMatrix()` runs
3. Check `invalidate()` is called after state changes

**Pages not saving**
1. Check file permissions
2. Verify `autosaveRunnable` triggers
3. Check SVG serialization completes

## Adding Features

### Adding a New Canvas Item Type

1. **Define Data Class**
```kotlin
data class ShapeItem(
    val shapeType: ShapeType,
    val matrix: Matrix,
    var color: Int,
    var strokeWidth: Float
) : CanvasItem() {
    override val bounds: RectF ...
    override fun toSvgData(): SvgData ...
}
```

2. **Add Touch Handling**
```kotlin
// In onTouchEvent ACTION_DOWN:
if (activeTool == ActiveTool.SHAPE) {
    isCreatingShape = true
    startShape(event)
}

// In onTouchEvent ACTION_MOVE:
if (isCreatingShape) {
    updateShape(event)
}

// In onTouchEvent ACTION_UP:
if (isCreatingShape) {
    finishShape(event)
    pushAction(AddItemAction(shapeItem))
}
```

3. **Add Drawing Code**
```kotlin
// In CanvasItem.draw() extension:
is ShapeItem -> {
    canvas.save()
    canvas.concat(matrix)
    when (shapeType) {
        ShapeType.RECTANGLE -> canvas.drawRect(rect, paint)
        ShapeType.OVAL -> canvas.drawOval(rect, paint)
    }
    canvas.restore()
}
```

4. **Add Serialization**
```kotlin
// In SvgSerializer
is ShapeData -> {
    // Serialize to SVG <rect> or <ellipse>
}

// In deserialize():
else if (parser.name == "rect" && ...) {
    // Parse shape data
}
```

### Adding a New Tool

1. **Add to ActiveTool Enum**
```kotlin
enum class ActiveTool { BRUSH, ERASER, LASSO, SELECT, SHAPE }
```

2. **Add Toolbar Button** (in activity_main.xml)
```xml
<ImageButton
    android:id="@+id/btnShape"
    android:src="@drawable/ic_shape" />
```

3. **Wire Up Handler**
```kotlin
btnShape = findViewById(R.id.btnShape)
btnShape.setOnClickListener {
    drawingView.activeTool = DrawingView.ActiveTool.SHAPE
    updateToolState()
}
```

## Performance Considerations

### Bitmap Management

1. **Thumbnail Generation**: Use 20% scale
2. **Recognition Bitmaps**: Release after use
3. **ImageItem Cache**: Invalidate on transform

### Canvas Rendering

1. **Viewport Culling**: Skip items outside visible area
2. **Path Optimization**: Simplify paths on save
3. **Background Lines**: Draw at correct scale

### Memory Management

1. **Recycle Bitmaps**: Call `bitmap.recycle()` when done
2. **Clear Lists**: Remove unused items from memory
3. **Cancel Coroutines**: Clean up in `onDestroy()`

## Release Process

1. **Version Bump**
   ```kotlin
   // In app/build.gradle
   versionCode 2  // increment
   versionName "1.1.0"  // semantic version
   ```

2. **Build Release APK**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Test on Multiple Devices**
   - Different screen sizes
   - Different Android versions
   - Different GPU families (for Gemma)

4. **ProGuard/R8**
   - Keep model-related classes
   - Keep serialization classes
   - Test thoroughly (rules may break reflection)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests (if any)
5. Submit a pull request

### Pull Request Guidelines

- Reference the issue number
- Describe what changed
- Include screenshots for UI changes
- Test on real device