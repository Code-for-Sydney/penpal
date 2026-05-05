# Penpal - Intelligent Drawing & Knowledge App

A feature-rich Android application combining drawing capabilities with AI-powered knowledge management. Penpal uses **Google Gemma 4 E2B-IT** for on-device inference and RAG (Retrieval-Augmented Generation) for intelligent document understanding.

> **Note**: This README reflects the **v2.x Compose-based architecture** currently in development. The v1.x legacy codebase is preserved for reference.

## Overview

Penpal provides a unified experience for:

- **Smart Notebooks**: Draw, annotate, and organize handwritten notes
- **Document Processing**: Extract and index content from PDFs, URLs, audio, and images
- **AI-Powered Chat**: Query your knowledge base with RAG-enabled conversations
- **On-Device Inference**: Run Gemma 4 E2B-IT locally for privacy-preserving AI

## Key Features

### AI Inference (Central Component)

Penpal's inference layer is built around **Gemma 4 E2B-IT** and uses the **LiteRT-LM Engine API** for real on-device LLM inference:

| Property | Value |
|----------|-------|
| **Model** | Google Gemma 4 E2B-IT |
| **Size** | ~2.6 GB |
| **Parameters** | 2 Billion (efficient) |
| **Context Window** | 8K tokens |
| **API** | LiteRT-LM Engine API (direct on-device inference) |
| **Source** | HuggingFace: `litert-community/gemma-4-E2B-it-litert-lm` |

#### Inference Capabilities

- **Real LiteRT-LM Integration**: Uses actual `Engine`, `Conversation`, `MessageCallback` APIs
- **GPU/CPU Fallback**: Automatic backend selection (GPU preferred, CPU fallback)
- **Streaming Responses**: Callback-based token streaming for chat UI
- **RAG Integration**: Combines vector similarity search with LLM inference
- **Image/Audio Support**: Content handling via `Content.ImageBytes`
- **Model Manager**: HuggingFace/Kaggle downloads with Android DownloadManager
- **Offline Mode**: Full on-device inference without network

#### Inference Architecture

```
┌────────────────────────────────────────┐
│           InferenceBridge               │
│     (Interface for all AI operations)   │
├────────────────────────────────────────┤
│ LiteRtInferenceBridge                  │
│ • Engine lifecycle management            │
│ • Conversation for multi-turn chat       │
│ • MessageCallback for streaming          │
│ • GPU/CPU backend fallback               │
├────────────────────────────────────────┤
│ LmEngineManager                         │
│ • Creates Engine with backend spec       │
│ • Tracks GPU/CPU backend state           │
├────────────────────────────────────────┤
│ ModelManager                            │
│ • HuggingFace/Kaggle downloads           │
│ • DownloadManager integration            │
├────────────────────────────────────────┤
│ OnnxMiniLmEmbedder                      │
│ • ONNX Runtime text embeddings           │
│ • Mean pooling + L2 normalization        │
├────────────────────────────────────────┤
│      Gemma 4 E2B-IT Model (.litertlm)   │
└────────────────────────────────────────┘
```

### Tab-Based Navigation (v2.x)

| Tab | Purpose | Status |
|-----|---------|--------|
| **Process** | Add documents, URLs, files for extraction | ✅ Functional |
| **Chat** | RAG-enabled conversations with your knowledge | ✅ Functional |
| **Think** | Block-based notebooks with graphs, drawings, images | ✅ Functional |
| **Inference** | Load/unload Gemma model, view status | ✅ Functional |
| **Settings** | App configuration, model management, preferences | ✅ Functional |

### Core Capabilities

- **Document Extraction**: Real parsers for PDF (PdfBox), Images (ML Kit OCR), Audio (metadata), URLs (Jsoup), and Code (language-aware chunking)
- **Vector Store**: Semantic similarity search with ONNX Runtime embeddings (MiniLM with mean pooling + L2 normalization)
- **RAG Chat**: Retrieve real document chunks and generate contextual responses via LiteRT-LM
- **Persistent Chat**: Room database conversations with history drawer, notebook attachment, and file pinning
- **Model Management**: Download and load Gemma 4 E2B-IT for on-device inference
- **Offline Mode**: Network monitoring with graceful degradation
- **GPU Acceleration**: GPU-first inference with automatic CPU fallback

## Build Configuration

| Component | Version |
|-----------|---------|
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.28 |
| Hilt | 2.51.1 |
| Room | 2.6.1 |
| Compose BOM | 2024.06.00 |
| AGP | 9.0.0 |

## Architecture

Penpal uses a modular architecture with clear dependency boundaries:

```
app/                    # Shell app, MainScreen, BottomNavigation
├── MainComposeActivity # Compose entry point
├── MainScreen         # NavHost + 5 tabs (Process, Chat, Think, Inference, Settings)
└── PenpalApplication  # Lazy DI singleton

core/
├── ai/                 # InferenceBridge, LmEngineManager, LiteRtInferenceBridge,
│                       # ModelManager, Gemma 4, TextEmbedder, OnnxMiniLmEmbedder,
│                       # VectorStoreRepository, ModelStatus
├── data/               # Room database (PenpalDatabase v3), entities, DAOs
├── processing/         # Real DocumentParsers (PDF, Image, Audio, URL, Code),
│                       # ExtractionWorker, WorkerLauncher, ParserFactory
├── media/              # Media processing utilities
└── ui/                 # Material 3 Theme

feature/
├── chat/               # ChatScreen, ChatViewModel (RAG flow, persistent conversations,
│                       # notebook/file attachment, drag-and-drop)
├── process/            # ProcessScreen, ProcessViewModel (job queue)
├── inference/          # InferenceScreen, InferenceViewModel (model management)
├── notebooks/          # NotebookScreen, NotebookEditorViewModel (block-based editor,
│                       # auto-processing, image picker)
└── settings/           # SettingsScreen, SettingsViewModel (model download UI)
```

### Dependency Direction

```
app ──> core:ai, core:data, core:processing, core:ui
core:processing ──> core:ai, core:data
feature:* ──> core:* (downward only)
```

## Installation

### Prerequisites

1. Android Studio Hedgehog (2024.1.1) or later
2. Android SDK 34
3. Kotlin 2.0.21

### Build Steps

```bash
git clone https://github.com/your-username/penpal.git
cd penpal
./gradlew assembleDebug
```

### Model Download

The Settings tab manages model downloads via ModelManager. Users can download Gemma 4 E2B-IT from HuggingFace (~2.6 GB).

**Download Flow:**
1. Enter HuggingFace token in Settings (if required)
2. Tap "Download Model" in ModelDownloadBottomSheet
3. Monitor progress with percentage display
4. On completion, model is ready for inference

**Features:**
- HuggingFace and Kaggle download sources
- Android DownloadManager for reliable downloads
- Real-time progress polling
- Retry on failure

**Privacy**: All inference runs locally on-device using LiteRT-LM Engine API - no data leaves the device.

## Supported Model Sources

| Source | URL | Authentication |
|--------|-----|----------------|
| **HuggingFace** | `litert-community/gemma-4-E2B-it-litert-lm` | Token required |
| **Kaggle** | Google Gemma models | Kaggle credentials |