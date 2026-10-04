package com.example.data.repository

import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.entity.AttachmentEntity
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ToolStepEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    fun getConversations(projectId: String): Flow<List<ConversationEntity>> =
        conversationDao.getConversationsForProject(projectId)

    suspend fun getConversation(id: String): ConversationEntity? =
        conversationDao.getConversationById(id)

    suspend fun createConversation(projectId: String, title: String = "New Chat"): ConversationEntity = withContext(Dispatchers.IO) {
        val entity = ConversationEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            title = title,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        conversationDao.insertConversation(entity)
        entity
    }

    suspend fun updateConversationTitle(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        val conv = conversationDao.getConversationById(id) ?: return@withContext
        conversationDao.updateConversation(conv.copy(title = newTitle, updatedAt = System.currentTimeMillis()))
    }

    suspend fun togglePin(id: String) = withContext(Dispatchers.IO) {
        val conv = conversationDao.getConversationById(id) ?: return@withContext
        conversationDao.updateConversation(conv.copy(isPinned = !conv.isPinned))
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        messageDao.deleteMessagesForConversation(id)
        conversationDao.deleteConversation(id)
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    fun getToolSteps(messageId: String): Flow<List<ToolStepEntity>> =
        messageDao.getToolStepsForMessage(messageId)

    suspend fun getToolStepsOnce(messageId: String): List<ToolStepEntity> =
        messageDao.getToolStepsForMessageOnce(messageId)

    fun getAllConversations() = conversationDao.getAllConversations()

    fun observeConversation(id: String) = conversationDao.observeConversation(id)

    suspend fun moveConversationToProject(id: String, projectId: String) {
        val c = conversationDao.getConversationById(id) ?: return
        conversationDao.updateConversation(c.copy(projectId = projectId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addUserMessage(
        conversationId: String,
        content: String,
        attachments: List<AttachmentEntity> = emptyList()
    ): MessageEntity = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            sender = "USER",
            content = content,
            timestamp = System.currentTimeMillis(),
            status = "SUCCESS"
        )
        messageDao.insertMessage(entity)
        for (att in attachments) {
            messageDao.insertAttachment(att.copy(messageId = messageId))
        }

        // Auto update conversation title if it's default
        val conv = conversationDao.getConversationById(conversationId)
        if (conv != null && (conv.title == "New Chat" || conv.title.startsWith("Chat #"))) {
            val autoTitle = content.take(30).trim() + if (content.length > 30) "…" else ""
            conversationDao.updateConversation(conv.copy(title = autoTitle, updatedAt = System.currentTimeMillis()))
        }
        entity
    }

    suspend fun exportConversationAsMarkdown(conversationId: String): String = withContext(Dispatchers.IO) {
        val conv = conversationDao.getConversationById(conversationId) ?: return@withContext ""
        val sb = StringBuilder()
        sb.appendLine("# ${conv.title}")
        sb.appendLine("*Exported from CodeForge on Android*\n")

        val msgs = messageDao.getMessagesForConversationOnce(conversationId)
        for (m in msgs) {
            sb.appendLine("### ${m.sender} (${m.status})")
            if (!m.reasoning.isNullOrBlank()) {
                sb.appendLine("> *Thinking:* ${m.reasoning}\n")
            }
            sb.appendLine(m.content)
            sb.appendLine("\n---\n")
        }
        sb.toString()
    }
}
