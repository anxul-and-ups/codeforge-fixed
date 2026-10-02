package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val rootPath: String,
    val fileCount: Int = 0,
    val activeConversationId: String? = null,
    val systemPrompt: String? = null,
    val githubOwnerRepo: String? = null,
    val githubBranch: String? = "main"
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sender: String, // "USER", "ASSISTANT", "SYSTEM"
    val content: String,
    val reasoning: String? = null,
    val toolCallsJson: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SUCCESS", // "SENDING", "STREAMING", "SUCCESS", "ERROR"
    val providerUsed: String? = null,
    val modelUsed: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["messageId"])]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long
)

@Entity(
    tableName = "tool_steps",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["messageId"])]
)
data class ToolStepEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val stepIndex: Int,
    val toolName: String,
    val argumentsJson: String,
    val resultText: String,
    val isError: Boolean = false,
    val status: String = "COMPLETED", // "RUNNING", "COMPLETED", "FAILED"
    val durationMs: Long = 0L
)

@Entity(tableName = "usage_records")
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val projectId: String,
    val conversationId: String,
    val providerName: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val estimatedCostUsd: Double = 0.0,
    val failoverOccurred: Boolean = false,
    val failoverReason: String? = null
)

@Entity(
    tableName = "checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val snapshotDirPath: String
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val apiFormat: String, // "ANTHROPIC", "OPENAI", "GEMINI"
    val encryptedApiKey: String,
    val modelsJson: String,
    val selectedModel: String,
    val priority: Int,
    val isEnabled: Boolean = true,
    val cooldownUntilTimestamp: Long = 0L,
    val lastError: String? = null,
    val customHeadersJson: String? = null,
    val supportsVision: Boolean = true,
    val supportsTools: Boolean = true
)
