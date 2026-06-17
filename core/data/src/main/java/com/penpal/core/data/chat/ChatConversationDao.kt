package com.penpal.core.data.chat

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatConversationDao {
    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ChatConversationEntity>>

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun getConversation(id: String): ChatConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ChatConversationEntity)

    @Query("UPDATE chat_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET stackIdsJson = :stackIdsJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStackIds(id: String, stackIdsJson: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET systemPrompt = :systemPrompt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSystemPrompt(id: String, systemPrompt: String, updatedAt: Long)

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun delete(id: String)
}
