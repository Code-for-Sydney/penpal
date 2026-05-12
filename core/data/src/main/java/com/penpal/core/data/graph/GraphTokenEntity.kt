package com.penpal.core.data.graph

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "graph_tokens")
data class GraphTokenEntity(
    @PrimaryKey val id: String,
    val token: String,
    val type: String,
    val frequency: Int = 0,
    val category: String = "",
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)
