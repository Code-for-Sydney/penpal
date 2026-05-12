package com.penpal.core.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.penpal.core.data.chat.ChatMessageEntity
import com.penpal.core.data.chat.ChatConversationEntity
import com.penpal.core.data.chat.ChatMessageDao
import com.penpal.core.data.chat.ChatConversationDao
import com.penpal.core.data.stack.StackEntity
import com.penpal.core.data.stack.StackDao
import com.penpal.core.data.knowledge.ChunkEntity
import com.penpal.core.data.knowledge.ChunkDao
import com.penpal.core.data.processing.ExtractionJobEntity
import com.penpal.core.data.processing.ExtractionJobDao
import com.penpal.core.data.graph.GraphNodeEntity
import com.penpal.core.data.graph.GraphEdgeEntity
import com.penpal.core.data.graph.GraphDao
import com.penpal.core.data.graph.GraphTokenEntity
import com.penpal.core.data.graph.GraphTokenDao
import com.penpal.core.data.notebook.NotebookEntity
import com.penpal.core.data.notebook.NotebookDao
import com.penpal.core.data.notebook.sheet.NotebookSheetEntity
import com.penpal.core.data.notebook.sheet.NotebookSheetDao

@Database(
    entities = [
        ChunkEntity::class,
        ExtractionJobEntity::class,
        ChatMessageEntity::class,
        ChatConversationEntity::class,
        GraphNodeEntity::class,
        GraphEdgeEntity::class,
        StackEntity::class,
        GraphTokenEntity::class,
        NotebookEntity::class,
        NotebookSheetEntity::class,
    ],
    version = 9,
    exportSchema = false
)
abstract class PenpalDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun extractionJobDao(): ExtractionJobDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatConversationDao(): ChatConversationDao
    abstract fun graphDao(): GraphDao
    abstract fun graphTokenDao(): GraphTokenDao
    abstract fun stackDao(): StackDao
    abstract fun notebookDao(): NotebookDao
    abstract fun notebookSheetDao(): NotebookSheetDao

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