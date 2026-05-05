package com.penpal.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChunkEntity::class,
        ExtractionJobEntity::class,
        ChatMessageEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
        NotebookEntity::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun extractionJobDao(): ExtractionJobDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun graphDao(): GraphDao
    abstract fun notebookDao(): NotebookDao

    companion object {
        @Volatile
        private var INSTANCE: PenpalDatabase? = null

        fun getInstance(context: android.content.Context): PenpalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    PenpalDatabase::class.java,
                    "penpal_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}