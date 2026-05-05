package com.penpal.feature.notebooks

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.penpal.core.data.NotebookDao
import com.penpal.core.data.NotebookEntity
import com.penpal.core.data.PenpalDatabase
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel for the notebook editor.
 * Manages the document's blocks and handles user interactions.
 */
class NotebookEditorViewModel(
    private val notebookDao: NotebookDao? = null
) : ViewModel() {

    private val gson = Gson()

    private val _uiState = MutableStateFlow(NotebookEditorState())
    val uiState: StateFlow<NotebookEditorState> = _uiState.asStateFlow()

    // Current viewport transform for the graph canvas
    private val _canvasOffset = MutableStateFlow(Offset.Zero)
    val canvasOffset: StateFlow<Offset> = _canvasOffset.asStateFlow()

    private val _canvasScale = MutableStateFlow(1f)
    val canvasScale: StateFlow<Float> = _canvasScale.asStateFlow()

    // Graph editing state
    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    private val _isAddingEdge = MutableStateFlow(false)
    val isAddingEdge: StateFlow<Boolean> = _isAddingEdge.asStateFlow()

    private val _edgeStartNodeId = MutableStateFlow<String?>(null)
    val edgeStartNodeId: StateFlow<String?> = _edgeStartNodeId.asStateFlow()

    init {
        // Create a new empty document on init
        createNewDocument()
    }

    /** Creates a new empty document */
    fun createNewDocument() {
        val docId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                document = NotebookDocument(
                    id = docId,
                    title = "Untitled",
                    blocks = listOf(
                        Block.TextBlock(
                            id = UUID.randomUUID().toString(),
                            content = ""
                        )
                    )
                ),
                selectedBlockId = null,
                isLoading = false,
                error = null,
                isDirty = false
            )
        }
    }

    /** Loads an existing document */
    fun loadDocument(document: NotebookDocument) {
        _uiState.update {
            it.copy(
                document = document,
                selectedBlockId = null,
                isLoading = false,
                error = null,
                isDirty = false
            )
        }
    }

    /**
     * Loads a notebook from the database by ID
     */
    fun loadFromDatabase(notebookId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val entity = notebookDao?.getNotebook(notebookId)
                if (entity != null) {
                    val blocks = deserializeBlocks(entity.blocksJson)
                    val document = NotebookDocument(
                        id = entity.id,
                        title = entity.title,
                        blocks = blocks,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt
                    )
                    loadDocument(document)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Notebook not found"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load notebook"
                    )
                }
            }
        }
    }

    /** Handles editor events */
    fun onEvent(event: NotebookEvent) {
        when (event) {
            is NotebookEvent.AddBlock -> addBlock(event.block, event.afterBlockId)
            is NotebookEvent.RemoveBlock -> removeBlock(event.blockId)
            is NotebookEvent.MoveBlock -> moveBlock(event.blockId, event.newIndex)
            is NotebookEvent.UpdateBlock -> updateBlock(event.block)
            is NotebookEvent.SelectBlock -> selectBlock(event.blockId)
            is NotebookEvent.UpdateGraphNode -> updateGraphNode(event.node)
            is NotebookEvent.AddGraphEdge -> addGraphEdge(event.edge)
            is NotebookEvent.UpdateDocumentTitle -> updateDocumentTitle(event.title)
            is NotebookEvent.SaveDocument -> saveDocument()
            is NotebookEvent.LoadDocument -> loadDocument()
            is NotebookEvent.SetImageUri -> setImageUri(event.blockId, event.uri)
            is NotebookEvent.DeleteDocument -> deleteDocument()
        }
    }

    /**
     * Sets the image URI for an ImageBlock
     */
    fun setImageUri(blockId: String, uri: Uri) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.ImageBlock && block.id == blockId) {
                            block.copy(uri = uri)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    private fun addBlock(block: Block, afterBlockId: String? = null) {
        _uiState.update { state ->
            val blocks = state.document.blocks.toMutableList()
            val index = if (afterBlockId != null) {
                blocks.indexOfFirst { it.id == afterBlockId } + 1
            } else {
                blocks.size
            }
            blocks.add(index.coerceAtLeast(0), block)
            state.copy(
                document = state.document.copy(
                    blocks = blocks,
                    updatedAt = System.currentTimeMillis()
                ),
                selectedBlockId = block.id,
                isDirty = true
            )
        }
    }

    private fun removeBlock(blockId: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.filterNot { it.id == blockId },
                    updatedAt = System.currentTimeMillis()
                ),
                selectedBlockId = if (state.selectedBlockId == blockId) null else state.selectedBlockId,
                isDirty = true
            )
        }
    }

    private fun moveBlock(blockId: String, newIndex: Int) {
        _uiState.update { state ->
            val blocks = state.document.blocks.toMutableList()
            val currentIndex = blocks.indexOfFirst { it.id == blockId }
            if (currentIndex < 0 || newIndex < 0 || newIndex >= blocks.size) return@update state

            val block = blocks.removeAt(currentIndex)
            blocks.add(newIndex, block)

            state.copy(
                document = state.document.copy(
                    blocks = blocks,
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    private fun updateBlock(block: Block) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map {
                        if (it.id == block.id) block else it
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    fun selectBlock(blockId: String?) {
        _uiState.update { it.copy(selectedBlockId = blockId) }
    }

    // ──────────────────────────────────────────────────────────────
    // Graph Node Editing
    // ──────────────────────────────────────────────────────────────

    /**
     * Updates a node's position (called during drag)
     */
    fun updateNodePosition(nodeId: String, newX: Float, newY: Float) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock) {
                            block.copy(
                                nodes = block.nodes.map { node ->
                                    if (node.id == nodeId) node.copy(posX = newX, posY = newY)
                                    else node
                                }
                            )
                        } else block
                    }
                ),
                isDirty = true
            )
        }
    }

    /**
     * Finalizes node position after drag ends (for undo support)
     */
    fun finalizeNodePosition(node: GraphNode) {
        updateGraphNode(node)
    }

    private fun updateGraphNode(node: GraphNode) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock) {
                            block.copy(
                                nodes = block.nodes.map { n ->
                                    if (n.id == node.id) node else n
                                }
                            )
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /**
     * Adds a new node to a graph block
     */
    fun addNodeToGraph(graphBlockId: String, label: String, atX: Float, atY: Float) {
        val newNode = GraphNode(
            id = UUID.randomUUID().toString(),
            label = label,
            posX = atX,
            posY = atY
        )
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock && block.id == graphBlockId) {
                            block.copy(nodes = block.nodes + newNode)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
        _selectedNodeId.value = newNode.id
    }

    /**
     * Adds an edge between two nodes
     */
    private fun addGraphEdge(edge: GraphEdge) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock) {
                            block.copy(edges = block.edges + edge)
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /**
     * Removes a node and its connected edges
     */
    fun removeNodeFromGraph(graphBlockId: String, nodeId: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    blocks = state.document.blocks.map { block ->
                        if (block is Block.GraphBlock && block.id == graphBlockId) {
                            block.copy(
                                nodes = block.nodes.filterNot { it.id == nodeId },
                                edges = block.edges.filterNot {
                                    it.fromNodeId == nodeId || it.toNodeId == nodeId
                                }
                            )
                        } else block
                    },
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
        if (_selectedNodeId.value == nodeId) {
            _selectedNodeId.value = null
        }
    }

    /**
     * Starts edge creation mode
     */
    fun startAddingEdge(fromNodeId: String) {
        _edgeStartNodeId.value = fromNodeId
        _isAddingEdge.value = true
    }

    /**
     * Completes edge creation or cancels
     */
    fun completeEdge(toNodeId: String) {
        val fromId = _edgeStartNodeId.value ?: return
        if (fromId != toNodeId) {
            addGraphEdge(
                GraphEdge(
                    id = UUID.randomUUID().toString(),
                    fromNodeId = fromId,
                    toNodeId = toNodeId
                )
            )
        }
        cancelEdgeCreation()
    }

    /**
     * Cancels edge creation mode
     */
    fun cancelEdgeCreation() {
        _edgeStartNodeId.value = null
        _isAddingEdge.value = false
    }

    // ──────────────────────────────────────────────────────────────
    // Canvas Transform
    // ──────────────────────────────────────────────────────────────

    fun updateCanvasOffset(offset: Offset) {
        _canvasOffset.value = offset
    }

    fun updateCanvasScale(scale: Float) {
        _canvasScale.value = scale.coerceIn(0.25f, 4f)
    }

    fun resetCanvasView() {
        _canvasOffset.value = Offset.Zero
        _canvasScale.value = 1f
    }

    // ──────────────────────────────────────────────────────────────
    // Document Management
    // ──────────────────────────────────────────────────────────────

    private fun updateDocumentTitle(title: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    title = title,
                    updatedAt = System.currentTimeMillis()
                ),
                isDirty = true
            )
        }
    }

    /**
     * Saves the current document to the database
     */
    fun saveDocument() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val document = _uiState.value.document
                val blocksJson = serializeBlocks(document.blocks)
                val entity = NotebookEntity(
                    id = document.id,
                    title = document.title,
                    blocksJson = blocksJson,
                    createdAt = document.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                notebookDao?.insert(entity)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isDirty = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to save"
                    )
                }
            }
        }
    }

    /**
     * Deletes the current document from the database
     */
    private fun deleteDocument() {
        viewModelScope.launch {
            val docId = _uiState.value.document.id
            _uiState.update { it.copy(isLoading = true) }
            try {
                notebookDao?.delete(docId)
                createNewDocument() // Reset to new document
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to delete"
                    )
                }
            }
        }
    }

    private fun loadDocument() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // TODO: Load from Room database
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Serialization Helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Serializes blocks to JSON string for storage
     */
    private fun serializeBlocks(blocks: List<Block>): String {
        val serializableBlocks = blocks.map { block ->
            when (block) {
                is Block.TextBlock -> mapOf(
                    "type" to "text",
                    "id" to block.id,
                    "content" to block.content
                )
                is Block.ImageBlock -> mapOf(
                    "type" to "image",
                    "id" to block.id,
                    "uri" to (block.uri?.toString() ?: ""),
                    "caption" to block.caption
                )
                is Block.DrawingBlock -> mapOf(
                    "type" to "drawing",
                    "id" to block.id,
                    "pathData" to block.pathData,
                    "width" to block.width,
                    "height" to block.height
                )
                is Block.LatexBlock -> mapOf(
                    "type" to "latex",
                    "id" to block.id,
                    "expression" to block.expression
                )
                is Block.GraphBlock -> mapOf(
                    "type" to "graph",
                    "id" to block.id,
                    "graphId" to block.graphId,
                    "nodes" to block.nodes.map { node ->
                        mapOf(
                            "id" to node.id,
                            "label" to node.label,
                            "posX" to node.posX,
                            "posY" to node.posY,
                            "type" to node.type.name
                        )
                    },
                    "edges" to block.edges.map { edge ->
                        mapOf(
                            "id" to edge.id,
                            "fromNodeId" to edge.fromNodeId,
                            "toNodeId" to edge.toNodeId,
                            "label" to edge.label,
                            "type" to edge.type.name
                        )
                    }
                )
                is Block.EmbedBlock -> mapOf(
                    "type" to "embed",
                    "id" to block.id,
                    "sourceId" to block.sourceId,
                    "preview" to block.preview,
                    "type" to block.type.name
                )
            }
        }
        return gson.toJson(serializableBlocks)
    }

    /**
     * Deserializes blocks from JSON string
     */
    private fun deserializeBlocks(json: String): List<Block> {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(json, type)
            data.mapNotNull { item ->
                when (item["type"] as? String) {
                    "text" -> Block.TextBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        content = item["content"] as? String ?: ""
                    )
                    "image" -> Block.ImageBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        uri = (item["uri"] as? String)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) },
                        caption = item["caption"] as? String ?: ""
                    )
                    "drawing" -> Block.DrawingBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        pathData = item["pathData"] as? String ?: "",
                        width = (item["width"] as? Number)?.toFloat() ?: 800f,
                        height = (item["height"] as? Number)?.toFloat() ?: 600f
                    )
                    "latex" -> Block.LatexBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        expression = item["expression"] as? String ?: ""
                    )
                    "graph" -> {
                        val nodesData = item["nodes"] as? List<Map<String, Any>> ?: emptyList()
                        val edgesData = item["edges"] as? List<Map<String, Any>> ?: emptyList()
                        Block.GraphBlock(
                            id = item["id"] as? String ?: return@mapNotNull null,
                            graphId = item["graphId"] as? String ?: "",
                            nodes = nodesData.mapNotNull { node ->
                                GraphNode(
                                    id = node["id"] as? String ?: return@mapNotNull null,
                                    label = node["label"] as? String ?: "",
                                    posX = (node["posX"] as? Number)?.toFloat() ?: 0f,
                                    posY = (node["posY"] as? Number)?.toFloat() ?: 0f
                                )
                            },
                            edges = edgesData.mapNotNull { edge ->
                                GraphEdge(
                                    id = edge["id"] as? String ?: return@mapNotNull null,
                                    fromNodeId = edge["fromNodeId"] as? String ?: return@mapNotNull null,
                                    toNodeId = edge["toNodeId"] as? String ?: return@mapNotNull null
                                )
                            }
                        )
                    }
                    "embed" -> Block.EmbedBlock(
                        id = item["id"] as? String ?: return@mapNotNull null,
                        sourceId = item["sourceId"] as? String ?: "",
                        preview = item["preview"] as? String ?: ""
                    )
                    else -> null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helper
    // ──────────────────────────────────────────────────────────────

    /** Generates a new block ID */
    fun newBlockId(): String = UUID.randomUUID().toString()

    /** Gets the currently selected block */
    fun getSelectedBlock(): Block? {
        val selectedId = _uiState.value.selectedBlockId ?: return null
        return _uiState.value.document.blocks.find { it.id == selectedId }
    }

    /** Gets a graph block by ID */
    fun getGraphBlock(graphId: String): Block.GraphBlock? {
        return _uiState.value.document.blocks
            .filterIsInstance<Block.GraphBlock>()
            .find { it.id == graphId }
    }
}