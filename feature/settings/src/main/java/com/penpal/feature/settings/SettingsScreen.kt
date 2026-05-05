package com.penpal.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.penpal.core.ai.ModelStatus

/**
 * Settings Screen - Configure AI model and app preferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Token input for HuggingFace download
    var hfToken by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ──────────────────────────────────────────────────────────────
            // AI Model Section
            // ──────────────────────────────────────────────────────────────
            SettingsSection(title = "AI Model") {
                // Current model info
                SettingsRow(
                    icon = Icons.Default.Psychology,
                    title = "Model",
                    subtitle = uiState.modelName
                )

                SettingsRow(
                    icon = Icons.Default.Description,
                    title = "File",
                    subtitle = uiState.modelFileName
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Model status with download progress
                when (uiState.modelStatus) {
                    ModelStatus.NOT_DOWNLOADED -> {
                        SettingsRow(
                            icon = Icons.Default.CloudOff,
                            title = "Status",
                            subtitle = "Not downloaded"
                        )
                    }
                    ModelStatus.DOWNLOADING -> {
                        SettingsRow(
                            icon = Icons.Default.Download,
                            title = "Status",
                            subtitle = "Downloading..."
                        )
                        LinearProgressIndicator(
                            progress = { uiState.downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (uiState.downloadProgressText.isNotEmpty()) {
                            Text(
                                text = uiState.downloadProgressText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    ModelStatus.DOWNLOADED -> {
                        SettingsRow(
                            icon = Icons.Default.CheckCircle,
                            title = "Status",
                            subtitle = "Ready"
                        )
                    }
                    ModelStatus.ERROR -> {
                        SettingsRow(
                            icon = Icons.Default.Error,
                            title = "Status",
                            subtitle = "Error - ${uiState.error ?: "Unknown"}"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Download/Delete button
                when (uiState.modelStatus) {
                    ModelStatus.NOT_DOWNLOADED, ModelStatus.ERROR -> {
                        Button(
                            onClick = { onEvent(SettingsEvent.DownloadModel) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isDownloading
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Download Model")
                        }
                    }
                    ModelStatus.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = { /* Cancel download - TODO */ },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.isDownloading
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel Download")
                        }
                    }
                    ModelStatus.DOWNLOADED -> {
                        OutlinedButton(
                            onClick = { onEvent(SettingsEvent.ShowDeleteConfirmation) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Model")
                        }
                    }
                }

                // Model size info
                if (uiState.modelStatus == ModelStatus.NOT_DOWNLOADED) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Model size: ~2.6 GB (Gemma 4 E2B IT)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Downloads from HuggingFace or Kaggle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ──────────────────────────────────────────────────────────────
            // Inference Mode Section
            // ──────────────────────────────────────────────────────────────
            SettingsSection(title = "Inference Mode") {
                SettingsRow(
                    icon = Icons.Default.Tune,
                    title = "Mode",
                    subtitle = when (uiState.inferenceMode) {
                        InferenceMode.ON_DEVICE -> "On-device (fastest, private)"
                        InferenceMode.CLOUD -> "Cloud (most capable)"
                        InferenceMode.HYBRID -> "Hybrid (balanced)"
                    },
                    action = {
                        IconButton(onClick = { onEvent(SettingsEvent.ToggleInferenceMode) }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Change mode")
                        }
                    }
                )
            }

            // ──────────────────────────────────────────────────────────────
            // Generation Settings
            // ──────────────────────────────────────────────────────────────
            SettingsSection(title = "Generation Settings") {
                // Max tokens
                var tokensExpanded by remember { mutableStateOf(false) }
                var tokensValue by remember { mutableStateOf(uiState.maxTokens.toString()) }

                ExposedDropdownMenuBox(
                    expanded = tokensExpanded,
                    onExpandedChange = { tokensExpanded = it }
                ) {
                    OutlinedTextField(
                        value = tokensValue,
                        onValueChange = { tokensValue = it },
                        label = { Text("Max Tokens") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tokensExpanded) },
                        supportingText = { Text("Output length: 256 - 8192") }
                    )
                    ExposedDropdownMenu(
                        expanded = tokensExpanded,
                        onDismissRequest = { tokensExpanded = false }
                    ) {
                        listOf(256, 512, 1024, 2048, 4096, 8192).forEach { value ->
                            DropdownMenuItem(
                                text = { Text("$value tokens") },
                                onClick = {
                                    tokensValue = value.toString()
                                    onEvent(SettingsEvent.UpdateMaxTokens(value))
                                    tokensExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Temperature
                Text(
                    "Temperature",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Controls randomness: ${String.format("%.1f", uiState.temperature)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = uiState.temperature,
                    onValueChange = { onEvent(SettingsEvent.UpdateTemperature(it)) },
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Precise", style = MaterialTheme.typography.labelSmall)
                    Text("Creative", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ──────────────────────────────────────────────────────────────
            // About Section
            // ──────────────────────────────────────────────────────────────
            SettingsSection(title = "About") {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "Version",
                    subtitle = uiState.appVersion
                )
                SettingsRow(
                    icon = Icons.Default.Code,
                    title = "Build",
                    subtitle = "Debug"
                )
            }
        }
    }

    // Download Dialog
    if (uiState.showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(SettingsEvent.HideDownloadDialog) },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
            title = { Text("Download Model") },
            text = {
                Column {
                    Text(
                        "Enter your HuggingFace access token to download the Gemma 4 E2B model.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = hfToken,
                        onValueChange = { hfToken = it },
                        label = { Text("Access Token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { /* Open HF tokens page */ }
                    ) {
                        Text("Get token at huggingface.co")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (hfToken.isNotBlank()) {
                            onEvent(SettingsEvent.StartHfDownload(hfToken))
                        }
                    },
                    enabled = hfToken.isNotBlank()
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(SettingsEvent.HideDownloadDialog) }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { onEvent(SettingsEvent.DismissDeleteConfirmation) },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Delete Model?") },
            text = {
                Text("This will remove the AI model from your device. You can download it again later.")
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(SettingsEvent.DeleteModel) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(SettingsEvent.DismissDeleteConfirmation) }) {
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
                TextButton(onClick = { onEvent(SettingsEvent.DismissError) }) {
                    Text("Dismiss")
                }
            }
        ) {
            Text(error)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Helper Components
// ─────────────────────────────────────────────────────────────────────

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action?.invoke()
    }
}