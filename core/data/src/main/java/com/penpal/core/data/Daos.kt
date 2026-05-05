package com.penpal.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
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
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

@Dao
interface GraphDao {
    @Query("SELECT * FROM graph_nodes")
    fun getAllNodes(): Flow<List<GraphNodeEntity>>

    @Query("SELECT * FROM graph_nodes WHERE notebookId = :notebookId")
    fun getNodesForNotebook(notebookId: String): Flow<List<GraphNodeEntity>>

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
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun getAllNotebooks(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getNotebook(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun getNotebookFlow(id: String): Flow<NotebookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE notebooks SET blocksJson = :blocksJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateBlocks(id: String, blocksJson: String, updatedAt: Long)

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM notebooks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM notebooks")
    suspend fun getCount(): Int
}