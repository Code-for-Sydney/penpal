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

Penpal's inference layer is built around **Gemma 4 E2B-IT** and uses the **ML Kit GenAI API**:

| Property | Value |
|----------|-------|
| **Model** | Google Gemma 4 E2B-IT |
| **Size** | ~2.6 GB |
| **Parameters** | 2 Billion (efficient) |
| **Context Window** | 8K tokens |
| **API** | ML Kit GenAI (LiteRT-based) |

#### Inference Capabilities

- **Text Generation**: Streaming token generation for chat responses
- **RAG Integration**: Combines vector similarity search with LLM inference
- **Task Inference**: Detection, OCR, transcription support
- **Offline Mode**: Full on-device inference without network

#### Architecture

```
┌────────────────────────────────────────┐
│           Inference Layer               │
│         (InferenceBridge)               │
├────────────────────────────────────────┤
│ LiteRtInferenceBridge                  │
│ (ML Kit GenAI - AI Edge Gallery)        │
├────────────────────────────────────────┤
│       Gemma 4 E2B-IT Model              │
│    (via LiteRT on-device inference)     │
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

- **Document Extraction**: Queue files and URLs for background parsing (WorkManager)
- **Vector Store**: Semantic similarity search across extracted content
- **RAG Chat**: Retrieve relevant chunks and generate contextual responses
- **Model Management**: Download and load Gemma 4 E2B-IT for on-device inference
- **Offline Mode**: Network monitoring with graceful degradation

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
├── ai/                 # InferenceBridge, Gemma 4, TextEmbedder, VectorStoreRepository, ModelStatus
├── data/               # Room database (PenpalDatabase v2), entities, DAOs
├── processing/         # DocumentParser, ExtractionWorker, WorkerLauncher
├── media/              # Media processing utilities
└── ui/                 # Material 3 Theme

feature/
├── chat/               # ChatScreen, ChatViewModel (RAG flow)
├── process/            # ProcessScreen, ProcessViewModel (job queue)
├── inference/          # InferenceScreen, InferenceViewModel (model management)
├── notebooks/          # NotebookScreen, NotebookEditorViewModel (block-based editor)
└── settings/           # SettingsScreen, SettingsViewModel (app configuration)
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

When the Inference tab is used, the app will prompt for Gemma 4 E2B-IT model download (~2.6 GB).

The model is managed via ML Kit GenAI with download progress tracking. Users can:
1. View model status and size in the Inference tab
2. Download on-demand when needed
3. Monitor download progress in real-time

**Privacy**: All inference runs locally on-device using LiteRT - no data leaves the device.