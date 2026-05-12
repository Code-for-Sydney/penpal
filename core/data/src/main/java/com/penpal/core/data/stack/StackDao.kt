package com.penpal.core.data.stack

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StackDao {
    @Query("SELECT * FROM stacks ORDER BY updatedAt DESC")
    fun getAllStacks(): Flow<List<StackEntity>>

    @Query("SELECT * FROM stacks WHERE id = :id")
    suspend fun getStack(id: String): StackEntity?

    @Query("SELECT * FROM stacks WHERE id = :id")
    fun getStackFlow(id: String): Flow<StackEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stack: StackEntity)

    @Query("UPDATE stacks SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE stacks SET blocksJson = :blocksJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBlocks(id: String, blocksJson: String, updatedAt: Long)

    @Query("UPDATE stacks SET systemPrompt = :systemPrompt, agentPrompt = :agentPrompt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePrompts(id: String, systemPrompt: String, agentPrompt: String, updatedAt: Long)

    @Query("UPDATE stacks SET notebookId = :notebookId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun linkNotebook(id: String, notebookId: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM stacks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM stacks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM stacks")
    suspend fun getCount(): Int
}
