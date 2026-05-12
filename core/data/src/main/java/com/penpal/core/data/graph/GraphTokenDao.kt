package com.penpal.core.data.graph

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GraphTokenDao {
    @Query("SELECT * FROM graph_tokens ORDER BY frequency DESC")
    fun getAllTokens(): Flow<List<GraphTokenEntity>>

    @Query("SELECT * FROM graph_tokens WHERE token = :token")
    suspend fun getToken(token: String): GraphTokenEntity?

    @Query("SELECT * FROM graph_tokens WHERE type = :type ORDER BY frequency DESC")
    fun getTokensByType(type: String): Flow<List<GraphTokenEntity>>

    @Query("SELECT * FROM graph_tokens WHERE category = :category ORDER BY frequency DESC")
    fun getTokensByCategory(category: String): Flow<List<GraphTokenEntity>>

    @Query("SELECT * FROM graph_tokens WHERE frequency >= :minFrequency ORDER BY frequency DESC")
    fun getFrequentTokens(minFrequency: Int): Flow<List<GraphTokenEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(token: GraphTokenEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tokens: List<GraphTokenEntity>)

    @Query("UPDATE graph_tokens SET frequency = frequency + 1 WHERE token = :token")
    suspend fun incrementFrequency(token: String)

    @Query("DELETE FROM graph_tokens WHERE token = :token")
    suspend fun deleteToken(token: String)

    @Query("DELETE FROM graph_tokens")
    suspend fun deleteAll()
}
