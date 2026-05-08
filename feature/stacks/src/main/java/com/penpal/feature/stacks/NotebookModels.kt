package com.penpal.feature.stacks

import android.net.Uri
import androidx.compose.ui.geometry.Offset

/**
 * Block model for the notebook editor.
 * Each block represents a content unit that can be rendered, edited, and reordered.
 */
sealed class Block {
    abstract val id: String

    /** A text block containing markdown-formatted content */
    data class TextBlock(
        override val id: String,
        val content: String = "",
        val isEditing: Boolean = false
    ) : Block()

    /** An image block with optional caption */
    data class ImageBlock(
        override val id: String,
        val uri: Uri? = null,
        val caption: String = "",
        val isEditing: Boolean = false
    ) : Block()

    /** A drawing block containing path data from canvas strokes */
    data class DrawingBlock(
        override val id: String,
        val pathData: String = "",
        val width: Float = 800f,
        val height: Float = 600f
    ) : Block()

    /** A LaTeX math expression rendered via MathJax */
    data class LatexBlock(
        override val id: String,
        val expression: String = ""
    ) : Block()

    /** A graph node canvas block */
    data class GraphBlock(
        override val id: String,
        val graphId: String,
        val nodes: List<GraphNode> = emptyList(),
        val edges: List<GraphEdge> = emptyList()
    ) : Block()

    /** An embedded block linking to external content */
    data class EmbedBlock(
        override val id: String,
        val sourceId: String,
        val preview: String = "",
        val type: EmbedType = EmbedType.LINK
    ) : Block()

    /** A media processing block (Image, Audio, Video, Text) */
    data class ProcessBlock(
        override val id: String,
        val sourceUri: String = "",
        val mediaType: MediaType = MediaType.TEXT,
        val status: ProcessStatus = ProcessStatus.PENDING,
        val progress: Int = 0,  // 0-100 processing progress
        val extractedText: String = "",
        val errorMessage: String? = null,
        val showParsedContent: Boolean = true
    ) : Block()
}

enum class EmbedType {
    LINK, AUDIO, VIDEO, FILE
}

enum class MediaType {
    IMAGE, AUDIO, VIDEO, TEXT
}

enum class ProcessStatus {
    PENDING,    // Added but not started
    QUEUED,     // In processing queue
    RUNNING,    // Currently extracting
    DONE,       // Extraction complete
    ERROR       // Extraction failed
}

/** Node in a graph block */
data class GraphNode(
    val id: String,
    val label: String,
    var posX: Float,
    var posY: Float,
    val type: NodeType = NodeType.DEFAULT
)

enum class NodeType {
    DEFAULT, CONCEPT, TOOL, DATA, STARRED
}

/** Edge connecting two nodes */
data class GraphEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String = "",
    val type: EdgeType = EdgeType.DEFAULT
)

enum class EdgeType {
    DEFAULT, LABELLED, BIDIRECTIONAL, HIGHLIGHTED
}

/** Document containing a list of blocks */
data class StackDocument(
    val id: String,
    val title: String,
    val blocks: List<Block> = emptyList(),
    val systemPrompt: String = "",      // Defines the overall goal for processing
    val agentPrompt: String = "",        // Guides the thinking/processing approach
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** State for the notebook editor */
data class StackEditorState(
    val document: StackDocument = StackEditorState.EmptyDocument,
    val selectedBlockId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDirty: Boolean = false  // Has unsaved changes
) {
    companion object {
        val EmptyDocument = StackDocument(
            id = "",
            title = "Untitled",
            blocks = emptyList()
        )
    }
}

/** Events that can be triggered in the editor */
sealed class StackEvent {
    data class AddBlock(val block: Block, val afterBlockId: String? = null) : StackEvent()
    data class RemoveBlock(val blockId: String) : StackEvent()
    data class MoveBlock(val blockId: String, val newIndex: Int) : StackEvent()
    data class UpdateBlock(val block: Block) : StackEvent()
    data class SelectBlock(val blockId: String?) : StackEvent()
    data class UpdateGraphNode(val node: GraphNode) : StackEvent()
    data class AddGraphEdge(val edge: GraphEdge) : StackEvent()
    data class UpdateDocumentTitle(val title: String) : StackEvent()
    data class UpdateSystemPrompt(val prompt: String) : StackEvent()
    data class UpdateAgentPrompt(val prompt: String) : StackEvent()
    data class ToggleProcessView(val blockId: String) : StackEvent()
    object SaveDocument : StackEvent()
    object LoadDocument : StackEvent()
    object DeleteDocument : StackEvent()
    data class SetImageUri(val blockId: String, val uri: Uri) : StackEvent()
    data class AddProcessBlock(val mediaType: MediaType, val afterBlockId: String? = null) : StackEvent()
    data class UpdateProcessBlockStatus(val blockId: String, val status: ProcessStatus, val text: String = "", val error: String? = null) : StackEvent()
    data class ReprocessBlock(val blockId: String) : StackEvent()
    data class ProcessBlockWithAI(val blockId: String) : StackEvent()
}

/** UI events from the screen (not stored in state) */
sealed class StackScreenEvent {
    data object NavigateToHome : StackScreenEvent()
    data class PickImage(val blockId: String) : StackScreenEvent()
}