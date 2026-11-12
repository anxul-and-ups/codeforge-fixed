package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.CheckpointDao
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.InstalledToolDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.dao.ProjectDao
import com.example.data.local.dao.ProviderDao
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.ApiKeyEntity
import com.example.data.local.entity.AttachmentEntity
import com.example.data.local.entity.CheckpointEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.HealthStateEntity
import com.example.data.local.entity.InstalledToolEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ModelCacheEntity
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
        ProviderConfigEntity::class,
        ApiKeyEntity::class,
        ModelCacheEntity::class,
        HealthStateEntity::class,
        InstalledToolEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun usageDao(): UsageDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun providerDao(): ProviderDao
    abstract fun installedToolDao(): InstalledToolDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safe non-destructive migration from v1 to v2 (Section 0 Rule 7)
                db.execSQL("ALTER TABLE projects ADD COLUMN slug TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE projects ADD COLUMN defaultChatModelRef TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN defaultBuildModelRef TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN permissionLevel TEXT NOT NULL DEFAULT 'ASK_RISKY'")
                db.execSQL("ALTER TABLE projects ADD COLUMN runCommand TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN testCommand TEXT")
                db.execSQL("ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'CHAT'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN modelOverride TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryText TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summarizedUpToMessageId TEXT")

                db.execSQL("ALTER TABLE attachments ADD COLUMN projectId TEXT NOT NULL DEFAULT 'scratch'")
                db.execSQL("ALTER TABLE attachments ADD COLUMN extractedTextRef TEXT")
                db.execSQL("ALTER TABLE attachments ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE tool_steps ADD COLUMN chatId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN runId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN type TEXT NOT NULL DEFAULT 'TOOL'")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN filePath TEXT")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN diffSummary TEXT")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN commandText TEXT")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN cwdText TEXT")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN outputRef TEXT")
                db.execSQL("ALTER TABLE tool_steps ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE usage_records ADD COLUMN keyId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE usage_records ADD COLUMN errorClass TEXT")

                db.execSQL("ALTER TABLE checkpoints ADD COLUMN runId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE checkpoints ADD COLUMN ref TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE checkpoints ADD COLUMN filesChangedJson TEXT NOT NULL DEFAULT '[]'")

                db.execSQL("ALTER TABLE provider_configs ADD COLUMN keyLabel TEXT NOT NULL DEFAULT 'Primary Key'")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN lastErrorClass TEXT")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN lastTestedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN lastTestStatus TEXT NOT NULL DEFAULT 'UNTESTED'")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN lastLatencyMs INTEGER NOT NULL DEFAULT 0")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS api_keys (
                        id TEXT NOT NULL PRIMARY KEY,
                        providerId TEXT NOT NULL,
                        label TEXT NOT NULL,
                        secretRef TEXT NOT NULL,
                        status TEXT NOT NULL,
                        lastTestedAt INTEGER NOT NULL,
                        lastTestResult TEXT,
                        lastLatencyMs INTEGER NOT NULL,
                        cooldownUntil INTEGER NOT NULL,
                        enabled INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_api_keys_providerId ON api_keys(providerId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS model_cache (
                        id TEXT NOT NULL PRIMARY KEY,
                        providerId TEXT NOT NULL,
                        keyId TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        contextWindow INTEGER NOT NULL,
                        maxOutput INTEGER NOT NULL,
                        supportsTools INTEGER NOT NULL,
                        supportsVision INTEGER NOT NULL,
                        supportsStreaming INTEGER NOT NULL,
                        inputPricePerMillion REAL NOT NULL,
                        outputPricePerMillion REAL NOT NULL,
                        pricingJson TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        hidden INTEGER NOT NULL,
                        favorite INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_model_cache_providerId ON model_cache(providerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_model_cache_modelId ON model_cache(modelId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS health_states (
                        keyId TEXT NOT NULL,
                        modelId TEXT NOT NULL,
                        state TEXT NOT NULL,
                        untilTimestamp INTEGER NOT NULL,
                        successRate REAL NOT NULL,
                        avgLatencyMs INTEGER NOT NULL,
                        lastErrorClass TEXT,
                        lastErrorMessage TEXT,
                        PRIMARY KEY(keyId, modelId)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS installed_tools (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        repoUrl TEXT NOT NULL,
                        path TEXT NOT NULL,
                        version TEXT NOT NULL,
                        type TEXT NOT NULL,
                        description TEXT NOT NULL,
                        exposedToolsJson TEXT NOT NULL,
                        commandTemplate TEXT NOT NULL,
                        trustLevel TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        installedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "codeforge_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
