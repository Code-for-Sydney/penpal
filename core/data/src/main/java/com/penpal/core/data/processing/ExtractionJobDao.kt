package com.penpal.core.data.processing

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
