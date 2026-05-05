package com.penpal.feature.notebooks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

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
fun NotebookScreen(
    viewModel: NotebookEditorViewModel,
    onNavigateBack: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
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

    val toolbarAlpha by animateFloatAsState(
        targetValue = if (showToolbar) 1f else 0.3f,
        animationSpec = tween(durationMillis = 300),
        label = "toolbarAlpha"
    )

    // Image picker launcher
    var imagePickerBlockId by remember { mutableStateOf<String?>(null) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            imagePickerBlockId?.let { blockId ->
                viewModel.onEvent(NotebookEvent.SetImageUri(blockId, it))
            }
        }
        imagePickerBlockId = null
    }

    // Auto-save when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            if (uiState.isDirty) {
                viewModel.saveDocument()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
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

            // Close graph button - top END
            FloatingActionButton(
                onClick = {
                    selectedBlockForGraph = null
                    viewModel.cancelEdgeCreation()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
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
                    viewModel.addNodeToGraph(
                        selectedBlockForGraph!!.id,
                        "New Node",
                        0f,
                        0f
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add node")
            }
        } else {
            // Blocks list mode
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp, bottom = 100.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    items = uiState.document.blocks,
                    key = { _, block -> block.id }
                ) { index, block ->
                    BlockCard(
                        block = block,
                        isSelected = block.id == uiState.selectedBlockId,
                        onSelect = { viewModel.onEvent(NotebookEvent.SelectBlock(block.id)) },
                        onUpdate = { updated -> viewModel.onEvent(NotebookEvent.UpdateBlock(updated)) },
                        onDelete = { viewModel.onEvent(NotebookEvent.RemoveBlock(block.id)) },
                        onOpenGraph = { graphBlock ->
                            selectedBlockForGraph = graphBlock
                        },
                        onMoveUp = {
                            if (index > 0) {
                                viewModel.onEvent(NotebookEvent.MoveBlock(block.id, index - 1))
                            }
                        },
                        onMoveDown = {
                            if (index < uiState.document.blocks.size - 1) {
                                viewModel.onEvent(NotebookEvent.MoveBlock(block.id, index + 1))
                            }
                        },
                        onPickImage = { blockId ->
                            imagePickerBlockId = blockId
                            imagePickerLauncher.launch("image/*")
                        }
                    )
                }

                // Empty state
                if (uiState.document.blocks.isEmpty()) {
                    item {
                        EmptyStateCard(
                            onAddBlock = { type ->
                                addNewBlock(viewModel, type)
                            }
                        )
                    }
                }
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Minimal floating toolbar (fades when not needed)
        // ──────────────────────────────────────────────────────────────

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .alpha(toolbarAlpha),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back/home
                ToolbarButton(
                    icon = Icons.Default.Home,
                    contentDescription = "Home",
                    onClick = onNavigateToHome
                )

                VerticalDivider(
                    modifier = Modifier
                        .height(24.dp)
                        .padding(horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // Text block
                ToolbarButton(
                    icon = Icons.Default.TextFields,
                    contentDescription = "Add text",
                    onClick = { addNewBlock(viewModel, "text") }
                )

                // Image block
                ToolbarButton(
                    icon = Icons.Default.Image,
                    contentDescription = "Add image",
                    onClick = { addNewBlock(viewModel, "image") }
                )

                // Math/LaTeX block
                ToolbarButton(
                    icon = Icons.Default.Functions,
                    contentDescription = "Add math",
                    onClick = { addNewBlock(viewModel, "latex") }
                )

                // Graph block
                ToolbarButton(
                    icon = Icons.Default.AccountTree,
                    contentDescription = "Add graph",
                    onClick = { addNewBlock(viewModel, "graph") }
                )

                // Drawing block
                ToolbarButton(
                    icon = Icons.Default.Draw,
                    contentDescription = "Add drawing",
                    onClick = { addNewBlock(viewModel, "drawing") }
                )
            }
        }

        // ──────────────────────────────────────────────────────────────
        // Floating action button - the main "think" action
        // ──────────────────────────────────────────────────────────────

        FloatingActionButton(
            onClick = { showAddMenu = !showAddMenu },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = if (showAddMenu) Icons.Default.Close else Icons.Default.Add,
                contentDescription = if (showAddMenu) "Close menu" else "Add block"
            )
        }

        // ──────────────────────────────────────────────────────────────
        // Quick actions when FAB is tapped (optional expanded menu)
        // ──────────────────────────────────────────────────────────────

        AnimatedVisibility(
            visible = showAddMenu,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 100.dp)
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
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Block Card - renders individual blocks with edit/delete actions
// ─────────────────────────────────────────────────────────────────

@Composable
fun BlockCard(
    block: Block,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (Block) -> Unit,
    onDelete: () -> Unit,
    onOpenGraph: (Block.GraphBlock) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPickImage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )
        } else null
    ) {
        Column {
            when (block) {
                is Block.TextBlock -> {
                    TextBlockContent(
                        block = block,
                        isSelected = isSelected,
                        onUpdate = onUpdate
                    )
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
                    DrawingBlockContent(
                        block = block,
                        isSelected = isSelected,
                        onUpdate = onUpdate
                    )
                }
                is Block.LatexBlock -> {
                    LatexBlockContent(
                        block = block,
                        isSelected = isSelected,
                        onUpdate = onUpdate
                    )
                }
                is Block.GraphBlock -> {
                    GraphBlockContent(
                        block = block,
                        isSelected = isSelected,
                        onClick = { onOpenGraph(block) }
                    )
                }
                is Block.EmbedBlock -> {
                    EmbedBlockContent(
                        block = block,
                        isSelected = isSelected,
                        onUpdate = onUpdate
                    )
                }
            }

            // Block actions (visible when expanded)
            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
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

            // Expand/collapse indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Block Content Composables
// ─────────────────────────────────────────────────────────────────

@Composable
fun TextBlockContent(
    block: Block.TextBlock,
    isSelected: Boolean,
    onUpdate: (Block) -> Unit
) {
    var text by remember(block.content) { mutableStateOf(block.content) }

    TextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            onUpdate(block.copy(content = newText))
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 24.sp
        ),
        placeholder = {
            Text(
                "Start typing your thoughts...",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        },
        colors = TextFieldDefaults.colors(
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
                modifier = Modifier
                    .fillMaxWidth()
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
                modifier = Modifier
                    .fillMaxWidth()
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            placeholder = { Text("Add a caption...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun DrawingBlockContent(
    block: Block.DrawingBlock,
    isSelected: Boolean,
    onUpdate: (Block) -> Unit
) {
    var isDrawingMode by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (isDrawingMode) {
            // Full-screen drawing mode
            DrawingCanvas(
                pathData = block.pathData,
                onPathDataChanged = { newPathData ->
                    onUpdate(block.copy(pathData = newPathData))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            )

            // Done button
            Button(
                onClick = { isDrawingMode = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Done")
            }
        } else {
            // Preview mode
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
fun LatexBlockContent(
    block: Block.LatexBlock,
    isSelected: Boolean,
    onUpdate: (Block) -> Unit
) {
    var text by remember(block.expression) { mutableStateOf(block.expression) }

    Column(modifier = Modifier.padding(12.dp)) {
        if (block.expression.isNotEmpty()) {
            LatexView(
                expression = block.expression,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
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
            colors = OutlinedTextFieldDefaults.colors(
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
fun GraphBlockContent(
    block: Block.GraphBlock,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.padding(12.dp)) {
        // Mini preview of graph
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
fun EmbedBlockContent(
    block: Block.EmbedBlock,
    isSelected: Boolean,
    onUpdate: (Block) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (block.type) {
            EmbedType.LINK -> Icons.Default.Link
            EmbedType.AUDIO -> Icons.Default.AudioFile
            EmbedType.VIDEO -> Icons.Default.VideoFile
            EmbedType.FILE -> Icons.Default.InsertDriveFile
        }

        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .size(32.dp)
                .padding(end = 12.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
            value = block.preview,
            onValueChange = { onUpdate(block.copy(preview = it)) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Paste URL or embed...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
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
fun ToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ─────────────────────────────────────────────────────────────────
// Empty State
// ─────────────────────────────────────────────────────────────────

@Composable
fun EmptyStateCard(
    onAddBlock: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
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

private fun addNewBlock(viewModel: NotebookEditorViewModel, type: String) {
    val blockId = viewModel.newBlockId()
    val block = when (type) {
        "text" -> Block.TextBlock(id = blockId)
        "image" -> Block.ImageBlock(id = blockId)
        "drawing" -> Block.DrawingBlock(id = blockId)
        "latex" -> Block.LatexBlock(id = blockId)
        "graph" -> Block.GraphBlock(
            id = blockId,
            graphId = java.util.UUID.randomUUID().toString()
        )
        "embed" -> Block.EmbedBlock(
            id = blockId,
            sourceId = java.util.UUID.randomUUID().toString()
        )
        else -> Block.TextBlock(id = blockId)
    }
    viewModel.onEvent(NotebookEvent.AddBlock(block))
}
