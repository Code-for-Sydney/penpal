- [x] In the Chat Tab, conversations aren't persistent. There needs to be a history similar to 
- [x] In the Chat Tab, conversations should be able to load notebooks into memory. 
- [x] In the Chat Tab, adding a file to the chat would append the file to a new notebook or add it to an existing notebook if it is already loaded to the chat. Any files added to the chat should be pinned to the chat conversation.
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
- In the Think Tab, when Notebooks when opened have a home button this should go back to the list of notebooks.
- In the Think Tab, Notebooks when opened have an "x" button - this closes the notebook (auto-saves).
- In the Think Tab, notebook should be processed and the information should be presented per parser present in the notebook. If there is a list of images it should process each one of those in logical order unless specified by the user to make it parse in higher priority. Each result should be a separate block.
- In the Think Tab, a toggle should be present that switches between the original content and the parsed content (per-block).
- In the Think Tab, text could be setup as a system prompt to define an overall goal (drag from title bar for the notebook).
- In the Think Tab, the user could input an agent prompt to guide the thinking process (over-drag panel, wired to processing).
- In the Chat Tab, add system prompt via over-drag panel (global default + per-conversation override).
- In a NEW TAB for Model process visualizer, breaks down how the model would process
- Future: NNAPI NPU Support
- Future: More Models
- Future: More Parsers
- In the Settings Tab, there should be a way to visualize the inputs and outputs of the model. 

## Current Sprint: Think Tab Button Harness & Processing

### Step 1: Fix Home Button Navigation
- [x] Change `onNavigateToHome` in `MainScreen` to navigate to `Screen.Notebooks.route` instead of `Screen.Chat.route`

### Step 2: Close Notebook Button + Auto-Save
- [x] Add Close (X) button to `NotebookScreen` toolbar
- [x] Wire `onCloseNotebook` callback to trigger `saveDocument()` then pop back stack
- [x] Auto-save silently when closing (no confirmation dialog)

### Step 3: Over-Drag Hidden Panel (Notebook)
- [x] Add `systemPrompt: String` and `agentPrompt: String` to `NotebookDocument` model
- [x] Add `UpdateSystemPrompt` and `UpdateAgentPrompt` events
- [x] Implement over-drag detection on `LazyColumn` (detect when scrolled to top + drag down)
- [x] Animate panel open/closed based on drag threshold
- [x] Add two `OutlinedTextField`s for prompts in the panel
- [x] Update serialization/deserialization to include prompts

### Step 4: Original ↔ Parsed Toggle (Per-Block)
- [x] Add `showParsedContent: Boolean` to `Block.ProcessBlock`
- [x] Add `ToggleProcessView` event
- [x] Add segmented toggle button in `ProcessBlockContent` UI
- [x] Show source (URI, type) vs parsed (extracted text) based on toggle
- [x] Include in serialization/deserialization

### Step 5: Global System Prompt in Settings
- [ ] Add "Default System Prompt" section in Settings screen
- [ ] Store in SharedPreferences

### Step 6: Over-Drag Hidden Panel (Chat)
- [x] Add `systemPrompt: String` to `ChatUiState` and `ChatConversationEntity`
- [x] Add `UpdateSystemPrompt` event in Chat
- [x] Implement over-drag pattern above message list
- [ ] Add system prompt field in panel
- [ ] Prepend system prompt in `sendMessage()` (global default + per-conversation override)

### Model Toggle Feature ✅
- [x] Added `isUnloading: StateFlow<Boolean>` to `InferenceBridge` interface
- [x] Added `unloadModel()` method to `InferenceBridge` interface
- [x] Implemented `unloadModel()` in `LiteRtInferenceBridge` (closes engine, resets state to DOWNLOADED)
- [x] Implemented `unloadModel()` in `OllamaInferenceBridge` (resets model state)
- [x] Added `ModelStatusIndicator` reusable component in `core:ui`
- [x] Added shared model status display in `MainScreen` across all tabs
- [x] Added `onToggleModel` handler at MainScreen level to avoid duplication
- [x] Increased `maxNumTokens` from 4096 to 8192 in EngineConfig

### Step 7: Wire Agent Prompt to Processing Pipeline
- [ ] Add `agentPrompt` parameter to `WorkerLauncher.enqueue()`
- [ ] Pass agent prompt through to processing workers
- [ ] Modify extraction logic to consider agent prompt (e.g., "Focus on equations" changes parsing)

### Step 8: Database Schema Updates
- [ ] Add `systemPrompt`, `agentPrompt` columns to `NotebookEntity`
- [ ] Add `systemPrompt` column to `ChatConversationEntity`
- [ ] Update DAOs if needed

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
