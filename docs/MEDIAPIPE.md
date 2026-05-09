# MediaPipe Tasks for Android

MediaPipe provides pre-built on-device ML models for vision, audio, and text processing. These can complement Gemma for specific tasks.

## Dependencies

Add to `build.gradle`:

```kotlin
// Vision tasks (detection, classification, segmentation)
implementation("com.google.mediapipe:tasks-vision:latest.release")

// Text tasks (classification, embedding, language detection)
implementation("com.google.mediapipe:tasks-text:latest.release")

// Audio tasks (classification)
implementation("com.google.mediapipe:tasks-audio:latest.release")

// Generative AI (LLM inference - already using LiteRT)
implementation("com.google.mediapipe:tasks-genai:latest.release")
```

## Quick Reference

### Vision Tasks

| Task | Use Case | Model |
|------|----------|-------|
| Object Detection | Find objects in images | `face_detection_short_range.tflite` |
| Image Classification | Categorize images | `mobilenet_v2.tflite` |
| Image Segmentation | Separate foreground/background | `selfie_segmentation.tflite` |
| Face Detection | Find faces | `face_detection_short_range.tflite` |
| Face Landmarks | Face mesh detection | `face_landmarker.task` |
| Hand Landmarks | Hand tracking | `hand_landmarker.task` |
| Pose Landmarks | Body pose detection | `pose_landmarker_lite.task` |
| Image Embedding | Semantic similarity | `mobile_net_v2.tflite` |

### Audio Tasks

| Task | Use Case |
|------|----------|
| Audio Classification | Identify sound types |

### Text Tasks

| Task | Use Case |
|------|----------|
| Text Classification | Sentiment analysis, spam detection |
| Text Embedding | Semantic search, similarity |
| Language Detection | Identify language |

## Example: Image Classification

```kotlin
val options = ImageClassifierOptions.builder()
    .setBaseOptions(BaseOptions.builder()
        .setModelAssetPath("mobilenet_v2.tflite")
        .build())
    .setRunningMode(RunningMode.IMAGE)
    .setMaxResults(5)
    .build()

val classifier = ImageClassifier.createFromOptions(context, options)
val result = classifier.classify(mpImage)
```

## Example: Image Embedding (for similarity)

```kotlin
val options = ImageEmbedderOptions.builder()
    .setBaseOptions(BaseOptions.builder()
        .setModelAssetPath("mobile_net_v2.tflite")
        .build())
    .setQuantize(true)
    .build()

val embedder = ImageEmbedder.createFromOptions(context, options)
val result = embedder.embed(mpImage)
```

## Using with Gemma

MediaPipe can preprocess data before sending to Gemma:
1. **Object Detection** → Identify what's in image → Include in prompt
2. **Image Embedding** → Find similar images in notebook
3. **Audio Classification** → Identify sound type → Route appropriately

## Model Files Location

Place `.tflite` or `.task` files in:
```
app/src/main/assets/
```

## Performance Notes

- Use GPU delegate for faster processing: `BaseOptions.builder().useGpu().build()`
- For live camera: use `LIVE_STREAM` mode with result listeners
- For single images: use `IMAGE` mode

## Resources

- [MediaPipe Samples](https://github.com/google-ai-edge/mediapipe-samples)
- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery)