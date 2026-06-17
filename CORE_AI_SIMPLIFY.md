# Core AI Setup (Simplified)

## What This Module Does

The `core:ai` module handles all AI/ML operations:
- **Chat with LLM** — On-device (LiteRT-LM) or remote (Ollama)
- **Text embeddings** — Convert text to vectors for semantic search
- **Vector storage** — Store and search embeddings
- **Model downloads** — Download AI models in background
- **Token filtering** — Clean up special tokens from model output

## Architecture Decision

### Local (LiteRT-LM) vs Remote (Ollama)

| | LiteRT (Local) | Ollama (Remote) |
|--|----------------|-----------------|
| Privacy | Data stays on device | Sent to server |
| Speed | Fast after load | Network dependent |
| Setup | Download ~2.6GB model | Need Ollama server |
| Offline | Works offline | Needs network |
| Cost | Free (runs on device) | Server compute cost |

**Recommendation:** Use `LiteRtInferenceBridge` for production (privacy + offline). Use `OllamaInferenceBridge` for development/testing.

## Quick Setup

### 1. Add Dependency

In your module's `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":core:ai"))
}
```

### 2. Initialize in Application

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 1. Create tokenizer (loads vocab.txt from assets)
        val tokenizer = WordPieceTokenizer.fromAssets(this) 
            ?: WordPieceTokenizer.fallback()
        
        // 2. Create embedder (uses ONNX model)
        val embedder = OnnxMiniLmEmbedder(
            modelPath = OnnxMiniLmEmbedder.modelFile(this).absolutePath,
            tokenizer = tokenizer
        )
        
        // 3. Create vector store (needs ChunkDao from core:data)
        val vectorStore = VectorStoreRepositoryImpl(
            chunkDao = /* your ChunkDao instance */,
            textEmbedder = embedder,
            gson = Gson()
        )
        
        // 4. Make available globally (for WorkManager workers)
        VectorStoreProvider.instance = vectorStore
    }
}
```

### 3. Use for Chat

```kotlin
// On-device inference (recommended)
val ai = LiteRtInferenceBridge(context)

// Initialize model
ai.initialize(context, "google/gemma-4-e2b-it") { result ->
    Log.d("AI", result) // "Model loaded" or "Model not found"
}

// Check if ready
lifecycleScope.launch {
    ai.isReady.collect { ready ->
        if (ready) startChat()
    }
}

// Simple chat
ai.runInference("Hello!", 
    resultListener = { text, done -> updateUI(text) },
    cleanUpListener = { /* done */ },
    onError = { error -> showError(error) }
)

// Streaming chat (recommended)
lifecycleScope.launch {
    ai.runInferenceFlow("Hello!")
        .catch { e -> showError(e.message) }
        .collect { partialText -> updateUI(partialText) }
}

// Streaming with structured parts (for rich UI)
lifecycleScope.launch {
    ai.runInferenceFlowParts("Hello!")
        .catch { e -> showError(e.message) }
        .collect { parts ->
            // parts is List<MessagePart> containing TextPart, ReasoningPart, etc.
            renderStructuredMessage(parts)
        }
}

// The feature:chat module provides MarkdownText composable for rendering
// TextPart content with support for code blocks, bold, italic, lists, and links.
```

### 4. Download Model

```kotlin
ai.downloadModel(context, modelName, lifecycleScope,
    onProgress = { downloaded, total ->
        val percent = (downloaded * 100 / total)
        updateProgress(percent)
    },
    onDone = { /* download complete */ },
    onError = { error -> showError(error) }
)
```

### 5. Use for Semantic Search

```kotlin
// Store chunks
val chunks = listOf(
    RawChunk(id="1", sourceId="doc1", text="Hello world", position=0)
)
vectorStore.embed(chunks)

// Search
val results = vectorStore.similaritySearch("hello", topK = 5)
```

## Required Files

Place these in your app module:

```
app/src/main/assets/
└── tokenizer/
    └── vocab.txt          # WordPiece vocabulary (or use fallback)

# Download to device external storage:
/sdcard/Download/
├── gemma-4-E2B-it.litertlm    # ~2.6GB LLM model
└── models/
    └── all-MiniLM-L6-v2.onnx  # ~80MB embedding model
```

## Configuration

No special configuration needed. The module auto-discovers models in:
- App external files directory
- Downloads folder
- `/sdcard/Download`
- `/sdcard/`

## Minimal Working Example

```kotlin
class MainActivity : AppCompatActivity() {
    private val ai by lazy { LiteRtInferenceBridge(this) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize
        ai.initialize(this) { status ->
            if (status.contains("loaded")) {
                runChat()
            } else {
                downloadModel()
            }
        }
    }
    
    private fun runChat() {
        ai.runInferenceFlow("What is 2+2?")
            .onEach { text -> textView.text = text }
            .launchIn(lifecycleScope)
    }
    
    private fun downloadModel() {
        ai.downloadModel(this, "google/gemma-4-e2b-it", lifecycleScope,
            onProgress = { dl, total -> 
                progressBar.progress = (dl * 100 / total).toInt() 
            },
            onDone = { runChat() },
            onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        )
    }
    
    override fun onDestroy() {
        ai.release()
        super.onDestroy()
    }
}
```

## Choosing a Model

Start with the smallest instruction-tuned (IT) Gemma core model:

1. **Gemma core** (not PaliGemma/CodeGemma) — general purpose
2. **IT variant** — ready to use without additional training
3. **Smallest parameters** — faster, less memory
4. **16-bit quantization** — good quality/performance balance

### Available Models

| Model | Size | Best For |
|---|---|---|
| Gemma4-E2B | 2.6 GB | Balanced performance/quality |
| Gemma3-1B | 1.0 GB | Low-memory devices |
| Gemma4-E4B | 3.7 GB | Higher quality, more memory |

## Gemma 4 Prompt Format

Format prompts with control tokens:

```
<|turn>system
You are a helpful assistant.<turn|>
<|turn>user
Hello!<turn|>
<|turn>model
```

The module handles this automatically via `GemmaSpecialTokens.formatPrompt()`.

## Android GPU Setup

Add to `AndroidManifest.xml`:
```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```

## Migration from MediaPipe

If migrating from MediaPipe LLM Inference API:
1. Replace `.task` bundles with `.litertlm` models
2. Use `LiteRtInferenceBridge` instead of `LlmInference`
3. Models from HuggingFace LiteRT Community work without conversion
