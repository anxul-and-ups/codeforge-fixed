package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.entity.AttachmentEntity
import com.example.data.local.entity.CheckpointEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.local.entity.UsageRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun getAllProjectsOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE projectId = :projectId ORDER BY isPinned DESC, updatedAt DESC")
    fun getConversationsForProject(projectId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolStep(toolStep: ToolStepEntity)

    @Query("SELECT * FROM tool_steps WHERE messageId = :messageId ORDER BY stepIndex ASC")
    fun getToolStepsForMessage(messageId: String): Flow<List<ToolStepEntity>>

    @Query("SELECT * FROM tool_steps WHERE messageId = :messageId ORDER BY stepIndex ASC")
    suspend fun getToolStepsForMessageOnce(messageId: String): List<ToolStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    fun getAttachmentsForMessage(messageId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun getAttachmentsForMessageOnce(messageId: String): List<AttachmentEntity>
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_records ORDER BY timestamp DESC")
    fun getAllUsageRecords(): Flow<List<UsageRecordEntity>>

    @Query("SELECT * FROM usage_records WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    fun getUsageRecordsSince(sinceTimestamp: Long): Flow<List<UsageRecordEntity>>

    @Query("SELECT * FROM usage_records WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getUsageRecordsForProject(projectId: String): Flow<List<UsageRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageRecord(record: UsageRecordEntity)

    @Query("SELECT COALESCE(SUM(estimatedCostUsd), 0.0) FROM usage_records WHERE timestamp >= :sinceTimestamp")
    suspend fun getCostSince(sinceTimestamp: Long): Double

    @Query("DELETE FROM usage_records")
    suspend fun clearUsageRecords()
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE projectId = :projectId ORDER BY timestamp DESC")
    fun getCheckpointsForProject(projectId: String): Flow<List<CheckpointEntity>>

    @Query("SELECT * FROM checkpoints WHERE projectId = :projectId ORDER BY timestamp DESC")
    suspend fun getCheckpointsForProjectOnce(projectId: String): List<CheckpointEntity>

    @Query("SELECT * FROM checkpoints WHERE id = :id LIMIT 1")
    suspend fun getCheckpointById(id: String): CheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Query("DELETE FROM checkpoints WHERE id = :id")
    suspend fun deleteCheckpoint(id: String)

    @Query("DELETE FROM checkpoints WHERE projectId = :projectId")
    suspend fun deleteCheckpointsForProject(projectId: String)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider_configs ORDER BY priority ASC")
    fun getAllProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs ORDER BY priority ASC")
    suspend fun getAllProvidersOnce(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE isEnabled = 1 ORDER BY priority ASC")
    fun getEnabledProviders(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs WHERE isEnabled = 1 ORDER BY priority ASC")
    suspend fun getEnabledProvidersOnce(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE id = :id LIMIT 1")
    suspend fun getProviderById(id: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: ProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProviders(providers: List<ProviderConfigEntity>)

    @Update
    suspend fun updateProvider(provider: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE id = :id")
    suspend fun deleteProvider(id: String)
}
