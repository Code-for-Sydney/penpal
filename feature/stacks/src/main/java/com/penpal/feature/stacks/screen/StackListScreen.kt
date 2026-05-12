package com.penpal.feature.stacks.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.penpal.core.ai.inference.model.ModelStatus
import com.penpal.core.ui.component.StatusIndicator
import androidx.compose.ui.graphics.Color as ComposeColor

import com.penpal.feature.stacks.viewmodel.StackListViewModel
import com.penpal.feature.stacks.viewmodel.StackSummary
import java.text.SimpleDateFormat
import java.util.*

private data class ListStatusColors(
    val dotColor: ComposeColor,
    val text: String,
    val containerColor: ComposeColor,
    val textColor: ComposeColor,
    val clickable: Boolean
)

/**
 * Stack List Screen - displays all saved notebooks
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackListScreen(
    viewModel: StackListViewModel,
    onStackSelected: (String) -> Unit,
    onCreateNew: () -> Unit,
    onChatWithStack: ((String) -> Unit)? = null,
    onNavigateToChat: (() -> Unit)? = null,
    isModelReady: Boolean = false,
    isModelLoading: Boolean = false,
    isModelUnloading: Boolean = false,
    modelStatus: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    onToggleModel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Think") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    val statusColors = when {
                        modelStatus == ModelStatus.READY || isModelReady ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFF4CAF50),
                                text = "ON",
                                containerColor = ComposeColor(0xFF4CAF50).copy(alpha = 0.2f),
                                textColor = ComposeColor(0xFF2E7D32),
                                clickable = true
                            )
                        modelStatus == ModelStatus.LOADING || isModelLoading ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFFFFC107),
                                text = "Loading...",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                clickable = false
                            )
                        isModelUnloading ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFFFFC107),
                                text = "Unloading...",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                clickable = false
                            )
                        modelStatus == ModelStatus.DOWNLOADING ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFFFFC107),
                                text = "Downloading...",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                clickable = false
                            )
                        modelStatus == ModelStatus.ERROR ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFFF44336),
                                text = "ERR",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                clickable = false
                            )
                        modelStatus == ModelStatus.DOWNLOADED ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFF9E9E9E),
                                text = "DL'd",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                clickable = true
                            )
                        else ->
                            ListStatusColors(
                                dotColor = ComposeColor(0xFF9E9E9E),
                                text = "OFF",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                clickable = false
                            )
                    }
                    StatusIndicator(
                        text = statusColors.text,
                        dotColor = statusColors.dotColor,
                        containerColor = statusColors.containerColor,
                        textColor = statusColors.textColor,
                        isClickable = statusColors.clickable && onToggleModel != null,
                        onClick = onToggleModel
                    )
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { onNavigateToChat?.invoke() },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                }
                FloatingActionButton(
                    onClick = onCreateNew,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create new stack")
                }
            }
        },
        modifier = modifier
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.stacks.isEmpty()) {
            // Empty state
            EmptyStacksState(
                onCreateNew = onCreateNew,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = uiState.stacks,
                    key = { it.id }
                ) { stack ->
                    StackCard(
                        stack = stack,
                        onClick = { onStackSelected(stack.id) },
                        onDelete = { viewModel.showDeleteConfirmation(stack) },
                        onChat = onChatWithStack?.let { { it(stack.id) } }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog && uiState.stackToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete Stack?") },
            text = {
                Text("Are you sure you want to delete \"${uiState.stackToDelete!!.title}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteStack() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error snackbar
    uiState.error?.let { error ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { /* TODO: clear error */ }) {
                    Text("Dismiss")
                }
            }
        ) {
            Text(error)
        }
    }
}

@Composable
private fun StackCard(
    stack: StackSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onChat: (() -> Unit)? = null
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stack.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${stack.blockCount} blocks • ${dateFormat.format(Date(stack.updatedAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onChat != null) {
                    IconButton(
                        onClick = onChat,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Chat",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (stack.preview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stack.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EmptyStacksState(
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your Think Space",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Start gathering your thoughts.\nTap + to create your first stack.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCreateNew) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create Stack")
        }
    }
}