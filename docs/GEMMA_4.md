# Gemma 4 - On-Device AI Capabilities

Gemma 4 is Google's latest open-weight AI model family, capable of running entirely on-device for Android applications. It supports multimodal inputs (text, images, audio) and various advanced features.

## Gemma 4 Capabilities Overview

### Multimodal Processing
- **Text**: Full language understanding, generation, reasoning
- **Images**: Object detection, OCR, visual QA, image captioning, reasoning
- **Audio**: Speech transcription, audio understanding
- **Video**: Frame-by-frame analysis, video understanding

### Key Features
- **Variable Resolution Processing**: 70/140/280/560/1120 token budgets for images
- **Function Calling**: Execute tools and external APIs
- **Thinking Mode**: Chain-of-thought reasoning
- **LoRA Customization**: Fine-tune model behavior with minimal resources

## Token Budgets for Images

| Budget | Patches | Use Case |
|--------|---------|----------|
| 70     | 630     | Fast processing, simple scenes |
| 140    | 1,260   | Standard images |
| 280    | 2,520   | Detailed scenes |
| 560    | 5,040   | Complex images |
| 1,120  | 10,080  | Fine details, small objects |

Higher budgets preserve more visual detail but take longer to process.

## Supported Media Types in Penpal

Based on our implementation in `NotebookModels.kt`:

```kotlin
enum class MediaType {
    IMAGE,   // Images, screenshots, photos
    AUDIO,   // Voice notes, recordings
    VIDEO,   // Video files, YouTube links
    TEXT     // Documents, code, URLs
}
```

## Processing Pipeline

### Current Implementation
1. **ProcessBlock** created with MediaType
2. User enters source URI (file path, URL)
3. "Process" button triggers AI analysis
4. Gemma model analyzes content based on type:
   - **IMAGE**: Uses `runInferenceWithImageFlow` for multimodal analysis
   - **AUDIO**: Text-based analysis of extracted content
   - **VIDEO**: Frame extraction + image analysis
   - **TEXT**: Direct text analysis

### Future Enhancement
Add MediaPipe preprocessing before sending to Gemma:
- Image classification/detection
- Audio classification
- Pre-extract text from PDFs/images

## Model Configuration

Current settings in `LiteRtInferenceBridge.kt`:
- `maxNumTokens`: 8192
- `temperature`: Configurable
- `topK`: Configurable
- Backend: GPU/CPU auto-selection

## References

- [Gemma Cookbook](https://github.com/google-gemma/cookbook)
- [Gemma 3n Documentation](https://ai.google.dev/gemma/docs/gemma-3n)
- [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)