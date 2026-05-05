package com.penpal.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val text: String,
    val embeddingJson: String,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "extraction_jobs")
data class ExtractionJobEntity(
    @PrimaryKey val id: String,
    val sourceUri: String,
    val mimeType: String,
    val rule: String,
    val status: String,
    val workerId: String?,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val sourcesJson: String,
    val createdAt: Long
)

@Entity(tableName = "graph_nodes")
data class GraphNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val type: String,
    val notebookId: String?,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val posZ: Float = 0f
)

@Entity(tableName = "graph_edges", primaryKeys = ["fromId", "toId"])
data class GraphEdgeEntity(
    val fromId: String,
    val toId: String,
    val relation: String,
    val weight: Float = 1f
)

/**
 * Entity for saved notebooks
 */
@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val blocksJson: String,  // JSON serialized blocks
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)