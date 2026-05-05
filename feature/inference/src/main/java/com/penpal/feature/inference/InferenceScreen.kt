package com.penpal.feature.inference

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.penpal.core.ai.ModelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceScreen(
    uiState: InferenceUiState,
    onEvent: (InferenceEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "AI Inference",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        ModelStatusCard(
            modelStatus = uiState.modelStatus,
            modelName = uiState.modelName,
            modelVariant = uiState.modelVariant,
            isProcessing = uiState.isProcessing,
            isServerConnected = uiState.isServerConnected
        )

        Spacer(modifier = Modifier.height(24.dp))

        ActionButtons(
            modelStatus = uiState.modelStatus,
            isDownloading = uiState.isDownloading,
            downloadProgress = uiState.downloadProgress,
            onLoad = { onEvent(InferenceEvent.LoadModel) },
            onUnload = { onEvent(InferenceEvent.UnloadModel) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Available Models Section
        AvailableModelsSection(
            models = uiState.availableModels,
            isLoading = uiState.isLoadingModels,
            currentModelPath = if (uiState.isReady) null else null, // We'll track this differently
            onSelect = { path -> onEvent(InferenceEvent.SelectModel(path)) },
            onDelete = { path -> onEvent(InferenceEvent.DeleteModel(path)) },
            onRefresh = { onEvent(InferenceEvent.RefreshModelList) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        ModelInfoCard()

        uiState.error?.let { error ->
            ErrorBanner(
                message = error,
                onDismiss = { onEvent(InferenceEvent.DismissError) }
            )
        }
    }
}

@Composable
private fun ModelStatusCard(
    modelStatus: ModelStatus,
    modelName: String,
    modelVariant: String,
    isProcessing: Boolean,
    isServerConnected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = modelVariant,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusIndicator(
                    label = "Model",
                    value = when (modelStatus) {
                        ModelStatus.NOT_LOADED -> "Not Loaded"
                        ModelStatus.LOADING -> "Loading..."
                        ModelStatus.DOWNLOADING -> "Downloading..."
                        ModelStatus.READY -> "Ready"
                        ModelStatus.ERROR -> "Error"
                    },
                    isActive = modelStatus == ModelStatus.READY
                )

                StatusIndicator(
                    label = "Server",
                    value = if (isServerConnected) "Connected" else "Offline",
                    isActive = isServerConnected
                )

                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(
    label: String,
    value: String,
    isActive: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Memory,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ActionButtons(
    modelStatus: ModelStatus,
    isDownloading: Boolean,
    downloadProgress: Int,
    onLoad: () -> Unit,
    onUnload: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isDownloading) {
            // Download progress UI
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Downloading Gemma 4 E2B-IT...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$downloadProgress% complete (~2.6 GB)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onLoad,
                    enabled = modelStatus != ModelStatus.LOADING && modelStatus != ModelStatus.READY,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Model")
                }

                OutlinedButton(
                    onClick = onUnload,
                    enabled = modelStatus == ModelStatus.READY,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unload")
                }
            }
        }
    }
}

@Composable
private fun ModelInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Model Information",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            InfoRow("Type", "Gemma 4 E2B-IT")
            InfoRow("Variant", "google/gemma-4-e2b-it")
            InfoRow("Quantization", "INT4")
            InfoRow("Context Length", "32K tokens")
            InfoRow("Backend", "LiteRT (TFLite)")
            InfoRow("Training", "Instruction-tuned")
            InfoRow("Size", "~2.6 GB")
            InfoRow("Source", "HuggingFace / Kaggle")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AvailableModelsSection(
    models: List<ModelManager.ModelInfo>,
    isLoading: Boolean,
    currentModelPath: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Available Models",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                }
            }

            if (models.isEmpty()) {
                Text(
                    text = "No models found. Download a model or place .litertlm files in your Downloads folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    items(models) { model ->
                        ModelListItem(
                            model = model,
                            isActive = model.path == currentModelPath,
                            onSelect = { onSelect(model.path) },
                            onDelete = { onDelete(model.path) }
                        )
                        if (model != models.last()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelListItem(
    model: ModelManager.ModelInfo,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val sizeMB = model.sizeBytes / (1024 * 1024)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${sizeMB} MB · ${if (model.lastUsed > 0) "Used" else "Available"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row {
            TextButton(
                onClick = onSelect,
                enabled = !isActive
            ) {
                Text(if (isActive) "Active" else "Load")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
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
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
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