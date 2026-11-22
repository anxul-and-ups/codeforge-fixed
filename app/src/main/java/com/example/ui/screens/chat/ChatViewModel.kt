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
import com.example.data.agent.AgentMode
import com.example.data.agent.ApprovalRequest
import com.example.data.agent.RunRequest
import com.example.data.agent.WorkingFileEntry
import com.example.data.api.LlmDocument
import com.example.data.local.entity.ConversationEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ModelCacheEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.repository.GhRepo
import com.example.data.security.KeyStoreManager
import com.example.data.termux.EngineHealthState
import com.example.domain.model.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

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
    val approval: ApprovalRequest? = null,
    val mode: AgentMode = AgentMode.CHAT,
    val permissionLevel: PermissionLevel = PermissionLevel.ASK_FOR_RISKY,
    val isPreparing: Boolean = false
)

data class AttachmentItem(
    val uri: Uri,
    val name: String,
    val isImage: Boolean,
    val base64Data: String? = null,
    val textContent: String? = null,
    val isZip: Boolean = false,
    val isApk: Boolean = false,
    val localArchivePath: String? = null,
    val pdfBase64: String? = null
)

data class CodebaseFileItem(
    val path: String,
    val name: String,
    val relativeDir: String,
    val modifiedState: com.example.data.agent.WorkingFileState? = null,
    val diffSummary: String? = null,
    val isManuallyEdited: Boolean = false
) {
    val isModifiedByAi: Boolean
        get() = modifiedState == com.example.data.agent.WorkingFileState.CREATED ||
            modifiedState == com.example.data.agent.WorkingFileState.EDITED
}

data class CodebaseClipboardItem(
    val sourceProjectId: String,
    val sourceProjectName: String,
    val relativePath: String,
    val fileName: String
)

data class CodeFileViewerState(
    val projectId: String = "",
    val path: String,
    val fileName: String,
    val content: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val modifiedState: com.example.data.agent.WorkingFileState? = null,
    val diffSummary: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CodeForgeApp
    private val projectRepo = app.projectRepository
    private val chatRepo = app.chatRepository
    private val settings = app.settingsStore
    private val agentEngine = app.agentEngine
    private val manualEditTracker = app.manualEditTracker
    val termuxBridge = app.termuxBridge

    val approval: StateFlow<ApprovalRequest?> = agentEngine.approvalRequest
    val workingFiles: StateFlow<List<WorkingFileEntry>> = agentEngine.workingFiles
    val runStartedAtMs: StateFlow<Long?> = agentEngine.runStartedAtMs
    val processState: StateFlow<com.example.data.agent.AgentProcessState> = agentEngine.processState
    val engineHealth: StateFlow<EngineHealthState> = termuxBridge.healthState
    val terminalOutput: StateFlow<String> = termuxBridge.terminalOutput

    private val streamBuf = StringBuilder()
    private val reasoningBuf = StringBuilder()
    private var flushJob: Job? = null

    private val _rawWorkspaceFiles = MutableStateFlow<List<String>>(emptyList())
    private val _codebaseLoading = MutableStateFlow(false)
    val codebaseLoading: StateFlow<Boolean> = _codebaseLoading.asStateFlow()

    private val _clipboardItem = MutableStateFlow<CodebaseClipboardItem?>(null)
    val clipboardItem: StateFlow<CodebaseClipboardItem?> = _clipboardItem.asStateFlow()

    private val _secondaryProject = MutableStateFlow<ProjectEntity?>(null)
    val secondaryProject: StateFlow<ProjectEntity?> = _secondaryProject.asStateFlow()

    private val _secondaryFiles = MutableStateFlow<List<CodebaseFileItem>>(emptyList())
    val secondaryFiles: StateFlow<List<CodebaseFileItem>> = _secondaryFiles.asStateFlow()

    private val _openedFileViewer = MutableStateFlow<CodeFileViewerState?>(null)
    val openedFileViewer: StateFlow<CodeFileViewerState?> = _openedFileViewer.asStateFlow()

    private val _uiState = MutableStateFlow(
        ChatUiState(
            mode = if (settings.agentMode == "BUILD") AgentMode.BUILD else AgentMode.CHAT,
            permissionLevel = settings.defaultPermissionLevel
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val activeProject: StateFlow<ProjectEntity?> = _uiState
        .map { it.activeProject }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Manual edits made ONLY by the user for the active project (never AI edits). */
    val manualEdits: StateFlow<List<com.example.data.agent.ManualEditRecord>> =
        combine(activeProject, manualEditTracker.records) { proj, _ ->
            if (proj == null) emptyList() else manualEditTracker.editsForProject(proj.id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val codebaseFiles: StateFlow<List<CodebaseFileItem>> =
        combine(_rawWorkspaceFiles, workingFiles, manualEdits) { rawPaths, worked, manualList ->
            val workedMap = LinkedHashMap<String, WorkingFileEntry>()
            for (w in worked) {
                val norm = w.path.trim().removePrefix("./").trimStart('/')
                workedMap[norm] = w
            }
            val manualPaths = manualList.map { it.relativePath }.toSet()
            val mergedPaths = LinkedHashSet<String>()
            // Put AI-created/edited files first so green ticks are immediately visible, then all workspace files
            for (w in worked) {
                val norm = w.path.trim().removePrefix("./").trimStart('/')
                if (norm.isNotBlank() && w.state != com.example.data.agent.WorkingFileState.DELETED) {
                    mergedPaths.add(norm)
                }
            }
            for (p in rawPaths) {
                val norm = p.trim().removePrefix("./").trimStart('/')
                if (norm.isNotBlank()) mergedPaths.add(norm)
            }
            mergedPaths.map { rel ->
                val w = workedMap[rel] ?: workedMap.entries.firstOrNull { rel.endsWith("/${it.key}") || it.key.endsWith("/$rel") }?.value
                val name = rel.substringAfterLast('/')
                val dir = rel.substringBeforeLast('/', "")
                CodebaseFileItem(
                    path = rel,
                    name = name,
                    relativeDir = dir,
                    modifiedState = w?.state,
                    diffSummary = w?.diffSummary,
                    isManuallyEdited = rel in manualPaths
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val activeProjectId = MutableStateFlow<String?>(null)
    private val activeConversationId = MutableStateFlow<String?>(null)
    private val refreshTick = MutableStateFlow(0)

    val conversations: StateFlow<List<ConversationEntity>> = chatRepo.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val projectNames: StateFlow<Map<String, String>> = projectRepo.allProjects
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val providers: StateFlow<List<ProviderConfigEntity>> = app.providerRepository.allProviders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cachedModels: StateFlow<List<ModelCacheEntity>> = app.providerRepository.allCachedModels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _preferredProviderId = MutableStateFlow(settings.preferredProviderId)
    val preferredProviderId: StateFlow<String?> = _preferredProviderId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = activeConversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepo.getMessages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val stepsCache = HashMap<String, Pair<String, List<ToolStepEntity>>>()

    val toolStepsMap: StateFlow<Map<String, List<ToolStepEntity>>> = combine(messages, refreshTick) { msgs, _ -> msgs }
        .mapLatest { msgs ->
            val map = mutableMapOf<String, List<ToolStepEntity>>()
            for (m in msgs) {
                if (m.sender != "ASSISTANT") continue
                val cached = stepsCache[m.id]
                val steps = if (cached != null && cached.first == m.status && m.status != "STREAMING") {
                    cached.second
                } else {
                    chatRepo.getToolStepsOnce(m.id).also { stepsCache[m.id] = Pair(m.status, it) }
                }
                if (steps.isNotEmpty()) map[m.id] = steps
            }
            map.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        viewModelScope.launch {
            agentEngine.agentEvents.collect { event ->
                when (event) {
                    is AgentEvent.StatusUpdate -> _uiState.update { it.copy(currentAgentStatus = event.statusText) }
                    is AgentEvent.StreamingChunk -> {
                        val isFirst = streamBuf.isEmpty()
                        streamBuf.append(event.text)
                        if (isFirst) {
                            // Zero-latency flush for instant Time-to-First-Token in UI
                            val text = streamBuf.toString()
                            val reasoning = reasoningBuf.toString()
                            _uiState.update { it.copy(streamingContent = text, streamingReasoning = reasoning) }
                        } else if (flushJob == null) {
                            flushJob = viewModelScope.launch {
                                delay(32)
                                flushJob = null
                                val text = streamBuf.toString()
                                val reasoning = reasoningBuf.toString()
                                _uiState.update { it.copy(streamingContent = text, streamingReasoning = reasoning) }
                            }
                        }
                    }
                    is AgentEvent.NoteAdded -> refreshTick.update { it + 1 }
                    is AgentEvent.StreamingReset -> {
                        flushJob?.cancel()
                        flushJob = null
                        streamBuf.setLength(0)
                        _uiState.update { it.copy(streamingContent = "") }
                    }
                    is AgentEvent.ReasoningChunk -> {
                        val isFirst = reasoningBuf.isEmpty()
                        reasoningBuf.append(event.text)
                        if (isFirst) {
                            // Zero-latency flush for instant live Chain of Thought visibility
                            val text = streamBuf.toString()
                            val reasoning = reasoningBuf.toString()
                            _uiState.update { it.copy(streamingContent = text, streamingReasoning = reasoning) }
                        } else if (flushJob == null) {
                            flushJob = viewModelScope.launch {
                                delay(32)
                                flushJob = null
                                val text = streamBuf.toString()
                                val reasoning = reasoningBuf.toString()
                                _uiState.update { it.copy(streamingContent = text, streamingReasoning = reasoning) }
                            }
                        }
                    }
                    is AgentEvent.FailoverNotice -> _uiState.update {
                        it.copy(failoverNotice = "Switched to ${event.toProvider} (${event.reason.take(100)})")
                    }
                    is AgentEvent.ToolStarted, is AgentEvent.ToolFinished -> refreshTick.update { it + 1 }
                    is AgentEvent.Finished -> {
                        flushJob?.cancel()
                        flushJob = null
                        streamBuf.setLength(0)
                        reasoningBuf.setLength(0)
                        _uiState.update { it.copy(streamingContent = "", streamingReasoning = "") }
                        refreshTick.update { it + 1 }
                        refreshCodebaseFiles()
                    }
                    is AgentEvent.Error -> {
                        flushJob?.cancel()
                        flushJob = null
                        streamBuf.setLength(0)
                        reasoningBuf.setLength(0)
                        _uiState.update {
                            it.copy(streamingContent = "", streamingReasoning = "", failoverNotice = "⚠️ ${event.error.take(200)}")
                        }
                        refreshTick.update { it + 1 }
                    }
                }
            }
        }

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
            activeConversationId
                .flatMapLatest { id -> if (id == null) flowOf(null) else chatRepo.observeConversation(id) }
                .collect { conv -> _uiState.update { it.copy(activeConversation = conv) } }
        }

        viewModelScope.launch {
            projectRepo.allProjects.collect { projects ->
                val current = _uiState.value.activeProject
                if (current == null) {
                    val scratch = projectRepo.getOrCreateScratchProject()
                    switchProject(scratch, null, startFresh = true)
                } else if (current.id != com.example.data.repository.ProjectRepository.SCRATCH_ID) {
                    val fresh = projects.firstOrNull { it.id == current.id }
                    if (fresh == null) {
                        val scratch = projectRepo.getOrCreateScratchProject()
                        switchProject(scratch, null, startFresh = true)
                    } else if (fresh != current) {
                        _uiState.update { it.copy(activeProject = fresh) }
                    }
                }
            }
        }
    }

    val projectsList: StateFlow<List<ProjectEntity>> = projectRepo.allProjects
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setMode(mode: AgentMode) {
        settings.agentMode = mode.name
        _uiState.update { it.copy(mode = mode) }
    }

    fun setPermissionLevel(level: PermissionLevel) {
        settings.defaultPermissionLevel = level
        _uiState.update { it.copy(permissionLevel = level) }
    }

    fun getDraftForCurrentChat(): String {
        val cid = activeConversationId.value ?: return ""
        return settings.getDraft(cid)
    }

    fun saveDraftForCurrentChat(text: String) {
        val cid = activeConversationId.value ?: return
        settings.saveDraft(cid, text)
    }

    fun attachProjectToChat(project: ProjectEntity) {
        if (agentEngine.isRunning.value) {
            _uiState.update { it.copy(failoverNotice = "The agent is working. Press Stop first.") }
            return
        }
        viewModelScope.launch {
            val conv = _uiState.value.activeConversation
            if (conv != null) {
                chatRepo.moveConversationToProject(conv.id, project.id)
                switchProject(project, conv.id)
            } else {
                switchProject(project, null)
            }
        }
    }

    fun detachProjectFromChat() {
        viewModelScope.launch { attachProjectToChat(projectRepo.getOrCreateScratchProject()) }
    }

    // ---------------------------------------------------------------------------------------
    // Working Files & Revert
    // ---------------------------------------------------------------------------------------

    fun revertSingleFile(relativePath: String) {
        val proj = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            val ok = projectRepo.revertSingleFileFromLastCheckpoint(proj.id, relativePath)
            _uiState.update {
                it.copy(failoverNotice = if (ok) "Reverted $relativePath to checkpoint." else "Could not revert $relativePath.")
            }
        }
    }

    fun revertAllChangesInLastRun() {
        val proj = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            val msg = projectRepo.undoLastRun(proj.id)
            val ok = msg != null
            if (ok) agentEngine.clearWorkingFiles()
            _uiState.update {
                it.copy(failoverNotice = msg ?: "No checkpoint found to revert.")
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // In-Chat Codebase File Browser & Code Viewer
    // ---------------------------------------------------------------------------------------

    private suspend fun activeWorkspaceDirPath(): String {
        val proj = _uiState.value.activeProject
        return if (proj != null && proj.id != com.example.data.repository.ProjectRepository.SCRATCH_ID) {
            projectRepo.projectDir(proj.id).absolutePath
        } else {
            com.example.data.termux.TermuxBridge.TERMUX_HOME
        }
    }

    fun refreshCodebaseFiles() {
        viewModelScope.launch {
            _codebaseLoading.value = true
            try {
                val proj = _uiState.value.activeProject
                val hasProject = proj != null && proj.id != com.example.data.repository.ProjectRepository.SCRATCH_ID
                val found = LinkedHashSet<String>()

                // 1. Always read local Codebase files first (instant, works offline & directly reflects AI + manual edits)
                if (proj != null) {
                    val localFiles = projectRepo.listAllRelativeFiles(proj.id)
                    found.addAll(localFiles)
                }

                // 2. If in scratch mode or Termux is online, also include Termux files when local list is empty
                if ((found.isEmpty() || !hasProject) && termuxBridge.status.value.engineRunning) {
                    val rootPath = activeWorkspaceDirPath()
                    val escaped = "'" + rootPath.replace("'", "'\"'\"'") + "'"
                    val cmd = "if [ -d $escaped ]; then cd $escaped && " +
                        "if command -v rg >/dev/null 2>&1; then " +
                        "rg --files --max-depth 6 --glob '!.git' --glob '!node_modules' --glob '!build' --glob '!.gradle' --glob '!__pycache__' --glob '!.venv' --glob '!.cache' 2>/dev/null | head -n 350; " +
                        "else " +
                        "find . -maxdepth 5 -not -path '*/.*' -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/__pycache__/*' -type f 2>/dev/null | sed 's|^\\./||' | head -n 350; " +
                        "fi; fi"
                    val res = termuxBridge.execCommand(cmd, cwd = com.example.data.termux.TermuxBridge.TERMUX_HOME, timeoutSec = 10)
                    if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                        res.stdout.lineSequence()
                            .map { it.trim().removePrefix("./") }
                            .filter { it.isNotBlank() }
                            .forEach { found.add(it) }
                    }
                }
                _rawWorkspaceFiles.value = found.sorted()
                refreshSecondaryCodebaseFiles()
            } catch (_: Exception) {
                // Keep existing list on transient scan error
            } finally {
                _codebaseLoading.value = false
            }
        }
    }

    fun openCodebaseFile(item: CodebaseFileItem, projectIdOverride: String? = null) {
        val targetProjId = projectIdOverride ?: _uiState.value.activeProject?.id ?: com.example.data.repository.ProjectRepository.SCRATCH_ID
        _openedFileViewer.value = CodeFileViewerState(
            projectId = targetProjId,
            path = item.path,
            fileName = item.name,
            isLoading = true,
            modifiedState = item.modifiedState,
            diffSummary = item.diffSummary
        )
        viewModelScope.launch {
            val clean = item.path.trim().removePrefix("./")
            var loaded: String? = withContext(Dispatchers.IO) {
                try {
                    projectRepo.readFile(targetProjId, clean)
                } catch (_: Exception) {
                    null
                }
            }
            var lastErr: String? = null

            if (loaded == null && projectIdOverride == null && termuxBridge.status.value.engineRunning) {
                val rootPath = activeWorkspaceDirPath()
                val candidatePaths = buildList {
                    if (clean.startsWith("/")) add(clean)
                    else {
                        add("$rootPath/$clean")
                        val homePath = "${com.example.data.termux.TermuxBridge.TERMUX_HOME}/$clean"
                        if (homePath != "$rootPath/$clean") add(homePath)
                    }
                }
                for (abs in candidatePaths) {
                    val escaped = "'" + abs.replace("'", "'\"'\"'") + "'"
                    val pyCmd = "python3 -c \"import base64,pathlib,sys; p=pathlib.Path($escaped); " +
                        "sys.exit(2) if not p.is_file() else print(base64.b64encode(p.read_bytes()[:262144]).decode('ascii'))\""
                    val res = termuxBridge.execCommand(pyCmd, cwd = com.example.data.termux.TermuxBridge.TERMUX_HOME, timeoutSec = 15)
                    if (res.exitCode == 0 && res.stdout.isNotBlank()) {
                        loaded = try {
                            String(Base64.decode(res.stdout.trim(), Base64.DEFAULT), Charsets.UTF_8)
                        } catch (_: Exception) {
                            res.stdout
                        }
                        break
                    } else if (res.stderr.isNotBlank()) {
                        lastErr = res.stderr.trim()
                    }
                }
            }

            _openedFileViewer.value = if (loaded != null) {
                CodeFileViewerState(
                    projectId = targetProjId,
                    path = item.path,
                    fileName = item.name,
                    content = loaded,
                    isLoading = false,
                    modifiedState = item.modifiedState,
                    diffSummary = item.diffSummary
                )
            } else {
                CodeFileViewerState(
                    projectId = targetProjId,
                    path = item.path,
                    fileName = item.name,
                    isLoading = false,
                    error = lastErr ?: "Could not read file from workspace.",
                    modifiedState = item.modifiedState,
                    diffSummary = item.diffSummary
                )
            }
        }
    }

    fun closeCodebaseFile() {
        _openedFileViewer.value = null
    }

    /** Saves a user's manual edit inside the Codebase file viewer and records it in ManualEditTracker. */
    fun saveManualFileEdit(projectId: String, relativePath: String, newContent: String) {
        viewModelScope.launch {
            try {
                val oldContent = withContext(Dispatchers.IO) {
                    try { projectRepo.readFile(projectId, relativePath) } catch (_: Exception) { null }
                }
                projectRepo.writeFile(projectId, relativePath, newContent)
                manualEditTracker.recordManualEdit(
                    projectId = projectId,
                    relativePath = relativePath,
                    action = com.example.data.agent.ManualEditAction.EDITED,
                    oldContent = oldContent,
                    newContent = newContent
                )
                _openedFileViewer.update { cur ->
                    if (cur != null && cur.path == relativePath && cur.projectId == projectId) {
                        cur.copy(content = newContent)
                    } else cur
                }
                refreshCodebaseFiles()
                _uiState.update { it.copy(failoverNotice = "✓ Saved manual edit to $relativePath (synced to agent)") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Save failed: ${e.message}") }
            }
        }
    }

    /** Uploads a file from Android SAF [uri] into [projectId] (optionally inside [folderPrefix]) and records Manual Edit. */
    fun uploadFileToCodebase(projectId: String, uri: Uri, folderPrefix: String = "") {
        viewModelScope.launch {
            try {
                val rawName = projectRepo.queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
                val cleanFolder = folderPrefix.trim().trim('/')
                val relPath = if (cleanFolder.isEmpty()) rawName else "$cleanFolder/$rawName"
                if (rawName.lowercase().endsWith(".zip")) {
                    val report = projectRepo.extractZipUriIntoProject(projectId, uri, cleanFolder.ifEmpty { null })
                    for (p in report.replacedFiles) {
                        manualEditTracker.recordManualEdit(projectId, p, com.example.data.agent.ManualEditAction.REPLACED)
                    }
                    for (p in report.addedFiles) {
                        manualEditTracker.recordManualEdit(projectId, p, com.example.data.agent.ManualEditAction.UPLOADED)
                    }
                    refreshCodebaseFiles()
                    _uiState.update { it.copy(failoverNotice = "✓ Unzipped $rawName: ${report.replacedFiles.size} replaced, ${report.addedFiles.size} added") }
                    return@launch
                }
                val (oldText, newText) = projectRepo.importFileFromUri(projectId, relPath, uri)
                manualEditTracker.recordManualEdit(
                    projectId = projectId,
                    relativePath = relPath,
                    action = if (oldText != null) com.example.data.agent.ManualEditAction.REPLACED else com.example.data.agent.ManualEditAction.UPLOADED,
                    oldContent = oldText,
                    newContent = newText
                )
                refreshCodebaseFiles()
                _uiState.update { it.copy(failoverNotice = "✓ Uploaded $relPath") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Upload failed: ${e.message}") }
            }
        }
    }

    /** Long-press Replace: overwrites [targetRelativePath] with the chosen file from Android storage & records Manual Edit. */
    fun replaceCodebaseFile(projectId: String, targetRelativePath: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val (oldText, newText) = projectRepo.importFileFromUri(projectId, targetRelativePath, uri)
                manualEditTracker.recordManualEdit(
                    projectId = projectId,
                    relativePath = targetRelativePath,
                    action = com.example.data.agent.ManualEditAction.REPLACED,
                    oldContent = oldText,
                    newContent = newText
                )
                refreshCodebaseFiles()
                _uiState.update { it.copy(failoverNotice = "✓ Replaced $targetRelativePath (synced to agent)") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Replace failed: ${e.message}") }
            }
        }
    }

    fun deleteCodebaseFileManually(projectId: String, relativePath: String) {
        viewModelScope.launch {
            try {
                val oldContent = withContext(Dispatchers.IO) {
                    try { projectRepo.readFile(projectId, relativePath) } catch (_: Exception) { null }
                }
                projectRepo.deleteFile(projectId, relativePath)
                manualEditTracker.recordManualEdit(
                    projectId = projectId,
                    relativePath = relativePath,
                    action = com.example.data.agent.ManualEditAction.DELETED,
                    oldContent = oldContent,
                    newContent = null
                )
                refreshCodebaseFiles()
                _uiState.update { it.copy(failoverNotice = "Deleted $relativePath") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Delete failed: ${e.message}") }
            }
        }
    }

    fun copyFileToClipboard(projectId: String, projectName: String, relativePath: String) {
        _clipboardItem.value = CodebaseClipboardItem(
            sourceProjectId = projectId,
            sourceProjectName = projectName,
            relativePath = relativePath,
            fileName = relativePath.substringAfterLast('/')
        )
        _uiState.update { it.copy(failoverNotice = "Copied ${relativePath.substringAfterLast('/')} to Codebase clipboard") }
    }

    fun clearClipboard() {
        _clipboardItem.value = null
    }

    fun pasteClipboardInto(destProjectId: String, destFolder: String = "") {
        val clip = _clipboardItem.value ?: return
        viewModelScope.launch {
            try {
                val cleanFolder = destFolder.trim().trim('/')
                val destRelPath = if (cleanFolder.isEmpty()) clip.fileName else "$cleanFolder/${clip.fileName}"
                val (oldText, newText) = projectRepo.copyFileBetweenProjects(
                    sourceProjectId = clip.sourceProjectId,
                    sourceRelPath = clip.relativePath,
                    destProjectId = destProjectId,
                    destRelPath = destRelPath
                )
                manualEditTracker.recordManualEdit(
                    projectId = destProjectId,
                    relativePath = destRelPath,
                    action = com.example.data.agent.ManualEditAction.PASTED,
                    oldContent = oldText,
                    newContent = newText
                )
                refreshCodebaseFiles()
                _uiState.update { it.copy(failoverNotice = "✓ Pasted ${clip.fileName} into ${if (cleanFolder.isEmpty()) "root" else cleanFolder}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Paste failed: ${e.message}") }
            }
        }
    }

    fun openSecondaryCodebase(project: ProjectEntity) {
        _secondaryProject.value = project
        refreshSecondaryCodebaseFiles()
    }

    fun createSecondaryCodebase(name: String) {
        viewModelScope.launch {
            try {
                val proj = projectRepo.createProject(name.trim().ifBlank { "Secondary Codebase" }, "Empty")
                _secondaryProject.value = proj
                refreshSecondaryCodebaseFiles()
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Could not create secondary codebase: ${e.message}") }
            }
        }
    }

    fun closeSecondaryCodebase() {
        _secondaryProject.value = null
        _secondaryFiles.value = emptyList()
    }

    fun refreshSecondaryCodebaseFiles() {
        val sec = _secondaryProject.value ?: return
        viewModelScope.launch {
            try {
                val rels = projectRepo.listAllRelativeFiles(sec.id)
                val manualPaths = manualEditTracker.editsForProject(sec.id).map { it.relativePath }.toSet()
                _secondaryFiles.value = rels.map { rel ->
                    CodebaseFileItem(
                        path = rel,
                        name = rel.substringAfterLast('/'),
                        relativeDir = rel.substringBeforeLast('/', ""),
                        isManuallyEdited = rel in manualPaths
                    )
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    fun downloadSingleFileToUri(projectId: String, relativePath: String, destUri: Uri) {
        viewModelScope.launch {
            try {
                projectRepo.exportSingleFileToUri(projectId, relativePath, destUri)
                _uiState.update { it.copy(failoverNotice = "✓ Downloaded ${relativePath.substringAfterLast('/')}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ Download failed: ${e.message}") }
            }
        }
    }

    fun downloadProjectZipToUri(projectId: String, destUri: Uri) {
        viewModelScope.launch {
            try {
                val count = projectRepo.exportProjectZipToUri(projectId, destUri)
                _uiState.update { it.copy(failoverNotice = "✓ Exported $count file(s) to ZIP archive") }
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ ZIP export failed: ${e.message}") }
            }
        }
    }

    fun dismissManualEdit(recordId: String) {
        manualEditTracker.dismissRecord(recordId)
    }

    fun clearManualEdits() {
        val proj = _uiState.value.activeProject ?: return
        manualEditTracker.clearForProject(proj.id)
    }

    fun activeProjectRootFile(): File? {
        val proj = _uiState.value.activeProject ?: return null
        return File(proj.rootPath)
    }

    fun pushActiveProjectToGitHub(repoInput: String, commitMessage: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val proj = _uiState.value.activeProject ?: return
        viewModelScope.launch {
            try {
                val repo = app.gitHubRepository.normalizeRepo(repoInput)
                if (repo.isBlank() || !repo.contains("/")) {
                    onDone(false, "Enter repository as owner/repo")
                    return@launch
                }
                settings.linkRepo(proj.id, repo)
                val res = app.githubManager.push(
                    projectId = proj.id,
                    repo = repo,
                    deleteExtra = false,
                    message = commitMessage.ifBlank { "Update from CodeForge" }
                )
                val desc = app.githubManager.describe(res, repo)
                if (!res.noChanges) {
                    app.githubManager.watchBuild(proj.name, repo, res.commitSha)
                }
                _uiState.update { it.copy(failoverNotice = "✓ $desc") }
                onDone(true, desc)
            } catch (e: Exception) {
                val err = e.message ?: "Push failed"
                _uiState.update { it.copy(failoverNotice = "⚠️ $err") }
                onDone(false, err)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Terminal Drawer & Engine Controls
    // ---------------------------------------------------------------------------------------

    fun runTerminalCommand(cmd: String) {
        if (cmd.isBlank()) return
        val proj = _uiState.value.activeProject
        viewModelScope.launch {
            val cwd = if (proj != null && proj.id != com.example.data.repository.ProjectRepository.SCRATCH_ID) {
                projectRepo.projectDir(proj.id).absolutePath
            } else {
                termuxBridge.projectsDir.absolutePath
            }
            termuxBridge.execCommand(cmd, cwd = cwd, timeoutSec = settings.commandTimeoutSeconds)
        }
    }

    fun clearTerminal() {
        termuxBridge.clearTerminalOutput()
    }

    fun repairEngine() {
        viewModelScope.launch {
            val res = termuxBridge.bootstrapAndStartEngine()
            _uiState.update {
                it.copy(
                    failoverNotice = res.fold(
                        onSuccess = { msg -> "✓ $msg" },
                        onFailure = { err -> "⚠️ ${err.message}" }
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // GitHub: repository list and clone
    // ---------------------------------------------------------------------------------------

    data class RepoListState(
        val loading: Boolean = false,
        val error: String? = null,
        val repos: List<GhRepo> = emptyList(),
        val connected: Boolean = true
    )

    private val _repoList = MutableStateFlow(RepoListState())
    val repoList: StateFlow<RepoListState> = _repoList.asStateFlow()

    fun loadGithubRepos() {
        if (settings.githubToken.isBlank()) {
            _repoList.value = RepoListState(connected = false)
            return
        }
        _repoList.update { it.copy(loading = true, error = null, connected = true) }
        viewModelScope.launch {
            try {
                val list = app.githubManager.listRepos()
                _repoList.value = RepoListState(repos = list)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _repoList.value = RepoListState(error = e.message ?: "Could not load repositories")
            }
        }
    }

    fun cloneRepo(repo: GhRepo) {
        if (agentEngine.isRunning.value) {
            _uiState.update { it.copy(failoverNotice = "The agent is working. Press Stop first.") }
            return
        }
        _uiState.update { it.copy(failoverNotice = "Cloning ${repo.fullName}…") }
        viewModelScope.launch {
            try {
                val project = app.githubManager.cloneRepo(repo)
                val conv = _uiState.value.activeConversation
                if (conv != null) {
                    chatRepo.moveConversationToProject(conv.id, project.id)
                    app.database.messageDao().insertMessage(
                        MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conv.id,
                            sender = "ASSISTANT",
                            content = "📥 Cloned **${repo.fullName}** (${project.fileCount} files). Pushes from this project go to this repository.",
                            status = "SUCCESS"
                        )
                    )
                    switchProject(project, conv.id)
                } else {
                    switchProject(project, null)
                }
                _uiState.update { it.copy(failoverNotice = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(failoverNotice = "⚠️ ${e.message ?: "Clone failed"}".take(240)) }
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
                val latest = chatRepo.getConversations(project.id).first().maxByOrNull { it.updatedAt }
                val latestEmpty = latest != null &&
                    app.database.messageDao().getMessagesForConversationOnce(latest.id).isEmpty()
                if (latestEmpty && latest != null) latest else chatRepo.createConversation(project.id, "New Chat")
            } else {
                chatRepo.getConversations(project.id).first().firstOrNull()
                    ?: chatRepo.createConversation(project.id, "New Chat")
            }
            setActiveConversation(conv.id)
            refreshCodebaseFiles()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Model picker & live server refresh
    // ---------------------------------------------------------------------------------------

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            val p = providers.value.firstOrNull { it.id == providerId } ?: return@launch
            app.providerRepository.updateProvider(p.copy(selectedModel = model, isEnabled = true))
            settings.preferredProviderId = providerId
            _preferredProviderId.value = providerId
        }
    }

    fun toggleFavoriteModel(providerId: String, modelId: String, favorite: Boolean) {
        viewModelScope.launch {
            app.providerRepository.toggleFavoriteModel(providerId, modelId, favorite)
        }
    }

    fun addCustomModelId(providerId: String, modelId: String) {
        viewModelScope.launch {
            app.providerRepository.addCustomModelId(providerId, modelId)
        }
    }

    fun refreshProviderModels(providerId: String) {
        viewModelScope.launch {
            val p = providers.value.firstOrNull { it.id == providerId } ?: return@launch
            val diag = app.providerRepository.testAndFetchModels(p)
            _uiState.update {
                it.copy(
                    failoverNotice = if (diag.ok) {
                        "✓ Loaded ${diag.modelCount} models from ${p.name} (${diag.latencyMs} ms)"
                    } else {
                        "⚠️ ${p.name}: ${diag.errorMessage ?: "Connection failed"}"
                    }
                )
            }
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
        if (agentEngine.isRunning.value) return
        viewModelScope.launch {
            val scratch = projectRepo.getOrCreateScratchProject()
            val currentId = activeConversationId.value
            val currentEmpty = currentId != null &&
                app.database.messageDao().getMessagesForConversationOnce(currentId).isEmpty()
            if (currentEmpty && _uiState.value.activeProject?.id == scratch.id) return@launch
            val conv = chatRepo.createConversation(scratch.id, "New Chat")
            switchProject(scratch, conv.id)
        }
    }

    fun newStarterProject(name: String = "New App", template: String = "Android Starter") {
        if (agentEngine.isRunning.value) return
        viewModelScope.launch {
            try {
                val p = projectRepo.createProject(name, template)
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
        val resolverType = try { resolver.getType(uri) } catch (_: Exception) { null }
        val displayName = projectRepo.queryDisplayName(uri) ?: name
        val lower = displayName.lowercase()
        val isApk = !isImage && (lower.endsWith(".apk") || lower.endsWith(".aab") || lower.endsWith(".jar"))
        val isZip = !isImage && !isApk && (lower.endsWith(".zip") || resolverType == "application/zip" ||
            resolverType == "application/x-zip-compressed")
        val isPdf = !isImage && (lower.endsWith(".pdf") || resolverType == "application/pdf")

        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) {
                when {
                    isImage -> {
                        val b64 = encodeImage(uri)
                        if (b64 == null) null else AttachmentItem(uri, displayName, true, base64Data = b64)
                    }
                    isApk -> {
                        // Copy APK to outputs dir so RepoToolInstaller can analyze & trigger "Save this source to a project?"
                        val outFile = File(projectRepo.outputsRootDir, "${System.currentTimeMillis()}_$displayName")
                        val copied = try {
                            resolver.openInputStream(uri)?.use { inp ->
                                outFile.outputStream().use { out -> inp.copyTo(out) }
                            } != null
                        } catch (_: Exception) {
                            false
                        }
                        if (!copied) null else AttachmentItem(
                            uri = uri,
                            name = displayName,
                            isImage = false,
                            isApk = true,
                            localArchivePath = outFile.absolutePath
                        )
                    }
                    isZip -> AttachmentItem(uri, displayName, false, isZip = true)
                    isPdf -> {
                        val bytes = try {
                            resolver.openInputStream(uri)?.use { it.readBytes() }
                        } catch (_: Throwable) {
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
                    "Could not attach \"$displayName\". Supported: images, PDF, APK/AAB, text/code files, .docx and .zip."
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
            if (bytes.any { it == 0.toByte() }) return null
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
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

            val orientation = try {
                resolver.openInputStream(uri)?.use {
                    android.media.ExifInterface(it).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
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
        } catch (_: Throwable) {
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
        if (_uiState.value.isPreparing) return
        if (agentEngine.isRunning.value) {
            _uiState.update { it.copy(failoverNotice = "The agent is still working. Wait for it or press Stop.") }
            return
        }

        val trimmedInput = promptText.trim()
        if (attachments.isEmpty() && (trimmedInput.equals("/push", ignoreCase = true) || trimmedInput.equals("/build", ignoreCase = true))) {
            saveDraftForCurrentChat("")
            pushAndBuild()
            return
        }

        saveDraftForCurrentChat("")

        val images = attachments.filter { it.isImage }.mapNotNull { it.base64Data }
        val files = attachments.filter { it.textContent != null }
        val pdfs = attachments.filter { it.pdfBase64 != null }
        val zip = attachments.firstOrNull { it.isZip }
        val apk = attachments.firstOrNull { it.isApk }

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
            } else if (apk != null) {
                append("I attached the archive/APK \"${apk.name}\". Analyze its structure, manifest, packages, and endpoints, and present the findings.")
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

        val mode = _uiState.value.mode
        _uiState.update {
            it.copy(
                pendingAttachments = emptyList(),
                streamingContent = "",
                streamingReasoning = "",
                failoverNotice = null,
                currentAgentStatus = "Thinking…",
                isPreparing = true
            )
        }
        viewModelScope.launch {
            val sent = chatRepo.addUserMessage(conv.id, display, status = "SENDING")
            var projectId = proj.id
            var finalPrompt = prompt
            if (zip != null) {
                _uiState.update { it.copy(currentAgentStatus = "Extracting ${zip.name}…") }
                try {
                    if (proj.id != com.example.data.repository.ProjectRepository.SCRATCH_ID) {
                        // Extract & replace matching files directly inside the active project!
                        val report = projectRepo.extractZipUriIntoProject(proj.id, zip.uri)
                        refreshCodebaseFiles()
                        finalPrompt = buildString {
                            append(prompt)
                            append("\n\n[Extracted ZIP \"${zip.name}\" into active project \"${proj.name}\"]\n")
                            append(report.toReadableSummary())
                        }
                    } else {
                        projectId = importZipKeepingChat(zip.uri, zip.name).id
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    chatRepo.setMessageStatus(sent.id, "ERROR")
                    _uiState.update {
                        it.copy(isPreparing = false, failoverNotice = "⚠️ ZIP import failed: ${e.message ?: e.javaClass.simpleName}".take(220))
                    }
                    return@launch
                }
            }
            chatRepo.setMessageStatus(sent.id, "SUCCESS")
            agentEngine.start(
                RunRequest(
                    projectId = projectId,
                    conversationId = conv.id,
                    userPrompt = finalPrompt,
                    imagesBase64 = images,
                    docs = docs,
                    mode = mode,
                    attachedArchivePath = apk?.localArchivePath
                )
            )
            _uiState.update { it.copy(isPreparing = false) }
        }
    }

    fun pushAndBuild() {
        val proj = _uiState.value.activeProject ?: return
        val conv = _uiState.value.activeConversation ?: return
        if (agentEngine.isRunning.value) return
        agentEngine.start(RunRequest(proj.id, conv.id, "", pushOnly = true))
    }

    fun watchBuildAfterPush(result: com.example.data.repository.PushResult): Boolean {
        val proj = _uiState.value.activeProject ?: return false
        val conv = _uiState.value.activeConversation ?: return false
        if (agentEngine.isRunning.value) return false
        agentEngine.start(RunRequest(proj.id, conv.id, "", watchPush = result))
        return true
    }

    fun stopAgent() {
        agentEngine.stop()
    }

    fun resolveApproval(approved: Boolean) {
        agentEngine.resolveApproval(approved)
    }

    fun resolveApprovalChoice(choice: String) {
        agentEngine.resolveApprovalChoice(choice)
    }

    fun retryFailedMessage(message: MessageEntity) {
        viewModelScope.launch {
            val text = message.content.lines().filter { !it.startsWith("📎") }.joinToString("\n").trim()
            app.database.messageDao().deleteMessage(message.id)
            if (text.isNotEmpty()) {
                sendMessage(text)
            } else {
                _uiState.update { it.copy(failoverNotice = "Attach the file again and press send.") }
            }
        }
    }

    fun retryLastMessage() {
        val lastUserMsg = messages.value.lastOrNull { it.sender == "USER" }
        if (lastUserMsg != null) sendMessage(lastUserMsg.content)
    }

    fun dismissFailoverNotice() {
        _uiState.update { it.copy(failoverNotice = null) }
    }
}
