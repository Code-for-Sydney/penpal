package com.penpal.feature.process

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.penpal.core.data.ExtractionJobEntity
import com.penpal.core.ui.PenpalTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(
    uiState: ProcessUiState,
    onEvent: (ProcessEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Document Ingestion",
                style = MaterialTheme.typography.headlineMedium
            )

            if (!uiState.isOnline) {
                OfflineIndicator()
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Show cached data status
        if (uiState.cachedChunkCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${uiState.cachedChunkCount} chunks indexed for offline RAG",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        SourceTypeSelector(
            selectedType = uiState.selectedSourceType,
            onTypeSelected = { onEvent(ProcessEvent.SelectSourceType(it)) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        InputSection(
            inputText = uiState.inputText,
            onInputChange = { onEvent(ProcessEvent.UpdateInput(it)) },
            onEnqueue = { onEvent(ProcessEvent.EnqueueJob) },
            isProcessing = uiState.isProcessing
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Queue (${uiState.jobs.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        JobList(
            jobs = uiState.jobs,
            onCancel = { onEvent(ProcessEvent.CancelJob(it)) }
        )

        uiState.error?.let { error ->
            ErrorBanner(
                message = error,
                onDismiss = { onEvent(ProcessEvent.DismissError) }
            )
        }
    }
}

@Composable
private fun OfflineIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = "Offline",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Offline",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun SourceTypeSelector(
    selectedType: SourceType,
    onTypeSelected: (SourceType) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SourceType.entries.forEach { type ->
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = { Text(type.name) },
                leadingIcon = {
                    Icon(
                        imageVector = when (type) {
                            SourceType.PDF -> Icons.Default.PictureAsPdf
                            SourceType.AUDIO -> Icons.Default.Mic
                            SourceType.IMAGE -> Icons.Default.Image
                            SourceType.URL -> Icons.Default.Link
                            SourceType.CODE -> Icons.Default.Code
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun InputSection(
    inputText: String,
    onInputChange: (String) -> Unit,
    onEnqueue: () -> Unit,
    isProcessing: Boolean
) {
    OutlinedTextField(
        value = inputText,
        onValueChange = onInputChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Enter URL, file path, or paste content...") },
        trailingIcon = {
            IconButton(
                onClick = onEnqueue,
                enabled = inputText.isNotBlank() && !isProcessing
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add to queue"
                )
            }
        },
        singleLine = false,
        minLines = 2,
        maxLines = 4
    )
}

@Composable
private fun JobList(
    jobs: List<ExtractionJobEntity>,
    onCancel: (String) -> Unit
) {
    if (jobs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No jobs in queue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(jobs, key = { it.id }) { job ->
                JobItem(
                    job = job,
                    onCancel = { onCancel(job.id) }
                )
            }
        }
    }
}

@Composable
private fun JobItem(
    job: ExtractionJobEntity,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (job.mimeType) {
                    "pdf" -> Icons.Default.PictureAsPdf
                    "audio" -> Icons.Default.Mic
                    "image" -> Icons.Default.Image
                    "url" -> Icons.Default.Link
                    else -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.sourceUri,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = job.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (job.status) {
                        "DONE" -> MaterialTheme.colorScheme.primary
                        "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (job.status == "QUEUED") {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel"
                    )
                }
            }

            if (job.status == "RUNNING") {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
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
