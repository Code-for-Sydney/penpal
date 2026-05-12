# REFACTOR0001: Architecture Documentation Sync

**Date**: 2026-05-12  
**Type**: Documentation  
**Scope**: docs/ARCHITECTURE.md

---

## 1. Summary

This refactoring addresses systematic inaccuracies in `docs/ARCHITECTURE.md` that diverged from the actual codebase. The document described an aspirational Hilt-based architecture with DI annotations, different version numbers, wrong file listings, incorrect module structures, and contradictory tab configurations. The update aligns ARCHITECTURE.md with the ground-truth state of every module, file, version, and dependency.

No code changes were needed — this is a documentation-only sync.

---

## 2. Discrepancies Found

### 2.1 Version Catalog (libs.versions.toml)

| Item | Doc Said | Actual |
|------|----------|--------|
| Kotlin | `2.1.0` | `2.0.21` |
| Compose compiler | `2.1.0` (separate entry) | N/A (KSP `2.0.21-1.0.28`) |
| Hilt | `2.54` | `2.51.1` |
| Room | `2.7.0` | `2.6.1` |

### 2.2 Build Configuration Table

- Room: Doc claimed `2.7.0-beta01` for `core:data`. Actual: `2.6.1` everywhere.
- Missing entries for `litertlm = "latest.release"`, `jsoup`, `mlkit-text-recognition`, `onnxruntime`, `coil`, `webkit`, `gson`, `pdfbox`.

### 2.3 Bottom Navigation / Tab Structure (Contradictory)

Three different claims existed within the same document:

| Location | Claim |
|----------|-------|
| Line 57 (NavigationBar code) | Chat, Think, Settings (3 tabs) |
| Line 293 (Tab Implementation table) | Chat, Think, Settings (3 tabs) |
| Line 300 (Note) | "2 tabs: Think, Settings, Chat via FAB" |
| Line 1257 (Bottom Navigation table) | Notebooks, Think, Settings, Chat (4 rows) |
| Line 1281 (Note) | "3 tabs: Notebooks, Think, Settings, Chat via FAB" |

**Actual**: 3 bottom navigation tabs: **Notebooks**, **Think** (Stacks), **Settings**. **Chat** is a separate NavHost route accessible via a FAB (only shown on the Settings tab).

### 2.4 Duplicate "Module Dependencies" Sections

Lines 357–373 and lines 1283–1295 are character-for-character identical. The second occurrence was a copy-paste duplicate.

### 2.5 Hilt Annotations (Fabricated)

The document extensively described Hilt DI annotations that do not exist anywhere in the codebase:

- `@HiltViewModel` on `StackEditorViewModel` — **does not exist**
- `@Singleton` + `@Inject constructor` on `LiteRtInferenceBridge` — **does not exist**
- `@HiltWorker` + `@AssistedInject` on `ExtractionWorker` — **does not exist**
- `@Qualifier` annotations (`@IoDispatcher`, `@DefaultDispatcher`, `@InferenceDispatcher`) — **do not exist**
- `@Module` / `@InstallIn` / DispatcherModule — **do not exist**

**Actual**: All DI is manual via `PenpalApplication` lazy singletons and `PenpalDatabase.getInstance()`.

### 2.6 InferenceBridge Interface (Wrong)

Doc described: `isReadyFlow`, `isProcessingFlow`, `modelInfoFlow`, `downloadProgressFlow`, `initialize()`, `downloadModel()`, `generate()`, `streamGenerate()`, `detectItems()`, `recognizeText()`, `transcribeAudio()`.

**Actual interface**: `isReady`, `isProcessing`, `isDownloading`, `isUnloading`, `downloadProgress`, `modelStatus`, `initialize()`, `downloadModel()` (2 overloads), `runInference()`, `runInferenceWithImage()`, `runInferenceFlow()`, `runInferenceFlowParts()`, `runInferenceWithImageFlow()`, `runInferenceWithImageFlowParts()`, `runInferenceWithAudio()`, `runInferenceWithAudioFlow()`, `runInferenceWithAudioFlowParts()`, `resetConversation()`, `stopInference()`, `release()`, `unloadModel()`, `listAvailableModels()`, `loadModel()`, `deleteModel()`.

### 2.7 LiteRtInferenceBridge

- Doc: `@Singleton class LiteRtInferenceBridge @Inject constructor(@InferenceDispatcher ...)`
- Actual: `class LiteRtInferenceBridge(private val context: Context) : InferenceBridge`

Engine management is inlined (not delegated to a separate `LmEngineManager` — though `LmEngineManager` exists as a standalone utility, it is not used by `LiteRtInferenceBridge`).

### 2.8 LmEngineManager Description

Doc described `createEngine()` returning `Engine?` with manual GPU/CPU fallback. Actual class uses `getEngine(modelPath, config, forceReload)` with `Mutex` locking, `StateFlow`-based initialization tracking, and an internal `Config` data class.

### 2.9 core:ai Module File Listings

Doc listed 26 files with a flat structure. Actual: 27 files organized in subdirectories:

- **Wrong/missing files**: Doc listed `ModelDownloadWorker.kt` (does not exist), `OllamaModel.kt` (actual: `OllamaModels.kt`). Doc omitted `GgufConverter.kt`, `WebSearchTools.kt`, `RawChunk.kt`, `ContentMode.kt`, `ModelTypes.kt`.
- **File organization**: Actual files are in `inference/`, `inference/implementation/`, `inference/model/`, `messaging/`, `model/`, `ollama/`, `tokenization/`, `embedding/`, `vectorstore/`, `tools/`, `tools/web/` — doc showed a flat listing.

### 2.10 core:data Module

| Aspect | Doc | Actual |
|--------|-----|--------|
| Files | 3 (`PenpalDatabase.kt`, `Entities.kt`, `Daos.kt`) | 22 files in subdirectories |
| Entities | 7 | 10 (+GraphToken, +Notebook, +NotebookSheet) |
| DAOs | 6 | 9 (+graphTokenDao, +notebookDao, +notebookSheetDao) |
| DB version | 3 | 9 |
| exportSchema | true | false |

### 2.11 core:media Module

- Doc: "empty shell, no source files"
- Actual: 8 Kotlin files (`AudioRecorder.kt`, `WavConstants.kt`, `AudioAnalyzer.kt`, `AudioPlayer.kt`, `AudioChunker.kt`, `SvgSerializer.kt`, `SvgResult.kt`, `SvgData.kt`)

### 2.12 core:ui Module

- Doc: Just `Theme.kt`
- Actual: 5 files (`Theme.kt`, `ActiveTool.kt`, `BackgroundType.kt`, `StatusIndicator.kt`, `PickerDialog.kt`)

### 2.13 core:processing Module

- Doc: Flat file listing, `ExtractionWorker` with `@HiltWorker` / `@AssistedInject`
- Actual: 8 files in subdirectories (`document/`, `worker/`, `notification/`, `network/`, `speech/`). `ExtractionWorker` uses manual DI (`PenpalDatabase.getInstance()`).

### 2.14 feature:stacks Module

| Aspect | Doc | Actual |
|--------|-----|--------|
| Number of files | 6 | 9 |
| Missing from doc | — | `StackListScreen.kt`, `StackListViewModel.kt`, `StackPicker.kt` |
| StackEditorViewModel | `@HiltViewModel @Inject` | Plain constructor |
| StackModels.kt | Missing `ProcessBlock`, `MediaType`, `ProcessStatus`, ~10 events, `StackScreenEvent` | All present |

### 2.15 Module Dependency Graph

- Doc: `feature:stacks` → `core:ai`, `core:data`, `core:processing`, `core:ui`
- Actual: `feature:stacks` also depends on **`core:media`** (in build.gradle.kts: `implementation(project(":core:media"))`)

### 2.16 Module Build Plugin Configurations

Doc claimed `core:ai` and `core:processing` use Hilt (`com.google.dagger.hilt.android`) and KSP plugins. Actual: neither module uses Hilt; `core:ai` has no KSP plugin at all.

### 2.17 Room Schema in Documentation

Doc showed `@Database(entities = [7 entities], version = 3, exportSchema = true)`. Actual: 10 entities, version 9, `exportSchema = false`.

### 2.18 ExtractionWorker Doc vs Actual

Doc showed `@HiltWorker`, `@AssistedInject`, injected `parser: DocumentParser`, `vectorStore: VectorStoreRepository`, `@IoDispatcher`. Actual: plain `CoroutineWorker` subclass, no annotations, obtains dependencies via `PenpalDatabase.getInstance()` and `VectorStoreProvider.instance`.

---

## 3. Changes Made to ARCHITECTURE.md

### 3.1 Removed

- All Hilt DI annotations (`@HiltViewModel`, `@Singleton`, `@Inject`, `@HiltWorker`, `@AssistedInject`, `@Qualifier`, `@Module`, `@InstallIn`)
- `DispatcherModule` section (no such code exists)
- `ModelDownloadWorker.kt` from core:ai file listing
- `OllamaModel.kt` (corrected to `OllamaModels.kt`)
- `Entities.kt` and `Daos.kt` as monolithic files (replaced with actual file tree)
- Duplicate Module Dependencies section (lines 1283–1295)
- `ExtractionRule`, `JobStatus`, `NodeType` enums that don't match actual code
- Incorrect `ExtractionWorker` Hilt-based code block
- The `ProcessViewModel` Channel Bridge pattern (no such class with `@Inject`)
- Contradictory tab descriptions (consolidated to one accurate section)

### 3.2 Updated

- Version catalog table: all versions match `libs.versions.toml`
- Build configuration table: Room is `2.6.1` everywhere
- Bottom Navigation section: accurately reflects 3 tabs (Notebooks, Think, Settings) + Chat FAB route
- Tab Implementation Status table: 4 screen routes, 3 bottom nav entries
- `InferenceBridge` interface: matches actual code
- `LiteRtInferenceBridge`: plain class with Context constructor
- `LmEngineManager`: matches actual `getEngine()` + mutex pattern
- `core:ai` file tree: accurate subdirectories and all 27 files
- `core:data` file tree: actual 22 files, 10 entities, 9 DAOs, version 9
- `core:media` file tree: actual 8 files
- `core:ui` file tree: actual 5 files
- `core:processing` file tree: actual 8 files in subdirectories
- `feature:stacks` file tree: actual 9 files, including `StackListScreen`, `StackListViewModel`, `StackPicker`
- `StackModels.kt`: includes `ProcessBlock`, `MediaType`, `ProcessStatus`, all StackEvents
- `StackEditorViewModel`: plain constructor, no Hilt
- Module dependency graph: `feature:stacks` → includes `core:media`
- `PenpalDatabase` schema: 10 entities, version 9, `exportSchema = false`
- `ExtractionWorker`: manual DI pattern
- `PenpalApplication`: accurate singleton structure
- "Last updated" line

### 3.3 Added

- `GgufConverter.kt`, `WebSearchTools.kt`, `RawChunk.kt`, `ContentMode.kt`, `ModelTypes.kt` to core:ai file tree
- `ActiveTool.kt`, `BackgroundType.kt`, `StatusIndicator.kt`, `PickerDialog.kt` to core:ui
- `NotebookEntity`, `NotebookDao`, `NotebookSheetEntity`, `NotebookSheetDao`, `GraphTokenEntity`, `GraphTokenDao`, `NotebookType.kt`, `NotebookMapper.kt` to core:data
- `GraphTokenDao` to `PenpalDatabase` DAO listing
- `ProcessBlock`, `StackListScreen`, `StackListViewModel`, `StackPicker` to feature:stacks
- Explicit note confirming manual DI and absence of Hilt annotations

---

## 4. Impact

- **Code changes**: None
- **Build changes**: None
- **Documentation**: `docs/ARCHITECTURE.md` now accurately reflects the codebase
- **Risk**: Zero — purely documentation synchronization
- **Future**: `docs/REFACTOR0001.md` serves as a historical record of what was corrected
