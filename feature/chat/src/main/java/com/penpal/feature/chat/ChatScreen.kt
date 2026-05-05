package com.penpal.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.penpal.core.ui.PenpalTheme

/**
 * Main Chat screen composable with message list, input field, and context display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onEvent: (ChatEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var showContext by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar with context toggle
        ChatTopBar(
            hasContext = uiState.retrievedContext.isNotEmpty(),
            showContext = showContext,
            onToggleContext = { showContext = !showContext },
            onClearChat = { onEvent(ChatEvent.ClearChat) }
        )

        // Context panel (collapsible)
        if (showContext && uiState.retrievedContext.isNotEmpty()) {
            ContextPanel(
                chunks = uiState.retrievedContext,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Loading indicator
            if (uiState.isLoading) {
                item {
                    LoadingIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        }

        // Error snackbar
        uiState.error?.let { error ->
            ErrorBanner(
                message = error,
                onDismiss = { onEvent(ChatEvent.DismissError) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }

        // Input field
        ChatInputField(
            text = uiState.inputText,
            onTextChange = { onEvent(ChatEvent.UpdateInput(it)) },
            onSend = { onEvent(ChatEvent.SendMessage) },
            isLoading = uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    hasContext: Boolean,
    showContext: Boolean,
    onToggleContext: () -> Unit,
    onClearChat: () -> Unit,
) {
    TopAppBar(
        title = { Text("Chat") },
        actions = {
            if (hasContext) {
                IconButton(onClick = onToggleContext) {
                    Icon(
                        imageVector = if (showContext) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showContext) "Hide context" else "Show context"
                    )
                }
            }
            IconButton(onClick = onClearChat) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear chat"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun ContextPanel(
    chunks: List<com.penpal.core.data.ChunkEntity>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Retrieved ${chunks.size} context chunks",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            chunks.take(3).forEach { chunk ->
                Text(
                    text = chunk.text.take(100) + if (chunk.text.length > 100) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            if (chunks.size > 3) {
                Text(
                    text = "+${chunks.size - 3} more chunks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = modifier,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (message.sources.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${message.sources.size} sources",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun LoadingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ChatInputField(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier,
        placeholder = { Text("Ask about your documents...") },
        trailingIcon = {
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isLoading
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank() && !isLoading)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        singleLine = true,
        enabled = !isLoading
    )
}