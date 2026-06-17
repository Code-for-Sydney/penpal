package com.penpal.core.data.knowledge

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
