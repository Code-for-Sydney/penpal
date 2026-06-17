package com.penpal.core.data.stack

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stacks")
data class StackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val blocksJson: String,
    val notebookId: String? = null,
    val systemPrompt: String = "",
    val agentPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
