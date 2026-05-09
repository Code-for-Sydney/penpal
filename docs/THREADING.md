# Threading — UI vs Inference separation

## Dispatcher assignment

```kotlin
// Dispatchers are used directly — no custom module needed
object Dispatchers {
    val Default = kotlinx.coroutines.Dispatchers.Default
    val IO = kotlinx.coroutines.Dispatchers.IO
    val Main = kotlinx.coroutines.Dispatchers.Main
}
```

**Note:** The project uses manual dependency injection via `PenpalApplication` lazy singletons. Standard Kotlin dispatchers are passed directly to ViewModels and repositories.

---

## ViewModel pattern

ViewModels never call suspend functions directly on Main. They launch into the right dispatcher and expose `StateFlow` to Compose.

```kotlin
class ProcessViewModel(
    private val extractionRepo: ExtractionRepository,
    private val inferenceEngine: InferenceEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _queue = MutableStateFlow<List<ExtractionJob>>(emptyList())
    val queue: StateFlow<List<ExtractionJob>> = _queue.asStateFlow()

    private val _inferenceState = MutableStateFlow<InferenceState>(InferenceState.Idle)
    val inferenceState: StateFlow<InferenceState> = _inferenceState.asStateFlow()

    // IO-bound: reading file bytes, writing to Room
    fun enqueueFile(uri: Uri) = viewModelScope.launch(ioDispatcher) {
        val job = extractionRepo.createJob(uri)
        _queue.update { it + job }
        WorkerLauncher.enqueue(job)          // hands off to WorkManager
    }

    // CPU-bound: embedding + inference on Default dispatcher
    fun runInference(query: String) = viewModelScope.launch(defaultDispatcher) {
        _inferenceState.value = InferenceState.Running
        val result = inferenceEngine.query(query)   // ONNX / API call
        _inferenceState.value = InferenceState.Done(result)
    }
}
```

---

## Inference engine

Runs entirely on `Dispatchers.Default`. Never touches Room directly — reads from an in-memory context window built by the repository.

```kotlin
// core/ai/src/main/kotlin/ai/InferenceEngine.kt
class InferenceEngine(
    private val vectorStore: VectorStoreRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val session: OrtSession by lazy { loadOnnxSession() }

    suspend fun query(prompt: String, topK: Int = 6): InferenceResult =
        withContext(dispatcher) {
            val chunks = vectorStore.similaritySearch(prompt, topK)  // in-memory, fast
            val context = chunks.joinToString("\n\n") { it.text }
            val input = buildPrompt(context, prompt)
            val tokens = tokenize(input)
            val output = session.run(tokens)
            InferenceResult(text = decode(output), sources = chunks.map { it.sourceId })
        }

    // Model lives in assets/, loaded once
    private fun loadOnnxSession(): OrtSession {
        val env = OrtEnvironment.getEnvironment()
        val bytes = javaClass.classLoader!!.getResourceAsStream("neural3.onnx")!!.readBytes()
        return env.createSession(bytes)
    }
}
```

---

## Channel bridge — UI events → background work

Use a `Channel` when the UI needs to fire-and-forget into a background pipeline without coupling to the result directly.

```kotlin
// In a ViewModel (activity-scoped)
private val _ingestionChannel = Channel<IngestionRequest>(capacity = Channel.BUFFERED)

init {
    viewModelScope.launch(ioDispatcher) {
        _ingestionChannel.consumeEach { request ->
            ingestionPipeline.process(request)   // runs on IO, emits progress via Flow
        }
    }
}

fun submit(request: IngestionRequest) {
    viewModelScope.launch { _ingestionChannel.send(request) }
}
```

---

## WorkManager — extraction workers

Long-running jobs (PDF parsing, WAV transcription, YouTube download+extract) leave the coroutine scope and run in `WorkManager`. Results write back to Room; the UI observes `WorkInfo` as a Flow.

```kotlin
// core/processing/src/main/kotlin/processing/ExtractionWorker.kt
class ExtractionWorker(
    ctx: Context,
    params: WorkerParameters,
    private val parser: DocumentParser,
    private val vectorStore: VectorStoreRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()

        setProgress(workDataOf(KEY_PROGRESS to 0))

        val raw = parser.parse(jobId)                    // PDF/WAV/image → text chunks

        setProgress(workDataOf(KEY_PROGRESS to 50))

        withContext(Dispatchers.Default) {
            vectorStore.embed(raw)                       // embedding on Default
        }

        setProgress(workDataOf(KEY_PROGRESS to 100))
        Result.success(workDataOf(KEY_JOB_ID to jobId))
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_PROGRESS = "progress"
    }
}
```

```kotlin
// Enqueue with chaining:  parse → embed → notify
fun enqueueExtractionChain(jobId: String): UUID {
    val parse = OneTimeWorkRequestBuilder<ExtractionWorker>()
        .setInputData(workDataOf(ExtractionWorker.KEY_JOB_ID to jobId))
        .setConstraints(Constraints(requiresStorageNotLow = true))
        .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, parse)

    return parse.id
}
```

```kotlin
// Observe from ViewModel:
fun observeJob(workId: UUID): Flow<ExtractionStatus> =
    WorkManager.getInstance(context)
        .getWorkInfoByIdFlow(workId)
        .map { info ->
            when (info?.state) {
                WorkInfo.State.RUNNING   -> ExtractionStatus.Running(
                    info.progress.getInt(ExtractionWorker.KEY_PROGRESS, 0)
                )
                WorkInfo.State.SUCCEEDED -> ExtractionStatus.Done
                WorkInfo.State.FAILED    -> ExtractionStatus.Failed
                else                     -> ExtractionStatus.Queued
            }
        }
```

---

## Audio pipeline — AudioRecord-based 16kHz WAV on worker thread

Recording runs on a dedicated native `Thread` (not a coroutine) because `AudioRecord.read()` is blocking. Callbacks are posted to the main `Handler` for UI safety. Spectrum analysis runs on a separate analyzer thread.

### AudioRecorder — raw PCM capture to WAV

```kotlin
// core/media/src/main/java/com/penpal/core/media/AudioRecorder.kt
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var outputStream: FileOutputStream? = null
    private var recordingActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Callbacks (all invoked on main thread)
    var onAmplitudeUpdate: ((Float) -> Unit)? = null
    var onPcmBuffer: ((ShortArray, Int) -> Unit)? = null
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((File?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun startRecording(fileName: String): Boolean {
        // Creates 16kHz mono 16-bit PCM AudioRecord
        // Writes WAV header on start, updates on stop
        // Spawns recordingThread → recordingLoop()
    }

    private fun recordingLoop() {
        // Blocking AudioRecord.read() into ShortArray buffer
        // Calculates RMS amplitude → onAmplitudeUpdate
        // Emits raw PCM → onPcmBuffer (for AudioAnalyzer)
        // Converts to bytes → FileOutputStream (streaming to disk)
    }

    fun stopRecording(): File? {
        // Stops thread, releases AudioRecord, closes stream, updates WAV header
    }

    fun getDurationMs(file: File): Long {
        // Computes duration from file size: (fileSize - 44) / (SAMPLE_RATE * 2)
    }
}
```

**Thread model:**
- `recordingThread`: native `Thread` (not coroutine) — blocking `AudioRecord.read()` loop
- All callbacks posted to `mainHandler` (main thread) for UI-safe updates
- `onPcmBuffer` fires on the recording thread — consumer must not block
- WAV file written incrementally to disk — crash-safe (only header lost on crash)

### AudioAnalyzer — real-time FFT spectrum

```kotlin
// core/media/src/main/java/com/penpal/core/media/AudioAnalyzer.kt
class AudioAnalyzer {

    companion object {
        const val NUM_BINS = 12
    }

    var onSpectrumUpdate: ((FloatArray) -> Unit)? = null

    private var specThread: Thread? = null
    private var isAnalyzing = false
    private var pendingBuffer: ShortArray? = null
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun feedPcmData(buffer: ShortArray, length: Int) {
        // Called from recording thread — thread-safe via synchronized(lock)
        synchronized(lock) {
            pendingBuffer = buffer.copyOf(length)
            pendingLength = length
        }
    }

    fun startAnalyzing() {
        isAnalyzing = true
        specThread = Thread { analysisLoop() }
        specThread?.start()
    }

    private fun analysisLoop() {
        while (isAnalyzing) {
            // Polls pendingBuffer with synchronized(lock)
            // Calls computeSpectrum() → onSpectrumUpdate (mainHandler)
            Thread.sleep(80)  // ~12.5 FPS spectrum updates
        }
    }

    fun computeSpectrum(buffer: ShortArray, length: Int): FloatArray {
        // 1. Pad to next power of two
        // 2. Apply Hanning window
        // 3. Cooley-Tukey radix-2 FFT (in-place, real+imag arrays)
        // 4. Compute magnitude spectrum
        // 5. Aggregate into 12 log-spaced frequency bins
        // 6. Normalize 0..1f
    }
}
```

**Thread model:**
- `specThread`: native `Thread` — polls pending PCM data, runs FFT computation
- `feedPcmData()` called from recording thread — `synchronized(lock)` for safe handoff
- `onSpectrumUpdate` posted to `mainHandler` → Compose `Canvas` renders 12 green bars

### Recording UI flow

```
User taps "Record Audio"
  → AudioRecordingDialog (IDLE state)
  → User taps "Start Recording"
  → audioPermissionLauncher.launch(RECORD_AUDIO)  // ActivityResultContracts
  → AudioRecorder.startRecording()
  → AudioAnalyzer.startAnalyzing()
       │
       ▼
  AudioRecorder.recordingLoop (Thread):
    AudioRecord.read(buffer) → RMS calc (onAmplitudeUpdate)
                              → feedPcmData(buffer) [→ AudioAnalyzer.thread]
                              → write bytes to FileOutputStream
       │
       ▼
  AudioAnalyzer.analysisLoop (Thread):
    feedPcmData → synchronized(lock) → computeSpectrum → onSpectrumUpdate (mainHandler)
       │
       ▼
  UI Canvas: 12 green bars update at ~12.5 FPS
  
User taps "Stop Recording"
  → AudioRecorder.stopRecording() (stops thread, finalizes WAV)
  → AudioAnalyzer.stop() (stops analysis thread)
  → Dialog transitions to DONE state
  → "Use Recording" → creates Block.ProcessBlock(MediaType.AUDIO, sourceUri=file.toURI())
```

---

## Thread safety checklist

- [ ] Every `Room` call wrapped in `withContext(Dispatchers.IO)`
- [ ] Every ONNX / embedding call wrapped in `withContext(Dispatchers.Default)`
- [ ] `StateFlow` updates never blocked — `update {}` is lock-free
- [ ] `WorkManager` workers use `CoroutineWorker`, not `Worker`
- [ ] No `runBlocking` anywhere in production code
- [ ] No `GlobalScope` — all coroutines tied to `viewModelScope` or worker scope
