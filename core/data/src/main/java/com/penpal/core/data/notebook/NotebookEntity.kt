package com.penpal.core.data.notebook

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: Int,
    val lastDisplayedPage: Int = 0,
    val defaultBackground: String = "RULED",
    val type: NotebookType = NotebookType.NOTEBOOK,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)