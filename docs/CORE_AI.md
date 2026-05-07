# Core AI Module Documentation

## Overview

The `core:ai` module is the central AI/ML infrastructure layer for the Penpal application. It provides on-device LLM inference, embedding generation, vector storage, model download management, and text tokenization. The module is designed to support both local inference via **LiteRT-LM** (Google AI Edge) and remote inference via **Ollama API**.

This module aligns with Google's recommended approach for on-device GenAI on Android, using LiteRT-LM for high-performance local inference with GPU/NPU acceleration.

## Framework Context

### Why LiteRT-LM?

LiteRT-LM is Google's production-ready, open-source inference framework for cross-platform LLM deployments on edge devices. Key capabilities:

- **Cross-Platform:** Android, iOS, Web, Desktop, IoT (Raspberry Pi)
- **Hardware Acceleration:** CPU, GPU, and NPU backends
- **Multi-Modality:** Vision and audio support
- **Tool Use:** Function calling with constrained decoding
- **Broad Model Support:** Gemma, Llama, Phi-4, Qwen, and more

### Framework Comparison

| Use Case | Recommended Framework | Best For |
|---|---|---|
| Run locally with Chat UI | LM Studio, Ollama | Beginners wanting Gemini-like experience |
| **Run efficiently on Edge** | **LiteRT-LM**, llama.cpp, MediaPipe, MLX | **High-performance local inference (our choice)** |
| Build/Train in Python | Hugging Face, Keras, JAX, Unsloth | Research and fine-tuning |
| Deploy to Production | GKE, Cloud Run, Vertex AI, vLLM | Scalable cloud deployment |

> **Note:** MediaPipe LLM Inference API for Android/iOS is deprecated. Google recommends migrating to LiteRT-LM.

### Supported Backends & Platforms

| Acceleration | Android | iOS | macOS | Windows | Linux | IoT |
|---|---|---|---|---|---|---|
| **CPU** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **GPU** | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| **NPU** | ✅ | - | - | - | - | - |

## Gemma Model Variants

The module defaults to **Gemma 4** models, specifically the `gemma-4-e2b-it` variant (~2.6 GB).

### Choosing a Gemma Variant

| Factor | Recommendation | Notes |
|---|---|---|
| **Model Family** | Gemma core (not PaliGemma/CodeGemma) | Best general-purpose starting point |
| **Training Type** | Instruction-tuned (IT) | Ready to respond to prompts without additional training |
| **Parameters** | Smallest available | Smaller = faster, less memory, easier development |
| **Quantization** | 16-bit (half precision) | Good balance of quality and performance |

### Gemma 4 Control Tokens

Gemma 4 introduces new control tokens for structured interactions:

| Token | Purpose |
|---|---|
| `<|turn>` / `<turn|>` | Dialogue turn boundaries |
| `system` / `user` / `model` | Role indicators |
| `<|image>` / `<image|>` | Image embeddings |
| `<|audio>` / `<audio|>` | Audio embeddings |
| `<|tool>` / `<tool|>` | Tool definition |
| `<|tool_call>` / `<tool_call|>` | Tool request |
| `<|tool_response>` / `<tool_response|>` | Tool result |
| `<|think|>` | Activates thinking mode |
| `<|channel>` / `<channel|>` | Internal reasoning channel |
| `<|"\|>` | String delimiter for structured data |

### Thinking Mode

To activate reasoning/thinking mode, include `<|think|>` in the system instruction:

```
<|turn>system
<|think|>You are a helpful assistant.<turn|>
<|turn>user
What is the water formula?<turn|>
<|turn>model
<|channel>thought
The user is asking for the chemical formula of water...
<channel|>The chemical formula for water is H₂O.<turn|>
```

> **Important:** Strip model-generated thoughts from previous turns before passing conversation history back (except during function calling sequences).

## Module Structure

**Note:** The project uses manual dependency injection via `PenpalApplication` lazy singletons, not Hilt. No `@Module`, `@Inject`, or `@HiltViewModel` annotations exist in the codebase.

```
core/ai/
├── build.gradle.kts                    # Module build configuration
└── src/main/java/com/penpal/core/ai/
    ├── GemmaSpecialTokens.kt           # Special token definitions for Gemma 4
    ├── InferenceBridge.kt              # Core abstraction interface for LLM inference
    ├── LiteRtInferenceBridge.kt        # On-device inference using Google LiteRT-LM
    ├── OllamaInferenceBridge.kt        # Remote inference using Ollama REST API
    ├── LmEngineManager.kt              # LiteRT-LM Engine lifecycle manager
    ├── ModelManager.kt                 # Model file location, download, and tracking
    ├── ModelDownloadManager.kt         # WorkManager-based download orchestration
    ├── ModelDownloadWorker.kt          # Background download worker for Ollama models
    ├── OllamaApiService.kt             # REST API client for Ollama
    ├── OllamaModel.kt                  # Data models for Ollama API responses
    ├── OnnxMiniLmEmbedder.kt           # ONNX Runtime-based text embedder (MiniLM)
    ├── TextEmbedder.kt                 # Interface for text embedding
    ├── VectorStoreProvider.kt          # Static provider for VectorStoreRepository
    ├── VectorStoreRepository.kt        # Vector storage and similarity search
    ├── MessagePart.kt                  # Structured message parts (Text, Reasoning, ToolCall)
    ├── MessagePartAggregator.kt        # Builds MessageParts from streaming transitions
    ├── StreamingTokenFilter.kt         # Real-time special token filtering
    └── WordPieceTokenizer.kt           # BERT/MiniLM-compatible tokenizer
```

## Key Components

### 1. InferenceBridge Interface

The `InferenceBridge` is the primary contract for all LLM inference operations.

**State Flows:**
- `isReady: StateFlow<Boolean>` — Model loaded and ready
- `isProcessing: StateFlow<Boolean>` — Inference in progress
- `isDownloading: StateFlow<Boolean>` — Model download active
- `downloadProgress: StateFlow<DownloadProgress>` — Download progress tracking
- `modelStatus: StateFlow<ModelStatus>` — Current model status (NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR)

**Core Operations:**
- `initialize(context, modelName, backend, onDone)` — Initialize model
- `isModelDownloaded()` — Check if model is available
- `downloadModel(...)` — Download model with progress callbacks
- `deleteModel()` — Delete downloaded model
- `runInference(input, resultListener, cleanUpListener, onError)` — Text-only inference
- `runInferenceWithImage(input, image, ...)` — Multimodal inference (text + image)
- `runInferenceFlow(input): Flow<String>` — Streaming text inference via Flow (plain text)
- `runInferenceFlowParts(input): Flow<List<MessagePart>>` — Streaming inference with structured parts
- `runInferenceWithImageFlow(input, image): Flow<String>` — Streaming multimodal inference (plain text)
- `runInferenceWithImageFlowParts(input, image): Flow<List<MessagePart>>` — Streaming multimodal with structured parts
- `resetConversation()` — Clear conversation history
- `stopInference()` — Cancel ongoing inference
- `release()` — Free all resources

**Data Classes:**
- `DownloadProgress(downloadedBytes, totalBytes)` — Download progress with percentage
- `InferenceConfig(temperature, topK, topP, maxTokens, prompt)` — Generation parameters
- `DetectedItem(text, boxYmin, boxXmin, boxYmax, boxXmax)` — Image analysis result

### 2. LiteRtInferenceBridge

Implementation using **Google AI Edge LiteRT-LM** for on-device inference.

**Features:**
- Supports GPU and CPU backends with automatic fallback
- Uses `Engine` and `Conversation` APIs from LiteRT-LM
- Thread-safe initialization via coroutines
- 120-second timeout on inference operations
- Streaming token filtering for clean output
- Automatic model discovery across common directories
- Image input support (JPEG compression at 85% quality)

**Model Discovery Strategy:**
1. Check ModelManager tracked file
2. Check persisted path + common locations
3. Check modelName-based paths (external files, Downloads, /sdcard)
4. Scan for ANY `.litertlm` file in common directories

**Backend Initialization:**
- Default: GPU first, fallback to CPU
- Configurable: GPU-only, CPU-only, or auto
- EngineConfig: `maxNumImages=1`, `maxNumTokens=4096`
- SamplerConfig: `topK=64`, `topP=0.95`, `temperature=0.7`

**LiteRT-LM Kotlin API Usage Pattern:**
```kotlin
val engineConfig = EngineConfig(
    modelPath = modelPath,
    backend = Backend.GPU(),
    visionBackend = Backend.GPU(),
    audioBackend = Backend.CPU(),
    maxNumImages = 1,
    maxNumTokens = 4096
)
val engine = Engine(engineConfig)
engine.initialize()

val conversation = engine.createConversation(
    ConversationConfig(
        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
    )
)

// Streaming via Flow (recommended)
conversation.sendMessageAsync(content)
    .catch { e -> /* handle error */ }
    .collect { message -> /* process streaming response */ }
```

### 3. OllamaInferenceBridge

Implementation delegating to Ollama REST API for remote inference.

**Features:**
- Connects to `http://10.0.2.2:11434` (Android emulator default)
- Uses `ModelDownloadManager` for background model downloads
- Supports streaming and non-streaming generation
- Image inference falls back to text inference (Ollama limitation in this implementation)

### 4. LmEngineManager

Manages LiteRT-LM Engine lifecycle independently of the bridge.

**Features:**
- Thread-safe initialization via `Mutex`
- Configurable temperature, topK, topP, maxTokens
- GPU/CPU backend selection
- Model directory management (`context.filesDir/models/`)
- Error state tracking and clearing

### 5. ModelManager

Handles model file location, downloading, and tracking.

**Model Sources:**
- HuggingFace: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`
- Kaggle: `https://www.kaggle.com/api/v1/models/google/gemma-4/tfLite/gemma4-e2b-it-web/1/download`

**Download Features:**
- Uses Android DownloadManager
- Manual redirect resolution to handle auth header security
- Supports HF tokens and Kaggle credentials
- Progress tracking via DownloadManager query API
- Saves to app external files directory (no storage permission needed)

**Model Tracking:**
- Persists model history in SharedPreferences
- Tracks model name, path, size, last used timestamp
- Scans for `.litertlm` and `.tflite` files across device

### 6. ModelDownloadManager & ModelDownloadWorker

WorkManager-based background download system for Ollama models.

**Features:**
- Enqueues unique work requests per model
- Progress reporting via WorkManager progress API
- Cancelable downloads

### 7. OllamaApiService

REST API client for Ollama.

**Endpoints:**
- `GET /api/tags` — List available models
- `POST /api/generate` — Generate text (streaming and non-streaming)
- `DELETE /api/delete` — Delete model
- `POST /api/pull` — Download model with progress

**Data Models:**
- `OllamaModel`, `OllamaTagsResponse`, `OllamaGenerateRequest`, `OllamaGenerateResponse`
- `OllamaPullRequest`, `OllamaPullResponse`

### 8. VectorStoreRepository

Provides semantic search via text embeddings.

**Interface:**
- `embed(chunks: List<RawChunk>)` — Embed and store chunks
- `similaritySearch(query, topK)` — Find most similar chunks via cosine similarity
- `getChunksForSource(sourceId)` — Retrieve chunks by source
- `deleteChunksForSource(sourceId)` — Remove chunks by source
- `getCachedChunkCount()` / `hasCachedData()` — Check cache status

**Implementation Details:**
- Uses `ChunkDao` for persistence
- LRU embedding cache (max 10,000 entries)
- Cosine similarity calculation
- Fallback to mock embeddings if ONNX unavailable

### 9. TextEmbedder Implementations

**OnnxMiniLmEmbedder:**
- ONNX Runtime-based inference
- Model: `all-MiniLM-L6-v2.onnx` (384-dimensional embeddings)
- Mean pooling over sequence length
- L2 normalization
- Fallback to mock embeddings on failure

**MiniLmEmbedder:**
- Pure mock implementation for testing/development
- Deterministic hash-based embeddings

### 10. WordPieceTokenizer

BERT/MiniLM-compatible tokenizer.

**Features:**
- Loads vocabulary from assets or filesystem
- Subword tokenization with `##` prefix handling
- Automatic `[CLS]` and `[SEP]` insertion
- Truncation to max length (default 512)
- Fallback vocabulary for development

### 11. StreamingTokenFilter

Real-time special token removal for clean LLM output.

**Features:**
- Trie-based efficient token matching
- Tracks content mode (thinking, image, audio, tool call)
- Handles partial tokens at chunk boundaries
- Smart whitespace preservation
- Supports all Gemma 4 special tokens
- **Mode transition tracking** for structured message parts
- **Smart spacing logic**: `lastEmittedChar` tracking prevents `\n\n` spam, only inserts spaces before word characters (not punctuation/symbols)

**Content Modes:**
- `REGULAR` — Normal text
- `THINKING` — Reasoning content
- `IMAGE` / `AUDIO` — Multimodal content
- `TOOL_CALL` / `TOOL_RESPONSE` — Function calling
- `SYSTEM` — System instructions

**Methods:**
- `append(chunk): FilteredChunk` — Classic API returning text + mode
- `appendWithTransitions(chunk): FilteredChunkWithTransitions` — Extended API that emits mode transition events for building MessageParts

### 12. MessagePart (Structured Message Architecture)

Inspired by the [opencode](https://github.com/anomalyco/opencode) library's "parts" architecture (which uses Vercel AI SDK), Penpal now supports structured message parsing from raw Gemma 4 token streams.

**Problem:** LiteRT-LM emits raw tokens (e.g., `<|channel>thought...<channel|>`) rather than structured events like Vercel AI SDK's `reasoning-start` / `reasoning-delta` / `reasoning-end`. Penpal must parse these tokens itself.

**Solution:** The `StreamingTokenFilter` tracks mode transitions, and `MessagePartAggregator` builds immutable `MessagePart` objects from the streaming text.

**Sealed Class Hierarchy:**

```kotlin
sealed class MessagePart {
    data class TextPart(val text: String) : MessagePart()
    data class ReasoningPart(val text: String, val isComplete: Boolean) : MessagePart()
    data class ToolCallPart(
        val name: String,
        val callId: String,
        val arguments: Map<String, Any>,
        val rawJson: String,
        val status: ToolStatus
    ) : MessagePart()
    data class ToolResponsePart(
        val name: String,
        val callId: String,
        val output: String,
        val isError: Boolean
    ) : MessagePart()
    data class ImagePart(val description: String) : MessagePart()
    data class AudioPart(val transcription: String) : MessagePart()
}
```

**Tool Status Lifecycle:**
- `PENDING` — Created but not executed
- `RUNNING` — Currently executing
- `COMPLETED` — Success
- `ERROR` — Failed

**Aggregation Flow:**
1. `StreamingTokenFilter.appendWithTransitions(chunk)` detects mode changes
2. `ModeTransitionEvent(fromMode, toMode, textBefore)` is emitted
3. `MessagePartAggregator.processChunk(result)` finalizes previous part, starts new part
4. `MessagePartAggregator.finalize()` completes any remaining buffered content

**InferenceBridge Integration:**
- `runInferenceFlowParts(input): Flow<List<MessagePart>>` — Returns structured parts instead of plain text
- Implemented in both `LiteRtInferenceBridge` (parses Gemma 4 tokens) and `OllamaInferenceBridge` (wraps text in TextPart)

**UI Rendering:**
- `TextPart` — Normal message text
- `ReasoningPart` — Collapsible "Thinking" card (collapsed by default, italic styling)
- `ToolCallPart` — Expandable card with status dot and raw JSON
- `ToolResponsePart` — Success/error card with formatted output

### 13. GemmaSpecialTokens

Definitions for all Gemma 4 control tokens.

**Token Categories:**
- Turn tokens: `<|turn>`, `<turn|>`, role markers
- Tool tokens: `<|tool>`, `<|tool_call>`, `<|tool_response>`
- Thinking tokens: `<|think|>`, `<|channel>`
- Media tokens: `<|image>`, `<|audio>`
- Sequence tokens: `<bos>`, `<eos>`, `<|endoftext|>`
- String delimiter: `<|"\u003e|>`

**Utility:**
- `formatPrompt(messages, systemInstruction)` — Format messages for Gemma chat template

## Build Configuration

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.penpal.core.ai"
    compileSdk = 34

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    api("com.google.code.gson:gson:2.11.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager for background model downloads
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // LiteRT-LM for on-device LLM inference
    implementation(libs.litertlm.android)

    // ONNX Runtime for embeddings
    implementation(libs.onnxruntime.android)

    implementation(project(":core:data"))
}
```

**Note:** Hilt is not used in this module. Dependencies are provided manually via `PenpalApplication` lazy singletons.

## Key Design Patterns

1. **Bridge Pattern** — `InferenceBridge` abstracts both local and remote inference
2. **StateFlow** — Reactive state management for UI binding
3. **Coroutine Flow** — Streaming inference with backpressure support
4. **Repository Pattern** — `VectorStoreRepository` abstracts data access
5. **Worker Pattern** — `ModelDownloadWorker` for background operations
6. **Object Provider** — `VectorStoreProvider` for dependency injection workaround
7. **Trie Data Structure** — Efficient special token matching in stream

## Threading Model

- LiteRT inference runs on `Dispatchers.IO` via `LiteRtInferenceBridge` internal scope
- Engine initialization uses `Dispatchers.IO`
- Token filtering is synchronous (no coroutines needed)
- Embedding generation uses `Dispatchers.Default`
- Download progress callbacks emit on main thread via coroutine launch
- **Note:** No custom `@InferenceDispatcher` or `DispatcherModule` exists. The project uses standard Kotlin dispatchers directly.

## Error Handling

- Model not found → `ModelStatus.NOT_DOWNLOADED`
- Download failure → `ModelStatus.ERROR` with reason
- Inference timeout → 120-second timeout with cancellation
- Backend failure → Automatic GPU→CPU fallback
- ONNX failure → Fallback to mock embeddings
- Empty response → Warning log, empty result callback

## Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| androidx.core:core-ktx | 1.13.1 | Android KTX extensions |
| kotlinx-coroutines-core | 1.8.1 | Coroutines |
| kotlinx-coroutines-android | 1.8.1 | Android coroutine dispatchers |
| gson | 2.11.0 | JSON serialization |
| okhttp3 | 4.12.0 | HTTP client for Ollama |
| work-runtime-ktx | 2.9.1 | Background downloads |
| litertlm.android | libs | Google LiteRT-LM inference |
| onnxruntime.android | libs | ONNX Runtime embeddings |
| core:data | project | Chunk data access |

## Model Requirements

### LiteRT-LM Model
- **Format:** `.litertlm` file
- **Size:** ~2.6 GB (Gemma-4-E2B)
- **Default:** `google/gemma-4-e2b-it`
- **Location:** App external files directory or Downloads

### Supported Models Reference

| Model | Type | Size | CPU Prefill | CPU Decode | GPU Prefill | GPU Decode |
|---|---|---|---|---|---|---|
| Gemma4-E2B | Chat | 2.58 GB | 557 tk/s | 47 tk/s | 3808 tk/s | 52 tk/s |
| Gemma4-E4B | Chat | 3.65 GB | 195 tk/s | 18 tk/s | 1293 tk/s | 22 tk/s |
| Gemma-3n-E2B | Chat | 2.97 GB | 233 tk/s | 28 tk/s | — | — |
| Gemma-3n-E4B | Chat | 4.24 GB | 170 tk/s | 20 tk/s | — | — |
| Gemma3-1B | Chat | 1.01 GB | 177 tk/s | 33 tk/s | 1191 tk/s | 24 tk/s |
| FunctionGemma | Base | 289 MB | 2238 tk/s | 154 tk/s | — | — |
| Qwen2.5-1.5B | Chat | 1.60 GB | 298 tk/s | 34 tk/s | 1668 tk/s | 31 tk/s |

*Performance data from Samsung S26 Ultra / iPhone 17 Pro / MacBook Pro M4*

### ONNX Embedding Model
- **Format:** `.onnx` file
- **Default:** `all-MiniLM-L6-v2.onnx`
- **Dimensions:** 384
- **Location:** `context.getExternalFilesDir(null)/models/`

### Vocabulary File
- **Format:** `vocab.txt` (one token per line)
- **Location:** `assets/tokenizer/vocab.txt`
- **Default:** Fallback vocabulary with basic tokens

## Android GPU Configuration

To use the GPU backend on Android, add to `AndroidManifest.xml` inside `<application>`:

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```

## NPU Configuration

For NPU backend, specify the native library directory:

```kotlin
val engineConfig = EngineConfig(
    modelPath = modelPath,
    backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
)
```

## Migration Notes

- **MediaPipe LLM Inference API** is deprecated for Android/iOS. Migrate to LiteRT-LM.
- LiteRT-LM uses `.litertlm` format (not `.task` bundles)
- Models from HuggingFace LiteRT Community are ready to use without conversion
