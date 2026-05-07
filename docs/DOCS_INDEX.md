# Project Docs

This directory contains documentation for the Penpal Android application.

## Documentation Files

| File | Description |
|------|-------------|
| [README.md](../README.md) | Project overview, features, setup instructions |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | System design, component relationships, data flow |
| [DEVELOPMENT.md](../DEVELOPMENT.md) | Development guidelines, testing, contribution |
| [CHANGELOG.md](../CHANGELOG.md) | Version history and notable changes |
| [TODO.md](../TODO.md) | Current tasks, known issues, and next steps |
| [MIGRATION.md](../MIGRATION.md) | v1.x to v2.x migration guide and phase tracking |

## Quick Links

- **Getting Started**: See [README.md](../README.md#installation)
- **Architecture**: See [ARCHITECTURE.md](../ARCHITECTURE.md)
- **Contributing**: See [DEVELOPMENT.md](../DEVELOPMENT.md#contributing)

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

- **Text Structure Fix**: Resolving spurious line breaks after special token filtering in chat streaming (see [TODO.md](../TODO.md) and [ARCHITECTURE.md](../ARCHITECTURE.md#known-issues))