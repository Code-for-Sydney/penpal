package com.penpal.core.data.graph

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
