# Penpal — Android Architecture

## Stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Navigation | Compose Navigation (single-activity) |
| State | ViewModel + StateFlow / SharedFlow |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Background | WorkManager (chained workers) |
| Local DB | Room |
| Preferences | DataStore (Proto) |
| Embeddings | on-device VectorStore (SQLite FTS5 or Chroma-lite) |
| **AI inference** | **Gemma 4 E2B-IT via ML Kit GenAI (LiteRT on-device)** |
| Audio | MediaRecorder → WAV 16kHz pipeline |
| Media parsing | iText (PDF) · Coil (image) · ExoPlayer (YouTube/audio) |

---

## Core Inference Architecture

**Inference is the central architectural component.** All AI-powered features depend on the inference layer.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         PENPAL INFERENCE LAYER                        │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                     InferenceBridge                            │   │
│  │              (interface for all AI operations)                  │   │
│  └────────────────────────────┬───────────────────────────────────┘   │
│                               │                                        │
│  ┌────────────────────────────▼───────────────────────────────────┐   │
│  │                  LiteRtInferenceBridge                          │   │
│  │              (ML Kit GenAI - AI Edge Gallery pattern)            │   │
│  └────────────────────────────┬───────────────────────────────────┘   │
│                               │                                        │
│  ┌────────────────────────────▼───────────────────────────────────┐   │
│  │                      Gemma 4 E2B-IT                             │   │
│  │           (Google's efficient on-device LLM)                    │   │
│  │                                                                    │   │
│  │  • 2B parameters, ~2.6 GB                                        │   │
│  │  • 8K token context window                                       │   │
│  │  • Instruction-tuned for RAG, chat, text generation              │   │
│  └────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Inference Data Flow

```
User Query (Chat tab)
       │
       ▼
VectorStoreRepository.similaritySearch(query, topK=6)
       │  ← Embed query, search chunks
       ▼
Context Chunks (top-K relevant text)
       │
       ▼
buildPrompt(userQuery, contextChunks)
       │
       ▼
InferenceBridge.streamGenerate(prompt, config)
       │  ← Gemma 4 E2B-IT via ML Kit GenAI
       ▼
Streaming Tokens (Flow<String>)
       │
       ▼
UI Updates (token-by-token display)
       │
       ▼
Complete Response
```

### Module Dependency on Inference

```
feature:chat ──> core:ai ──> InferenceBridge ──> LiteRtInferenceBridge ──> Gemma 4
                     │
                     └─> VectorStoreRepository ──> RAG pipeline

feature:notebooks ──> core:ai ──> InferenceBridge ──> Handwriting recognition
feature:process ──> core:ai ──> InferenceBridge ──> Text extraction tasks
```

---

## Thread model

```
┌─────────────────────────────────────────────┐
│              Main Thread (UI)               │
│  Compose recomposition · NavController      │
│  ViewModel.uiState (StateFlow collect)      │
└────────────────┬────────────────────────────┘
                 │ suspend / Flow
        ┌────────┴────────┐
        │                 │
┌───────▼──────┐  ┌───────▼──────────────────┐
│ Dispatchers  │  │ Dispatchers.Default        │
│ .IO          │  │ AI inference · FFT · parse │
│ DB · files   │  │ graph layout · embeddings  │
│ network      │  └──────────┬───────────────┘
└──────────────┘             │ enqueue
                    ┌────────▼────────┐
                    │  WorkManager    │
                    │  extraction     │
                    │  workers        │
                    └────────┬────────┘
                             │ read/write
                    ┌────────▼────────┐
                    │   Data Layer    │
                    │  Room · DSProto │
                    │  VectorStore    │
                    │  MediaStore     │
                    └─────────────────┘
```

**Rule:** Nothing blocking runs on Main. Every ViewModel scope call dispatches explicitly.

---

## Module graph

```
:app
 ├── :feature:chat           ──────────────┐
 ├── :feature:notebooks      ──────────────┼──┐
 ├── :feature:process       ──────────────┼──┤
 ├── :feature:organize      ──────────────┼──┤
 ├── :feature:settings      ──────────────┼──┤
 ├── :core:ai                ◄────────────────┘  ← Central module
 ├── :core:processing        ──────────────┐
 ├── :core:data                               │
 ├── :core:media          (planned)           │
 └── :core:ui              (shared composables, theme)
```

**Key Points:**
- `core:ai` is the central module - all features depend on `InferenceBridge`
- `InferenceBridge` → `LiteRtInferenceBridge` → `Gemma 4 E2B-IT`
- Dependencies flow **downward only**. Feature modules never import each other.

---

## Navigation

Single `NavHost` in `:app`. Bottom bar tabs are top-level destinations defined in `PenpalDestinations.kt`. Deep links from notifications (WorkManager completion) route directly into `:feature:process`.

```
Chat  ←→  Notebooks  ←→  Process  ←→  Organize  ←→  Settings
                              ↑
                     sheet: Add Data
```

The "Add Data" surface is a `ModalBottomSheet` within Process — not a standalone tab. This collapses the UX without losing functionality.

---

## State ownership

| Scope | Owner | Lifetime |
|---|---|---|
| Per-screen UI state | `ViewModel` (Hilt-injected) | `NavBackStackEntry` |
| Cross-tab shared state | `@ActivityRetainedScoped` ViewModel | Activity |
| Persistent user data | Room + DataStore | App |
| Processing queue | `WorkManager` DB | App |
| In-memory embeddings | `VectorStoreRepository` singleton | App process |
