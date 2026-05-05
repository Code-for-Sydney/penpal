package com.penpal.feature.notebooks

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
}

enum class EmbedType {
    LINK, AUDIO, VIDEO, FILE
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
data class NotebookDocument(
    val id: String,
    val title: String,
    val blocks: List<Block> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** State for the notebook editor */
data class NotebookEditorState(
    val document: NotebookDocument = NotebookEditorState.EmptyDocument,
    val selectedBlockId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isDirty: Boolean = false  // Has unsaved changes
) {
    companion object {
        val EmptyDocument = NotebookDocument(
            id = "",
            title = "Untitled",
            blocks = emptyList()
        )
    }
}

/** Events that can be triggered in the editor */
sealed class NotebookEvent {
    data class AddBlock(val block: Block, val afterBlockId: String? = null) : NotebookEvent()
    data class RemoveBlock(val blockId: String) : NotebookEvent()
    data class MoveBlock(val blockId: String, val newIndex: Int) : NotebookEvent()
    data class UpdateBlock(val block: Block) : NotebookEvent()
    data class SelectBlock(val blockId: String?) : NotebookEvent()
    data class UpdateGraphNode(val node: GraphNode) : NotebookEvent()
    data class AddGraphEdge(val edge: GraphEdge) : NotebookEvent()
    data class UpdateDocumentTitle(val title: String) : NotebookEvent()
    object SaveDocument : NotebookEvent()
    object LoadDocument : NotebookEvent()
    object DeleteDocument : NotebookEvent()
    data class SetImageUri(val blockId: String, val uri: Uri) : NotebookEvent()
}

/** UI events from the screen (not stored in state) */
sealed class NotebookScreenEvent {
    data object NavigateToHome : NotebookScreenEvent()
    data class PickImage(val blockId: String) : NotebookScreenEvent()
}