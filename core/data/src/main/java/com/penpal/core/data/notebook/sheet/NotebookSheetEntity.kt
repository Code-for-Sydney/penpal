package com.penpal.core.data.notebook.sheet

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notebook_sheets",
    foreignKeys = [
        ForeignKey(
            entity = com.penpal.core.data.stack.StackEntity::class,
            parentColumns = ["id"],
            childColumns = ["stackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["stackId"])]
)
data class NotebookSheetEntity(
    @PrimaryKey val id: String,
    val stackId: String,
    val pageIndex: Int,
    val svgFilePath: String,
    val thumbnailPath: String? = null,
    val ocrStatus: ProcessingStatus = ProcessingStatus.PENDING,
    val ocrProgress: Int = 0,
    val ocrText: String? = null,
    val ocrError: String? = null,
    val exportStatus: ProcessingStatus = ProcessingStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}