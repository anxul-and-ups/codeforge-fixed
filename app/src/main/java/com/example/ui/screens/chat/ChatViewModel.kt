package com.example.ui.screens.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CodeForgeApp
import com.example.data.agent.AgentEvent
import com.example.data.agent.ApprovalRequest
import com.example.data.agent.RunRequest
import com.example.data.api.LlmDocument
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ProviderConfigEntity
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.zip.ZipInputStream
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.security.KeyStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ChatUiState(
    val activeProject: ProjectEntity? = null,
    val activeConversation: ConversationEntity? = null,
    val isAgentRunning: Boolean = false,
    val currentAgentStatus: String = "",
    val streamingContent: String = "",
    val streamingReasoning: String = "",
    val failoverNotice: String? = null,
    val pendingAttachments: List<AttachmentItem> = emptyList(),
    val warningSecretFile: String? = null,
    val approval: ApprovalRequest? = null
)

data class AttachmentItem(
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    val base64Data: String? = null,
    val textContent: String? = null,
    val isZip: Boolean = false,
    val pdfBase64: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CodeForgeApp
    private val projectRepo = app.projectRepository
    private val chatRepo = app.chatRepository
    private val settings = app.settingsStore
    private val agentEngine = app.agentEngine

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val activeProjectId = MutableStateFlow<String?>(null)
    private val activeConversationId = MutableStateFlow<String?>(null)
    private val refreshTick = MutableStateFlow(0)
    private var creatingDefaultProject = false

    /** All chats of all projects, so history never "disappears" when another project is imported. */
    val conversations: StateFlow<List<ConversationEntity>> = chatRepo.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projectNames: StateFlow<Map<String, String>> = projectRepo.allProjects
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val providers: StateFlow<List<ProviderConfigEntity>> = app.providerRepository.allProviders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _preferredProviderId = MutableStateFlow(settings.preferredProviderId)
    val preferredProviderId: StateFlow<String?> = _preferredProviderId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = activeConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepo.getMessages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val toolStepsMap: StateFlow<Map<String, List<ToolStepEntity>>> = combine(messages, refreshTick) { msgs, _ -> msgs }
        .mapLatest { msgs ->
            val map = mutableMapOf<String, List<ToolStepEntity>>()
            for (m in msgs) {
                if (m.sender != "ASSISTANT") continue
                val steps = chatRepo.getToolStepsOnce(m.id)
                if (steps.isNotEmpty()) map[m.id] = steps
            }
            map.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // Live agent events (streaming text, status, failover notices)
        viewModelScope.launch {
            agentEngine.agentEvents.collect { event ->
                when (event) {
                    is AgentEvent.StatusUpdate -> _uiState.update { it.copy(currentAgentStatus = event.statusText) }
                    is AgentEvent.StreamingChunk -> _uiState.update { it.copy(streamingContent = it.streamingContent + event.text) }
                    is AgentEvent.NoteAdded -> refreshTick.update { it + 1 }
                    is AgentEvent.StreamingReset -> _uiState.update { it.copy(streamingContent = "", streamingReasoning = "") }
                    is AgentEvent.ReasoningChunk -> _uiState.update { it.copy(streamingReasoning = it.streamingReasoning + event.text) }
                    is AgentEvent.FailoverNotice -> _uiState.update {
                        it.copy(failoverNotice = "Switched to ${event.toProvider} (${event.reason.take(100)})")
                    }
                    is AgentEvent.ToolStarted, is AgentEvent.ToolFinished -> refreshTick.update { it + 1 }
                    is AgentEvent.Finished -> {
                        _uiState.update { it.copy(streamingContent = "", streamingReasoning = "") }
                        refreshTick.update { it + 1 }
                    }
                    is AgentEvent.Error -> {
                        _uiState.update {
                            it.copy(streamingContent = "", streamingReasoning = "", failoverNotice = "⚠️ ${event.error.take(200)}")
                        }
                        refreshTick.update { it + 1 }
                    }
                }
            }
        }

        // Engine state (survives screen rotation / activity recreation)
        viewModelScope.launch {
            combine(agentEngine.isRunning, agentEngine.runningConversationId, activeConversationId) { running, runConv, activeConv ->
                running && runConv != null && runConv == activeConv
            }.collect { running ->
                _uiState.update {
                    if (running) it.copy(isAgentRunning = true)
                    else it.copy(isAgentRunning = false, currentAgentStatus = "", streamingContent = "", streamingReasoning = "")
                }
            }
        }
        viewModelScope.launch {
            agentEngine.approvalRequest.collect { req -> _uiState.update { it.copy(approval = req) } }
        }

        // Keep the active conversation entity (title etc.) in sync
        viewModelScope.launch {
            activeConversationId
                .flatMapLatest { id -> if (id == null) flowOf(null) else chatRepo.observeConversation(id) }
                .collect { conv -> _uiState.update { it.copy(activeConversation = conv) } }
        }

        // Project selection (restores the last used project; no race with an initial empty list)
        viewModelScope.launch {
            projectRepo.allProjects.collect { projects ->
                val current = _uiState.value.activeProject
                if (projects.isEmpty()) {
                    if (!creatingDefaultProject) {
                        creatingDefaultProject = true
                        try {
                            val p = projectRepo.createProject("CodeForge App", "Android Starter")
                            selectProject(p)
                        } finally {
                            creatingDefaultProject = false
                        }
                    }
                } else if (current == null) {
                    // App start: open the last project with a NEW empty chat. Old chats live in the side drawer.
                    val project = projects.firstOrNull { it.id == settings.activeProjectId } ?: projects.first()
                    switchProject(project, null, startFresh = true)
                } else {
                    val fresh = projects.firstOrNull { it.id == current.id }
                    if (fresh == null) selectProject(projects.first())
                    else if (fresh != current) _uiState.update { it.copy(activeProject = fresh) }
                }
            }
        }
    }

    fun selectProject(project: ProjectEntity) {
        switchProject(project, null)
    }

    private fun setActiveConversation(id: String?) {
        activeConversationId.value = id
        settings.activeConversationId = id
    }

    /** Opens [project]. If [conversationId] is given that chat stays open, otherwise the latest chat is opened. */
    private fun switchProject(project: ProjectEntity, conversationId: String?, startFresh: Boolean = false) {
        settings.activeProjectId = project.id
        activeProjectId.value = project.id
        _uiState.update {
            it.copy(activeProject = project, streamingContent = "", streamingReasoning = "")
        }
        viewModelScope.launch {
            val existing = if (conversationId != null) chatRepo.getConversation(conversationId) else null
            val conv = if (existing != null && existing.projectId == project.id) {
                existing
            } else if (startFresh) {
                // reuse the newest chat only if it is still empty, otherwise start a new one
                val latest = chatRepo.getConversations(project.id).first().maxByOrNull { it.updatedAt }
                val latestEmpty = latest != null &&
                    app.database.messageDao().getMessagesForConversationOnce(latest.id).isEmpty()
                if (latestEmpty && latest != null) latest else chatRepo.createConversation(project.id, "New Chat")
            } else {
                chatRepo.getConversations(project.id).first().firstOrNull()
                    ?: chatRepo.createConversation(project.id, "New Chat")
            }
            setActiveConversation(conv.id)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Model picker
    // ---------------------------------------------------------------------------------------

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            val p = providers.value.firstOrNull { it.id == providerId } ?: return@launch
            app.providerRepository.updateProvider(p.copy(selectedModel = model, isEnabled = true))
            settings.preferredProviderId = providerId
            _preferredProviderId.value = providerId
        }
    }

    // ---------------------------------------------------------------------------------------
    // Conversation history
    // ---------------------------------------------------------------------------------------

    fun selectConversation(conversation: ConversationEntity) {
        if (agentEngine.isRunning.value) {
            _uiState.update { it.copy(failoverNotice = "The agent is working. Press Stop before switching chats.") }
            return
        }
        _uiState.update { it.copy(streamingContent = "", streamingReasoning = "") }
        val current = _uiState.value.activeProject
        if (current != null && current.id == conversation.projectId) {
            setActiveConversation(conversation.id)
        } else {
            viewModelScope.launch {
                val project = projectRepo.getProject(conversation.projectId)
                if (project != null) {
                    switchProject(project, conversation.id)
                } else {
                    setActiveConversation(conversation.id)
                }
            }
        }
    }

    fun newConversation() {
        val proj = _uiState.value.activeProject ?: return
        if (agentEngine.isRunning.value) return
        viewModelScope.launch {
            val currentId = activeConversationId.value
            val currentEmpty = currentId != null &&
                app.database.messageDao().getMessagesForConversationOnce(currentId).isEmpty()
            if (currentEmpty) return@launch // already looking at an empty chat
            val conv = chatRepo.createConversation(proj.id, "New Chat")
            setActiveConversation(conv.id)
        }
    }

    /** Creates a new blank Android starter project and opens a fresh chat in it. */
    fun newStarterProject() {
        if (agentEngine.isRunning.value) return
        viewModelScope.launch {
            try {
                val p = projectRepo.createProject("New App", "Android Starter")
                switchProject(p, null, startFresh = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "Could not create the project: ${e.message}") }
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { chatRepo.updateConversationTitle(id, title.trim().take(80)) }
    }

    fun togglePin(id: String) {
        viewModelScope.launch { chatRepo.togglePin(id) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            val wasActive = activeConversationId.value == id
            chatRepo.deleteConversation(id)
            if (wasActive) {
                val proj = _uiState.value.activeProject
                if (proj != null) {
                    val remaining = chatRepo.getConversations(proj.id).first()
                    val next = remaining.firstOrNull() ?: chatRepo.createConversation(proj.id, "New Chat")
                    setActiveConversation(next.id)
                } else {
                    setActiveConversation(null)
                }
            }
        }
    }

    suspend fun exportConversationMarkdown(id: String): String = chatRepo.exportConversationAsMarkdown(id)

    // ---------------------------------------------------------------------------------------
    // Attachments
    // ---------------------------------------------------------------------------------------

    /** Imports a ZIP and moves the CURRENT chat into the new project (the chat stays open). */
    private suspend fun importZipKeepingChat(uri: Uri, displayName: String): ProjectEntity {
        val project = projectRepo.importProjectFromZip(
            displayName.removeSuffix(".zip").removeSuffix(".ZIP").ifBlank { "Imported Project" }, uri
        )
        val conv = _uiState.value.activeConversation
        if (conv != null) {
            chatRepo.moveConversationToProject(conv.id, project.id)
            switchProject(project, conv.id)
        } else {
            switchProject(project, null)
        }
        return project
    }

    fun addAttachment(uri: Uri, name: String, isImage: Boolean) {
        val resolver = getApplication<Application>().contentResolver
        val resolverType = try { resolver.getType(uri) } catch (e: Exception) { null }
        val displayName = projectRepo.queryDisplayName(uri) ?: name
        val lower = displayName.lowercase()
        val isZip = !isImage && (lower.endsWith(".zip") || resolverType == "application/zip" ||
            resolverType == "application/x-zip-compressed")
        val isPdf = !isImage && (lower.endsWith(".pdf") || resolverType == "application/pdf")

        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                when {
                    isImage -> {
                        val b64 = encodeImage(uri)
                        if (b64 == null) null else AttachmentItem(uri, displayName, true, base64Data = b64)
                    }
                    isZip -> AttachmentItem(uri, displayName, false, isZip = true)
                    isPdf -> {
                        val bytes = try {
                            resolver.openInputStream(uri)?.use { it.readBytes() }
                        } catch (e: Throwable) {
                            null
                        }
                        if (bytes == null || bytes.size > 15 * 1024 * 1024) null
                        else AttachmentItem(
                            uri, displayName, false,
                            pdfBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        )
                    }
                    else -> {
                        val text = readTextAttachment(uri, lower)
                        if (text == null) null else AttachmentItem(uri, displayName, false, textContent = text)
                    }
                }
            }
            if (item == null) {
                val hint = if (isPdf) {
                    "This PDF is too large (max 15 MB) or could not be read."
                } else {
                    "Could not attach \"$displayName\". Supported: images, PDF, text/code files, .docx and .zip (project)."
                }
                _uiState.update { it.copy(failoverNotice = hint) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    pendingAttachments = it.pendingAttachments + item,
                    warningSecretFile = if (KeyStoreManager.isPotentialSecretFile(displayName)) displayName else it.warningSecretFile
                )
            }
        }
    }

    fun dismissSecretWarning() {
        _uiState.update { it.copy(warningSecretFile = null) }
    }

    fun removeAttachment(item: AttachmentItem) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - item) }
    }

    private fun readTextAttachment(uri: Uri, lowerName: String): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            if (lowerName.endsWith(".docx")) {
                return resolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        var result: String? = null
                        while (entry != null) {
                            if (entry.name == "word/document.xml") {
                                val xml = zis.readBytes().toString(Charsets.UTF_8)
                                result = xml
                                    .replace("</w:p>", "\n")
                                    .replace("<w:tab/>", "\t")
                                    .replace("<w:br/>", "\n")
                                    .replace(Regex("<[^>]+>"), "")
                                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                                    .replace("&quot;", "\"").replace("&apos;", "'")
                                    .take(200_000)
                                break
                            }
                            entry = zis.nextEntry
                        }
                        result
                    }
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(200_000)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                buf.copyOf(total)
            } ?: return null
            if (bytes.any { it == 0.toByte() }) return null // binary
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeImage(uri: Uri): String? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            // Respect EXIF rotation (camera photos)
            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                android.media.ExifInterface.ORIENTATION_NORMAL
            }
            val degrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val m = Matrix().apply { postRotate(degrees) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }

            val maxDim = 1568
            if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val w = if (ratio >= 1f) maxDim else (maxDim * ratio).toInt().coerceAtLeast(1)
                val h = if (ratio >= 1f) (maxDim / ratio).toInt().coerceAtLeast(1) else maxDim
                bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sending / control
    // ---------------------------------------------------------------------------------------

    fun sendMessage(promptText: String) {
        val proj = _uiState.value.activeProject ?: return
        val conv = _uiState.value.activeConversation ?: return
        val attachments = _uiState.value.pendingAttachments
        if (promptText.isBlank() && attachments.isEmpty()) return
        if (agentEngine.isRunning.value) {
            _uiState.update { it.copy(failoverNotice = "The agent is still working. Wait for it or press Stop.") }
            return
        }

        val images = attachments.filter { it.isImage }.mapNotNull { it.base64Data }
        val files = attachments.filter { it.textContent != null }
        val pdfs = attachments.filter { it.pdfBase64 != null }
        val zip = attachments.firstOrNull { it.isZip }

        val display = buildString {
            for (a in attachments) {
                append("📎 ").append(a.name).append('\n')
            }
            append(promptText.trim())
        }.trim()
        val prompt = buildString {
            val typed = promptText.trim()
            if (typed.isNotEmpty()) {
                append(typed)
            } else if (zip != null) {
                append("I uploaded the project ZIP \"${zip.name}\". Explore it, summarize what it contains, and ask me what to do next.")
            } else {
                append("Please look at the attached file(s)/image(s) and help.")
            }
            for (f in files) {
                append("\n\n[Attached file: ${f.name}]\n```\n${f.textContent}\n```")
            }
        }
        val docs = pdfs.map { LlmDocument(it.name, "application/pdf", it.pdfBase64 ?: "") }

        _uiState.update {
            it.copy(
                pendingAttachments = emptyList(),
                streamingContent = "",
                streamingReasoning = "",
                failoverNotice = null,
                currentAgentStatus = "Starting…"
            )
        }
        viewModelScope.launch {
            var projectId = proj.id
            if (zip != null) {
                _uiState.update { it.copy(currentAgentStatus = "Importing ${zip.name}…") }
                try {
                    projectId = importZipKeepingChat(zip.uri, zip.name).id
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _uiState.update { it.copy(failoverNotice = "⚠️ ZIP import failed: ${e.message ?: e.javaClass.simpleName}".take(220)) }
                    return@launch
                }
            }
            chatRepo.addUserMessage(conv.id, display)
            agentEngine.start(RunRequest(projectId, conv.id, prompt, images, docs))
        }
    }

    /** Push the current project to GitHub, wait for the Actions build and auto-fix compile errors. */
    fun pushAndBuild() {
        val proj = _uiState.value.activeProject ?: return
        val conv = _uiState.value.activeConversation ?: return
        if (agentEngine.isRunning.value) return
        agentEngine.start(RunRequest(proj.id, conv.id, "", pushOnly = true))
    }

    fun stopAgent() {
        agentEngine.stop()
    }

    fun resolveApproval(approved: Boolean) {
        agentEngine.resolveApproval(approved)
    }

    fun retryLastMessage() {
        val lastUserMsg = messages.value.lastOrNull { it.sender == "USER" }
        if (lastUserMsg != null) sendMessage(lastUserMsg.content)
    }

    fun dismissFailoverNotice() {
        _uiState.update { it.copy(failoverNotice = null) }
    }
}
