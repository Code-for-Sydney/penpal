package com.penpal.feature.stacks

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.penpal.core.media.AudioAnalyzer
import com.penpal.core.media.AudioRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * The Notebook Editor Screen - a "think space" for gathering thoughts.
 *
 * Design principles:
 * - Minimal, unobtrusive toolbar that fades when not needed
 * - Content-first approach - the canvas/blocks are the focus
 * - Floating action buttons for key actions, not a static bar
 * - Smooth animations that don't distract
 * - Dark theme that recedes and lets content pop
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StackScreen(
        viewModel: StackEditorViewModel,
        onNavigateBack: () -> Unit = {},
        onNavigateToHome: () -> Unit = {},
        onCloseStack: () -> Unit = {},
        onChatWithStack: ((String) -> Unit)? = null,
        onNavigateToChat: (() -> Unit)? = null,
        isModelReady: Boolean = false,
        isModelLoading: Boolean = false,
        isModelUnloading: Boolean = false,
        modelStatus: com.penpal.core.ai.ModelStatus = com.penpal.core.ai.ModelStatus.NOT_DOWNLOADED,
        onToggleModel: (() -> Unit)? = null,
        modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val canvasOffset by viewModel.canvasOffset.collectAsStateWithLifecycle()
    val canvasScale by viewModel.canvasScale.collectAsStateWithLifecycle()
    val selectedNodeId by viewModel.selectedNodeId.collectAsStateWithLifecycle()
    val isAddingEdge by viewModel.isAddingEdge.collectAsStateWithLifecycle()
    val edgeStartNodeId by viewModel.edgeStartNodeId.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    var showToolbar by remember { mutableStateOf(true) }
    var showAddMenu by remember { mutableStateOf(false) }
    var selectedBlockForGraph by remember { mutableStateOf<Block.GraphBlock?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedBlockIds by remember { mutableStateOf(setOf<String>()) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showRecordingDialog by remember { mutableStateOf(false) }

    // Over-drag panel state
    var isPanelExpanded by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    val panelHeight by
            animateFloatAsState(
                    targetValue = if (isPanelExpanded) 200f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    label = "panelHeight"
            )

    // Check if at top of list for over-drag detection
    val isAtTop =
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0

    val toolbarAlpha by
            animateFloatAsState(
                    targetValue = if (showToolbar) 1f else 0.3f,
                    animationSpec = tween(durationMillis = 300),
                    label = "toolbarAlpha"
            )

    // Get context for content resolver
    val context = LocalContext.current

    // Permission launcher for media access
    val permissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions -> }

    // Audio recording permission launcher
    val audioPermissionLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    showRecordingDialog = true
                }
            }

    // Image picker launcher (multiple) with persistable permissions
    val imagePickerLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                uris.forEach { uri ->
                    // Take persistable permission so we can read later
                    try {
                        context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w(
                                "NotebookScreen",
                                "Could not take persistable permission: ${e.message}"
                        )
                    }
                    val block =
                            Block.ProcessBlock(
                                    id = viewModel.newBlockId(),
                                    mediaType = MediaType.IMAGE,
                                    sourceUri = uri.toString(),
                                    status = ProcessStatus.PENDING
                            )
                    viewModel.onEvent(StackEvent.AddBlock(block))
                }
            }

    // Audio picker launcher (multiple) with persistable permissions
    val audioPickerLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w(
                                "NotebookScreen",
                                "Could not take persistable permission: ${e.message}"
                        )
                    }
                    val block =
                            Block.ProcessBlock(
                                    id = viewModel.newBlockId(),
                                    mediaType = MediaType.AUDIO,
                                    sourceUri = uri.toString(),
                                    status = ProcessStatus.PENDING
                            )
                    viewModel.onEvent(StackEvent.AddBlock(block))
                }
            }

    // Video picker launcher (multiple) with persistable permissions
    val videoPickerLauncher =
            rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w(
                                "NotebookScreen",
                                "Could not take persistable permission: ${e.message}"
                        )
                    }
                    val block =
                            Block.ProcessBlock(
                                    id = viewModel.newBlockId(),
                                    mediaType = MediaType.VIDEO,
                                    sourceUri = uri.toString(),
                                    status = ProcessStatus.PENDING
                            )
                    viewModel.onEvent(StackEvent.AddBlock(block))
                }
            }

    // Text picker launcher with persistable permissions
    val textPickerLauncher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) {
                    uri: Uri? ->
                uri?.let {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                                it,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w(
                                "NotebookScreen",
                                "Could not take persistable permission: ${e.message}"
                        )
                    }
                    val block =
                            Block.ProcessBlock(
                                    id = viewModel.newBlockId(),
                                    mediaType = MediaType.TEXT,
                                    sourceUri = it.toString(),
                                    status = ProcessStatus.PENDING
                            )
                    viewModel.onEvent(StackEvent.AddBlock(block))
                }
            }

    // Auto-save when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            if (uiState.isDirty) {
                viewModel.saveDocument()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Main content area - blocks list or graph canvas
        if (selectedBlockForGraph != null) {
            // Graph canvas mode
            GraphNodeCanvas(
                    nodes = selectedBlockForGraph!!.nodes,
                    edges = selectedBlockForGraph!!.edges,
                    selectedNodeId = selectedNodeId,
                    isAddingEdge = isAddingEdge,
                    edgeStartNodeId = edgeStartNodeId,
                    onNodePositionChanged = viewModel::updateNodePosition,
                    onNodeDragEnded = viewModel::finalizeNodePosition,
                    onNodeSelected = { nodeId -> viewModel.selectBlock(nodeId) },
                    onNodeDoubleTap = { x, y ->
                        viewModel.addNodeToGraph(selectedBlockForGraph!!.id, "New Node", x, y)
                    },
                    onNodeLongPress = { nodeId, _ ->
                        viewModel.removeNodeFromGraph(selectedBlockForGraph!!.id, nodeId)
                    },
                    onEdgeStart = viewModel::startAddingEdge,
                    onEdgeComplete = viewModel::completeEdge,
                    onCanvasTap = {
                        selectedBlockForGraph = null
                        viewModel.cancelEdgeCreation()
                    },
                    onCanvasPan = viewModel::updateCanvasOffset,
                    onCanvasScale = viewModel::updateCanvasScale,
                    canvasOffset = canvasOffset,
                    canvasScale = canvasScale,
                    modifier = Modifier.fillMaxSize()
            )

            // Close graph button - top END (navigates back to notebook list)
            FloatingActionButton(
                    onClick = {
                        selectedBlockForGraph = null
                        viewModel.cancelEdgeCreation()
                        onNavigateBack()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                        Icons.Default.Close,
                        contentDescription = "Close graph",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Add node button - bottom START (avoids overlap with FAB menu)
            FloatingActionButton(
                    onClick = {
                        viewModel.addNodeToGraph(selectedBlockForGraph!!.id, "New Node", 0f, 0f)
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
            ) { Icon(Icons.Default.Add, contentDescription = "Add node") }
        } else {
            // Blocks list mode with over-drag hidden panel
            Box(modifier = Modifier.fillMaxSize()) {
                // Hidden panel (revealed by over-drag)
                if (panelHeight > 0) {
                    PromptsPanel(
                            systemPrompt = uiState.document.systemPrompt,
                            agentPrompt = uiState.document.agentPrompt,
                            onSystemPromptChange = {
                                viewModel.onEvent(StackEvent.UpdateSystemPrompt(it))
                            },
                            onAgentPromptChange = {
                                viewModel.onEvent(StackEvent.UpdateAgentPrompt(it))
                            },
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .height(panelHeight.dp)
                                            .background(
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(
                                                            alpha = 0.95f
                                                    )
                                            )
                    )
                }

                // Blocks list with over-drag detection
                LazyColumn(
                        state = listState,
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(
                                                top =
                                                        if (panelHeight > 0)
                                                                (80.dp + panelHeight.dp)
                                                        else 80.dp
                                        )
                                        .padding(bottom = 100.dp)
                                        .pointerInput(isAtTop) {
                                            if (isAtTop) {
                                                detectVerticalDragGestures(
                                                        onDragStart = { dragOffset = 0f },
                                                        onDragEnd = {
                                                            // Snap open if dragged past threshold,
                                                            // otherwise close
                                                            if (dragOffset > 120) {
                                                                isPanelExpanded = true
                                                            } else {
                                                                isPanelExpanded = false
                                                            }
                                                            dragOffset = 0f
                                                        },
                                                        onDragCancel = { dragOffset = 0f },
                                                        onVerticalDrag = { change, dragAmount ->
                                                            dragOffset += dragAmount
                                                            // If dragging down and at threshold,
                                                            // expand panel dynamically
                                                            if (dragOffset > 0) {
                                                                // Allow panel to follow finger
                                                            }
                                                            change.consume()
                                                        }
                                                )
                                            }
                                        },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(items = uiState.document.blocks, key = { _, block -> block.id }) {
                            index,
                            block ->
                        BlockCard(
                                block = block,
                                isSelected = block.id == uiState.selectedBlockId,
                                isSelectionMode = isSelectionMode,
                                isChecked = selectedBlockIds.contains(block.id),
                                onSelect = { viewModel.onEvent(StackEvent.SelectBlock(block.id)) },
                                onCheckChange = { checked ->
                                    selectedBlockIds =
                                            if (checked) {
                                                selectedBlockIds + block.id
                                            } else {
                                                selectedBlockIds - block.id
                                            }
                                },
                                onUpdate = { updated ->
                                    viewModel.onEvent(StackEvent.UpdateBlock(updated))
                                },
                                onDelete = { viewModel.onEvent(StackEvent.RemoveBlock(block.id)) },
                                onOpenGraph = { graphBlock -> selectedBlockForGraph = graphBlock },
                                onMoveUp = {
                                    if (index > 0) {
                                        viewModel.onEvent(StackEvent.MoveBlock(block.id, index - 1))
                                    }
                                },
                                onMoveDown = {
                                    if (index < uiState.document.blocks.size - 1) {
                                        viewModel.onEvent(StackEvent.MoveBlock(block.id, index + 1))
                                    }
                                },
                                onPickImage = { _ ->
                                    imagePickerLauncher.launch(arrayOf("image/*"))
                                },
                                onProcess =
                                        if (block is Block.ProcessBlock) {
                                            {
                                                viewModel.onEvent(
                                                        StackEvent.ProcessBlockWithAI(block.id)
                                                )
                                            }
                                        } else null
                        )
                    }

                    // Empty state
                    if (uiState.document.blocks.isEmpty()) {
                        item {
                            EmptyStateCard(onAddBlock = { type -> addNewBlock(viewModel, type) })
                        }
                    }
                }

                // Audio Recording Dialog
                if (showRecordingDialog) {
                    AudioRecordingDialog(
                            onDismiss = { showRecordingDialog = false },
                            onRecordingComplete = { file ->
                                showRecordingDialog = false
                                if (file != null) {
                                    val block =
                                            Block.ProcessBlock(
                                                    id = viewModel.newBlockId(),
                                                    mediaType = MediaType.AUDIO,
                                                    sourceUri = file.toURI().toString(),
                                                    status = ProcessStatus.PENDING
                                            )
                                    viewModel.onEvent(StackEvent.AddBlock(block))
                                }
                            }
                    )
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Minimal floating toolbar (fades when not needed)
        // ──────────────────────────────────────────────────────────────

        Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).alpha(toolbarAlpha),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
        ) {
            Row(
                    modifier =
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                // Close notebook - goes back to notebooks list
                ToolbarButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Close notebook",
                        onClick = onNavigateToHome
                )

                // Model status indicator
                com.penpal.core.ui.ModelStatusIndicator(
                        isReady = isModelReady,
                        isLoading = isModelLoading,
                        isUnloading = isModelUnloading,
                        modelStatus = modelStatus,
                        onToggleModel = onToggleModel
                )

                // Selection mode toggle
                ToolbarButton(
                        icon =
                                if (isSelectionMode) Icons.Default.CheckBox
                                else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription =
                                if (isSelectionMode) "Exit selection" else "Select blocks",
                        onClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) {
                                selectedBlockIds = emptySet()
                            }
                        }
                )

                VerticalDivider(
                        modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                )

                // Process blocks - Image, Audio, Video, Text (opens file pickers)
                ToolbarButton(
                        icon = Icons.Default.Image,
                        contentDescription = "Add Images",
                        onClick = {
                            val permissions =
                                    arrayOf(
                                            android.Manifest.permission.READ_MEDIA_IMAGES,
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                                    )
                            permissionLauncher.launch(permissions)
                            imagePickerLauncher.launch(arrayOf("image/*"))
                        }
                )

                // Audio button with submenu (record vs pick from files)
                Box {
                    ToolbarButton(
                            icon = Icons.Default.Mic,
                            contentDescription = "Add Audio",
                            onClick = { showAudioMenu = true }
                    )
                    DropdownMenu(
                            expanded = showAudioMenu,
                            onDismissRequest = { showAudioMenu = false }
                    ) {
                        DropdownMenuItem(
                                text = { Text("Record Audio") },
                                leadingIcon = {
                                    Icon(Icons.Default.Mic, contentDescription = null)
                                },
                                onClick = {
                                    showAudioMenu = false
                                    audioPermissionLauncher.launch(
                                            android.Manifest.permission.RECORD_AUDIO
                                    )
                                }
                        )
                        DropdownMenuItem(
                                text = { Text("Pick from Files") },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                },
                                onClick = {
                                    showAudioMenu = false
                                    val permissions =
                                            arrayOf(
                                                    android.Manifest.permission.READ_MEDIA_AUDIO,
                                                    android.Manifest.permission.RECORD_AUDIO
                                            )
                                    permissionLauncher.launch(permissions)
                                    audioPickerLauncher.launch(arrayOf("audio/*"))
                                }
                        )
                    }
                }

                ToolbarButton(
                        icon = Icons.Default.Videocam,
                        contentDescription = "Add Video",
                        onClick = {
                            val permissions =
                                    arrayOf(
                                            android.Manifest.permission.READ_MEDIA_VIDEO,
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                                    )
                            permissionLauncher.launch(permissions)
                            videoPickerLauncher.launch(arrayOf("video/*"))
                        }
                )

                ToolbarButton(
                        icon = Icons.Default.TextFields,
                        contentDescription = "Add Text",
                        onClick = {
                            val permissions =
                                    arrayOf(
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                            android.Manifest.permission.READ_MEDIA_IMAGES
                                    )
                            permissionLauncher.launch(permissions)
                            textPickerLauncher.launch(
                                    arrayOf(
                                            "text/*",
                                            "application/pdf",
                                            "text/plain",
                                            "application/json"
                                    )
                            )
                        }
                )
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Selection action bar - appears when blocks are selected
        // ──────────────────────────────────────────────────────────────

        if (selectedBlockIds.isNotEmpty()) {
            Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
            ) {
                Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                            text = "${selectedBlockIds.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    // Process all selected process blocks
                    IconButton(
                            onClick = {
                                selectedBlockIds.forEach { blockId ->
                                    val block = uiState.document.blocks.find { it.id == blockId }
                                    if (block is Block.ProcessBlock) {
                                        viewModel.onEvent(StackEvent.ProcessBlockWithAI(blockId))
                                    }
                                }
                                selectedBlockIds = emptySet()
                                isSelectionMode = false
                            }
                    ) {
                        Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Process selected",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    // Delete all selected blocks
                    IconButton(
                            onClick = {
                                selectedBlockIds.forEach { blockId ->
                                    viewModel.onEvent(StackEvent.RemoveBlock(blockId))
                                }
                                selectedBlockIds = emptySet()
                                isSelectionMode = false
                            }
                    ) {
                        Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error
                        )
                    }

                    // Clear selection
                    IconButton(
                            onClick = {
                                selectedBlockIds = emptySet()
                                isSelectionMode = false
                            }
                    ) {
                        Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear selection",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Quick actions when FAB is tapped (optional expanded menu)
        // ──────────────────────────────────────────────────────────────

        AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp)
        ) {
            Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quick action chips - only visible when menu is open (appears above FABs)
                AnimatedVisibility(
                        visible = showAddMenu,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it }
                ) {
                    Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionChip(
                                icon = Icons.Default.TextFields,
                                label = "Note",
                                onClick = {
                                    addNewBlock(viewModel, "text")
                                    showAddMenu = false
                                }
                        )
                        QuickActionChip(
                                icon = Icons.Default.Draw,
                                label = "Draw",
                                onClick = {
                                    addNewBlock(viewModel, "drawing")
                                    showAddMenu = false
                                }
                        )
                        QuickActionChip(
                                icon = Icons.Default.Functions,
                                label = "Math",
                                onClick = {
                                    addNewBlock(viewModel, "latex")
                                    showAddMenu = false
                                }
                        )
                        QuickActionChip(
                                icon = Icons.Default.AccountTree,
                                label = "Graph",
                                onClick = {
                                    addNewBlock(viewModel, "graph")
                                    showAddMenu = false
                                }
                        )
                        QuickActionChip(
                                icon = Icons.Default.PictureAsPdf,
                                label = "PDF",
                                onClick = {
                                    addNewBlock(viewModel, "pdf")
                                    showAddMenu = false
                                }
                        )
                        QuickActionChip(
                                icon = Icons.Default.Mic,
                                label = "Audio",
                                onClick = {
                                    addNewBlock(viewModel, "audio")
                                    showAddMenu = false
                                }
                        )
                        QuickActionChip(
                                icon = Icons.Default.Link,
                                label = "URL",
                                onClick = {
                                    addNewBlock(viewModel, "url")
                                    showAddMenu = false
                                }
                        )
                    }
                }

                // Column with Chat and Add FABs - always visible, moves up when menu opens
                Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Chat FAB
                    FloatingActionButton(
                            onClick = { onNavigateToChat?.invoke() },
                            containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                    }

                    // Add FAB - toggles menu
                    FloatingActionButton(
                            onClick = { showAddMenu = !showAddMenu },
                            containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                                imageVector = if (showAddMenu) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = if (showAddMenu) "Close menu" else "Add block"
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Audio Recording Dialog
// ─────────────────────────────────────────────────────────────────

private enum class RecState {
    IDLE,
    RECORDING,
    DONE
}

@Composable
private fun AudioRecordingDialog(onDismiss: () -> Unit, onRecordingComplete: (File?) -> Unit) {
    val context = LocalContext.current
    val recorder = remember { AudioRecorder(context) }
    val analyzer = remember { AudioAnalyzer() }
    var state by remember { mutableStateOf(RecState.IDLE) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var spectrum by remember { mutableStateOf(FloatArray(AudioAnalyzer.NUM_BINS)) }

    // Duration timer
    LaunchedEffect(state) {
        if (state == RecState.RECORDING) {
            elapsedSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (state != RecState.RECORDING) break
                elapsedSeconds++
            }
        }
    }

    // Wire up analyzer to recorder
    DisposableEffect(recorder) {
        recorder.onPcmBuffer = { buf, len -> analyzer.feedPcmData(buf, len) }
        recorder.onRecordingStopped = { file ->
            analyzer.stop()
            recordedFile = file
            state = RecState.DONE
        }
        recorder.onError = { msg ->
            analyzer.stop()
            state = RecState.IDLE
        }
        onDispose {
            recorder.cancelRecording()
            analyzer.stop()
        }
    }

    // Spectrum update callback
    LaunchedEffect(analyzer) { analyzer.onSpectrumUpdate = { bins -> spectrum = bins } }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US) }

    AlertDialog(
            onDismissRequest = {
                if (state == RecState.RECORDING) recorder.cancelRecording()
                onDismiss()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                            when (state) {
                                RecState.IDLE -> "Record Audio"
                                RecState.RECORDING -> "Recording..."
                                RecState.DONE -> "Recording Complete"
                            }
                    )
                }
            },
            text = {
                Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Timer
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            style = MaterialTheme.typography.headlineLarge,
                            color =
                                    if (state == RecState.RECORDING) Color(0xFFE53935)
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Spectrum analyzer canvas
                    Card(
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(
                                                            alpha = 0.5f
                                                    )
                                    )
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                            val barWidth = size.width / spectrum.size
                            spectrum.forEachIndexed { index, magnitude ->
                                val barHeight = magnitude * size.height
                                drawRect(
                                        color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                                        topLeft =
                                                Offset(
                                                        x = index * barWidth + 1f,
                                                        y = size.height - barHeight
                                                ),
                                        size =
                                                Size(
                                                        width = barWidth - 2f,
                                                        height = barHeight.coerceAtLeast(1f)
                                                )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Status text
                    Text(
                            text =
                                    when (state) {
                                        RecState.IDLE -> "Tap the button below to start recording"
                                        RecState.RECORDING -> "Recording to internal storage..."
                                        RecState.DONE -> {
                                            val name = recordedFile?.name ?: "recording.wav"
                                            val dur =
                                                    if (recordedFile != null) "${elapsedSeconds}s"
                                                    else ""
                                            "Saved: $name ($dur)"
                                        }
                                    },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state == RecState.DONE && recordedFile != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                                text =
                                        "File saved to app's internal recordings folder.\nYou can copy it later using a file manager.",
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.7f
                                        )
                        )
                    }
                }
            },
            confirmButton = {
                when (state) {
                    RecState.IDLE -> {
                        Button(
                                onClick = {
                                    val fileName = "recording_${dateFormat.format(Date())}"
                                    if (recorder.startRecording(fileName)) {
                                        analyzer.startAnalyzing()
                                        state = RecState.RECORDING
                                    }
                                }
                        ) {
                            Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Start Recording")
                        }
                    }
                    RecState.RECORDING -> {
                        Button(
                                onClick = { recorder.stopRecording() },
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFE53935)
                                        )
                        ) {
                            Icon(
                                    Icons.Default.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Stop Recording")
                        }
                    }
                    RecState.DONE -> {
                        Button(onClick = { onRecordingComplete(recordedFile) }) {
                            Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Use Recording")
                        }
                    }
                }
            },
            dismissButton = {
                when (state) {
                    RecState.IDLE -> {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                    RecState.RECORDING -> {
                        TextButton(
                                onClick = {
                                    recorder.cancelRecording()
                                    state = RecState.IDLE
                                }
                        ) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                    }
                    RecState.DONE -> {
                        TextButton(
                                onClick = {
                                    recordedFile?.delete()
                                    recordedFile = null
                                    state = RecState.IDLE
                                }
                        ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
    )
}

// ─────────────────────────────────────────────────────────────────
// Block Card - renders individual blocks with edit/delete actions
// ─────────────────────────────────────────────────────────────────

@Composable
fun BlockCard(
        block: Block,
        isSelected: Boolean,
        isSelectionMode: Boolean = false,
        isChecked: Boolean = false,
        onSelect: () -> Unit,
        onCheckChange: ((Boolean) -> Unit)? = null,
        onUpdate: (Block) -> Unit,
        onDelete: () -> Unit,
        onOpenGraph: (Block.GraphBlock) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onPickImage: (String) -> Unit,
        onProcess: (() -> Unit)? = null,
        modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
            modifier =
                    modifier.fillMaxWidth().clickable {
                        if (isSelectionMode && onCheckChange != null) {
                            onCheckChange(!isChecked)
                        } else {
                            onSelect()
                        }
                    },
            shape = RoundedCornerShape(12.dp),
            colors =
                    CardDefaults.cardColors(
                            containerColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(
                                                alpha = 0.3f
                                        )
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                    ),
            border =
                    if (isSelected) {
                        CardDefaults.outlinedCardBorder()
                                .copy(
                                        brush =
                                                androidx.compose.ui.graphics.SolidColor(
                                                        MaterialTheme.colorScheme.primary.copy(
                                                                alpha = 0.5f
                                                        )
                                                )
                                )
                    } else null
    ) {
        Column {
            // Checkbox for selection mode
            if (isSelectionMode) {
                Row(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(
                                                        alpha = 0.3f
                                                )
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                            checked = isChecked,
                            onCheckedChange = onCheckChange,
                            modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                            text = "Select",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when (block) {
                is Block.TextBlock -> {
                    TextBlockContent(block = block, isSelected = isSelected, onUpdate = onUpdate)
                }
                is Block.ImageBlock -> {
                    ImageBlockContent(
                            block = block,
                            isSelected = isSelected,
                            onUpdate = onUpdate,
                            onPickImage = onPickImage
                    )
                }
                is Block.DrawingBlock -> {
                    DrawingBlockContent(block = block, isSelected = isSelected, onUpdate = onUpdate)
                }
                is Block.LatexBlock -> {
                    LatexBlockContent(block = block, isSelected = isSelected, onUpdate = onUpdate)
                }
                is Block.GraphBlock -> {
                    GraphBlockContent(
                            block = block,
                            isSelected = isSelected,
                            onClick = { onOpenGraph(block) }
                    )
                }
                is Block.EmbedBlock -> {
                    EmbedBlockContent(block = block, isSelected = isSelected, onUpdate = onUpdate)
                }
                is Block.ProcessBlock -> {
                    ProcessBlockContent(
                            block = block,
                            isSelected = isSelected,
                            isExpanded = isExpanded,
                            onUpdate = onUpdate,
                            onProcess = onProcess
                    )
                }
            }

            // Block actions (visible when expanded)
            if (isExpanded) {
                Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row {
                        IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ArrowUpward, "Move up", Modifier.size(18.dp))
                        }
                        IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ArrowDownward, "Move down", Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                                Icons.Default.Delete,
                                "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Expand/collapse indicator with status color
            val statusColor =
                    when (block) {
                        is Block.ProcessBlock ->
                                when (block.status) {
                                    ProcessStatus.ERROR -> MaterialTheme.colorScheme.error
                                    ProcessStatus.RUNNING -> Color(0xFFFFB300) // Amber/Yellow
                                    ProcessStatus.DONE ->
                                            if (block.extractedText.isNotEmpty()) Color(0xFF4CAF50)
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable { isExpanded = !isExpanded }
                                    .padding(4.dp),
                    horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                        imageVector =
                                if (isExpanded) Icons.Default.KeyboardArrowUp
                                else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(16.dp),
                        tint = statusColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Block Content Composables
// ─────────────────────────────────────────────────────────────────

@Composable
fun TextBlockContent(block: Block.TextBlock, isSelected: Boolean, onUpdate: (Block) -> Unit) {
    var text by remember(block.content) { mutableStateOf(block.content) }

    TextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                onUpdate(block.copy(content = newText))
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            placeholder = {
                Text(
                        "Start typing your thoughts...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            colors =
                    TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                    ),
            singleLine = false
    )
}

@Composable
fun ImageBlockContent(
        block: Block.ImageBlock,
        isSelected: Boolean,
        onUpdate: (Block) -> Unit,
        onPickImage: (String) -> Unit
) {
    Column(modifier = Modifier.padding(12.dp)) {
        if (block.uri != null) {
            // Image loaded - show it with tap to change
            Box(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onPickImage(block.id) },
                    contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                        model = block.uri,
                        contentDescription = "Selected image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                )
            }
        } else {
            // Placeholder for adding image
            Box(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onPickImage(block.id) },
                    contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                            "Tap to add image",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Caption
        OutlinedTextField(
                value = block.caption,
                onValueChange = { onUpdate(block.copy(caption = it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("Add a caption...") },
                singleLine = true,
                colors =
                        OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                        )
        )
    }
}

@Composable
fun DrawingBlockContent(block: Block.DrawingBlock, isSelected: Boolean, onUpdate: (Block) -> Unit) {
    var isDrawingMode by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isDrawingMode) {
            // Full-screen drawing mode
            DrawingCanvas(
                    pathData = block.pathData,
                    onPathDataChanged = { newPathData ->
                        onUpdate(block.copy(pathData = newPathData))
                    },
                    modifier = Modifier.fillMaxWidth().height(400.dp)
            )

            // Done button
            Button(
                    onClick = { isDrawingMode = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Done")
            }
        } else {
            // Preview mode
            Box(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { isDrawingMode = true },
                    contentAlignment = Alignment.Center
            ) {
                if (block.pathData.isNotEmpty()) {
                    // Show mini preview of drawing
                    DrawingCanvas(
                            pathData = block.pathData,
                            onPathDataChanged = {},
                            modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                                Icons.Default.Draw,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                                "Tap to draw",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LatexBlockContent(block: Block.LatexBlock, isSelected: Boolean, onUpdate: (Block) -> Unit) {
    var text by remember(block.expression) { mutableStateOf(block.expression) }

    Column(modifier = Modifier.padding(12.dp)) {
        if (block.expression.isNotEmpty()) {
            LatexView(
                    expression = block.expression,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }

        OutlinedTextField(
                value = text,
                onValueChange = { newExpr ->
                    text = newExpr
                    onUpdate(block.copy(expression = newExpr))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Enter LaTeX expression...") },
                singleLine = true,
                colors =
                        OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                        ),
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        Icon(
                                Icons.Default.Calculate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
        )
    }
}

@Composable
fun GraphBlockContent(block: Block.GraphBlock, isSelected: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.padding(12.dp)) {
        // Mini preview of graph
        Box(
                modifier =
                        Modifier.fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                        text = "${block.nodes.size} nodes · ${block.edges.size} edges",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                        "Tap to open graph",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun EmbedBlockContent(block: Block.EmbedBlock, isSelected: Boolean, onUpdate: (Block) -> Unit) {
    Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        val icon =
                when (block.type) {
                    EmbedType.LINK -> Icons.Default.Link
                    EmbedType.AUDIO -> Icons.Default.AudioFile
                    EmbedType.VIDEO -> Icons.Default.VideoFile
                    EmbedType.FILE -> Icons.Default.InsertDriveFile
                }

        Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp).padding(end = 12.dp),
                tint = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
                value = block.preview,
                onValueChange = { onUpdate(block.copy(preview = it)) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Paste URL or embed...") },
                singleLine = true,
                colors =
                        OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                        )
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Toolbar Components
// ─────────────────────────────────────────────────────────────────

@Composable
fun ToolbarButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun QuickActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(
            onClick = onClick,
            colors =
                    ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ─────────────────────────────────────────────────────────────────
// Empty State
// ─────────────────────────────────────────────────────────────────

@Composable
fun EmptyStateCard(onAddBlock: (String) -> Unit) {
    Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors =
                    CardDefaults.cardColors(
                            containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
    ) {
        Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                    "Start your thinking",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                    "Tap the + button to add your first block",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Helper
// ─────────────────────────────────────────────────────────────────

@Composable
fun ProcessBlockContent(
        block: Block.ProcessBlock,
        isSelected: Boolean,
        isExpanded: Boolean,
        onUpdate: (Block) -> Unit,
        onProcess: (() -> Unit)? = null
) {
    var uri by remember(block.sourceUri) { mutableStateOf(block.sourceUri) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        // Media type icon and status row
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Media type icon with colored background
            Box(
                    modifier =
                            Modifier.size(48.dp)
                                    .background(
                                            when (block.mediaType) {
                                                MediaType.IMAGE ->
                                                        MaterialTheme.colorScheme.primaryContainer
                                                MediaType.AUDIO ->
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                MediaType.VIDEO ->
                                                        MaterialTheme.colorScheme.tertiaryContainer
                                                MediaType.TEXT ->
                                                        MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            RoundedCornerShape(8.dp)
                                    ),
                    contentAlignment = Alignment.Center
            ) {
                Icon(
                        imageVector =
                                when (block.mediaType) {
                                    MediaType.IMAGE -> Icons.Default.Image
                                    MediaType.AUDIO -> Icons.Default.Mic
                                    MediaType.VIDEO -> Icons.Default.Videocam
                                    MediaType.TEXT -> Icons.Default.TextFields
                                },
                        contentDescription = block.mediaType.name,
                        tint =
                                when (block.mediaType) {
                                    MediaType.IMAGE -> MaterialTheme.colorScheme.onPrimaryContainer
                                    MediaType.AUDIO ->
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                    MediaType.VIDEO -> MaterialTheme.colorScheme.onTertiaryContainer
                                    MediaType.TEXT -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Status and progress
            Column(modifier = Modifier.weight(1f)) {
                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                            text = block.mediaType.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                    )

                    // Status chip
                    Surface(
                            shape = RoundedCornerShape(12.dp),
                            color =
                                    when (block.status) {
                                        ProcessStatus.DONE ->
                                                MaterialTheme.colorScheme.primaryContainer
                                        ProcessStatus.RUNNING ->
                                                MaterialTheme.colorScheme.primaryContainer.copy(
                                                        alpha = 0.5f
                                                )
                                        ProcessStatus.ERROR ->
                                                MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                    ) {
                        Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                    imageVector =
                                            when (block.status) {
                                                ProcessStatus.PENDING -> Icons.Default.Schedule
                                                ProcessStatus.QUEUED -> Icons.Default.HourglassTop
                                                ProcessStatus.RUNNING -> Icons.Default.Sync
                                                ProcessStatus.DONE -> Icons.Default.CheckCircle
                                                ProcessStatus.ERROR -> Icons.Default.Error
                                            },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint =
                                            when (block.status) {
                                                ProcessStatus.DONE ->
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                ProcessStatus.RUNNING ->
                                                        MaterialTheme.colorScheme.primary
                                                ProcessStatus.ERROR ->
                                                        MaterialTheme.colorScheme.onErrorContainer
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                    text =
                                            when (block.status) {
                                                ProcessStatus.PENDING -> "Ready"
                                                ProcessStatus.QUEUED -> "Queued"
                                                ProcessStatus.RUNNING -> "Processing"
                                                ProcessStatus.DONE -> "Done"
                                                ProcessStatus.ERROR -> "Error"
                                            },
                                    style = MaterialTheme.typography.labelSmall,
                                    color =
                                            when (block.status) {
                                                ProcessStatus.DONE ->
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                ProcessStatus.RUNNING ->
                                                        MaterialTheme.colorScheme.primary
                                                ProcessStatus.ERROR ->
                                                        MaterialTheme.colorScheme.onErrorContainer
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                            )
                        }
                    }
                }

                // Progress bar when running
                if (block.status == ProcessStatus.RUNNING || block.status == ProcessStatus.QUEUED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                            progress = { block.progress / 100f },
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Source URI input
        OutlinedTextField(
                value = uri,
                onValueChange = {
                    uri = it
                    onUpdate(block.copy(sourceUri = it))
                },
                label = { Text("Source URI or path") },
                placeholder = { Text("Enter file path, URL, or content") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                enabled = true
        )

        // Media preview
        if (block.sourceUri.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            when (block.mediaType) {
                MediaType.IMAGE -> {
                    // Image preview
                    Card(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.surfaceVariant
                                    )
                    ) {
                        AsyncImage(
                                model = block.sourceUri,
                                contentDescription = "Image preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                        )
                    }
                }
                MediaType.VIDEO -> {
                    // Video preview
                    Card(
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.surfaceVariant
                                    )
                    ) {
                        Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "Video",
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                        text =
                                                "Video: ${Uri.parse(block.sourceUri).lastPathSegment ?: "file"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                MediaType.AUDIO -> {
                    // Audio preview
                    Card(
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.surfaceVariant
                                    )
                    ) {
                        Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = "Audio",
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                        text =
                                                "Audio: ${Uri.parse(block.sourceUri).lastPathSegment ?: "file"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                MediaType.TEXT -> {
                    // Text preview - try to show content or file name
                    val fileName = Uri.parse(block.sourceUri).lastPathSegment ?: "file"
                    Card(
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.surfaceVariant
                                    )
                    ) {
                        Box(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "Text",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                        text = fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }

        // Process button - always visible when not done
        if (onProcess != null && block.sourceUri.isNotBlank() && block.status != ProcessStatus.DONE
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                    onClick = onProcess,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            ButtonDefaults.buttonColors(
                                    containerColor =
                                            when (block.status) {
                                                ProcessStatus.RUNNING ->
                                                        MaterialTheme.colorScheme.tertiary
                                                ProcessStatus.ERROR ->
                                                        MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.primary
                                            }
                            )
            ) {
                Icon(
                        imageVector =
                                when (block.status) {
                                    ProcessStatus.RUNNING -> Icons.Default.Sync
                                    ProcessStatus.ERROR -> Icons.Default.Refresh
                                    else -> Icons.Default.PlayArrow
                                },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                        when (block.status) {
                            ProcessStatus.PENDING -> "Process with AI"
                            ProcessStatus.QUEUED -> "Queued..."
                            ProcessStatus.RUNNING -> "Processing..."
                            ProcessStatus.DONE -> "Done"
                            ProcessStatus.ERROR -> "Retry Processing"
                        }
                )
            }
        }

        // Show parsed content when done - controlled by block's expand/collapse
        if (block.extractedText.isNotEmpty() && isExpanded) {
            Log.d(
                    "StackScreen",
                    "Displaying AI content: blockId=${block.id}, extractedTextLen=${block.extractedText.length}, textPreview=${block.extractedText.take(50)}"
            )
            Spacer(modifier = Modifier.height(12.dp))

            // AI result section - shown when block is expanded
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor =
                                            MaterialTheme.colorScheme.primaryContainer.copy(
                                                    alpha = 0.3f
                                            )
                            )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = "AI Result",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                                text = "AI Analysis",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                            text = block.extractedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        if (block.status == ProcessStatus.RUNNING) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                    progress = { block.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // Error message
        block.errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ToggleButton(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
            onClick = onClick,
            shape = RoundedCornerShape(6.dp),
            color =
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color =
                        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PromptsPanel(
        systemPrompt: String,
        agentPrompt: String,
        onSystemPromptChange: (String) -> Unit,
        onAgentPromptChange: (String) -> Unit,
        modifier: Modifier = Modifier
) {
    Column(
            modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
                text = "Prompts",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                label = { Text("System Prompt") },
                placeholder = { Text("Define the overall goal...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                colors =
                        OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
        )

        OutlinedTextField(
                value = agentPrompt,
                onValueChange = onAgentPromptChange,
                label = { Text("Agent Prompt") },
                placeholder = { Text("Guide the thinking process...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                colors =
                        OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
        )
    }
}

private fun addNewBlock(viewModel: StackEditorViewModel, type: String) {
    val blockId = viewModel.newBlockId()
    val block =
            when (type) {
                "text" -> Block.TextBlock(id = blockId)
                "image" -> Block.ImageBlock(id = blockId)
                "drawing" -> Block.DrawingBlock(id = blockId)
                "latex" -> Block.LatexBlock(id = blockId)
                "graph" ->
                        Block.GraphBlock(
                                id = blockId,
                                graphId = java.util.UUID.randomUUID().toString()
                        )
                "embed" ->
                        Block.EmbedBlock(
                                id = blockId,
                                sourceId = java.util.UUID.randomUUID().toString()
                        )
                "process-image" -> Block.ProcessBlock(id = blockId, mediaType = MediaType.IMAGE)
                "process-audio" -> Block.ProcessBlock(id = blockId, mediaType = MediaType.AUDIO)
                "process-video" -> Block.ProcessBlock(id = blockId, mediaType = MediaType.VIDEO)
                "process-text" -> Block.ProcessBlock(id = blockId, mediaType = MediaType.TEXT)
                else -> Block.TextBlock(id = blockId)
            }
    viewModel.onEvent(StackEvent.AddBlock(block))
}
