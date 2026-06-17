package com.penpal.core.data.notebook

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun getAllNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getNotebookById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observeNotebookById(id: String): Flow<NotebookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notebook: NotebookEntity)

    @Update
    suspend fun update(notebook: NotebookEntity)

    @Delete
    suspend fun delete(notebook: NotebookEntity)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecentNotebooks(limit: Int): List<NotebookEntity>

    @Query("UPDATE notebooks SET lastDisplayedPage = :page, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateLastDisplayedPage(id: String, page: Int, updatedAt: Long = System.currentTimeMillis())
}