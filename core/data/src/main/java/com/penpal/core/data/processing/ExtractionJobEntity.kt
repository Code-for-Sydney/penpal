package com.penpal.core.data.processing

import androidx.room.Entity
import androidx.room.PrimaryKey

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
