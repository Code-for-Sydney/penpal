package com.penpal.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for model download with progress indication.
 * 
 * Shows:
 * - Download source selection (HuggingFace/Kaggle)
 * - Token/API key input
 * - Progress bar with percentage
 * - Cancel option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadBottomSheet(
    isVisible: Boolean,
    modelName: String,
    downloadProgress: Float,
    downloadProgressText: String,
    isDownloading: Boolean,
    error: String?,
    onDownload: (source: DownloadSource, token: String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSource by remember { mutableStateOf(DownloadSource.HUGGINGFACE) }
    var token by remember { mutableStateOf("") }

    val animatedProgress by animateFloatAsState(
        targetValue = downloadProgress,
        label = "progress"
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Icon(
                Icons.Default.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Download Model",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = modelName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!isDownloading && error == null) {
                // Source selection
                Text(
                    text = "Select source:",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DownloadSourceCard(
                        source = DownloadSource.HUGGINGFACE,
                        isSelected = selectedSource == DownloadSource.HUGGINGFACE,
                        onClick = { selectedSource = DownloadSource.HUGGINGFACE },
                        modifier = Modifier.weight(1f)
                    )
                    DownloadSourceCard(
                        source = DownloadSource.KAGGLE,
                        isSelected = selectedSource == DownloadSource.KAGGLE,
                        onClick = { selectedSource = DownloadSource.KAGGLE },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Token input
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(getTokenLabel(selectedSource)) },
                    placeholder = { Text(getTokenPlaceholder(selectedSource)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null
                        )
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { /* Open token page */ }
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(getTokenHelpText(selectedSource))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Download button
                Button(
                    onClick = { onDownload(selectedSource, token) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = token.isNotBlank()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start Download")
                }

            } else if (isDownloading) {
                // Progress display
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge
                )
                if (downloadProgressText.isNotEmpty()) {
                    Text(
                        text = downloadProgressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel Download")
                }

            } else if (error != null) {
                // Error display
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Download Failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }
        }
    }
}

@Composable
private fun DownloadSourceCard(
    source: DownloadSource,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = when (source) {
                    DownloadSource.HUGGINGFACE -> Icons.Default.Cloud
                    DownloadSource.KAGGLE -> Icons.Default.CloudCircle
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (source) {
                    DownloadSource.HUGGINGFACE -> "HuggingFace"
                    DownloadSource.KAGGLE -> "Kaggle"
                },
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

enum class DownloadSource {
    HUGGINGFACE,
    KAGGLE
}

private fun getTokenLabel(source: DownloadSource): String = when (source) {
    DownloadSource.HUGGINGFACE -> "HuggingFace Token"
    DownloadSource.KAGGLE -> "Kaggle Username"
}

private fun getTokenPlaceholder(source: DownloadSource): String = when (source) {
    DownloadSource.HUGGINGFACE -> "hf_..."
    DownloadSource.KAGGLE -> "username"
}

private fun getTokenHelpText(source: DownloadSource): String = when (source) {
    DownloadSource.HUGGINGFACE -> "Get token at huggingface.co"
    DownloadSource.KAGGLE -> "Get credentials at kaggle.com"
}

/**
 * Compact download progress indicator for embedding in other screens.
 */
@Composable
fun DownloadProgressIndicator(
    progress: Float,
    progressText: String,
    isDownloading: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        label = "progress"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Downloading...",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}