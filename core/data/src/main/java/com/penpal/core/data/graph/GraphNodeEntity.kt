package com.penpal.core.data.graph

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "graph_nodes")
data class GraphNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val type: String,
    val stackId: String?,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val posZ: Float = 0f
)
