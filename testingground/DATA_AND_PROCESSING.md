# Data Layer + Processing Pipeline

## Room schema

```kotlin
// core/data/src/main/kotlin/data/db/PenpalDatabase.kt
@Database(
    entities = [
        NotebookEntity::class,
        ChunkEntity::class,
        ExtractionJobEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun chunkDao(): ChunkDao
    abstract fun jobDao(): ExtractionJobDao
    abstract fun graphDao(): GraphDao
}
```

```kotlin
@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey val id: String,
    val sourceId: String,          // FK → ExtractionJob
    val text: String,
    val embeddingJson: String,     // float[] serialized as JSON
    val pageOrTimestamp: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "extraction_jobs")
data class ExtractionJobEntity(
    @PrimaryKey val id: String,
    val sourceUri: String,
    val mimeType: String,          // application/pdf · audio/wav · image/* · text/uri-list
    val rule: ExtractionRule,
    val status: JobStatus,
    val workerId: String?,         // WorkManager UUID
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class ExtractionRule { FFT_PEAKS, DICOM_METADATA, FULL_TEXT, TRANSCRIPT, IMAGE_OCR }
enum class JobStatus { QUEUED, RUNNING, DONE, FAILED }
```

---

## VectorStore (SQLite FTS5)

On-device similarity search without a native library — uses SQLite's FTS5 with precomputed cosine distance.

```kotlin
// core/data/src/main/kotlin/data/vector/VectorStoreRepository.kt
class VectorStoreRepository @Inject constructor(
    private val chunkDao: ChunkDao,
    private val embedder: TextEmbedder,           // MiniLM ONNX, ~22MB
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val cache = ConcurrentHashMap<String, FloatArray>()

    suspend fun embed(chunks: List<RawChunk>) = withContext(dispatcher) {
        val entities = chunks.map { chunk ->
            val vec = embedder.embed(chunk.text)
            cache[chunk.id] = vec
            ChunkEntity(
                id = chunk.id,
                sourceId = chunk.sourceId,
                text = chunk.text,
                embeddingJson = vec.toJson(),
                pageOrTimestamp = chunk.position,
            )
        }
        chunkDao.insertAll(entities)
    }

    suspend fun similaritySearch(query: String, topK: Int): List<ChunkEntity> =
        withContext(dispatcher) {
            val queryVec = embedder.embed(query)
            chunkDao.getAll()
                .map { entity ->
                    val vec = cache.getOrPut(entity.id) { entity.embeddingJson.toFloatArray() }
                    entity to cosineSimilarity(queryVec, vec)
                }
                .sortedByDescending { it.second }
                .take(topK)
                .map { it.first }
        }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        return dot / (sqrt(normA) * sqrt(normB) + 1e-8f)
    }
}
```

---

## Document parser — multi-format

```kotlin
// core/processing/src/main/kotlin/processing/DocumentParser.kt
class DocumentParser @Inject constructor(
    private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun parse(jobId: String, uri: Uri, rule: ExtractionRule): List<RawChunk> =
        withContext(ioDispatcher) {
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            when {
                mime == "application/pdf"      -> parsePdf(uri, rule)
                mime.startsWith("audio/")      -> parseAudio(uri)
                mime.startsWith("image/")      -> parseImage(uri)
                mime == "text/uri-list"        -> parseUrl(uri)
                mime.startsWith("text/")       -> parseText(uri)
                else -> error("Unsupported mime: $mime")
            }
        }

    private fun parsePdf(uri: Uri, rule: ExtractionRule): List<RawChunk> {
        val stream = context.contentResolver.openInputStream(uri)!!
        val reader = PdfReader(stream)
        val doc = PdfDocument(reader)
        return (1..doc.numberOfPages).flatMap { page ->
            val text = PdfTextExtractor.getTextFromPage(doc.getPage(page))
            text.chunked(512).mapIndexed { i, chunk ->
                RawChunk(id = uuid(), sourceId = uri.toString(), text = chunk, position = page * 100 + i)
            }
        }
    }

    private fun parseAudio(uri: Uri): List<RawChunk> {
        // Whisper.cpp via JNI  →  transcription chunks
        val wav = AudioConverter.toWav16kHz(context, uri)
        return WhisperBridge.transcribe(wav).segments.map { seg ->
            RawChunk(
                id = uuid(),
                sourceId = uri.toString(),
                text = seg.text,
                position = seg.startMs.toInt(),
            )
        }
    }

    private fun parseImage(uri: Uri): List<RawChunk> {
        val bitmap = context.contentResolver.openInputStream(uri)!!
            .use { BitmapFactory.decodeStream(it) }
        val result = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bitmap, 0)).await()
        return listOf(RawChunk(uuid(), uri.toString(), result.text, 0))
    }

    private suspend fun parseUrl(uri: Uri): List<RawChunk> {
        val url = uri.toString()
        val html = Jsoup.connect(url).get().text()
        return html.chunked(512).mapIndexed { i, chunk ->
            RawChunk(uuid(), url, chunk, i)
        }
    }
}
```

---

## Extraction protocol rules — user-configurable

Rules are stored in DataStore Proto and applied at parse time.

```protobuf
// core/data/src/main/proto/extraction_rules.proto
syntax = "proto3";
option java_package = "ai.penpal.core.data.proto";

message ExtractionConfig {
    repeated ExtractionRule rules = 1;
}

message ExtractionRule {
    string id = 1;
    string name = 2;
    RuleTarget target = 3;
    map<string, string> params = 4;     // e.g. window: "hanning", resolution: "1hz"
}

enum RuleTarget {
    FULL_TEXT = 0;
    FFT_PEAKS = 1;
    DICOM_METADATA = 2;
    IMAGE_OCR = 3;
    WHISPER_TRANSCRIPT = 4;
}
```

```kotlin
// Read / write via DataStore
val extractionConfigFlow: Flow<ExtractionConfig> =
    context.extractionConfigDataStore.data

suspend fun addRule(rule: ExtractionRule) {
    context.extractionConfigDataStore.updateData { config ->
        config.toBuilder().addRules(rule).build()
    }
}
```

---

## Settings — DataStore Proto

```protobuf
// core/data/src/main/proto/app_settings.proto
message AppSettings {
    AiModel selected_model = 1;
    bool wav_extraction_enabled = 2;
    bool hipaa_cloud_sync = 3;
    uint32 buffer_persistence_mb = 4;    // default 1024
    uint32 thread_count = 5;             // WorkManager parallelism
    VisualizationMode viz_mode = 6;
}

enum AiModel { NEURAL_3 = 0; TITAN_X = 1; LEGACY_S = 2; }
enum VisualizationMode { TWO_D = 0; THREE_D = 1; }
```

---

## AI model routing

`InferenceEngine` delegates to on-device or remote backend depending on `AppSettings.selected_model` and network state.

```kotlin
sealed interface ModelBackend {
    data class OnDevice(val session: OrtSession) : ModelBackend
    data class Remote(val api: InferenceApi) : ModelBackend   // Retrofit
}

class InferenceEngine @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
) {
    private val backend: ModelBackend by lazy { resolveBackend() }

    private fun resolveBackend(): ModelBackend {
        val model = settingsRepo.currentSettings.selectedModel
        val online = networkMonitor.isOnline
        return when {
            model == AiModel.NEURAL_3 || !online -> ModelBackend.OnDevice(loadSession("neural3.onnx"))
            model == AiModel.TITAN_X             -> ModelBackend.Remote(buildApi(TITAN_X_URL))
            else                                 -> ModelBackend.OnDevice(loadSession("legacy_s.onnx"))
        }
    }
}
```

---

## Graph / Organize data model

Nodes in the Organize tab are stored in Room as typed entities. The 2D/3D layout position is computed on `Dispatchers.Default` and cached.

```kotlin
@Entity(tableName = "graph_nodes")
data class GraphNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val type: NodeType,            // PAPER · CONCEPT · TOOL · DATA_MODEL
    val notebookId: String?,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val posZ: Float = 0f,          // non-zero only in 3D mode
)

@Entity(tableName = "graph_edges", primaryKeys = ["fromId", "toId"])
data class GraphEdgeEntity(
    val fromId: String,
    val toId: String,
    val relation: String,          // "depends_on" · "references" · "conflicts_with"
    val weight: Float = 1f,
)
```
