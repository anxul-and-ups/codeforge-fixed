package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val slug: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val rootPath: String,
    val fileCount: Int = 0,
    val activeConversationId: String? = null,
    val systemPrompt: String? = null,
    val githubOwnerRepo: String? = null,
    val githubBranch: String? = "main",
    val defaultChatModelRef: String? = null,
    val defaultBuildModelRef: String? = null,
    val permissionLevel: String = "ASK_RISKY", // "ASK_ALWAYS", "ASK_RISKY", "AUTO_PROJECT"
    val runCommand: String? = null,
    val testCommand: String? = null,
    val archived: Boolean = false
) {
    @get:androidx.room.Ignore
    val gitBranch: String get() = githubBranch ?: "main"
}

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
    val mode: String = "CHAT", // "CHAT" or "BUILD"
    val modelOverride: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val summaryText: String? = null,
    val summarizedUpToMessageId: String? = null
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
    val projectId: String = "scratch",
    val fileName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val extractedTextRef: String? = null,
    val createdAt: Long = System.currentTimeMillis()
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
    val chatId: String = "",
    val runId: String = "",
    val stepIndex: Int,
    val type: String = "TOOL", // "TOOL", "NOTE", "PLAN", "GUARD", "SYSTEM", "COMMAND"
    val toolName: String,
    val argumentsJson: String,
    val resultText: String,
    val isError: Boolean = false,
    val status: String = "COMPLETED", // "RUNNING", "COMPLETED", "FAILED"
    val durationMs: Long = 0L,
    val filePath: String? = null,
    val diffSummary: String? = null, // e.g. "+8 -2"
    val commandText: String? = null,
    val cwdText: String? = null,
    val outputRef: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "usage_records")
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val projectId: String,
    val conversationId: String,
    val keyId: String = "",
    val providerName: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val estimatedCostUsd: Double = 0.0,
    val failoverOccurred: Boolean = false,
    val failoverReason: String? = null,
    val errorClass: String? = null
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
    val runId: String = "",
    val ref: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val snapshotDirPath: String,
    val filesChangedJson: String = "[]"
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val apiFormat: String, // "ANTHROPIC", "OPENAI", "GEMINI", "CUSTOM"
    val encryptedApiKey: String,
    val keyLabel: String = "Primary Key",
    val modelsJson: String = "[]",
    val selectedModel: String = "",
    val priority: Int,
    val isEnabled: Boolean = true,
    val cooldownUntilTimestamp: Long = 0L,
    val lastError: String? = null,
    val lastErrorClass: String? = null, // RATE_LIMIT, QUOTA_EXHAUSTED, AUTH, etc.
    val lastTestedAt: Long = 0L,
    val lastTestStatus: String = "UNTESTED", // "WORKING", "FAILED", "UNTESTED", "INVALID"
    val lastLatencyMs: Long = 0L,
    val customHeadersJson: String? = null,
    val supportsVision: Boolean = true,
    val supportsTools: Boolean = true
)

@Entity(
    tableName = "api_keys",
    indices = [Index(value = ["providerId"])]
)
data class ApiKeyEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val label: String,
    val secretRef: String, // Encrypted via KeyStoreManager
    val status: String = "UNTESTED", // "WORKING", "FAILED", "INVALID", "COOLDOWN", "EXHAUSTED"
    val lastTestedAt: Long = 0L,
    val lastTestResult: String? = null,
    val lastLatencyMs: Long = 0L,
    val cooldownUntil: Long = 0L,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "model_cache",
    indices = [Index(value = ["providerId"]), Index(value = ["modelId"])]
)
data class ModelCacheEntity(
    @PrimaryKey val id: String, // "$providerId::$modelId"
    val providerId: String,
    val keyId: String = "",
    val modelId: String,
    val displayName: String,
    val contextWindow: Int = 128000,
    val maxOutput: Int = 8192,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val pricingJson: String = "{}",
    val fetchedAt: Long = System.currentTimeMillis(),
    val hidden: Boolean = false,
    val favorite: Boolean = false,
    val lastUsedAt: Long = 0L
) {
    @get:androidx.room.Ignore
    val isFavorite: Boolean get() = favorite
}

@Entity(tableName = "health_states", primaryKeys = ["keyId", "modelId"])
data class HealthStateEntity(
    val keyId: String,
    val modelId: String,
    val state: String = "HEALTHY", // "HEALTHY", "COOLDOWN", "EXHAUSTED", "INVALID"
    val untilTimestamp: Long = 0L,
    val successRate: Double = 1.0,
    val avgLatencyMs: Long = 0L,
    val lastErrorClass: String? = null,
    val lastErrorMessage: String? = null
)

@Entity(tableName = "installed_tools")
data class InstalledToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val repoUrl: String,
    val path: String,
    val version: String = "1.0.0",
    val type: String = "CLI", // "MCP" or "CLI"
    val description: String = "",
    val exposedToolsJson: String = "[]", // JSON array of tool names/schemas
    val commandTemplate: String = "",
    val trustLevel: String = "UNVERIFIED", // "VERIFIED", "UNVERIFIED"
    val enabled: Boolean = true,
    val status: String = "READY", // "RUNNING", "STOPPED", "READY", "ERROR"
    val sizeBytes: Long = 0L,
    val installedAt: Long = System.currentTimeMillis()
) {
    @get:androidx.room.Ignore
    val entryCommand: String get() = commandTemplate.ifBlank { name }
    @get:androidx.room.Ignore
    val installPath: String get() = path
    @get:androidx.room.Ignore
    val lastVerifyStatus: String get() = status
}
