package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.CheckpointDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.dao.ProjectDao
import com.example.data.local.dao.ProviderDao
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.AttachmentEntity
import com.example.data.local.entity.CheckpointEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.local.entity.UsageRecordEntity

@Database(
    entities = [
        ProjectEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ToolStepEntity::class,
        UsageRecordEntity::class,
        CheckpointEntity::class,
        ProviderConfigEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun usageDao(): UsageDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun providerDao(): ProviderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "codeforge_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
