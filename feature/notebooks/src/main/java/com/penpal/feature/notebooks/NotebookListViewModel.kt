package com.penpal.feature.notebooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.penpal.core.data.NotebookDao
import com.penpal.core.data.NotebookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for the notebook list screen
 */
data class NotebookListUiState(
    val notebooks: List<NotebookSummary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    val notebookToDelete: NotebookSummary? = null
)

/**
 * Summary of a notebook for list display
 */
data class NotebookSummary(
    val id: String,
    val title: String,
    val preview: String,
    val blockCount: Int,
    val updatedAt: Long
)

/**
 * ViewModel for the notebook list screen
 */
class NotebookListViewModel(
    private val notebookDao: NotebookDao? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotebookListUiState())
    val uiState: StateFlow<NotebookListUiState> = _uiState.asStateFlow()

    init {
        loadNotebooks()
    }

    /**
     * Loads all notebooks from the database
     */
    fun loadNotebooks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                notebookDao?.getAllNotebooks()?.collect { entities ->
                    val summaries = entities.map { entity ->
                        NotebookSummary(
                            id = entity.id,
                            title = entity.title,
                            preview = extractPreview(entity.blocksJson),
                            blockCount = countBlocks(entity.blocksJson),
                            updatedAt = entity.updatedAt
                        )
                    }
                    _uiState.update {
                        it.copy(
                            notebooks = summaries,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load notebooks"
                    )
                }
            }
        }
    }

    /**
     * Shows delete confirmation dialog
     */
    fun showDeleteConfirmation(notebook: NotebookSummary) {
        _uiState.update {
            it.copy(
                showDeleteDialog = true,
                notebookToDelete = notebook
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
                notebookToDelete = null
            )
        }
    }

    /**
     * Deletes the selected notebook
     */
    fun deleteNotebook() {
        viewModelScope.launch {
            val notebook = _uiState.value.notebookToDelete ?: return@launch
            try {
                notebookDao?.delete(notebook.id)
                dismissDeleteConfirmation()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        showDeleteDialog = false,
                        notebookToDelete = null,
                        error = e.message ?: "Failed to delete notebook"
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
                    else -> "Empty notebook"
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