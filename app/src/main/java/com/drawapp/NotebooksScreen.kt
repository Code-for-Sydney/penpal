package com.drawapp

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var notebooks by remember { mutableStateOf(NotebookManager.getNotebooks(context)) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<Notebook?>(null) }
    var newNotebookName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebooks") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create new notebook")
            }
        },
        modifier = modifier
    ) { padding ->
        if (notebooks.isEmpty()) {
            EmptyNotebooksState(
                onCreateNew = { showCreateDialog = true },
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
                    items = notebooks,
                    key = { it.id }
                ) { notebook ->
                    NotebookCard(
                        notebook = notebook,
                        onClick = {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                putExtra("NOTEBOOK_ID", notebook.id)
                            }
                            context.startActivity(intent)
                        },
                        onDelete = { showDeleteDialog = notebook }
                    )
                }
            }
        }
    }

    // Create notebook dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateDialog = false
                newNotebookName = ""
            },
            icon = { Icon(Icons.Default.Book, contentDescription = null) },
            title = { Text("Create Notebook") },
            text = {
                OutlinedTextField(
                    value = newNotebookName,
                    onValueChange = { newNotebookName = it },
                    label = { Text("Notebook name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newNotebookName.isNotBlank()) {
                            val notebook = Notebook(
                                name = newNotebookName.trim(),
                                color = AndroidColor.parseColor("#FF4081"),
                                type = NotebookType.NOTEBOOK
                            )
                            NotebookManager.addNotebook(context, notebook)
                            notebooks = NotebookManager.getNotebooks(context)
                            showCreateDialog = false
                            newNotebookName = ""
                        }
                    },
                    enabled = newNotebookName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        newNotebookName = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { notebook ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete Notebook?") },
            text = {
                Text("Are you sure you want to delete \"${notebook.name}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        NotebookManager.deleteNotebook(context, notebook.id)
                        notebooks = NotebookManager.getNotebooks(context)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun NotebookCard(
    notebook: Notebook,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(notebook.color).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (notebook.type == NotebookType.WHITEBOARD)
                        Icons.Default.Dashboard else Icons.Default.Book,
                    contentDescription = null,
                    tint = Color(notebook.color),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notebook.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (notebook.type == NotebookType.WHITEBOARD) "Whiteboard" else "Notebook",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun EmptyNotebooksState(
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Book,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "No Notebooks",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Create your first notebook to start drawing.\nTap + to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onCreateNew) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create Notebook")
        }
    }
}