package com.penpal.core.data.graph

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "graph_edges", primaryKeys = ["fromId", "toId"])
data class GraphEdgeEntity(
    val fromId: String,
    val toId: String,
    val relation: String,
    val weight: Float = 1f
)
