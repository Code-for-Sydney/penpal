package com.penpal.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ChunkEntity::class,
        ExtractionJobEntity::class,
        ChatMessageEntity::class,
        ChatConversationEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
        StackEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun extractionJobDao(): ExtractionJobDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatConversationDao(): ChatConversationDao
    abstract fun graphDao(): GraphDao
    abstract fun stackDao(): StackDao

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