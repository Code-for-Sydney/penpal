# Project Docs

This directory contains documentation for the Penpal Android application.

## Documentation Files

| File | Description |
|------|-------------|
| [README.md](../README.md) | Project overview, features, setup instructions |
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design, component relationships, data flow |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Development guidelines, testing, contribution |
| [CHANGELOG.md](CHANGELOG.md) | Version history and notable changes |
| [TODO.md](TODO.md) | Current tasks, known issues, and next steps |
| [MIGRATION.md](MIGRATION.md) | v1.x to v2.x migration guide and phase tracking |
| [CORE_AI.md](CORE_AI.md) | AI inference architecture, LiteRT-LM integration, token filtering |
| [FEATURES.md](FEATURES.md) | Feature notes by tab/module |
| [DATA_AND_PROCESSING.md](DATA_AND_PROCESSING.md) | Data layer, document parsing, WorkManager |
| [MODULES.md](MODULES.md) | Gradle module setup and dependencies |
| [THREADING.md](THREADING.md) | UI vs inference threading model |

## Quick Links

- **Getting Started**: See [README.md](../README.md#installation)
- **Architecture**: See [ARCHITECTURE.md](ARCHITECTURE.md)
- **Contributing**: See [DEVELOPMENT.md](DEVELOPMENT.md#contributing)
- **AI/Inference**: See [CORE_AI.md](CORE_AI.md)

## Project Overview

Penpal is a handwriting recognition and drawing application for Android that uses on-device AI (Google Gemma 4 E2B-IT) for real-time OCR, text recognition, and RAG-enabled chat.

### Key Features

- **AI Inference**: Real LiteRT-LM Engine API integration with GPU/CPU fallback
- **Flow-Based Streaming**: Kotlin Flow-based inference with trie-based special token filtering
- **RAG Chat**: Retrieval-Augmented Generation combining vector search with LLM inference
- **Model Management**: Download Gemma 4 E2B-IT from HuggingFace/Kaggle via ModelManager
- **Multi-page Notebooks**: Block-based editor with graphs, drawings, images
- **Whiteboard Mode**: Infinite canvas for brainstorming and sketching
- **Document Processing**: Extract and index content from PDFs, URLs, audio, and images
- **Vector Store**: Semantic similarity search across extracted content
- **Offline Mode**: Full on-device inference without network connectivity

### Current Focus

- **Think Tab Navigation**: Home button to return to notebook list, X button to close notebook
- **Code Formatting**: Better syntax highlighting in chat markdown rendering
- **Model Process Visualizer**: New tab for visualizing model inference steps
