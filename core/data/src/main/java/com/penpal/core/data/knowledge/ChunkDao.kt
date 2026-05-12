package com.penpal.core.data.knowledge

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Query("SELECT DISTINCT sourceId FROM chunks ORDER BY createdAt DESC")
    suspend fun getAllSourceIds(): List<String>

    @Query("SELECT * FROM chunks WHERE sourceId = :sourceId ORDER BY position")
    fun getChunksForSource(sourceId: String): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getAllPaged(limit: Int, offset: Int): Flow<List<ChunkEntity>>

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunks: List<ChunkEntity>)

    @Query("DELETE FROM chunks WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM chunks")
    suspend fun deleteAll()
}
