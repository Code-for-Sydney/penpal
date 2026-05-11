package com.penpal.core.data

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

@Dao
interface ExtractionJobDao {
    @Query("SELECT * FROM extraction_jobs ORDER BY createdAt DESC")
    fun getAllJobs(): Flow<List<ExtractionJobEntity>>

    @Query("SELECT * FROM extraction_jobs WHERE id = :id")
    suspend fun getJob(id: String): ExtractionJobEntity?

    @Query("SELECT * FROM extraction_jobs WHERE status = :status ORDER BY createdAt")
    fun getJobsByStatus(status: String): Flow<List<ExtractionJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: ExtractionJobEntity)

    @Query("UPDATE extraction_jobs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE extraction_jobs SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Int)

    @Query("UPDATE extraction_jobs SET workerId = :workerId WHERE id = :id")
    suspend fun updateWorkerId(id: String, workerId: String?)

    @Query("DELETE FROM extraction_jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

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

@Dao
interface GraphDao {
    @Query("SELECT * FROM graph_nodes")
    fun getAllNodes(): Flow<List<GraphNodeEntity>>

    @Query("SELECT * FROM graph_nodes WHERE stackId = :stackId")
    fun getNodesForStack(stackId: String): Flow<List<GraphNodeEntity>>

    @Query("SELECT * FROM graph_edges")
    fun getAllEdges(): Flow<List<GraphEdgeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: GraphNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: GraphEdgeEntity)

    @Query("DELETE FROM graph_nodes WHERE id = :id")
    suspend fun deleteNode(id: String)

    @Query("DELETE FROM graph_edges WHERE fromId = :fromId AND toId = :toId")
    suspend fun deleteEdge(fromId: String, toId: String)
}

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
    @Query("DELETE FROM stacks WHERE id = :id")
    suspend fun delete(id: String)
    @Query("DELETE FROM stacks")
    suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM stacks")
    suspend fun getCount(): Int
}