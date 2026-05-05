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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                // Model selection
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it }
                ) {
                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Selected Model") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null) }
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        uiState.availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onEvent(SettingsEvent.SelectModel(model))
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }

                // Model status
                if (uiState.isSimulated) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Demo mode - download is simulated",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                SettingsRow(
                    icon = when (uiState.modelStatus) {
                        ModelStatus.NOT_DOWNLOADED -> Icons.Default.CloudOff
                        ModelStatus.DOWNLOADING -> Icons.Default.Download
                        ModelStatus.DOWNLOADED -> Icons.Default.CheckCircle
                        ModelStatus.ERROR -> Icons.Default.Error
                    },
                    title = "Status",
                    subtitle = when (uiState.modelStatus) {
                        ModelStatus.NOT_DOWNLOADED -> "Not downloaded"
                        ModelStatus.DOWNLOADING -> "Downloading... ${(uiState.downloadProgress * 100).toInt()}%"
                        ModelStatus.DOWNLOADED -> "Ready"
                        ModelStatus.ERROR -> "Error"
                    }
                )

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
                        Column {
                            LinearProgressIndicator(
                                progress = { uiState.downloadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Downloading model...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

            // ──────────────────────────────────────────────────────────────
            // Danger Zone
            // ──────────────────────────────────────────────────────────────
            SettingsSection(title = "Danger Zone") {
                OutlinedButton(
                    onClick = { /* TODO: Export data */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Data")
                }
            }
        }
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