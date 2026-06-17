package com.penpal.core.data.notebook.sheet

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookSheetDao {
    @Query("SELECT * FROM notebook_sheets WHERE stackId = :stackId ORDER BY pageIndex ASC")
    fun getSheetsForStack(stackId: String): Flow<List<NotebookSheetEntity>>

    @Query("SELECT * FROM notebook_sheets WHERE stackId = :stackId ORDER BY pageIndex ASC")
    suspend fun getSheetsForStackSync(stackId: String): List<NotebookSheetEntity>

    @Query("SELECT * FROM notebook_sheets WHERE id = :id")
    suspend fun getSheetById(id: String): NotebookSheetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sheet: NotebookSheetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sheets: List<NotebookSheetEntity>)

    @Update
    suspend fun update(sheet: NotebookSheetEntity)

    @Delete
    suspend fun delete(sheet: NotebookSheetEntity)

    @Query("DELETE FROM notebook_sheets WHERE stackId = :stackId")
    suspend fun deleteForStack(stackId: String)

    @Query("UPDATE notebook_sheets SET ocrStatus = :status, ocrProgress = :progress WHERE id = :id")
    suspend fun updateOcrProgress(id: String, status: ProcessingStatus, progress: Int)

    @Query("UPDATE notebook_sheets SET ocrStatus = :status, ocrText = :text, ocrError = :error WHERE id = :id")
    suspend fun updateOcrResult(id: String, status: ProcessingStatus, text: String?, error: String?)

    @Query("UPDATE notebook_sheets SET exportStatus = :status WHERE id = :id")
    suspend fun updateExportStatus(id: String, status: ProcessingStatus)

    @Query("SELECT COUNT(*) FROM notebook_sheets WHERE stackId = :stackId")
    suspend fun getSheetCount(stackId: String): Int

    @Query("SELECT COUNT(*) FROM notebook_sheets WHERE stackId = :stackId AND ocrStatus = :status")
    suspend fun getSheetCountByStatus(stackId: String, status: ProcessingStatus): Int

    @Query("SELECT AVG(ocrProgress) FROM notebook_sheets WHERE stackId = :stackId")
    suspend fun getAverageOcrProgress(stackId: String): Float?
}