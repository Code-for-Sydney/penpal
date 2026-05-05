# Threading — UI vs Inference separation

## Dispatcher assignment

```kotlin
// core/ai/src/main/kotlin/ai/Dispatchers.kt
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher
```

---

## ViewModel pattern

ViewModels never call suspend functions directly on Main. They launch into the right dispatcher and expose `StateFlow` to Compose.

```kotlin
@HiltViewModel
class ProcessViewModel @Inject constructor(
    private val extractionRepo: ExtractionRepository,
    private val inferenceEngine: InferenceEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
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
class InferenceEngine @Inject constructor(
    private val vectorStore: VectorStoreRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
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
// In a @ActivityRetainedScoped ViewModel
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
@HiltWorker
class ExtractionWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
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

## Audio pipeline — WAV 16kHz on IO dispatcher

```kotlin
// core/media/src/main/kotlin/media/AudioRecorder.kt
class AudioRecorder @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun record(outputFile: File, durationMs: Long): Flow<Int> = flow {
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            emit(recorder.maxAmplitude)
            delay(100)
        }
        recorder.stop()
        recorder.release()
    }.flowOn(ioDispatcher)
}
```

---

## Thread safety checklist

- [ ] Every `Room` call wrapped in `withContext(Dispatchers.IO)`
- [ ] Every ONNX / embedding call wrapped in `withContext(Dispatchers.Default)`
- [ ] `StateFlow` updates never blocked — `update {}` is lock-free
- [ ] `WorkManager` workers use `CoroutineWorker`, not `Worker`
- [ ] No `runBlocking` anywhere in production code
- [ ] No `GlobalScope` — all coroutines tied to `viewModelScope` or worker scope
