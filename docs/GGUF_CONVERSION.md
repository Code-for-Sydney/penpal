# GGUF to LiteRT Model Conversion

## Overview

This document describes the GGUF to LiteRT conversion feature implemented in PenPal.

## What is GGUF?

GGUF (Generic GPU Model Format) is a format developed by llama.cpp for efficient storage and loading of quantized LLMs. Many community models are available in GGUF format.

## Supported Models

PenPal uses Google AI Edge LiteRT-LM for on-device inference, which requires `.litertlm` format files.

| Source | Format | Conversion |
|--------|--------|------------|
| HuggingFace LiteRT Community | `.litertlm` | ✅ Direct use |
| Google Gemma | `.litertlm` | ✅ Direct use |
| Ollama/Llama.cpp | `.gguf` | ⚠️ Requires conversion |

## Conversion Options

### Option 1: Use Pre-converted Models (Recommended)

Download pre-converted Gemma/Llama models from HuggingFace LiteRT Community:

```
https://huggingface.co/litert-community
```

Example models:
- `litert-community/gemma-4-E2B-it-litert-lm` (~2.6 GB)
- `litert-community/gemma-4-E4B-it-litert-lm` (~3.7 GB)

### Option 2: Use Ollama (GGUF Native)

PenPal includes `OllamaInferenceBridge` which can run GGUF models via the Ollama API without conversion.

Benefits:
- No conversion needed
- Wide GGUF model support
- GPU acceleration

Setup:
1. Install Ollama on your computer
2. Download GGUF models via Ollama
3. Configure PenPal to use Ollama backend

### Option 3: Convert GGUF to LiteRT

For on-device conversion without Ollama, use the Python conversion script.

## Using the Conversion Script

```bash
# Analyze a GGUF file
python3 scripts/convert_gguf_to_litert.py --analyze model.gguf

# Convert to LiteRT format
python3 scripts/convert_gguf_to_litert.py --input model.gguf --output model.litertlm

# List available LiteRT-ready models
python3 scripts/convert_gguf_to_litert.py --list-models
```

## Implementation Details

### GgufConverter (Kotlin)

Located in `core/ai/src/main/java/com/penpal/core/ai/model/GgufConverter.kt`

Features:
- Scans device for GGUF files in common locations
- Validates GGUF magic bytes
- Parses metadata (architecture, quantization, vocab size)
- Provides conversion instructions

```kotlin
// Scan for GGUF files
val files = GgufConverter.scanForGgufFiles(context)

// Parse file info
val info = GgufConverter.parseGgufInfo(file)

// Check if file is valid GGUF
val isValid = GgufConverter.isGgufFile(file)
```

### Settings Integration

The GGUF conversion feature is integrated into the Settings screen:

1. **Scan for GGUF Files** - Detects GGUF models on device
2. **Show Model Info** - Displays architecture, size, quantization
3. **Conversion Instructions** - Guides user to conversion script

## Limitations

1. **In-app conversion not available** - Full conversion requires Google's official tooling
2. **GGUF parsing is limited** - Only reads basic metadata
3. **Android-side conversion not supported** - Requires desktop Python environment

## Recommended Workflow

1. **New Users**: Download pre-converted `.litertlm` from HuggingFace
2. **Ollama Users**: Use `OllamaInferenceBridge` for native GGUF support
3. **Advanced Users**: Convert GGUF → LiteRT using the Python script

## API Reference

### GgufConverter

```kotlin
object GgufConverter {
    fun scanForGgufFiles(context: Context): List<GgufModelInfo>
    fun scanForGgufFilesFlow(context: Context): Flow<List<GgufModelInfo>>
    fun isGgufFile(file: File): Boolean
    fun parseGgufInfo(file: File): GgufModelInfo
    fun getPythonScriptPath(): String
    fun getConversionInstructions(): String
    fun generateAdbCommand(inputPath: String, outputPath: String): String
}
```

### GgufModelInfo

```kotlin
data class GgufModelInfo(
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val fileSizeDisplay: String,
    val architecture: String?,
    val quantization: String?
)
```

### ConversionState

```kotlin
sealed class ConversionState {
    data object Idle : ConversionState()
    data class Scanning(val progress: Int) : ConversionState()
    data class Converting(val modelName: String, val progress: Float) : ConversionState()
    data class Success(val outputPath: String, val modelName: String) : ConversionState()
    data class Error(val message: String, val modelName: String?) : ConversionState()
}
```

## Future Enhancements

- [ ] Add cloud-based conversion service
- [ ] Support for more GGUF metadata fields
- [ ] Direct download links for popular models
- [ ] Integration with model registries (Replicate, Modal)