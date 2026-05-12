package com.penpal.feature.stacks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.data.stack.StackDao
import com.penpal.core.data.stack.StackEntity
import com.penpal.core.ui.picker.PickerItem
import com.penpal.core.ui.picker.PickerUiState
import com.penpal.core.ui.picker.PickerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for the notebook list screen
 */
data class StackListUiState(
    val stacks: List<StackSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    val stackToDelete: StackSummary? = null,
    val isSelectionMode: Boolean = false,
    val selectedStackIds: Set<String> = emptySet(),
    val showRenameDialog: Boolean = false,
    val stackToRename: StackSummary? = null
)

/**
 * Summary of a notebook for list display
 */
data class StackSummary(
    val id: String,
    val title: String,
    val preview: String,
    val blockCount: Int,
    val updatedAt: Long
)

/**
 * ViewModel for the notebook list screen
 */
class StackListViewModel(
    private val stackDao: StackDao? = null
) : ViewModel(), PickerViewModel {

    private val _uiState = MutableStateFlow(StackListUiState())
    val uiState: StateFlow<StackListUiState> = _uiState.asStateFlow()

    override val pickerUiState: StateFlow<PickerUiState> = uiState.map { state ->
        PickerUiState(
            items = state.stacks.map { stack ->
                PickerItem(
                    id = stack.id,
                    title = stack.title,
                    subtitle = "${stack.blockCount} blocks"
                )
            },
            isLoading = state.isLoading,
            error = state.error
        )
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), PickerUiState())

    override fun refresh() {
        loadStacks()
    }

    init {
        loadStacks()
    }

    /**
     * Loads all stacks from the database
     */
    fun loadStacks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                stackDao?.getAllStacks()?.collect { entities ->
                    val summaries = entities.map { entity ->
                        StackSummary(
                            id = entity.id,
                            title = entity.title,
                            preview = extractPreview(entity.blocksJson),
                            blockCount = countBlocks(entity.blocksJson),
                            updatedAt = entity.updatedAt
                        )
                    }
                    _uiState.update {
                        it.copy(
                            stacks = summaries,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load stacks"
                    )
                }
            }
        }
    }

    /**
     * Shows delete confirmation dialog
     */
    fun showDeleteConfirmation(stack: StackSummary) {
        _uiState.update {
            it.copy(
                showDeleteDialog = true,
                stackToDelete = stack
            )
        }
    }

    /**
     * Dismisses delete confirmation dialog
     */
    fun dismissDeleteConfirmation() {
        _uiState.update {
            it.copy(
                showDeleteDialog = false,
                stackToDelete = null
            )
        }
    }

    /**
     * Deletes the selected stack
     */
    fun deleteStack() {
        viewModelScope.launch {
            val stack = _uiState.value.stackToDelete ?: return@launch
            try {
                stackDao?.delete(stack.id)
                dismissDeleteConfirmation()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        showDeleteDialog = false,
                        stackToDelete = null,
                        error = e.message ?: "Failed to delete stack"
                    )
                }
            }
        }
    }

    /**
     * Enters selection mode on long press
     */
    fun enterSelectionMode(stackId: String) {
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedStackIds = setOf(stackId)
            )
        }
    }

    /**
     * Toggles selection of a stack
     */
    fun toggleStackSelection(stackId: String) {
        _uiState.update { state ->
            val newSelected = if (stackId in state.selectedStackIds) {
                state.selectedStackIds - stackId
            } else {
                state.selectedStackIds + stackId
            }
            state.copy(
                selectedStackIds = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    /**
     * Exits selection mode
     */
    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedStackIds = emptySet()
            )
        }
    }

    /**
     * Shows rename dialog for a stack
     */
    fun showRenameDialog(stack: StackSummary) {
        _uiState.update {
            it.copy(
                showRenameDialog = true,
                stackToRename = stack
            )
        }
    }

    /**
     * Dismisses rename dialog
     */
    fun dismissRenameDialog() {
        _uiState.update {
            it.copy(
                showRenameDialog = false,
                stackToRename = null
            )
        }
    }

    /**
     * Renames a stack
     */
    fun renameStack(newTitle: String) {
        viewModelScope.launch {
            val stack = _uiState.value.stackToRename ?: return@launch
            try {
                stackDao?.updateTitle(stack.id, newTitle, System.currentTimeMillis())
                dismissRenameDialog()
                loadStacks()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        showRenameDialog = false,
                        stackToRename = null,
                        error = e.message ?: "Failed to rename stack"
                    )
                }
            }
        }
    }

    /**
     * Deletes all selected stacks
     */
    fun deleteSelectedStacks() {
        viewModelScope.launch {
            val idsToDelete = _uiState.value.selectedStackIds.toList()
            try {
                idsToDelete.forEach { stackDao?.delete(it) }
                exitSelectionMode()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Failed to delete stacks"
                    )
                }
            }
        }
    }

    /**
     * Extracts a preview text from blocks JSON
     */
    private fun extractPreview(blocksJson: String): String {
        return try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(blocksJson, type)

            // Find first text block
            val firstText = data.firstOrNull { it["type"] == "text" }
            val content = (firstText?.get("content") as? String) ?: ""

            if (content.isNotEmpty()) {
                content.take(100).let { if (it.length < content.length) "$it..." else it }
            } else {
                // Check for other content
                val otherBlock = data.firstOrNull()
                when (otherBlock?.get("type")) {
                    "drawing" -> "Drawing"
                    "latex" -> "Math expression"
                    "graph" -> {
                        val nodeCount = (otherBlock["nodes"] as? List<*>)?.size ?: 0
                        "$nodeCount nodes"
                    }
                    "image" -> "Image"
                    else -> "Empty stack"
                }
            }
        } catch (e: Exception) {
            "Unable to load preview"
        }
    }

    /**
     * Counts blocks from JSON
     */
    private fun countBlocks(blocksJson: String): Int {
        return try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(blocksJson, type)
            data.size
        } catch (e: Exception) {
            0
        }
    }
}