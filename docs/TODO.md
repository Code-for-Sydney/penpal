- [x] In the Chat Tab, conversations aren't persistent. There needs to be a history similar to 
- [x] In the Chat Tab, conversations should be able to load stacks into memory. 
- [x] In the Chat Tab, adding a file to the chat would append the file to a new stack or add it to an existing stack if it is already loaded to the chat. Any files added to the chat should be pinned to the chat conversation.
- [x] In the Chat Tab, files should be able to be dragged and dropped into the conversation to load their content into the memory.
- [x] In the Core AI, grab knowledge from it, simplify the setup, use reference material and start fresh
  - [x] CORE_AI.md — Comprehensive documentation with Gemma 4 specs, LiteRT-LM reference, model performance
  - [x] CORE_AI_SIMPLIFY.md — Quick-start guide with setup examples
  - [x] CORE_AI_REFERENCES.md — Module dependency graph and usage patterns
  - Resources referenced:
    - EdgeGallery: https://github.com/google-ai-edge/gallery
    - MediaPipe: https://ai.google.dev/edge/mediapipe/solutions/guide.md.txt
    - MultiTokenPrediction: https://raw.githubusercontent.com/google-gemma/cookbook/refs/heads/main/docs/mtp/mtp.ipynb
    - Opencode (parts architecture): https://github.com/anomalyco/opencode
- In the Chat Tab, the Programming languages should have better parsing capabilities and better color formatting
- In the Think Tab, when Stacks when opened have a home button this should go back to the list of stacks.
- In the Think Tab, Stacks when opened have an "x" button - this closes the stack (auto-saves).
- In the Think Tab, stack should be processed and the information should be presented per parser present in the stack. If there is a list of images it should process each one of those in logical order unless specified by the user to make it parse in higher priority. Each result should be a separate block.
- In the Think Tab, a toggle should be present that switches between the original content and the parsed content (per-block).
- In the Think Tab, text could be setup as a system prompt to define an overall goal (drag from title bar for the stack).
- In the Think Tab, the user could input an agent prompt to guide the thinking process (over-drag panel, wired to processing).
- In the Chat Tab, add system prompt via over-drag panel (global default + per-conversation override).
- In a NEW TAB for Model process visualizer, breaks down how the model would process
- Future: NNAPI NPU Support
- Future: More Models
- Future: More Parsers
- In the Settings Tab, there should be a way to visualize the inputs and outputs of the model. 

## Current Sprint: Testing & Refinement (May 2026)

### LiteRT API Fixes ✅ (Completed)
- [x] Added `LOADING` and `READY` states to `ModelStatus` enum in `InferenceBridge.kt`
- [x] Reverted LiteRT version from 0.10.0 to `latest.release` in `gradle/libs.versions.toml`
- [x] Fixed `renderMessageIntoString()` API compatibility - uses reflection-based `getContent()` method
- [x] Updated `LiteRtInferenceBridge.loadModel()` to set `LOADING` → `READY` status

### Model Status Toggle Fixes ✅ (Completed)
- [x] Fixed `ModelStatusIndicator` to show proper status text for all states: "ON", "Loading...", "Unloading...", "Downloading...", "DL'd", "ERR", "OFF"
- [x] Fixed `MainScreen.onToggleModel` to allow toggle when modelStatus is `DOWNLOADED` or `READY`
- [x] Fixed `MainScreen` model file existence detection when status is `NOT_DOWNLOADED`
- [x] Fixed toggle handler in `ChatScreen`, `StackListScreen`, and `SettingsScreen`
- [x] Updated `SettingsViewModel` to set `ModelStatus.READY` on successful load

### Stacks Refactoring ✅ (Completed)
- [x] Renamed all `Notebook*` files to `Stack*` in `feature/stacks` module

### Testing: Model Loading
- [ ] Test app on device to verify model loads correctly with the reflection fix
- [ ] If still crashing, investigate model file compatibility

### Audio Features
- [ ] Test audio recording in stacks
- [ ] Verify audio playback functionality

## Previous Sprint: Block Parsing & Stacks Enhancement (May 2026)

### Audio Recording in Stacks ✅ (Completed)
- [x] Created `AudioRecorder` in `core:media` — AudioRecord-based 16kHz mono 16-bit PCM WAV recorder
  - [x] Streams to disk in real-time for crash safety
  - [x] Files saved to `context.filesDir/recordings/`
  - [x] Callbacks for amplitude, PCM buffer, start/stop/error
  - [x] Permission checking for RECORD_AUDIO
  - [x] WAV header written at start, updated on stop
- [x] Created `AudioAnalyzer` in `core:media` — Real-time FFT spectrum analyzer
  - [x] Cooley-Tukey radix-2 FFT with Hanning window
  - [x] 12 log-spaced frequency bins (bass→treble) normalized 0..1f
  - [x] `feedPcmData()` and `onSpectrumUpdate` callback API
- [x] Added `api(project(":core:media"))` to `feature/stacks/build.gradle.kts`
- [x] StackScreen scrollable toolbar (`Modifier.horizontalScroll`)
- [x] Audio submenu in toolbar (DropdownMenu: "Record Audio" / "Pick from Files")
- [x] Audio recording permission via `ActivityResultContracts.RequestPermission()`
- [x] `AudioRecordingDialog` composable with 3 states: IDLE, RECORDING, DONE
  - [x] RECORDING: elapsed timer (MM:SS), real-time FFT spectrum Canvas (12 green bars)
  - [x] DONE: file name + duration, "Use Recording" (auto-adds as `Block.ProcessBlock`) and "Discard"
- [x] Closed graph button now calls `onNavigateBack()` to return to stack list
- [x] Model status parameters passed to `StackScreen` from `MainScreen`
- [x] Settings screen model status indicator moved into `TopAppBar` actions slot

### Stacks Migration & Block Parsing ✅ (Completed)
- [x] Renamed `feature/notebooks` → `feature/stacks`
- [x] Added `ModelStatusIndicator` to `NotebookListScreen` TopBar
- [x] Updated `MainScreen` for stack module navigation
- [x] Enhanced `NotebookEditorViewModel` with block parsing
- [x] Auto-processing: stacks process each block according to type
- [x] Per-block toggle: original vs parsed content view

### Model Toggle Feature ✅ (Completed)
- [x] Implemented `unloadModel()` in `LiteRtInferenceBridge`
- [x] Implemented `unloadModel()` in `OllamaInferenceBridge`
- [x] Added `isUnloading` StateFlow to InferenceBridge interface
- [x] Created `ModelStatusIndicator` component in `core:ui`
- [x] Added shared model status across all tabs in `MainScreen`
- [x] Simplified ToggleModel handler in ChatViewModel

### Step 5: Global System Prompt in Settings
- [x] Add "Default System Prompt" section in Settings screen
- [x] Store in SharedPreferences

### Step 6: Over-Drag Hidden Panel (Chat)
- [x] Add `systemPrompt: String` to `ChatUiState` and `ChatConversationEntity`
- [x] Add `UpdateSystemPrompt` event in Chat
- [x] Implement over-drag pattern above message list
- [x] Add system prompt field in panel
- [x] Prepend system prompt in `sendMessage()` (global default + per-conversation override)

### Step 7: Wire Agent Prompt to Processing Pipeline
- [x] Add `agentPrompt` parameter to `WorkerLauncher.enqueue()`
- [x] Pass agent prompt through to processing workers
- [ ] Modify extraction logic to consider agent prompt (e.g., "Focus on equations" changes parsing)

### Step 8: Database Schema Updates
- [x] Add `systemPrompt`, `agentPrompt` columns to `StackEntity`
- [x] Add `systemPrompt`, `agentPrompt` columns to `ChatConversationEntity`
- [x] Update DAOs if needed

## Current Sprint: Chat Bubble Features

### Implemented
- [x] **Retry when model becomes ready** - Added `pendingRetryMessage` and `pendingRetryError` to `ChatUiState`, `RetryLastMessage` event, and `retryLastMessage()` function that auto-retries when model status changes to ready
- [x] **Sub-chat nesting (data layer)** - Added `parentId: String?` to `ChatConversation` in `ChatViewModel.kt` and `ChatConversationEntity` in `Entities.kt`

### Not Yet Implemented (file reverted due to compilation errors)
- [ ] **Deselect on tap outside** - Need to add tap outside detection to clear selection state
- [ ] **Timestamp by swiping left** - Need to add horizontal drag gesture detection
- [ ] **Share message** - Need to add share functionality
- [ ] **Sub-chat UI** - Need to implement the sub-chat screen navigation and display

### Notes
- Previous attempt to modify `ChatScreen.kt` failed - compilation errors when adding features to `MessageBubble` composable about local functions needing different modifiers. File was reverted to original state.
- Need to re-implement features carefully: add imports first, then function parameters, then composable body. Test compile after each major change.

## Previous Sprint: Streaming Token Filter & Text Structure Fix

- [x] Create `GemmaSpecialTokens.kt` with all Gemma 4 control token definitions
- [x] Create `StreamingTokenFilter.kt` with trie-based character-by-character filtering
- [x] Implement `runInferenceFlow()` and `runInferenceWithImageFlow()` in `InferenceBridge`
- [x] Implement Flow-based inference in `LiteRtInferenceBridge` with 120s timeout
- [x] Implement Flow-based inference in `OllamaInferenceBridge` for parity
- [x] Migrate `ChatViewModel` from callback-based to Flow-based inference
- [x] Add `AtomicBoolean` timeout guards to prevent hung inference
- [x] Fix `loadConversation()` to preserve pending assistant message when `isLoading=true`
 - [x] **Fix text splitting after special character filtering**
  - [x] Remove `skipStructuralNewlines()` that was stripping all newlines after tokens
  - [x] Add `hasEmittedContent` tracking to detect tokens at start vs inline
  - [x] Tokens at start: strip following whitespace (e.g., `<|turn>model\nHello` → `Hello`)
  - [x] Tokens inline with word after: insert space (e.g., `Hello<|turn>modelWorld` → `Hello World`)
  - [x] Tokens inline with whitespace after: preserve natural whitespace (e.g., `Hello<|turn>model\nWorld` → `Hello\nWorld`)

## Completed Sprint: Structured Message Parts (Opencode-Inspired)

Based on analysis of the opencode library's Vercel AI SDK "parts" architecture:

### High Priority — Core Architecture
- [x] Extend `StreamingTokenFilter` to track active message parts (Text, Reasoning, ToolCall)
  - [x] Emit part transition events when mode changes (REGULAR → THINKING → REGULAR, etc.)
  - [x] Buffer content per part instead of single output stream
- [x] Create `MessagePart` sealed class hierarchy in core:ai
  - [x] `TextPart(text: String)` — Regular assistant response text
  - [x] `ReasoningPart(text: String, isComplete: Boolean)` — Thinking/reasoning blocks
  - [x] `ToolCallPart(name: String, callId: String, args: Map, rawJson: String, status: ToolStatus)` — Tool invocation
  - [x] `ToolResponsePart(name: String, callId: String, output: String)` — Tool execution result
  - [x] `ImagePart(description: String)` — Image descriptions
  - [x] `AudioPart(transcription: String)` — Audio transcriptions
- [x] Update `InferenceBridge` interface to emit `Flow<List<MessagePart>>`
- [x] Update `LiteRtInferenceBridge` to parse Gemma 4 tokens into MessageParts
  - [x] `<|channel>thought` → creates ReasoningPart
  - [x] `<|tool_call>...<tool_call|>` → creates ToolCallPart
  - [x] Regular text → creates TextPart
- [x] Update `ChatViewModel` to collect MessageParts and build structured assistant messages
  - [x] Store `List<MessagePart>` on ChatMessage alongside plain String content
  - [x] Aggregate streaming parts into final message structure

### Medium Priority — UI & Features
- [x] Add ReasoningPart UI rendering
  - [x] Collapsible "Thinking" section (collapsed by default)
  - [x] Distinct styling (italic, muted color, card background)
  - [x] Show/hide toggle with animation
- [x] Add ToolCallPart UI rendering
  - [x] Structured JSON display
  - [x] Execution status indicators (pending → running → completed/error)
  - [x] Expandable tool call details
- [x] Implement tool call JSON parsing from `<|tool_call>` tokens
  - [x] Parse function name and arguments with regex
  - [x] Handle `<|"\|>` string delimiter token

### Low Priority — Docs
- [x] Update CORE_AI.md with MessageParts architecture
- [x] Update CORE_AI_SIMPLIFY.md with streaming parts pattern
- [x] Update CORE_AI_REFERENCES.md with opencode design reference

## Backlog

- [ ] Mark/model content types (thinking, image, audio, bos, eos, turn) in data model
  - [ ] Consider annotated spans or rich text model instead of plain String
  - [ ] Track thinking blocks separately from main response text
  - [ ] Display thinking content in collapsible UI section
- [ ] Ensure special token filtering works correctly across all inference paths
  - [ ] Verify callback-based `runInference()` token filtering
  - [ ] Verify Flow-based `runInferenceFlow()` token filtering
  - [ ] Test with multimodal (image + text) inputs
- [ ] Update UI to handle cleaned streaming text properly
  - [ ] Verify `ChatScreen` text rendering with filtered output
  - [ ] Ensure auto-scroll works with variable-length chunks
