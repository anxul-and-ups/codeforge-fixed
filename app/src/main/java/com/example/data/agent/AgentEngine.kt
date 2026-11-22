package com.example.data.agent

import android.content.Context
import com.example.data.api.LlmDocument
import com.example.data.api.LlmMessage
import com.example.data.api.LlmRequest
import com.example.data.api.LlmResponse
import com.example.data.api.LlmTool
import com.example.data.api.LlmToolCall
import com.example.data.github.GitHubManager
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.InstalledToolDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.InstalledToolEntity
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.logs.BuildLogEntry
import com.example.data.logs.BuildLogStore
import com.example.data.repository.GitHubRepository
import com.example.data.repository.ProjectRepository
import com.example.data.repository.ProviderRepository
import com.example.data.repository.PushResult
import com.example.data.security.KeyStoreManager
import com.example.data.service.AgentForegroundService
import com.example.data.settings.SettingsStore
import com.example.data.termux.TermuxBridge
import com.example.domain.model.FileNode
import com.example.domain.model.PermissionLevel
import com.example.domain.model.ReasoningLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

sealed class AgentEvent {
    data class StatusUpdate(val statusText: String) : AgentEvent()
    data class ToolStarted(val stepIndex: Int, val toolName: String, val argsJson: String) : AgentEvent()
    data class ToolFinished(val stepIndex: Int, val toolName: String, val result: String, val isError: Boolean) : AgentEvent()
    data class NoteAdded(val text: String) : AgentEvent()
    data class StreamingChunk(val text: String) : AgentEvent()
    data class ReasoningChunk(val text: String) : AgentEvent()
    object StreamingReset : AgentEvent()
    data class FailoverNotice(val fromProvider: String, val toProvider: String, val reason: String) : AgentEvent()
    data class Finished(val finalSummary: String, val totalSteps: Int) : AgentEvent()
    data class Error(val error: String) : AgentEvent()
}

/** CHAT: talk, explain, answer, look at code (read-only). BUILD: may edit files and run commands. */
enum class AgentMode { CHAT, BUILD }

private val READ_ONLY_TOOLS = setOf(
    "list_files", "find_files", "read_file", "search_code", "discover_skills_and_repos", "run_command", "ask_user"
)

enum class WorkingFileState(val label: String) {
    READING("Reading"),
    EDITED("Edited"),
    CREATED("Created"),
    DELETED("Deleted")
}

data class WorkingFileEntry(
    val path: String,
    val state: WorkingFileState,
    val diffSummary: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class RunRequest(
    val projectId: String,
    val conversationId: String,
    val userPrompt: String,
    val imagesBase64: List<String> = emptyList(),
    val docs: List<LlmDocument> = emptyList(),
    val pushOnly: Boolean = false,
    val watchPush: PushResult? = null,
    val mode: AgentMode = AgentMode.BUILD,
    val attachedArchivePath: String? = null
)

data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val summary: String,
    val title: String = "Allow this action?",
    val allowLabel: String = "Allow",
    val denyLabel: String = "Deny",
    val options: List<String> = emptyList(),
    val isSaveOutputPrompt: Boolean = false,
    val extractedSourceDirPath: String? = null,
    val analysisReportMarkdown: String? = null,
    val sourceArchiveName: String? = null
)

private data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val changed: Boolean = false,
    val touched: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val diffSummary: String? = null,
    val commandText: String? = null,
    val cwdText: String? = null,
    val filePath: String? = null,
    val stepType: String = "TOOL",
    val uiResultJson: String? = null,
    val fatalBridgeError: Boolean = false
)

private data class LoopResult(
    val ok: Boolean,
    val changed: Boolean,
    val summary: String,
    val touched: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
    val assistantMessageId: String? = null,
    val lastStepIndex: Int = 0
)

class AgentEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val projectRepository: ProjectRepository,
    private val providerRepository: ProviderRepository,
    private val gitHubRepository: GitHubRepository,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val usageDao: UsageDao,
    private val installedToolDao: InstalledToolDao,
    private val termuxBridge: TermuxBridge,
    private val settings: SettingsStore,
    private val buildLogStore: BuildLogStore,
    private val githubManager: GitHubManager
) {
    private val _agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8192)
    val agentEvents = _agentEvents.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _runningConversationId = MutableStateFlow<String?>(null)
    val runningConversationId: StateFlow<String?> = _runningConversationId.asStateFlow()

    private val _approvalRequest = MutableStateFlow<ApprovalRequest?>(null)
    val approvalRequest: StateFlow<ApprovalRequest?> = _approvalRequest.asStateFlow()

    private val _workingFiles = MutableStateFlow<List<WorkingFileEntry>>(emptyList())
    val workingFiles: StateFlow<List<WorkingFileEntry>> = _workingFiles.asStateFlow()

    private val _runStartedAtMs = MutableStateFlow<Long?>(null)
    val runStartedAtMs: StateFlow<Long?> = _runStartedAtMs.asStateFlow()

    private val _processState = MutableStateFlow(AgentProcessState())
    val processState: StateFlow<AgentProcessState> = _processState.asStateFlow()

    private var pendingDecision: CompletableDeferred<String>? = null
    private var job: Job? = null

    @Volatile private var cachedTermuxSkillsSnapshot: String = ""
    @Volatile private var cachedTermuxSkillsAtMs: Long = 0L

    private fun updateProcess(
        phase: AgentProcessPhase,
        detail: String = "",
        activeCommand: String? = null,
        stepIndex: Int = _processState.value.stepIndex
    ) {
        _processState.value = AgentProcessState(
            phase = phase,
            detail = detail,
            activeCommand = activeCommand,
            stepIndex = stepIndex,
            startedAtMs = _runStartedAtMs.value
        )
    }

    fun start(request: RunRequest) {
        if (_isRunning.value) {
            if (job?.isActive != true) {
                // Recover from any stale flag if coroutine already terminated
                _isRunning.value = false
            } else {
                return
            }
        }
        val now = System.currentTimeMillis()
        _isRunning.value = true
        _runningConversationId.value = request.conversationId
        _runStartedAtMs.value = now
        _workingFiles.value = emptyList()
        _processState.value = AgentProcessState(
            phase = AgentProcessPhase.THINKING,
            detail = "Initializing Cloud Code Engine…",
            startedAtMs = now
        )
        AgentForegroundService.startService(appContext, "Agent is working…")
        job = scope.launch {
            try {
                runFull(request)
                if (_processState.value.phase != AgentProcessPhase.ERROR &&
                    _processState.value.phase != AgentProcessPhase.ABORTED
                ) {
                    updateProcess(AgentProcessPhase.COMPLETED, "Completed")
                }
            } catch (e: CancellationException) {
                updateProcess(AgentProcessPhase.ABORTED, "Stopped by user")
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                updateProcess(AgentProcessPhase.ERROR, msg.take(120))
                _agentEvents.tryEmit(AgentEvent.Error(msg))
            } finally {
                withContext(NonCancellable) {
                    _approvalRequest.value = null
                    pendingDecision?.cancel()
                    pendingDecision = null
                    _isRunning.value = false
                    _runningConversationId.value = null
                    _runStartedAtMs.value = null
                    _processState.value = AgentProcessState(phase = AgentProcessPhase.IDLE)
                    AgentForegroundService.stopService(appContext)
                }
            }
        }
    }

    fun stop() {
        updateProcess(AgentProcessPhase.ABORTED, "Aborting active task & Termux processes…")
        pendingDecision?.cancel()
        pendingDecision = null
        _approvalRequest.value = null
        job?.cancel()
        // Kill whatever is running inside Termux right now, otherwise the command keeps running
        // after Stop and the cancelled run waits for the blocking HTTP call to return.
        termuxBridge.killAllRunningCommands()
    }

    fun resolveApproval(approved: Boolean) {
        pendingDecision?.complete(if (approved) "ALLOW" else "DENY")
    }

    fun resolveApprovalChoice(choice: String) {
        pendingDecision?.complete(choice)
    }

    fun clearWorkingFiles() {
        _workingFiles.value = emptyList()
    }

    private fun recordWorkingFile(path: String, state: WorkingFileState, diffSummary: String? = null) {
        val clean = cleanPath(path)
        if (clean.isEmpty() || clean == ".") return
        val current = _workingFiles.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.path == clean }
        val updated = WorkingFileEntry(clean, state, diffSummary ?: current.getOrNull(existingIdx)?.diffSummary)
        if (existingIdx >= 0) {
            // Don't downgrade EDITED/CREATED back to READING
            val prev = current[existingIdx].state
            val nextState = if ((prev == WorkingFileState.EDITED || prev == WorkingFileState.CREATED) && state == WorkingFileState.READING) {
                prev
            } else {
                state
            }
            current[existingIdx] = updated.copy(state = nextState)
        } else {
            current.add(updated)
        }
        _workingFiles.value = current
    }

    private fun status(text: String) {
        _agentEvents.tryEmit(AgentEvent.StatusUpdate(text))
        AgentForegroundService.updateStatus(appContext, text)
    }

    // ---------------------------------------------------------------------------------------
    // Top level run
    // ---------------------------------------------------------------------------------------

    private suspend fun runFull(req: RunRequest) {
        var changed = false
        var summary = ""
        var touched: Set<String>? = null
        var removed: Set<String> = emptySet()
        var targetAssistantId: String? = null
        var stepIdx = 0
        if (req.watchPush != null) {
            if (!settings.githubConfigured) return
            buildAndFix(req, "build check", null, emptySet(), req.watchPush, null, 0)
            return
        }
        if (!req.pushOnly) {
            val result = runAgentLoop(req, req.userPrompt, req.imagesBase64, req.docs)
            changed = result.changed
            summary = result.summary
            touched = result.touched
            removed = result.removed
            targetAssistantId = result.assistantMessageId
            stepIdx = result.lastStepIndex
            if (!result.ok) return
        } else {
            changed = true
            summary = "manual push"
        }
        val githubReady = settings.githubConfigured
        if (req.pushOnly && !githubReady) {
            postNote(req.conversationId, "⚠️ GitHub is not connected. Open Settings → GitHub and connect your account.")
            _agentEvents.tryEmit(AgentEvent.Finished("GitHub not connected", 0))
            return
        }
        if (githubReady && (req.pushOnly || (settings.autoPushBuild && changed))) {
            buildAndFix(req, summary, touched, removed, null, targetAssistantId, stepIdx)
        }
    }

    // ---------------------------------------------------------------------------------------
    // GitHub: Termux sync -> push changed files -> wait for build -> stream logs -> auto-fix
    // ---------------------------------------------------------------------------------------

    private suspend fun buildAndFix(
        req: RunRequest,
        summaryHint: String,
        firstTouched: Set<String>?,
        firstRemoved: Set<String>,
        initialPush: PushResult?,
        existingAssistantId: String?,
        startingStepIndex: Int
    ) {
        val pat = settings.githubToken
        val repo = settings.repoFor(req.projectId)
        if (repo == null) {
            postNote(
                req.conversationId,
                "⚠️ No GitHub repository is selected for this project. Open Settings → GitHub and choose or create one."
            )
            _agentEvents.tryEmit(AgentEvent.Finished("No repository selected", 0))
            return
        }
        val maxAttempts = settings.maxBuildFixAttempts
        var attempt = 0
        var touched: Set<String>? = firstTouched
        var removed: Set<String> = firstRemoved
        var preDone: PushResult? = initialPush
        var currentAssistantId = existingAssistantId
        var stepCounter = startingStepIndex

        if (currentAssistantId == null) {
            val newMsgId = UUID.randomUUID().toString()
            messageDao.insertMessage(
                MessageEntity(
                    id = newMsgId,
                    conversationId = req.conversationId,
                    sender = "ASSISTANT",
                    content = "Building `$repo` on GitHub Actions…",
                    status = "SUCCESS"
                )
            )
            currentAssistantId = newMsgId
        }

        while (true) {
            stepCounter++
            val buildStepId = UUID.randomUUID().toString()
            val buildStartMs = System.currentTimeMillis()
            val subSteps = mutableListOf<JSONObject>()

            suspend fun syncBuildStep(
                stepStatus: String,
                isErr: Boolean,
                logOutput: String
            ) {
                val argsObj = JSONObject()
                    .put("repo", repo)
                    .put("workflow", ".github/workflows/build.yml")
                    .put("changed_files", JSONArray((touched ?: emptySet()).toList()))
                    .put("sub_steps", JSONArray(subSteps))
                messageDao.insertToolStep(
                    ToolStepEntity(
                        id = buildStepId,
                        messageId = currentAssistantId!!,
                        stepIndex = stepCounter,
                        toolName = "building",
                        type = "BUILD",
                        commandText = "$repo · GitHub Actions",
                        argumentsJson = argsObj.toString(),
                        resultText = logOutput.take(4000),
                        isError = isErr,
                        status = stepStatus,
                        durationMs = System.currentTimeMillis() - buildStartMs
                    )
                )
            }

            fun setSubStep(id: String, label: String, state: String, detail: String = "") {
                val idx = subSteps.indexOfFirst { it.optString("id") == id }
                val obj = JSONObject()
                    .put("id", id)
                    .put("label", label)
                    .put("state", state) // "RUNNING", "DONE", "ERROR"
                    .put("detail", detail)
                if (idx >= 0) subSteps[idx] = obj else subSteps.add(obj)
            }

            // Sub-step 1: Sync changed source files via Termux
            val projDir = projectRepository.projectDir(req.projectId)
            val displayPath = termuxBridge.toDisplayPath(projDir.absolutePath)
            val changedCount = (touched?.size ?: 0) + removed.size
            setSubStep("termux_sync", "1. Syncing changed files via Termux ($displayPath)", "RUNNING", "$changedCount file(s) staged")
            _agentEvents.tryEmit(AgentEvent.ToolStarted(stepCounter, "building", "{\"repo\":\"$repo\"}"))
            status("Building: Syncing files via Termux…")
            syncBuildStep("RUNNING", false, "Syncing changed files in $displayPath via Termux…")
            val syncExec = termuxBridge.execCommand("ls -la", cwd = projDir.absolutePath, timeoutSec = 15)
            setSubStep("termux_sync", "1. Synced source files in Termux ($displayPath)", "DONE", "exit=${syncExec.exitCode}")

            // Sub-step 2: Upload changed files to GitHub
            val push: PushResult
            if (preDone != null) {
                push = preDone
                preDone = null
                setSubStep("github_upload", "2. Uploaded changed files to GitHub ($repo)", "DONE", "commit ${push.commitSha.take(7)}")
            } else {
                setSubStep("github_upload", "2. Uploading changed source files to GitHub ($repo)", "RUNNING", "Pushing modified files…")
                status("Building: Uploading changed files to GitHub…")
                syncBuildStep("RUNNING", false, "Uploading modified files to https://github.com/$repo…")
                push = try {
                    githubManager.push(
                        projectId = req.projectId,
                        repo = repo,
                        deleteExtra = false,
                        message = "CodeForge: ${summaryHint.lineSequence().firstOrNull().orEmpty().take(60).ifBlank { "update" }}",
                        explicitDeletes = removed,
                        requireOverlap = false,
                        onlyPaths = touched
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setSubStep("github_upload", "2. Upload to GitHub failed", "ERROR", e.message ?: "Push failed")
                    syncBuildStep("FAILED", true, "GitHub push failed: ${e.message}")
                    postNote(req.conversationId, "⚠️ GitHub push failed: ${e.message}")
                    _agentEvents.tryEmit(AgentEvent.Finished("Push failed", stepCounter))
                    return
                }
                if (push.noChanges) {
                    setSubStep("github_upload", "2. GitHub repo already up to date", "DONE", "No new file diffs")
                    syncBuildStep("COMPLETED", false, "Repository $repo already has all current changes.")
                    _agentEvents.tryEmit(AgentEvent.Finished("No changes to push", stepCounter))
                    return
                }
                setSubStep(
                    "github_upload",
                    "2. Uploaded ${push.changedFiles + push.deletedFiles} changed file(s) to GitHub",
                    "DONE",
                    "commit ${push.commitSha.take(7)}"
                )
            }

            // Sub-step 3: Trigger & Queue GitHub Actions workflow
            val short = push.commitSha.take(7)
            setSubStep("workflow_queue", "3. Starting GitHub Actions build workflow ($short)", "RUNNING", "Waiting for runner…")
            status("Building: Waiting for GitHub Actions runner ($short)…")
            syncBuildStep("RUNNING", false, "Commit $short pushed to $repo.\nWaiting for GitHub Actions workflow (.github/workflows/build.yml)…")
            githubManager.log.info("Waiting for the GitHub Actions build…")

            // Sub-step 4: Stream live build status & logs
            var lastStreamStatus = "Queued on GitHub Actions…"
            val run = gitHubRepository.waitForRun(pat, repo, push.commitSha) { liveMsg ->
                lastStreamStatus = liveMsg
                status("Building: $liveMsg")
                setSubStep("workflow_queue", "3. GitHub Actions workflow triggered ($short)", "DONE", "Runner active")
                setSubStep("build_logs", "4. Streaming live GitHub build logs via Termux", "RUNNING", liveMsg)
                scope.launch {
                    syncBuildStep("RUNNING", false, "Commit: $short\nRepo: $repo\nLive Build Status: $liveMsg")
                }
            }

            if (run == null) {
                setSubStep("workflow_queue", "3. No GitHub Actions workflow started for $short", "ERROR", "Missing .github/workflows/build.yml")
                syncBuildStep(
                    "FAILED",
                    true,
                    "Pushed commit $short to $repo, but no GitHub Actions workflow started.\nAdd .github/workflows/build.yml in Settings → GitHub."
                )
                postNote(
                    req.conversationId,
                    "⚠️ Pushed commit $short, but GitHub did not start a build. Open Settings → GitHub and tap \"Add build workflow\", then push again."
                )
                _agentEvents.tryEmit(AgentEvent.Finished("No build run found", stepCounter))
                return
            }

            val projectName = projectRepository.getProject(req.projectId)?.name ?: "Project"
            if (run.conclusion == "success") {
                setSubStep("workflow_queue", "3. GitHub Actions workflow triggered ($short)", "DONE", "Runner completed")
                setSubStep("build_logs", "4. Streamed GitHub Actions build logs", "DONE", "0 errors")
                setSubStep("apk_artifact", "5. APK Build Succeeded ($short)", "DONE", run.htmlUrl)
                val successLog = "✓ GitHub Actions Build Succeeded!\nRepository: $repo\nCommit: $short\nWorkflow Run & APK Artifact: ${run.htmlUrl}"
                syncBuildStep("COMPLETED", false, successLog)
                _agentEvents.tryEmit(AgentEvent.ToolFinished(stepCounter, "building", successLog, false))
                githubManager.log.ok("Build succeeded for $short. APK: ${run.htmlUrl}")
                buildLogStore.add(
                    BuildLogEntry(
                        id = System.currentTimeMillis(), time = System.currentTimeMillis(), projectName = projectName,
                        repo = repo, commit = short, status = "SUCCESS", attempt = attempt + 1,
                        runUrl = run.htmlUrl, log = "Build succeeded."
                    )
                )
                postNote(
                    req.conversationId,
                    "✅ GitHub build succeeded for commit `$short`.\nDownload APK from Artifacts: ${run.htmlUrl}"
                )
                _agentEvents.tryEmit(AgentEvent.Finished("Build succeeded", stepCounter))
                return
            }
            if (run.conclusion != "failure") {
                setSubStep("build_logs", "4. Build finished (${run.conclusion ?: run.status})", "DONE", run.htmlUrl)
                syncBuildStep("COMPLETED", false, "Build finished with status: ${run.conclusion ?: run.status}\n${run.htmlUrl}")
                postNote(req.conversationId, "ℹ️ Build finished with status: ${run.conclusion ?: run.status}. ${run.htmlUrl}")
                _agentEvents.tryEmit(AgentEvent.Finished("Build ${run.conclusion}", stepCounter))
                return
            }

            attempt++
            githubManager.log.error("Build FAILED for $short (attempt $attempt). Reading the error log…")
            status("Building: Fetching failure log from GitHub…")
            val log = gitHubRepository.fetchFailureLog(pat, repo, run.id)
            // Also write build error log into Termux workspace so the user/agent can inspect it via Termux
            termuxBridge.execCommand(
                command = "mkdir -p .engine && cat << 'EOF' > .engine/last_github_build.log\n${log.take(8000)}\nEOF",
                cwd = projDir.absolutePath,
                timeoutSec = 15
            )
            val firstError = log.lineSequence()
                .firstOrNull { it.contains("error", ignoreCase = true) || it.startsWith("e: ") || it.contains("FAILED") }
                ?.trim()?.take(160) ?: "See the expanded build log below."

            setSubStep("workflow_queue", "3. GitHub Actions workflow triggered ($short)", "DONE", "Attempt $attempt/$maxAttempts")
            setSubStep("build_logs", "4. Build failed — extracted error logs via Termux", "ERROR", firstError)
            syncBuildStep("FAILED", true, "GitHub Actions Build Failed (commit $short, attempt $attempt/$maxAttempts):\n\n$log")
            _agentEvents.tryEmit(AgentEvent.ToolFinished(stepCounter, "building", log.take(500), true))

            buildLogStore.add(
                BuildLogEntry(
                    id = System.currentTimeMillis(), time = System.currentTimeMillis(), projectName = projectName,
                    repo = repo, commit = short, status = "FAILED", attempt = attempt,
                    runUrl = run.htmlUrl, log = log
                )
            )

            if (attempt > maxAttempts) {
                postNote(
                    req.conversationId,
                    "❌ Build is still failing after $maxAttempts auto-fix attempt(s). Tap the **building** action card above to inspect the full log.\nLast run: ${run.htmlUrl}"
                )
                _agentEvents.tryEmit(AgentEvent.Finished("Build still failing", stepCounter))
                return
            }

            if (settings.askBeforeBuildFix) {
                status("Build failed. Waiting for your permission to fix it…")
                val allowed = withTimeoutOrNull(15 * 60_000L) {
                    awaitDecision(
                        ApprovalRequest(
                            id = UUID.randomUUID().toString(),
                            toolName = "build_fix",
                            summary = "The GitHub build failed:\n$firstError\n\nLet the AI read the error log and fix the code?",
                            title = "GitHub Build Failed",
                            allowLabel = "Fix with AI",
                            denyLabel = "Not now"
                        )
                    ) == "ALLOW"
                } ?: false
                if (!allowed) {
                    postNote(req.conversationId, "OK, I paused auto-fix. Tap the **building** card above to inspect the build steps and logs.")
                    _agentEvents.tryEmit(AgentEvent.Finished("Build failed; waiting", stepCounter))
                    return
                }
            }
            insertUserNote(req.conversationId, "🔧 Auto-fixing GitHub build error (attempt $attempt/$maxAttempts)…")
            val prompt = "The GitHub Actions build FAILED after the last changes. Fix the compile/build errors.\n\n" +
                "Build log (errors extracted):\n```\n$log\n```\n\n" +
                "Read the affected files first, fix the root cause with minimal edits, and call finish when done."
            val result = runAgentLoop(req, prompt, emptyList())
            if (!result.ok) return
            currentAssistantId = result.assistantMessageId ?: currentAssistantId
            stepCounter = result.lastStepIndex
            touched = result.touched
            removed = result.removed
            if (!result.changed) {
                postNote(req.conversationId, "⚠️ The AI made no file changes for this build error, so I stopped. Last run: ${run.htmlUrl}")
                return
            }
        }
    }

    private suspend fun postNote(conversationId: String, text: String) {
        messageDao.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                sender = "ASSISTANT",
                content = text,
                status = "SUCCESS"
            )
        )
        touchConversation(conversationId)
    }

    private suspend fun insertUserNote(conversationId: String, text: String) {
        messageDao.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                sender = "USER",
                content = text,
                status = "SUCCESS"
            )
        )
    }

    private suspend fun renameConversationIfDefault(conversationId: String, title: String) {
        val c = conversationDao.getConversationById(conversationId) ?: return
        if (title.isNotBlank()) {
            conversationDao.updateConversation(c.copy(title = title.take(60)))
        }
    }

    private suspend fun touchConversation(conversationId: String) {
        val c = conversationDao.getConversationById(conversationId) ?: return
        conversationDao.updateConversation(c.copy(updatedAt = System.currentTimeMillis()))
    }

    // ---------------------------------------------------------------------------------------
    // The agent loop
    // ---------------------------------------------------------------------------------------

    private suspend fun loadHistory(conversationId: String): List<LlmMessage> {
        val conv = conversationDao.getConversationById(conversationId)
        val all = messageDao.getMessagesForConversationOnce(conversationId)
            .filter { (it.sender == "USER" || it.sender == "ASSISTANT") && it.content.isNotBlank() && it.status != "STREAMING" && it.status != "ERROR" }
            .toMutableList()
        if (all.isNotEmpty() && all.last().sender == "USER") all.removeAt(all.size - 1)

        val picked = ArrayList<MessageEntity>()
        var chars = 0
        for (m in all.asReversed()) {
            if (picked.size >= 30 || chars > 40_000) break
            picked.add(m)
            chars += m.content.length
        }
        picked.reverse()
        val result = ArrayList<LlmMessage>()
        if (!conv?.summaryText.isNullOrBlank()) {
            result.add(LlmMessage(role = "user", content = "[Summary of earlier conversation]\n${conv?.summaryText}"))
            result.add(LlmMessage(role = "assistant", content = "Understood. Continuing with that context."))
        }
        result.addAll(
            picked
                .map { LlmMessage(role = if (it.sender == "USER") "user" else "assistant", content = it.content.take(6000)) }
                .dropWhile { it.role == "assistant" }
        )
        return result
    }

    private fun startOfMonth(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun resolveReasoning(setting: ReasoningLevel, prompt: String, step: Int, lastToolError: Boolean): ReasoningLevel {
        if (setting != ReasoningLevel.AUTO) return setting
        // Fast low-latency thinking budget (1024 tok) so live Chain of Thought streams cleanly
        // without 12k-token stalls; LlmClient auto-falls back if the model doesn't support thinking.
        return ReasoningLevel.LOW
    }

    /**
     * Returns the cached Termux skills snapshot immediately (0 ms TTFT overhead) and
     * refreshes in the background if stale (>90s). Never blocks the first LLM token.
     */
    private fun getCachedTermuxSkillsAndRefreshAsync(): String {
        val now = System.currentTimeMillis()
        if ((cachedTermuxSkillsSnapshot.isBlank() || (now - cachedTermuxSkillsAtMs) >= 90_000L) &&
            termuxBridge.status.value.engineRunning
        ) {
            scope.launch { fetchTermuxSkillsSnapshot() }
        }
        return cachedTermuxSkillsSnapshot
    }

    /**
     * Fetches a lightweight snapshot of installed Termux skills & repositories via HTTP Bridge
     * (cached for 90s, 4s timeout, never uses local java.io.File).
     */
    private suspend fun fetchTermuxSkillsSnapshot(): String {
        val now = System.currentTimeMillis()
        if (cachedTermuxSkillsSnapshot.isNotBlank() && (now - cachedTermuxSkillsAtMs) < 90_000L) {
            return cachedTermuxSkillsSnapshot
        }
        if (!termuxBridge.status.value.engineRunning) return cachedTermuxSkillsSnapshot
        val probeCmd = """
python3 - << 'PYEOF'
import os
home = "/data/data/com.termux/files/home"
roots = [
    ("HOME", home),
    ("TOOLS", os.path.join(home, "forge", "tools")),
    ("PROJECTS", os.path.join(home, "forge", "projects")),
    ("CLAUDE_SKILLS", os.path.join(home, ".claude", "skills")),
    ("AGENTS_SKILLS", os.path.join(home, ".agents", "skills")),
    ("CODEX_SKILLS", os.path.join(home, ".codex", "skills")),
]
found = []
seen = set()
for label, root in roots:
    if not os.path.isdir(root):
        continue
    try:
        entries = sorted(os.listdir(root))
    except Exception:
        continue
    for name in entries:
        if name.startswith(".") and label == "HOME":
            continue
        p = os.path.join(root, name)
        if not os.path.isdir(p) or p in seen:
            continue
        markers = [m for m in ("SKILL.md", "AGENTS.md", "CLAUDE.md", "README.md", ".git", "package.json", "pyproject.toml") if os.path.exists(os.path.join(p, m))]
        if markers or label.endswith("SKILLS") or label in ("TOOLS", "PROJECTS"):
            seen.add(p)
            rel = p.replace(home, "~", 1)
            found.append(f"- [{label}] {name} -> {rel} ({', '.join(markers[:4])})")
        if len(found) >= 35:
            break
print("\n".join(found))
PYEOF
        """.trimIndent()
        return try {
            val res = withTimeoutOrNull(4_500L) {
                termuxBridge.execCommand(probeCmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 4)
            }
            if (res != null && res.exitCode == 0 && res.stdout.isNotBlank()) {
                cachedTermuxSkillsSnapshot = res.stdout.trim().take(1500)
                cachedTermuxSkillsAtMs = now
            }
            cachedTermuxSkillsSnapshot
        } catch (_: Exception) {
            cachedTermuxSkillsSnapshot
        }
    }

    private suspend fun buildSystemPrompt(
        projectId: String,
        projectName: String,
        projectPrompt: String?,
        mode: AgentMode,
        hasProject: Boolean
    ): String {
        val sb = StringBuilder()
        val understand = """
HOW TO UNDERSTAND THE USER
- The user writes informally, often in Hinglish (Hindi written in English letters) or mixed languages, with typos. Understand their real intent immediately.
- Use earlier conversation context. Do not start from scratch when the user says "is repo me", "ye fix karo", "wo skill chalao", etc.
- CRITICAL CLARIFICATION RULE: If a repo, skill, folder, command, or requirement is missing, ambiguous, or you are unsure where something is located in Termux or how the user wants it used, DO NOT GUESS OR LOOP — IMMEDIATELY call `ask_user` with a clear question and 2-4 helpful options (the user can also type a custom path/reply).
        """.trimIndent()

        val agentMemory = if (hasProject && settings.autoReadAgentMd) {
            projectRepository.readAgentMemory(projectId).take(2500)
        } else ""

        val installedTools = try {
            installedToolDao.getAllToolsOnce().filter { it.enabled }
        } catch (_: Exception) {
            emptyList()
        }
        val liveTermuxSkills = getCachedTermuxSkillsAndRefreshAsync()

        val bridgeMandate = """
CRITICAL CLOUD CODE ENGINE ARCHITECTURE (TERMUX HTTP BRIDGE)
- You are CodeForge v2 — a deterministic Cloud Code Engine controlling the user's real Termux environment via HTTP Bridge (`http://127.0.0.1:47831/exec`, cwd `/data/data/com.termux/files/home`).
- NEVER assume or inspect Android's app sandbox (`/data/data/com.aistudio...`). Everything lives in Termux (`/data/data/com.termux/files/home`).
- SURGICAL CODE PATCHING & FILE CREATION:
  1. ALWAYS call `read_file` before editing any existing file.
  2. Prefer minimal, surgical diffs using `apply_patch` (unified diff `@@` hunks), `edit_file` (exact/whitespace/indent-tolerant snippet replacement), `replace_line_range`, or `insert_after_line` for targeted fixes and refactors.
  3. Use `write_file` freely to create new files or perform full file writes when writing a complete file from scratch or replacing most of a file.
- INDEXED FILE DISCOVERY & NAVIGATION:
  1. Use `find_files` (ripgrep/glob indexed search) to locate target files in deep directories accurately.
  2. Use `search_code` (ripgrep regex search with file globs & context) to pinpoint exact functions, classes, or symbols before reading/patching.
- TERMUX INSTALLED SKILLS & REPOSITORIES MASTERY:
  1. When the user mentions an installed skill or repo (e.g., "is repo ka use karke...", "rea skill chalao", "mere repo se ye karo"), FIRST call `discover_skills_and_repos(query="...")` to automatically locate it across `~`, `~/forge/tools`, `~/forge/projects`, `~/.claude/skills`, `~/.agents/skills`, `~/.codex/skills` and read its `SKILL.md` / `AGENTS.md` / `README.md`.
  2. Follow the exact commands, flags, virtualenvs, and workflows defined in that repo/skill's `SKILL.md` or `README.md`.
  3. If `discover_skills_and_repos` cannot find the repo/skill or multiple matches exist, immediately call `ask_user` to ask the user for the exact folder path or permission.
- NON-BLOCKING EXECUTION DISCIPLINE:
  1. Default command timeout is 120s (pass `timeout` up to 1800s for builds/installs). Never run interactive prompts (`read`, `vim`, `nano`, `less`) or blocking servers in the foreground — use non-interactive flags (`-y`, `--yes`) and `nohup ... > out.log 2>&1 &` for daemons.
  2. If the bridge returns a real connection/auth error (401, 404, ECONNREFUSED), report it clearly and STOP. Do not loop.
        """.trimIndent()

        if (mode == AgentMode.CHAT) {
            sb.append(
                """
You are CodeForge v2 in PLAN / CHAT MODE (read-only analysis & Termux inspection) connected via `http://127.0.0.1:47831/exec`. Active workspace: "$projectName"${if (hasProject) "" else " (Termux Home `/data/data/com.termux/files/home`)"}.

$bridgeMandate

$understand

CHAT/PLAN MODE RULES
- Inspect files, discover repos/skills (`discover_skills_and_repos`, `find_files`, `search_code`, `read_file`), and run read-only Termux checks via `run_command`.
- If the user asks to patch files, explain the surgical plan or remind them to switch the header pill to `build` mode.
                """.trimIndent()
            )
        } else {
            sb.append(
                """
You are CodeForge v2 in BUILD MODE: an autonomous Cloud Code Engine controlling the user's real Termux environment via `http://127.0.0.1:47831/exec`. Active workspace: "$projectName"${if (hasProject) "" else " (Termux Home `/data/data/com.termux/files/home`)"}.

$bridgeMandate

$understand

BUILD MODE WORKFLOW
1. Discover first: use `discover_skills_and_repos`, `find_files`, and `search_code` to pinpoint exact paths and line numbers.
2. Read before patching: call `read_file` on the target range, then apply a minimal surgical patch using `apply_patch`, `edit_file`, `replace_line_range`, or `insert_after_line`.
3. Verify: run tests, linters, or the target CLI/repo tool via `run_command` or `run_installed_tool` to verify the fix.
4. When finished, write a concise summary in the user's language and call `finish`.
                """.trimIndent()
            )
        }

        sb.append(
            """


PROGRESS NOTES (shown in Activity Timeline, ALWAYS in English)
- Before calling tools, write ONE short plain-English sentence (max 14 words) saying what you are doing.
- In your first reply of a task, begin with a line "TITLE: " plus a 3-8 word English title.

RULES
- Default working directory in Termux is `/data/data/com.termux/files/home`.
- For simple checks, run ONE single command, read STDOUT, and answer immediately. NEVER repeat a command that already succeeded.
- Never read or print secrets (.env, keystores, google-services.json).
            """.trimIndent()
        )

        if (agentMemory.isNotBlank()) {
            sb.append("\n\nPROJECT MEMORY (AGENT.md):\n").append(agentMemory)
        }
        if (liveTermuxSkills.isNotBlank()) {
            sb.append("\n\nDISCOVERED TERMUX REPOS & SKILLS (live snapshot — call `discover_skills_and_repos` for full SKILL.md/README.md):\n")
            sb.append(liveTermuxSkills)
        }
        if (installedTools.isNotEmpty()) {
            sb.append("\n\nREGISTERED TOOLS IN APP DB (invoke via `run_installed_tool` or `run_command`):\n")
            installedTools.forEach { t ->
                sb.append("- **${t.name}** (id=`${t.id}`, type=${t.type}): entry=`${t.entryCommand}` in `${t.installPath}` — ${t.description}\n")
            }
        }

        val global = settings.globalSystemPrompt.trim()
        if (global.isNotEmpty()) sb.append("\n\nUSER INSTRUCTIONS (global):\n").append(global)
        if (!projectPrompt.isNullOrBlank()) sb.append("\n\nPROJECT INSTRUCTIONS:\n").append(projectPrompt.trim())
        val lang = settings.language
        if (lang == "Auto" || lang.isBlank()) {
            sb.append("\n\nLANGUAGE: reply in the same language and style the user writes in (Hinglish -> Hinglish, Hindi -> Hindi, English -> English). Keep code, identifiers and file paths unchanged.")
        } else if (lang != "English") {
            sb.append("\n\nLANGUAGE: reply to the user in $lang (keep code, identifiers and file paths unchanged).")
        } else {
            sb.append("\n\nLANGUAGE: reply in English.")
        }
        return sb.toString()
    }

    private fun requestTitle(req: RunRequest, assistantId: String, prompt: String, isFirstTurn: Boolean) {
        scope.launch {
            try {
                val resp = providerRepository.executeWithFailover(
                    projectId = req.projectId,
                    conversationId = req.conversationId,
                    request = LlmRequest(
                        systemPrompt = "Write a short English title (3 to 7 words, imperative mood, no quotes, no trailing punctuation) describing the task in the user's message. The message may be informal Hinglish. Output ONLY the title.",
                        messages = listOf(LlmMessage(role = "user", content = prompt.take(700))),
                        tools = emptyList(),
                        reasoningLevel = ReasoningLevel.OFF,
                        maxTokens = 30
                    ),
                    preferredProviderId = settings.preferredProviderId,
                    strategy = settings.fallbackStrategy,
                    maxSwitches = settings.maxProviderSwitchesPerTurn
                )
                val title = resp.content.trim().trim('"', '\'', '.', ' ', '\n').lineSequence().firstOrNull().orEmpty().take(70)
                if (title.length >= 3) {
                    messageDao.insertToolStep(
                        ToolStepEntity(
                            id = UUID.randomUUID().toString(), messageId = assistantId, stepIndex = 0,
                            toolName = "note", type = "NOTE",
                            argumentsJson = JSONObject().put("title", title).put("text", "").toString(),
                            resultText = "", isError = false, status = "COMPLETED", durationMs = 0L
                        )
                    )
                    if (isFirstTurn) renameConversationIfDefault(req.conversationId, title)
                    _agentEvents.tryEmit(AgentEvent.NoteAdded(title))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // title is optional
            }
        }
    }

    private fun compactToolResults(messages: MutableList<LlmMessage>, thresholdChars: Int = 60_000) {
        val toolIdx = messages.indices.filter { messages[it].role == "tool" }
        val total = toolIdx.sumOf { messages[it].content.length }
        if (total < thresholdChars) return
        for (i in toolIdx.dropLast(6)) {
            val m = messages[i]
            if (m.content.length > 500) {
                messages[i] = m.copy(content = m.content.take(400) + "\n[older tool result trimmed to save context window]")
            }
        }
    }

    private suspend fun runAgentLoop(
        req: RunRequest,
        prompt: String,
        images: List<String>,
        docs: List<LlmDocument> = emptyList()
    ): LoopResult {
        val project = projectRepository.getProject(req.projectId)
        if (project == null) {
            _agentEvents.tryEmit(AgentEvent.Error("Project not found. Select or import a project first."))
            return LoopResult(false, false, "")
        }

        val history = loadHistory(req.conversationId)
        val assistantId = UUID.randomUUID().toString()
        messageDao.insertMessage(
            MessageEntity(
                id = assistantId,
                conversationId = req.conversationId,
                sender = "ASSISTANT",
                content = "",
                status = "STREAMING"
            )
        )

        val hasProject = project.id != ProjectRepository.SCRATCH_ID
        try {
            if (req.mode == AgentMode.BUILD && hasProject && settings.autoCheckpointBeforeBuild) {
                val cp = projectRepository.createCheckpoint(req.projectId, "Before: ${prompt.lineSequence().firstOrNull().orEmpty().take(50)}")
                projectRepository.recordRunCheckpoint(req.projectId, cp.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // continue without checkpoint
        }

        // If user attached an APK or ZIP archive for analysis/reverse-engineering, run analysis & show Save Output card
        if (!req.attachedArchivePath.isNullOrBlank()) {
            val archiveFile = File(req.attachedArchivePath)
            if (archiveFile.exists()) {
                status("Analyzing attached archive ${archiveFile.name}…")
                val outDir = File(projectRepository.outputsRootDir, archiveFile.nameWithoutExtension + "_" + System.currentTimeMillis())
                val analysis = RepoToolInstaller.analyzeArchiveOrApk(archiveFile, outDir)
                handleAnalysisSavePrompt(req.projectId, project.name, archiveFile.name, outDir, analysis.markdownReport)
            }
        }

        val systemPrompt = buildSystemPrompt(project.id, project.name, project.systemPrompt, req.mode, hasProject)
        val activeTools = if (req.mode == AgentMode.CHAT) {
            toolsList.filter { it.name in READ_ONLY_TOOLS }
        } else {
            // Expose ALL surgical patching, discovery, skills, and Termux tools even in clean chat!
            toolsList
        }
        val messages = ArrayList<LlmMessage>(history)
        messages.add(LlmMessage(role = "user", content = prompt, imagesBase64 = images, docs = docs))

        val maxSteps = settings.maxSteps
        val fullText = StringBuilder()
        val reasoningAll = StringBuilder()
        var totalPrompt = 0
        var totalCompletion = 0
        var totalCached = 0
        var providerName: String? = null
        var modelName: String? = null
        var toolIndex = 0
        var anyChange = false
        var finishSummary: String? = null
        var lastToolError = false
        var errorText: String? = null
        var hitStepLimit = false
        val touchedAll = LinkedHashSet<String>()
        val removedAll = LinkedHashSet<String>()

        // Read-Before-Edit guard & LoopGuard (Section 6.4 & 6.5)
        val readFilesInRun = HashSet<String>()
        val loopGuard = LoopGuard()
        var forceFinal = false
        var forcedOnce = false
        var titleRequested = false

        try {
            var step = 0
            while (true) {
                step++
                if (step > maxSteps) {
                    hitStepLimit = true
                    break
                }

                if (settings.budgetHardStop && settings.monthlyBudgetUsd > 0) {
                    val spent = usageDao.getCostSince(startOfMonth())
                    if (spent >= settings.monthlyBudgetUsd) {
                        errorText = "Monthly budget of $${"%.2f".format(settings.monthlyBudgetUsd)} reached (spent $${"%.2f".format(spent)}). " +
                            "Increase the budget in Settings or turn off the hard stop."
                        break
                    }
                }

                compactToolResults(messages)
                val thinkStatus = if (step == 1) "Thinking…" else "Analyzing step $step…"
                status(thinkStatus)
                updateProcess(AgentProcessPhase.THINKING, thinkStatus, stepIndex = step)
                val stepText = StringBuilder()
                val level = resolveReasoning(settings.reasoningLevel, prompt, step, lastToolError)

                val response: LlmResponse = providerRepository.executeWithFailover(
                    projectId = req.projectId,
                    conversationId = req.conversationId,
                    request = LlmRequest(
                        systemPrompt = systemPrompt,
                        messages = messages,
                        tools = activeTools,
                        reasoningLevel = level
                    ),
                    preferredProviderId = settings.preferredProviderId,
                    strategy = settings.fallbackStrategy,
                    maxSwitches = settings.maxProviderSwitchesPerTurn,
                    onChunk = { t ->
                        stepText.append(t)
                        _agentEvents.tryEmit(AgentEvent.StreamingChunk(t))
                    },
                    onReasoning = { t -> _agentEvents.tryEmit(AgentEvent.ReasoningChunk(t)) },
                    onFailoverNotice = { ev ->
                        if (settings.showProviderSwitchNotice) {
                            _agentEvents.tryEmit(AgentEvent.FailoverNotice(ev.fromProvider, ev.toProvider, ev.reason))
                        }
                    },
                    onAttempt = {
                        stepText.clear()
                        _agentEvents.tryEmit(AgentEvent.StreamingReset)
                    }
                )

                totalPrompt += response.promptTokens
                totalCompletion += response.completionTokens
                totalCached += response.cachedTokens
                providerName = response.providerUsed
                modelName = response.modelUsed
                if (!response.reasoning.isNullOrBlank()) {
                    if (reasoningAll.isNotEmpty()) reasoningAll.append("\n\n")
                    reasoningAll.append(response.reasoning)
                }
                if (forceFinal && response.toolCalls.isNotEmpty() && forcedOnce) {
                    if (fullText.isEmpty()) {
                        fullText.append(
                            "I paused because the same step was repeating without progress. " +
                                "Please clarify which file or approach you prefer, and I will continue right away."
                        )
                    }
                    break
                }
                if (response.toolCalls.isNotEmpty()) {
                    val raw = response.content.trim()
                    if (raw.isNotEmpty()) {
                        var noteText = raw
                        var title: String? = null
                        val m = Regex("^TITLE:\\s*(.+?)\\s*(\\n|$)").find(raw)
                        if (m != null) {
                            title = m.groupValues[1].trim()
                            noteText = raw.substring(m.range.last + 1).trim()
                        }
                        toolIndex++
                        val noteArgs = JSONObject().put("text", noteText.take(300))
                        if (title != null) noteArgs.put("title", title.take(80))
                        messageDao.insertToolStep(
                            ToolStepEntity(
                                id = UUID.randomUUID().toString(), messageId = assistantId, stepIndex = toolIndex,
                                toolName = "note", type = "NOTE", argumentsJson = noteArgs.toString(),
                                resultText = "", isError = false, status = "COMPLETED", durationMs = 0L
                            )
                        )
                        if (title != null && history.isEmpty()) {
                            renameConversationIfDefault(req.conversationId, title)
                        }
                        if (noteText.isNotBlank() && response.reasoning.isNullOrBlank()) {
                            val planLine = "• $noteText"
                            if (reasoningAll.isNotEmpty()) reasoningAll.append("\n")
                            reasoningAll.append(planLine)
                            _agentEvents.tryEmit(AgentEvent.ReasoningChunk(if (reasoningAll.length == planLine.length) planLine else "\n$planLine"))
                        }
                        _agentEvents.tryEmit(AgentEvent.NoteAdded(noteText))
                    }
                    _agentEvents.tryEmit(AgentEvent.StreamingReset)
                } else if (response.content.isNotBlank()) {
                    var answer = response.content.trim()
                    val m = Regex("^TITLE:\\s*(.+?)\\s*(\\n|$)").find(answer)
                    if (m != null) {
                        if (history.isEmpty()) renameConversationIfDefault(req.conversationId, m.groupValues[1].trim())
                        answer = answer.substring(m.range.last + 1).trim()
                    }
                    if (answer.isNotEmpty()) {
                        if (fullText.isNotEmpty()) fullText.append("\n\n")
                        fullText.append(answer)
                    }
                }

                messages.add(
                    LlmMessage(
                        role = "assistant",
                        content = response.content,
                        toolCalls = response.toolCalls.ifEmpty { null },
                        rawContentJson = response.rawContentJson,
                        rawFormat = response.rawFormat
                    )
                )

                if (response.toolCalls.isEmpty()) {
                    val fr = (response.finishReason ?: "").lowercase()
                    if ((fr == "max_tokens" || fr == "length") && response.content.isBlank()) {
                        errorText = "The model ran out of output tokens before answering. Try a smaller request."
                    }
                    break
                }

                if (forceFinal) forcedOnce = true
                var finished = false
                lastToolError = false
                for (call in response.toolCalls) {
                    if (!titleRequested) {
                        titleRequested = true
                        requestTitle(req, assistantId, prompt, history.isEmpty())
                    }

                    // Pre-call check (read-only): is this exact call currently blocked by the loop guard?
                    if (settings.loopGuardEnabled) {
                        val blockMsg = loopGuard.shouldBlock(call.name, call.argumentsJson)
                        if (blockMsg != null) {
                            toolIndex++
                            messageDao.insertToolStep(
                                ToolStepEntity(
                                    id = UUID.randomUUID().toString(),
                                    messageId = assistantId,
                                    stepIndex = toolIndex,
                                    toolName = "note",
                                    type = "NOTE",
                                    argumentsJson = JSONObject().put("text", "Trying a different approach…").toString(),
                                    resultText = blockMsg,
                                    isError = false,
                                    status = "COMPLETED",
                                    durationMs = 0L
                                )
                            )
                            _agentEvents.tryEmit(AgentEvent.NoteAdded("Trying a different approach…"))
                            messages.add(LlmMessage(role = "tool", content = blockMsg, toolCallId = call.id, toolName = call.name))
                            continue
                        }
                    }

                    toolIndex++
                    val stepId = UUID.randomUUID().toString()
                    _agentEvents.tryEmit(AgentEvent.ToolStarted(toolIndex, call.name, call.argumentsJson))
                    val callDesc = describeCall(call)
                    status(callDesc)
                    updateProcess(
                        phase = inferProcessPhase(call.name),
                        detail = callDesc,
                        activeCommand = extractActiveCommandPreview(call),
                        stepIndex = toolIndex
                    )
                    messageDao.insertToolStep(
                        ToolStepEntity(
                            id = stepId, messageId = assistantId, stepIndex = toolIndex,
                            toolName = call.name, type = inferStepType(call.name),
                            argumentsJson = call.argumentsJson,
                            resultText = "", isError = false, status = "RUNNING", durationMs = 0L
                        )
                    )
                    val t0 = System.currentTimeMillis()
                    val result = if (call.name == "finish") {
                        val s = try { JSONObject(call.argumentsJson).optString("summary") } catch (_: Exception) { "" }
                        finishSummary = s
                        finished = true
                        ToolResult("Done.", stepType = "FINISH")
                    } else {
                        val hardTimeoutMs = when (call.name) {
                            "ask_user" -> 200_000L
                            "run_command", "run_installed_tool", "git_clone", "install_repo_tool" -> {
                                val reqSec = try {
                                    JSONObject(call.argumentsJson).optInt("timeout", 120).coerceIn(10, 1800)
                                } catch (_: Exception) { 120 }
                                (reqSec + 25) * 1000L
                            }
                            else -> 90_000L
                        }
                        withTimeoutOrNull(hardTimeoutMs) {
                            executeTool(req.projectId, call, req.mode, readFilesInRun)
                        } ?: ToolResult(
                            text = "Tool `${call.name}` timed out after ${hardTimeoutMs / 1000}s and was safely aborted so the loop can continue.",
                            isError = true,
                            commandText = extractActiveCommandPreview(call),
                            stepType = inferStepType(call.name)
                        )
                    }

                    if (result.changed) {
                        anyChange = true
                        // Update AGENT.md learned notes when files change or commands run
                        if (settings.autoUpdateAgentMd && hasProject && result.touched.isNotEmpty()) {
                            projectRepository.appendLearnedProjectNote(
                                req.projectId,
                                "Modified ${result.touched.joinToString(", ")} (${result.diffSummary ?: "updated"})"
                            )
                        }
                    }
                    touchedAll.addAll(result.touched)
                    removedAll.addAll(result.removed)
                    if (result.isError) lastToolError = true

                    var toolText = result.text
                    if (toolText.length > 14_000) toolText = toolText.take(14_000) + "\n[result truncated]"

                    // Post-execution LoopGuard check with the REAL result of every tool
                    if (settings.loopGuardEnabled && call.name != "finish" && call.name != "ask_user" && call.name != "plan") {
                        val d = loopGuard.recordStep(
                            toolName = call.name,
                            argsJson = call.argumentsJson,
                            resultText = result.text,
                            isError = result.isError,
                            fileChanged = result.changed && !result.isError,
                            filePath = result.filePath
                        )
                        if (d.triggered) {
                            _agentEvents.tryEmit(AgentEvent.NoteAdded(d.activityNote.ifBlank { "Trying a different approach…" }))
                            val reread = d.forceReadFile
                            if (reread != null) {
                                val targetPath = resolveTermuxCwd(reread)
                                val rereadRes = termuxBridge.execCommand(
                                    command = "nl -ba ${shellEscapeSingleQuotes(targetPath)} | sed -n '1,250p'",
                                    cwd = TermuxBridge.TERMUX_HOME,
                                    timeoutSec = 20
                                )
                                val fullFileNow = if (rereadRes.exitCode == 0) rereadRes.stdout.take(8000) else ""
                                toolText += "\n\n${d.systemInjection}\nCurrent file content with line numbers:\n$fullFileNow"
                            } else {
                                toolText += "\n\n${d.systemInjection}"
                            }
                            if (d.shouldStop) forceFinal = true
                        }
                    }

                    messageDao.insertToolStep(
                        ToolStepEntity(
                            id = stepId,
                            messageId = assistantId,
                            stepIndex = toolIndex,
                            toolName = call.name,
                            type = result.stepType,
                            filePath = result.filePath,
                            diffSummary = result.diffSummary,
                            commandText = result.commandText,
                            cwdText = result.cwdText,
                            argumentsJson = call.argumentsJson,
                            resultText = (result.uiResultJson ?: result.text).take(6000),
                            isError = result.isError,
                            status = if (result.isError) "FAILED" else "COMPLETED",
                            durationMs = System.currentTimeMillis() - t0
                        )
                    )
                    _agentEvents.tryEmit(AgentEvent.ToolFinished(toolIndex, call.name, (result.uiResultJson ?: result.text).take(500), result.isError))
                    messages.add(
                        LlmMessage(
                            role = "tool",
                            content = toolText,
                            toolCallId = call.id,
                            toolName = call.name
                        )
                    )
                    if (result.fatalBridgeError) {
                        errorText = result.text
                        finished = true
                        break
                    }
                }
                if (finished) break
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                finalizeMessage(
                    assistantId, req.conversationId,
                    (fullText.toString() + "\n\n⏹ Stopped by user.").trim(),
                    reasoningAll.toString(), "SUCCESS", providerName, modelName, totalPrompt, totalCompletion, totalCached
                )
                _agentEvents.tryEmit(AgentEvent.Finished("Stopped", toolIndex))
            }
            throw e
        } catch (e: Exception) {
            val rawMsg = e.message ?: e.javaClass.simpleName
            val isRateLimit = (e is com.example.data.api.ApiException && e.statusCode == 429) ||
                rawMsg.contains("429") ||
                rawMsg.contains("Rate Limit", ignoreCase = true) ||
                rawMsg.contains("Quota", ignoreCase = true) ||
                rawMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
            val msg = if (isRateLimit) {
                "API Rate Limit Exceeded. Please change API key or provider."
            } else {
                rawMsg
            }
            val displayContent = if (isRateLimit) {
                msg
            } else {
                (fullText.toString() + "\n\n⚠️ $msg").trim()
            }
            finalizeMessage(
                assistantId, req.conversationId,
                displayContent,
                reasoningAll.toString(), "ERROR", providerName, modelName, totalPrompt, totalCompletion, totalCached
            )
            _agentEvents.tryEmit(AgentEvent.Error(msg))
            return LoopResult(false, anyChange, "", touchedAll, removedAll, assistantId, toolIndex)
        }

        val body = StringBuilder(fullText.toString())
        val fs = finishSummary
        if (!fs.isNullOrBlank() && !body.contains(fs.trim().take(40))) {
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(fs.trim())
        }
        if (body.isBlank() && anyChange) {
            val names = (touchedAll + removedAll).map { it.substringAfterLast('/') }.distinct().take(8)
            body.append("Done. I changed: ").append(names.joinToString(", ")).append(". Check the Working Files panel or Changes tab to review or revert.")
        }
        if (hitStepLimit) {
            body.append("\n\n⏸ Reached the step limit ($maxSteps). Send \"continue\" to keep going, or raise the limit in Settings.")
        }
        errorText?.let { body.append("\n\n⚠️ ").append(it) }

        finalizeMessage(
            assistantId, req.conversationId, body.toString().trim().ifEmpty { "(no response)" },
            reasoningAll.toString(), if (errorText != null) "ERROR" else "SUCCESS",
            providerName, modelName, totalPrompt, totalCompletion, totalCached
        )
        _agentEvents.tryEmit(AgentEvent.Finished(fs ?: fullText.toString().take(200), toolIndex))
        touchedAll.removeAll(removedAll)
        return LoopResult(errorText == null, anyChange, fs ?: fullText.toString(), touchedAll, removedAll, assistantId, toolIndex)
    }

    private suspend fun handleAnalysisSavePrompt(
        activeProjectId: String,
        activeProjectName: String,
        archiveName: String,
        extractedDir: File,
        reportMarkdown: String
    ) {
        val options = listOf(
            "Save to a new project",
            "Save into \"$activeProjectName\"",
            "Save report only",
            "Don't save"
        )
        val choice = awaitDecision(
            ApprovalRequest(
                id = UUID.randomUUID().toString(),
                toolName = "save_analysis_output",
                title = "Save this source to a project?",
                summary = "Extracted and analyzed $archiveName.\nWhere would you like to save the extracted source tree and ANALYSIS.md report?",
                allowLabel = "Save to new project",
                denyLabel = "Don't save",
                options = options,
                isSaveOutputPrompt = true,
                extractedSourceDirPath = extractedDir.absolutePath,
                analysisReportMarkdown = reportMarkdown,
                sourceArchiveName = archiveName
            )
        )
        when (choice) {
            "Save to a new project", "ALLOW" -> {
                val newProj = projectRepository.createEmptyProject(archiveName.substringBeforeLast('.'))
                projectRepository.saveAnalysisOutputToProject(
                    targetProjectId = newProj.id,
                    sourceName = archiveName,
                    extractedDir = extractedDir,
                    analysisMarkdown = reportMarkdown,
                    reportOnly = false
                )
            }
            "Save into \"$activeProjectName\"" -> {
                if (activeProjectId != ProjectRepository.SCRATCH_ID) {
                    projectRepository.saveAnalysisOutputToProject(
                        targetProjectId = activeProjectId,
                        sourceName = archiveName,
                        extractedDir = extractedDir,
                        analysisMarkdown = reportMarkdown,
                        reportOnly = false
                    )
                }
            }
            "Save report only" -> {
                if (activeProjectId != ProjectRepository.SCRATCH_ID) {
                    projectRepository.saveAnalysisOutputToProject(
                        targetProjectId = activeProjectId,
                        sourceName = archiveName,
                        extractedDir = null,
                        analysisMarkdown = reportMarkdown,
                        reportOnly = true
                    )
                }
            }
            else -> {
                // Keep temporary files in ~/forge/outputs/ only
            }
        }
    }

    private suspend fun finalizeMessage(
        id: String, conversationId: String, content: String, reasoning: String, status: String,
        provider: String?, model: String?, promptTokens: Int, completionTokens: Int, cached: Int
    ) {
        val existing = messageDao.getMessageById(id) ?: return
        messageDao.updateMessage(
            existing.copy(
                content = content,
                reasoning = reasoning.ifBlank { null },
                status = status,
                providerUsed = provider,
                modelUsed = model,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cached
            )
        )
        touchConversation(conversationId)
    }

    private fun inferStepType(toolName: String): String = when (toolName) {
        "read_file", "list_files" -> "READ"
        "search_code", "find_files", "discover_skills_and_repos" -> "SEARCH"
        "apply_patch", "edit_file", "write_file", "insert_after_line", "replace_line_range", "delete_file", "move_file" -> "EDIT"
        "run_command", "git_clone", "install_repo_tool", "run_installed_tool" -> "CMD"
        "plan" -> "PLAN"
        "ask_user" -> "ASK"
        else -> "TOOL"
    }

    private fun inferProcessPhase(toolName: String): AgentProcessPhase = when (toolName) {
        "plan" -> AgentProcessPhase.PLANNING
        "find_files", "search_code", "list_files", "read_file", "discover_skills_and_repos" -> AgentProcessPhase.DISCOVERING
        "apply_patch", "edit_file", "insert_after_line", "replace_line_range", "write_file", "delete_file", "move_file" -> AgentProcessPhase.PATCHING
        "ask_user" -> AgentProcessPhase.WAITING_APPROVAL
        "finish" -> AgentProcessPhase.COMPLETED
        else -> AgentProcessPhase.EXECUTING_COMMAND
    }

    private fun extractActiveCommandPreview(call: LlmToolCall): String? {
        val a = try { JSONObject(call.argumentsJson) } catch (_: Exception) { JSONObject() }
        return when (call.name) {
            "run_command" -> a.optString("command").take(70).ifBlank { null }
            "apply_patch", "edit_file", "read_file", "write_file", "replace_line_range", "insert_after_line" ->
                a.optString("path").take(70).ifBlank { null }
            "find_files" -> a.optString("pattern").take(60).ifBlank { a.optString("path", "~") }
            "search_code" -> a.optString("query").take(60).ifBlank { null }
            "discover_skills_and_repos" -> a.optString("query").ifBlank { "Scanning Termux skills & repos" }
            "run_installed_tool" -> "${a.optString("tool_name")} ${a.optString("args")}".trim().take(70)
            else -> null
        }
    }

    private fun describeCall(call: LlmToolCall): String {
        val a = try { JSONObject(call.argumentsJson) } catch (_: Exception) { JSONObject() }
        return when (call.name) {
            "list_files" -> "Listing ${a.optString("path", ".")}"
            "find_files" -> "Discovering files matching “${a.optString("pattern", "*").take(36)}”"
            "read_file" -> "Reading ${a.optString("path")}"
            "search_code" -> "Searching regex “${a.optString("query").take(40)}”"
            "discover_skills_and_repos" -> {
                val q = a.optString("query").trim()
                if (q.isNotBlank()) "Finding Termux repo/skill “$q”" else "Scanning Termux skills & repos"
            }
            "apply_patch" -> "Patching ${a.optString("path")}"
            "edit_file" -> "Surgically editing ${a.optString("path")}"
            "insert_after_line" -> "Inserting lines in ${a.optString("path")}"
            "replace_line_range" -> "Replacing lines in ${a.optString("path")}"
            "write_file" -> "Creating ${a.optString("path")}"
            "delete_file" -> "Deleting ${a.optString("path")}"
            "move_file" -> "Moving ${a.optString("from_path")}"
            "run_command" -> "Running `${a.optString("command").take(42)}`"
            "run_installed_tool" -> "Running tool `${a.optString("tool_name")}`"
            "git_clone" -> "Cloning ${a.optString("url").substringAfterLast('/')}"
            "install_repo_tool" -> "Installing tool ${a.optString("url").substringAfterLast('/')}"
            "ask_user" -> "Waiting for your answer…"
            "plan" -> "Updating plan…"
            "update_memory" -> "Updating AGENT.md memory…"
            "finish" -> "Finishing up…"
            else -> "Running ${call.name}"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Permission & Approval helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun awaitApproval(toolName: String, summary: String, isRisky: Boolean = false): Boolean {
        val perm = settings.defaultPermissionLevel
        val shouldAsk = when (perm) {
            PermissionLevel.ASK_ALWAYS -> true
            PermissionLevel.ASK_RISKY -> isRisky || settings.safeMode
            PermissionLevel.AUTO_PROJECT -> isRisky && settings.confirmBeforeDestructiveCmd
        }
        if (!shouldAsk) return true
        return awaitDecision(ApprovalRequest(UUID.randomUUID().toString(), toolName, summary)) == "ALLOW"
    }

    private suspend fun awaitDecision(request: ApprovalRequest): String {
        val prevState = _processState.value
        updateProcess(AgentProcessPhase.WAITING_APPROVAL, request.title, stepIndex = prevState.stepIndex)
        val d = CompletableDeferred<String>()
        pendingDecision = d
        _approvalRequest.value = request
        try {
            // 3-minute safety timeout so an unanswered dialog can never freeze the agent forever
            return withTimeoutOrNull(180_000L) { d.await() } ?: "TIMEOUT_NO_RESPONSE"
        } finally {
            _approvalRequest.value = null
            pendingDecision = null
            if (_processState.value.phase == AgentProcessPhase.WAITING_APPROVAL) {
                _processState.value = prevState
            }
        }
    }

    private fun cleanPath(p: String): String = p.trim().removePrefix("./").trim('/').replace('\\', '/')
        .removePrefix("data/data/com.termux/files/home/").removePrefix("~/")

    private fun findNode(root: FileNode, path: String): FileNode? {
        val clean = path.trim().removePrefix("./").trim('/').ifEmpty { "." }
        if (clean == ".") return root
        fun walk(n: FileNode): FileNode? {
            if (n.path == clean) return n
            for (c in n.children) {
                val r = walk(c)
                if (r != null) return r
            }
            return null
        }
        return walk(root)
    }

    private fun renderTree(node: FileNode, depth: Int, out: StringBuilder, counter: IntArray) {
        for (child in node.children) {
            if (counter[0] >= 500) return
            counter[0]++
            val indent = "  ".repeat(depth)
            if (child.isDirectory) {
                out.append(indent).append(child.name).append("/\n")
                renderTree(child, depth + 1, out, counter)
            } else {
                out.append(indent).append(child.name).append("  (").append(child.sizeBytes).append(" B)\n")
            }
        }
    }

    private fun resolveTermuxCwd(relPath: String = "."): String {
        val trimmed = relPath.trim()
        return when {
            trimmed.isEmpty() || trimmed == "." || trimmed == "~" -> TermuxBridge.TERMUX_HOME
            trimmed.startsWith("~/") -> "${TermuxBridge.TERMUX_HOME}/${trimmed.removePrefix("~/")}"
            trimmed.startsWith("/data/data/com.termux") -> trimmed
            trimmed.startsWith("/sdcard") || trimmed.startsWith("/storage") -> trimmed
            trimmed.startsWith("/data/data/") || trimmed.startsWith("/data/user/") -> TermuxBridge.TERMUX_HOME
            trimmed.startsWith("/") -> "${TermuxBridge.TERMUX_HOME}/${trimmed.trimStart('/')}"
            else -> "${TermuxBridge.TERMUX_HOME}/${trimmed.removePrefix("./")}"
        }
    }

    private fun shellEscapeSingleQuotes(input: String): String =
        "'" + input.replace("'", "'\"'\"'") + "'"

    /** Only real bridge failures are fatal. A user command that times out (124) or exits with any code is NOT. */
    private fun isFatalBridgeExit(res: com.example.data.termux.ExecResult): Boolean = res.bridgeError

    private suspend fun readTermuxFileRaw(targetPath: String): Pair<String?, com.example.data.termux.ExecResult> {
        val pyCmd = "python3 -c \"import base64,pathlib,sys; p=pathlib.Path(${shellEscapeSingleQuotes(targetPath)}); " +
            "sys.exit(2) if not p.is_file() else print(base64.b64encode(p.read_bytes()).decode('ascii'))\""
        val res = termuxBridge.execCommand(command = pyCmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 30)
        if (res.exitCode != 0) return Pair(null, res)
        return try {
            val decoded = String(android.util.Base64.decode(res.stdout.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
            Pair(decoded, res)
        } catch (_: Exception) {
            Pair(null, res.copy(exitCode = 1, stderr = "Failed to decode file content for $targetPath"))
        }
    }

    private suspend fun writeTermuxFileAtomic(targetPath: String, content: String): com.example.data.termux.ExecResult {
        val b64 = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val cmd = "mkdir -p \$(dirname ${shellEscapeSingleQuotes(targetPath)}) && " +
            "printf '%s' '$b64' | base64 -d > ${shellEscapeSingleQuotes(targetPath)}.forge_tmp && " +
            "mv ${shellEscapeSingleQuotes(targetPath)}.forge_tmp ${shellEscapeSingleQuotes(targetPath)}"
        return termuxBridge.execCommand(command = cmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 60)
    }

    private suspend fun validatePatchedSyntax(targetPath: String): String? {
        val lower = targetPath.lowercase()
        val checkCmd = when {
            lower.endsWith(".py") -> "python3 -m py_compile ${shellEscapeSingleQuotes(targetPath)}"
            lower.endsWith(".json") -> "python3 -c \"import json,pathlib; json.loads(pathlib.Path(${shellEscapeSingleQuotes(targetPath)}).read_text())\""
            else -> return null
        }
        val res = termuxBridge.execCommand(command = checkCmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 15)
        return if (res.exitCode == 0) null else (res.stderr.ifBlank { res.stdout }).trim().take(500)
    }

    private fun buildDiffUiJson(
        commandLabel: String,
        cwd: String,
        filePath: String,
        diffPreview: String,
        addedLines: Int,
        removedLines: Int,
        stdout: String,
        stderr: String,
        exitCode: Int,
        durationMs: Long
    ): String = JSONObject()
        .put("command", commandLabel)
        .put("cwd", cwd)
        .put("file_path", filePath)
        .put("diff_preview", diffPreview)
        .put("added_lines", addedLines)
        .put("removed_lines", removedLines)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("exit_code", exitCode)
        .put("duration_ms", durationMs)
        .toString()

    private fun formatBridgeToolResult(
        execRes: com.example.data.termux.ExecResult,
        stepType: String = "CMD",
        filePath: String? = null,
        changed: Boolean = false,
        touched: List<String> = emptyList(),
        removed: List<String> = emptyList(),
        diffSummary: String? = null,
        overrideUiJson: String? = null
    ): ToolResult {
        val combined = buildString {
            appendLine("Command: ${execRes.command}")
            appendLine("CWD: ${execRes.cwd}")
            appendLine("Exit code: ${execRes.exitCode} (${execRes.durationMs} ms)")
            if (execRes.stdout.isNotBlank()) {
                appendLine("STDOUT:")
                appendLine(execRes.stdout.trim())
            }
            if (execRes.stderr.isNotBlank()) {
                appendLine("STDERR:")
                appendLine(execRes.stderr.trim())
            }
        }.trim()
        val fatal = isFatalBridgeExit(execRes)
        return ToolResult(
            text = combined,
            isError = execRes.exitCode != 0,
            changed = changed && execRes.exitCode == 0,
            touched = if (execRes.exitCode == 0) touched else emptyList(),
            removed = if (execRes.exitCode == 0) removed else emptyList(),
            diffSummary = diffSummary,
            commandText = execRes.command,
            cwdText = execRes.cwd,
            filePath = filePath,
            stepType = stepType,
            uiResultJson = overrideUiJson ?: execRes.toJsonSummary(),
            fatalBridgeError = fatal
        )
    }

    private suspend fun executeTool(
        projectId: String,
        call: LlmToolCall,
        mode: AgentMode,
        readFilesInRun: MutableSet<String>
    ): ToolResult {
        if (mode == AgentMode.CHAT && call.name !in READ_ONLY_TOOLS) {
            return ToolResult(
                "Chat mode is read-only: you cannot use ${call.name}. Do not retry it. Tell the user (in their language) that they are in Chat mode " +
                    "and should switch to Build mode using the Chat | Build toggle in the header before you can edit files.",
                isError = true
            )
        }
        val args = try {
            JSONObject(if (call.argumentsJson.isBlank()) "{}" else call.argumentsJson)
        } catch (_: Exception) {
            return ToolResult(
                "Error: tool arguments were not valid JSON (your output was probably cut off). " +
                    "Retry with a smaller payload, e.g. several small edit_file calls instead of one huge write_file.",
                isError = true
            )
        }
        return try {
            when (call.name) {
                "list_files" -> {
                    val path = args.optString("path", ".")
                    val targetPath = resolveTermuxCwd(path)
                    val cmd = "ls -la ${shellEscapeSingleQuotes(targetPath)}"
                    val execRes = termuxBridge.execCommand(
                        command = cmd,
                        cwd = TermuxBridge.TERMUX_HOME,
                        timeoutSec = 60
                    )
                    formatBridgeToolResult(execRes, stepType = "READ", filePath = cleanPath(path))
                }
                "find_files" -> {
                    val rawPath = args.optString("path", ".")
                    val pattern = args.optString("pattern", "").trim()
                    val maxDepth = args.optInt("max_depth", 8).coerceIn(1, 20)
                    val targetPath = resolveTermuxCwd(rawPath)
                    val escapedRoot = shellEscapeSingleQuotes(targetPath)
                    val patternFilter = if (pattern.isNotBlank()) {
                        " | grep -iE -- ${shellEscapeSingleQuotes(pattern)}"
                    } else ""
                    val cmd = "if command -v rg >/dev/null 2>&1; then " +
                        "rg --files --max-depth $maxDepth --glob '!.git' --glob '!node_modules' --glob '!build' --glob '!.gradle' --glob '!__pycache__' --glob '!.venv' --glob '!.cache' --glob '!dist' --glob '!target' $escapedRoot 2>/dev/null$patternFilter | head -n 250; " +
                        "else " +
                        "find $escapedRoot -maxdepth $maxDepth -not -path '*/.git/*' -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/.gradle/*' -not -path '*/__pycache__/*' -not -path '*/.venv/*' -type f 2>/dev/null$patternFilter | head -n 250; " +
                        "fi"
                    val execRes = termuxBridge.execCommand(command = cmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 45)
                    val normalized = if (execRes.exitCode == 1 && execRes.stderr.isBlank() && execRes.stdout.isBlank()) {
                        execRes.copy(exitCode = 0, stdout = "(no files matched '$pattern' under $targetPath)")
                    } else execRes
                    formatBridgeToolResult(normalized, stepType = "SEARCH", filePath = cleanPath(rawPath))
                }
                "read_file" -> {
                    val path = args.getString("path")
                    val clean = cleanPath(path)
                    if (KeyStoreManager.isPotentialSecretFile(clean)) {
                        return ToolResult("Blocked: $clean looks like a secrets/credentials file and will not be read.", true, filePath = clean)
                    }
                    val targetPath = resolveTermuxCwd(path)
                    val start = if (args.has("start_line")) args.optInt("start_line", 1).coerceAtLeast(1) else 1
                    val end = if (args.has("end_line")) args.optInt("end_line", start + 250).coerceAtLeast(start) else (start + 250)
                    val cmd = "nl -ba ${shellEscapeSingleQuotes(targetPath)} | sed -n '${start},${end}p'"
                    val execRes = termuxBridge.execCommand(
                        command = cmd,
                        cwd = TermuxBridge.TERMUX_HOME,
                        timeoutSec = 60
                    )
                    if (execRes.exitCode == 0) {
                        readFilesInRun.add(clean)
                        recordWorkingFile(clean, WorkingFileState.READING)
                    }
                    formatBridgeToolResult(execRes, stepType = "READ", filePath = clean)
                }
                "search_code" -> {
                    val q = args.getString("query")
                    val isRegex = args.optBoolean("regex", true)
                    val glob = args.optString("glob", "").trim()
                    val contextLines = args.optInt("context_lines", 0).coerceIn(0, 5)
                    val searchRoot = resolveTermuxCwd(args.optString("path", "."))
                    val rootExpr = if (args.has("path")) shellEscapeSingleQuotes(searchRoot)
                    else "\$( [ -d ~/forge/projects ] && echo ~/forge/projects || echo ~ )"
                    val rgGlob = if (glob.isNotBlank()) " --glob ${shellEscapeSingleQuotes(glob)}" else ""
                    val rgRegexFlag = if (isRegex) "" else " -F"
                    val rgCtx = if (contextLines > 0) " -C $contextLines" else ""
                    val grepFlags = "-rnI -i " + (if (isRegex) "-E " else "-F ") +
                        (if (contextLines > 0) "-C $contextLines " else "") +
                        "--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build --exclude-dir=.gradle --exclude-dir=__pycache__ --exclude-dir=.venv --exclude-dir=.cache --exclude-dir=.npm --exclude-dir=storage --exclude-dir=.m2 "
                    val cmd = "if command -v rg >/dev/null 2>&1; then " +
                        "rg -n --no-heading --smart-case --max-filesize 2M$rgRegexFlag$rgCtx$rgGlob --glob '!.git' --glob '!node_modules' --glob '!build' --glob '!.gradle' --glob '!__pycache__' --glob '!.venv' -- ${shellEscapeSingleQuotes(q)} $rootExpr 2>&1 | head -n 300; " +
                        "else " +
                        "grep $grepFlags -e ${shellEscapeSingleQuotes(q)} $rootExpr 2>&1 | head -n 300; " +
                        "fi"
                    val execRes = termuxBridge.execCommand(
                        command = cmd,
                        cwd = TermuxBridge.TERMUX_HOME,
                        timeoutSec = 60
                    )
                    val normalized = if ((execRes.exitCode == 0 || execRes.exitCode == 1) && execRes.stdout.isBlank() && execRes.stderr.isBlank()) {
                        execRes.copy(exitCode = 0, stdout = "(no matches found for '$q')")
                    } else execRes
                    formatBridgeToolResult(normalized, stepType = "SEARCH")
                }
                "discover_skills_and_repos" -> {
                    val query = args.optString("query", "").trim()
                    val qB64 = android.util.Base64.encodeToString(query.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    val pyScript = """
python3 - << 'PYEOF'
import os, base64, json
query = base64.b64decode('$qB64').decode('utf-8', errors='ignore').strip().lower()
home = "/data/data/com.termux/files/home"
scan_roots = [
    ("HOME", home),
    ("TOOLS", os.path.join(home, "forge", "tools")),
    ("PROJECTS", os.path.join(home, "forge", "projects")),
    ("CLAUDE_SKILLS", os.path.join(home, ".claude", "skills")),
    ("AGENTS_SKILLS", os.path.join(home, ".agents", "skills")),
    ("CODEX_SKILLS", os.path.join(home, ".codex", "skills")),
]
items = []
seen = set()
for label, root in scan_roots:
    if not os.path.isdir(root):
        continue
    try:
        names = sorted(os.listdir(root))
    except Exception:
        continue
    for name in names:
        if name.startswith(".") and label == "HOME":
            continue
        p = os.path.join(root, name)
        if not os.path.isdir(p) or p in seen:
            continue
        markers = [m for m in ("SKILL.md", "AGENTS.md", "CLAUDE.md", "README.md", ".git", "package.json", "pyproject.toml", "setup.py", "Makefile", "Cargo.toml") if os.path.exists(os.path.join(p, m))]
        if not markers and label == "HOME":
            continue
        seen.add(p)
        doc_file = next((m for m in ("SKILL.md", "AGENTS.md", "CLAUDE.md", "README.md") if m in markers), None)
        doc_preview = ""
        if doc_file:
            try:
                with open(os.path.join(p, doc_file), "r", errors="ignore") as f:
                    doc_preview = "".join(f.readlines()[:55]).strip()
            except Exception:
                pass
        score = 0
        if query:
            nl = name.lower()
            if nl == query: score = 100
            elif query in nl: score = 70
            elif any(w in nl for w in query.split() if len(w) > 1): score = 50
            elif doc_preview and query in doc_preview.lower(): score = 30
            else: continue
        items.append((score, label, name, p, markers, doc_file, doc_preview))

items.sort(key=lambda x: (-x[0], x[1], x[2]))
if not items:
    print(f"NO_MATCHES_FOUND for query='{query}'. Call ask_user to ask the user where this repo/skill is located.")
else:
    for score, label, name, p, markers, doc_file, doc_preview in items[:15]:
        print(f"=== [{label}] {name} ===")
        print(f"Path: {p}")
        print(f"Markers: {', '.join(markers) if markers else 'directory'}")
        if query and doc_preview:
            print(f"--- {doc_file} (excerpt) ---")
            print(doc_preview[:2400])
        elif doc_file and doc_preview:
            first_line = next((l.strip() for l in doc_preview.splitlines() if l.strip()), "")
            if first_line:
                print(f"Summary ({doc_file}): {first_line[:160]}")
        print()
PYEOF
                    """.trimIndent()
                    val execRes = termuxBridge.execCommand(command = pyScript, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 30)
                    if (execRes.exitCode == 0 && execRes.stdout.isNotBlank() && query.isBlank()) {
                        cachedTermuxSkillsSnapshot = execRes.stdout.take(1500)
                        cachedTermuxSkillsAtMs = System.currentTimeMillis()
                    }
                    formatBridgeToolResult(execRes, stepType = "SEARCH")
                }
                "apply_patch" -> {
                    val rawPath = args.getString("path")
                    val patchText = args.getString("patch")
                    val path = cleanPath(rawPath)
                    if (!awaitApproval("apply_patch", "Apply surgical patch to $path in Termux")) {
                        return ToolResult("The user denied this patch.", true, filePath = path, stepType = "EDIT")
                    }
                    val targetPath = resolveTermuxCwd(rawPath)
                    val t0 = System.currentTimeMillis()
                    val (originalContent, readRes) = readTermuxFileRaw(targetPath)
                    if (originalContent == null) {
                        return formatBridgeToolResult(
                            readRes.copy(stderr = readRes.stderr.ifBlank { "File not found: $targetPath" }),
                            stepType = "EDIT",
                            filePath = path
                        )
                    }
                    val (patchedContent, outcome) = SurgicalPatchEngine.applyUnifiedPatch(originalContent, patchText, path)
                    if (!outcome.success) {
                        val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        return ToolResult(
                            text = outcome.message,
                            isError = true,
                            filePath = path,
                            commandText = "apply_patch $path",
                            cwdText = TermuxBridge.TERMUX_HOME,
                            stepType = "EDIT",
                            uiResultJson = buildDiffUiJson(
                                commandLabel = "apply_patch $path",
                                cwd = TermuxBridge.TERMUX_HOME,
                                filePath = path,
                                diffPreview = "",
                                addedLines = 0,
                                removedLines = 0,
                                stdout = "",
                                stderr = outcome.message,
                                exitCode = 1,
                                durationMs = dur
                            )
                        )
                    }
                    val writeRes = writeTermuxFileAtomic(targetPath, patchedContent)
                    if (writeRes.exitCode != 0) {
                        return formatBridgeToolResult(writeRes, stepType = "EDIT", filePath = path)
                    }
                    val syntaxWarn = validatePatchedSyntax(targetPath)
                    val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val diffLabel = "+${outcome.linesAdded} -${outcome.linesRemoved}"
                    readFilesInRun.add(path)
                    recordWorkingFile(path, WorkingFileState.EDITED, diffLabel)
                    val summaryOut = buildString {
                        appendLine(outcome.message)
                        if (syntaxWarn != null) appendLine("⚠️ Syntax check warning:\n$syntaxWarn")
                        if (outcome.diffPreview.isNotBlank()) {
                            appendLine()
                            appendLine(outcome.diffPreview)
                        }
                    }.trim()
                    ToolResult(
                        text = summaryOut,
                        isError = false,
                        changed = true,
                        touched = listOf(path),
                        diffSummary = diffLabel,
                        commandText = "apply_patch $path ($diffLabel)",
                        cwdText = TermuxBridge.TERMUX_HOME,
                        filePath = path,
                        stepType = "EDIT",
                        uiResultJson = buildDiffUiJson(
                            commandLabel = "apply_patch $path",
                            cwd = TermuxBridge.TERMUX_HOME,
                            filePath = path,
                            diffPreview = outcome.diffPreview,
                            addedLines = outcome.linesAdded,
                            removedLines = outcome.linesRemoved,
                            stdout = outcome.message + (syntaxWarn?.let { "\nSyntax warning: $it" } ?: ""),
                            stderr = "",
                            exitCode = 0,
                            durationMs = dur
                        )
                    )
                }
                "edit_file" -> {
                    val rawPath = args.getString("path")
                    val path = cleanPath(rawPath)
                    if (!awaitApproval("edit_file", "Surgically edit $path in Termux")) {
                        return ToolResult("The user denied this edit.", true, filePath = path, stepType = "EDIT")
                    }
                    val targetPath = resolveTermuxCwd(rawPath)
                    val oldStr = args.getString("old_str")
                    val newStr = args.getString("new_str")
                    val replaceAll = args.optBoolean("replace_all", false)
                    val t0 = System.currentTimeMillis()
                    val (originalContent, readRes) = readTermuxFileRaw(targetPath)
                    if (originalContent == null) {
                        return formatBridgeToolResult(
                            readRes.copy(stderr = readRes.stderr.ifBlank { "File not found: $targetPath" }),
                            stepType = "EDIT",
                            filePath = path
                        )
                    }
                    val (updatedContent, outcome) = FileEditEngine.applyStructuredEdit(
                        content = originalContent,
                        rawOldText = oldStr,
                        rawNewText = newStr,
                        replaceAll = replaceAll
                    )
                    if (!outcome.success) {
                        val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                        return ToolResult(
                            text = outcome.message,
                            isError = true,
                            filePath = path,
                            commandText = "edit_file $path",
                            cwdText = TermuxBridge.TERMUX_HOME,
                            stepType = "EDIT",
                            uiResultJson = buildDiffUiJson(
                                commandLabel = "edit_file $path",
                                cwd = TermuxBridge.TERMUX_HOME,
                                filePath = path,
                                diffPreview = "",
                                addedLines = 0,
                                removedLines = 0,
                                stdout = "",
                                stderr = outcome.message,
                                exitCode = 1,
                                durationMs = dur
                            )
                        )
                    }
                    val writeRes = writeTermuxFileAtomic(targetPath, updatedContent)
                    if (writeRes.exitCode != 0) {
                        return formatBridgeToolResult(writeRes, stepType = "EDIT", filePath = path)
                    }
                    val syntaxWarn = validatePatchedSyntax(targetPath)
                    val unifiedDiff = SurgicalPatchEngine.generateUnifiedDiff(path, originalContent, updatedContent)
                    val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val diffLabel = "+${outcome.linesAdded} -${outcome.linesRemoved}"
                    readFilesInRun.add(path)
                    recordWorkingFile(path, WorkingFileState.EDITED, diffLabel)
                    val outMsg = buildString {
                        appendLine("Updated $path (${outcome.matchStrategyUsed}, $diffLabel).")
                        if (syntaxWarn != null) appendLine("⚠️ Syntax check warning:\n$syntaxWarn")
                        if (unifiedDiff.isNotBlank()) appendLine("\n$unifiedDiff")
                    }.trim()
                    ToolResult(
                        text = outMsg,
                        isError = false,
                        changed = true,
                        touched = listOf(path),
                        diffSummary = diffLabel,
                        commandText = "edit_file $path ($diffLabel)",
                        cwdText = TermuxBridge.TERMUX_HOME,
                        filePath = path,
                        stepType = "EDIT",
                        uiResultJson = buildDiffUiJson(
                            commandLabel = "edit_file $path",
                            cwd = TermuxBridge.TERMUX_HOME,
                            filePath = path,
                            diffPreview = unifiedDiff,
                            addedLines = outcome.linesAdded,
                            removedLines = outcome.linesRemoved,
                            stdout = outcome.message + (syntaxWarn?.let { "\nSyntax warning: $it" } ?: ""),
                            stderr = "",
                            exitCode = 0,
                            durationMs = dur
                        )
                    )
                }
                "insert_after_line" -> {
                    val rawPath = args.getString("path")
                    val path = cleanPath(rawPath)
                    if (!awaitApproval("insert_after_line", "Insert lines in $path")) {
                        return ToolResult("The user denied this edit.", true, filePath = path, stepType = "EDIT")
                    }
                    val targetPath = resolveTermuxCwd(rawPath)
                    val lineNum = args.getInt("line").coerceAtLeast(0)
                    val content = args.getString("content")
                    val t0 = System.currentTimeMillis()
                    val (originalContent, readRes) = readTermuxFileRaw(targetPath)
                    if (originalContent == null) {
                        return formatBridgeToolResult(readRes, stepType = "EDIT", filePath = path)
                    }
                    val (updatedContent, outcome) = FileEditEngine.applyStructuredEdit(
                        content = originalContent,
                        rawOldText = "",
                        rawNewText = content,
                        insertAfterLine = lineNum
                    )
                    val writeRes = writeTermuxFileAtomic(targetPath, updatedContent)
                    if (writeRes.exitCode != 0) {
                        return formatBridgeToolResult(writeRes, stepType = "EDIT", filePath = path)
                    }
                    val unifiedDiff = SurgicalPatchEngine.generateUnifiedDiff(path, originalContent, updatedContent)
                    val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val diffLabel = "+${outcome.linesAdded} -0"
                    recordWorkingFile(path, WorkingFileState.EDITED, diffLabel)
                    ToolResult(
                        text = "${outcome.message}\n\n$unifiedDiff",
                        isError = false,
                        changed = true,
                        touched = listOf(path),
                        diffSummary = diffLabel,
                        commandText = "insert_after_line $path:$lineNum ($diffLabel)",
                        cwdText = TermuxBridge.TERMUX_HOME,
                        filePath = path,
                        stepType = "EDIT",
                        uiResultJson = buildDiffUiJson(
                            commandLabel = "insert_after_line $path (after line $lineNum)",
                            cwd = TermuxBridge.TERMUX_HOME,
                            filePath = path,
                            diffPreview = unifiedDiff,
                            addedLines = outcome.linesAdded,
                            removedLines = 0,
                            stdout = outcome.message,
                            stderr = "",
                            exitCode = 0,
                            durationMs = dur
                        )
                    )
                }
                "replace_line_range" -> {
                    val rawPath = args.getString("path")
                    val path = cleanPath(rawPath)
                    if (!awaitApproval("replace_line_range", "Replace lines in $path")) {
                        return ToolResult("The user denied this edit.", true, filePath = path, stepType = "EDIT")
                    }
                    val targetPath = resolveTermuxCwd(rawPath)
                    val startLine = args.getInt("start_line").coerceAtLeast(1)
                    val endLine = args.getInt("end_line").coerceAtLeast(startLine)
                    val content = args.getString("content")
                    val t0 = System.currentTimeMillis()
                    val (originalContent, readRes) = readTermuxFileRaw(targetPath)
                    if (originalContent == null) {
                        return formatBridgeToolResult(readRes, stepType = "EDIT", filePath = path)
                    }
                    val (updatedContent, outcome) = FileEditEngine.applyStructuredEdit(
                        content = originalContent,
                        rawOldText = "",
                        rawNewText = content,
                        replaceStartLine = startLine,
                        replaceEndLine = endLine
                    )
                    val writeRes = writeTermuxFileAtomic(targetPath, updatedContent)
                    if (writeRes.exitCode != 0) {
                        return formatBridgeToolResult(writeRes, stepType = "EDIT", filePath = path)
                    }
                    val unifiedDiff = SurgicalPatchEngine.generateUnifiedDiff(path, originalContent, updatedContent)
                    val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val diffLabel = "+${outcome.linesAdded} -${outcome.linesRemoved}"
                    recordWorkingFile(path, WorkingFileState.EDITED, diffLabel)
                    ToolResult(
                        text = "${outcome.message}\n\n$unifiedDiff",
                        isError = false,
                        changed = true,
                        touched = listOf(path),
                        diffSummary = diffLabel,
                        commandText = "replace_line_range $path:$startLine..$endLine ($diffLabel)",
                        cwdText = TermuxBridge.TERMUX_HOME,
                        filePath = path,
                        stepType = "EDIT",
                        uiResultJson = buildDiffUiJson(
                            commandLabel = "replace_line_range $path ($startLine..$endLine)",
                            cwd = TermuxBridge.TERMUX_HOME,
                            filePath = path,
                            diffPreview = unifiedDiff,
                            addedLines = outcome.linesAdded,
                            removedLines = outcome.linesRemoved,
                            stdout = outcome.message,
                            stderr = "",
                            exitCode = 0,
                            durationMs = dur
                        )
                    )
                }
                "write_file" -> {
                    val rawPath = args.getString("path")
                    val path = cleanPath(rawPath)
                    if (!awaitApproval("write_file", "Write file $path in Termux")) {
                        return ToolResult("The user denied this write.", true, filePath = path, stepType = "EDIT")
                    }
                    val targetPath = resolveTermuxCwd(rawPath)
                    val content = args.getString("content")
                    val t0 = System.currentTimeMillis()

                    // Read previous content if the file exists so we produce a real unified diff & accurate CREATED/EDITED badge
                    val (existingContent, _) = readTermuxFileRaw(targetPath)
                    val isNewFile = existingContent == null

                    val writeRes = writeTermuxFileAtomic(targetPath, content)
                    if (writeRes.exitCode != 0) {
                        return formatBridgeToolResult(writeRes, stepType = "EDIT", filePath = path)
                    }
                    val syntaxWarn = validatePatchedSyntax(targetPath)
                    readFilesInRun.add(path)
                    val oldLines = existingContent?.lines().orEmpty()
                    val newLines = content.lines()
                    val addedCount = if (isNewFile) newLines.size else (newLines.size - oldLines.size).coerceAtLeast(1)
                    val removedCount = if (isNewFile) 0 else (oldLines.size - newLines.size).coerceAtLeast(0)
                    val diffLabel = if (isNewFile) "+${newLines.size}" else "+$addedCount -$removedCount"
                    val unifiedDiff = SurgicalPatchEngine.generateUnifiedDiff(path, existingContent.orEmpty(), content)
                    val dur = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    recordWorkingFile(path, if (isNewFile) WorkingFileState.CREATED else WorkingFileState.EDITED, diffLabel)
                    val verb = if (isNewFile) "Created" else "Updated"
                    val summaryOut = buildString {
                        appendLine("$verb $path (${newLines.size} lines, $diffLabel).")
                        if (syntaxWarn != null) appendLine("⚠️ Syntax check warning:\n$syntaxWarn")
                    }.trim()
                    ToolResult(
                        text = summaryOut,
                        isError = false,
                        changed = true,
                        touched = listOf(path),
                        diffSummary = diffLabel,
                        commandText = "write_file $path ($diffLabel)",
                        cwdText = TermuxBridge.TERMUX_HOME,
                        filePath = path,
                        stepType = "EDIT",
                        uiResultJson = buildDiffUiJson(
                            commandLabel = "write_file $path",
                            cwd = TermuxBridge.TERMUX_HOME,
                            filePath = path,
                            diffPreview = unifiedDiff,
                            addedLines = addedCount,
                            removedLines = removedCount,
                            stdout = summaryOut,
                            stderr = "",
                            exitCode = 0,
                            durationMs = dur
                        )
                    )
                }
                "delete_file" -> {
                    val rawPath = args.getString("path")
                    val path = cleanPath(rawPath)
                    if (!awaitApproval("delete_file", "Delete $path in Termux", isRisky = true)) {
                        return ToolResult("The user denied this deletion.", true, filePath = path, stepType = "EDIT")
                    }
                    val targetPath = resolveTermuxCwd(rawPath)
                    val execRes = termuxBridge.execCommand(
                        command = "rm -rf ${shellEscapeSingleQuotes(targetPath)}",
                        cwd = TermuxBridge.TERMUX_HOME,
                        timeoutSec = 120
                    )
                    if (execRes.exitCode == 0) recordWorkingFile(path, WorkingFileState.DELETED)
                    formatBridgeToolResult(execRes, stepType = "EDIT", filePath = path, changed = true, removed = listOf(path))
                }
                "move_file" -> {
                    val rawFrom = args.getString("from_path")
                    val rawTo = args.getString("to_path")
                    val from = cleanPath(rawFrom)
                    val to = cleanPath(rawTo)
                    if (!awaitApproval("move_file", "Move $from → $to in Termux")) {
                        return ToolResult("The user denied this move.", true, filePath = to, stepType = "EDIT")
                    }
                    val fromTarget = resolveTermuxCwd(rawFrom)
                    val toTarget = resolveTermuxCwd(rawTo)
                    val execRes = termuxBridge.execCommand(
                        command = "mkdir -p \$(dirname ${shellEscapeSingleQuotes(toTarget)}) && mv ${shellEscapeSingleQuotes(fromTarget)} ${shellEscapeSingleQuotes(toTarget)}",
                        cwd = TermuxBridge.TERMUX_HOME,
                        timeoutSec = 120
                    )
                    if (execRes.exitCode == 0) {
                        readFilesInRun.add(to)
                        recordWorkingFile(from, WorkingFileState.DELETED)
                        recordWorkingFile(to, WorkingFileState.CREATED)
                    }
                    formatBridgeToolResult(
                        execRes,
                        stepType = "EDIT",
                        filePath = to,
                        changed = true,
                        touched = listOf(to),
                        removed = listOf(from)
                    )
                }
                "run_command" -> {
                    val cmd = args.getString("command")
                    val relCwd = args.optString("cwd", TermuxBridge.TERMUX_HOME)
                    val targetCwd = resolveTermuxCwd(relCwd)
                    val isRisky = cmd.contains("rm -rf /")
                    if (!awaitApproval("run_command", "Run command in Termux:\n`$cmd`\nin `$targetCwd`", isRisky = isRisky)) {
                        return ToolResult("The user denied running this command.", true, commandText = cmd, cwdText = targetCwd, stepType = "CMD")
                    }
                    val cmdTimeout = args.optInt("timeout", 120).coerceIn(5, 1800)
                    val execRes = termuxBridge.execCommand(
                        command = cmd,
                        cwd = targetCwd,
                        timeoutSec = cmdTimeout
                    )
                    formatBridgeToolResult(execRes, stepType = "CMD")
                }
                "git_clone" -> {
                    val url = args.getString("url").trim()
                    val modeType = args.optString("mode", "project") // "project" or "tool"
                    if (modeType.equals("tool", ignoreCase = true)) {
                        return executeInstallRepoTool(url)
                    }
                    val repoName = url.substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
                    val termuxProjectsDir = "${TermuxBridge.TERMUX_HOME}/forge/projects"
                    val destPath = "$termuxProjectsDir/$repoName"
                    val cloneRes = termuxBridge.execCommand(
                        command = "mkdir -p \"$termuxProjectsDir\" && rm -rf \"$destPath\" && git clone --depth 1 \"$url\" \"$destPath\"",
                        cwd = TermuxBridge.TERMUX_HOME,
                        timeoutSec = 120
                    )
                    formatBridgeToolResult(
                        cloneRes,
                        stepType = "CMD",
                        changed = cloneRes.exitCode == 0
                    )
                }
                "install_repo_tool" -> {
                    val url = args.getString("url").trim()
                    executeInstallRepoTool(url)
                }
                "run_installed_tool" -> {
                    val toolQuery = args.getString("tool_name").trim()
                    val toolArgs = args.optString("args", "").trim()
                    val enabledTools = installedToolDao.getAllToolsOnce().filter { it.enabled }
                    val matched = enabledTools.firstOrNull {
                        it.id.equals(toolQuery, ignoreCase = true) ||
                            it.name.equals(toolQuery, ignoreCase = true) ||
                            it.name.contains(toolQuery, ignoreCase = true)
                    }
                    if (matched != null) {
                        val fullCmd = if (toolArgs.isNotBlank()) "${matched.entryCommand} $toolArgs" else matched.entryCommand
                        if (!awaitApproval("run_installed_tool", "Run installed tool `${matched.name}`:\n`$fullCmd`")) {
                            return ToolResult("User denied running tool ${matched.name}.", isError = true, commandText = fullCmd, stepType = "CMD")
                        }
                        val targetCwd = resolveTermuxCwd(matched.installPath)
                        val execRes = termuxBridge.execCommand(
                            command = fullCmd,
                            cwd = targetCwd,
                            timeoutSec = 120
                        )
                        return formatBridgeToolResult(execRes, stepType = "CMD")
                    }
                    // Fallback: check if a matching repo/skill/binary exists directly in Termux!
                    val safeQ = shellEscapeSingleQuotes(toolQuery)
                    val probeCmd = "if command -v $safeQ >/dev/null 2>&1; then echo \"BIN:\$(command -v $safeQ)\"; fi; " +
                        "for d in ~ ~/forge/tools ~/forge/projects ~/.claude/skills ~/.agents/skills ~/.codex/skills; do " +
                        "[ -d \"\$d\" ] && find \"\$d\" -maxdepth 2 -iname \"*${toolQuery.replace("\"", "").replace("'", "")}*\" 2>/dev/null; done | head -n 15"
                    val probeRes = termuxBridge.execCommand(command = probeCmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 20)
                    val binLine = probeRes.stdout.lines().firstOrNull { it.startsWith("BIN:") }
                    if (binLine != null) {
                        val fullCmd = if (toolArgs.isNotBlank()) "$toolQuery $toolArgs" else toolQuery
                        if (!awaitApproval("run_installed_tool", "Run Termux CLI `$fullCmd`")) {
                            return ToolResult("User denied running `$fullCmd`.", isError = true, commandText = fullCmd, stepType = "CMD")
                        }
                        val execRes = termuxBridge.execCommand(command = fullCmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 120)
                        return formatBridgeToolResult(execRes, stepType = "CMD")
                    }
                    val foundPaths = probeRes.stdout.trim()
                    val msg = if (foundPaths.isNotBlank()) {
                        "Tool '$toolQuery' is not in app DB, but matching Termux paths were found:\n$foundPaths\n" +
                            "Call `discover_skills_and_repos(query=\"$toolQuery\")` to read its SKILL.md/README.md and run it with `run_command`."
                    } else {
                        "Tool or repo '$toolQuery' was not found in app DB or standard Termux directories. " +
                            "Call `discover_skills_and_repos` or `ask_user` to ask the user where '$toolQuery' is located."
                    }
                    ToolResult(msg, isError = true, stepType = "CMD")
                }
                "ask_user" -> {
                    val question = args.getString("question")
                    val optsArr = args.optJSONArray("options") ?: JSONArray()
                    val opts = mutableListOf<String>()
                    for (i in 0 until optsArr.length()) {
                        opts.add(optsArr.optString(i))
                    }
                    val answer = awaitDecision(
                        ApprovalRequest(
                            id = UUID.randomUUID().toString(),
                            toolName = "ask_user",
                            title = "Question from Agent",
                            summary = question,
                            allowLabel = opts.firstOrNull() ?: "Continue",
                            denyLabel = opts.getOrNull(1) ?: "Cancel",
                            options = opts
                        )
                    )
                    ToolResult("User answered: $answer", stepType = "ASK")
                }
                "plan" -> {
                    val itemsArr = args.optJSONArray("steps") ?: JSONArray()
                    val sb = StringBuilder("Plan checklist:\n")
                    for (i in 0 until itemsArr.length()) {
                        sb.append("- [ ] ").append(itemsArr.optString(i)).append("\n")
                    }
                    ToolResult(sb.toString().trim(), stepType = "PLAN")
                }
                "update_memory" -> {
                    val note = args.getString("note")
                    projectRepository.appendLearnedProjectNote(projectId, note)
                    ToolResult("Updated AGENT.md with: $note", stepType = "EDIT", filePath = "AGENT.md")
                }
                else -> ToolResult("Error: unknown tool '${call.name}'", true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: org.json.JSONException) {
            ToolResult("Error: missing or invalid argument (${e.message}). Check the tool schema and retry.", true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    private suspend fun executeInstallRepoTool(url: String): ToolResult {
        val repoName = url.substringAfterLast('/').removeSuffix(".git").ifBlank { "tool" }
        val targetDir = File(projectRepository.toolsRootDir, repoName)
        if (!targetDir.exists()) {
            val cloneRes = termuxBridge.execCommand(
                command = "git clone --depth 1 \"$url\" \"${targetDir.absolutePath}\"",
                cwd = projectRepository.toolsRootDir.absolutePath,
                timeoutSec = 180
            )
            if (cloneRes.exitCode != 0 && !targetDir.exists()) {
                return ToolResult(
                    "Failed to clone tool repository $url:\n${cloneRes.stderr.ifBlank { cloneRes.stdout }}",
                    isError = true,
                    commandText = "git clone $url",
                    stepType = "CMD"
                )
            }
        }
        val analysis = RepoToolInstaller.inspectDirectory(targetDir)
        val planSummary = RepoToolInstaller.buildInstallPlanSummary(repoName, analysis, isToolInstall = true)
        val approved = awaitDecision(
            ApprovalRequest(
                id = UUID.randomUUID().toString(),
                toolName = "install_repo_tool",
                title = "Install tool: $repoName?",
                summary = planSummary,
                allowLabel = "Approve & Install",
                denyLabel = "Cancel"
            )
        ) == "ALLOW"
        if (!approved) {
            return ToolResult("User cancelled installation of $repoName.", isError = true, stepType = "CMD")
        }
        val logSb = StringBuilder()
        for (cmd in analysis.installCommands) {
            val r = termuxBridge.execCommand(cmd, cwd = targetDir.absolutePath, timeoutSec = 240)
            logSb.append("$ ").append(cmd).append(" -> exit ").append(r.exitCode).append("\n")
            if (r.stdout.isNotBlank()) logSb.append(r.stdout.take(600)).append("\n")
            if (r.stderr.isNotBlank()) logSb.append(r.stderr.take(600)).append("\n")
        }
        val smokeRes = termuxBridge.execCommand(analysis.smokeTestCommand, cwd = targetDir.absolutePath, timeoutSec = 30)
        val verifyStatus = if (smokeRes.exitCode == 0) "OK: ${smokeRes.stdout.lineSequence().firstOrNull().orEmpty().take(80)}" else "WARN: exit ${smokeRes.exitCode}"
        val toolEntity = InstalledToolEntity(
            id = repoName.lowercase(),
            name = repoName,
            repoUrl = url,
            path = targetDir.absolutePath,
            type = if (analysis.isMcpServer) "MCP" else "CLI",
            description = "Installed from $url (${analysis.detectedStack})",
            commandTemplate = analysis.entryCommand,
            enabled = true,
            status = verifyStatus
        )
        installedToolDao.insertTool(toolEntity)
        return ToolResult(
            text = "Installed & registered tool '$repoName' (${toolEntity.type}) at ${targetDir.absolutePath}.\n" +
                "Entry command: `${toolEntity.entryCommand}`\n" +
                "Verification: ${toolEntity.lastVerifyStatus}\n\nInstall log:\n$logSb",
            changed = true,
            commandText = analysis.installCommands.joinToString(" && "),
            cwdText = targetDir.absolutePath,
            stepType = "CMD"
        )
    }

    private val toolsList = listOf(
        LlmTool(
            name = "list_files",
            description = "List files and folders under a Termux directory path (use '.' for Termux home).",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string","description":"Directory path, e.g. '.' or '~/forge/projects/myapp'"}},"required":["path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "find_files",
            description = "Fast indexed file discovery in deep Termux directories using ripgrep/find with optional regex/glob filter (auto-excludes .git, node_modules, build, .gradle, __pycache__, .venv).",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string","description":"Root directory to scan (default: Termux home '.')"},"pattern":{"type":"string","description":"Optional case-insensitive regex to filter file paths, e.g. 'Auth.*\\.kt$' or 'SKILL\\.md'"},"max_depth":{"type":"integer","description":"Max directory depth (default 8, max 20)"}},"required":[]}
            """.trimIndent()
        ),
        LlmTool(
            name = "read_file",
            description = "Read a text file with line numbers. Always call read_file before patching a file. Optionally pass start_line/end_line (1-based).",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "search_code",
            description = "Fast indexed regex/text code search across Termux directories using ripgrep (rg) with grep fallback. Returns path:line: match.",
            parametersJsonSchema = """
                {"type":"object","properties":{"query":{"type":"string","description":"Regex or literal text to search for"},"regex":{"type":"boolean","description":"Treat query as regex (default true)"},"path":{"type":"string","description":"Directory to search (default: ~/forge/projects or ~)"},"glob":{"type":"string","description":"Optional file glob filter, e.g. '*.kt' or '*.py'"},"context_lines":{"type":"integer","description":"Lines of context around matches (0-5)"}},"required":["query"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "discover_skills_and_repos",
            description = "Automatically locate installed skills and Git repositories in Termux (~, ~/forge/tools, ~/forge/projects, ~/.claude/skills, ~/.agents/skills, ~/.codex/skills) and read their SKILL.md / AGENTS.md / README.md usage guides.",
            parametersJsonSchema = """
                {"type":"object","properties":{"query":{"type":"string","description":"Optional repo or skill name to search for and inspect its SKILL.md/README.md (leave empty to list all installed repos & skills)"}},"required":[]}
            """.trimIndent()
        ),
        LlmTool(
            name = "apply_patch",
            description = "PREFERRED surgical patching tool. Apply standard unified diff hunks (`@@ -oldStart,oldCount +newStart,newCount @@` with context/'-'/'+' lines) to modify only the specific buggy snippets in an existing file. Generates a diff preview.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string","description":"Target file path in Termux"},"patch":{"type":"string","description":"Unified diff patch containing one or more @@ hunks"}},"required":["path","patch"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "edit_file",
            description = "Surgical snippet replacement: replace old_str with new_str in an existing file you already read. Supports exact, whitespace-tolerant, and indentation-insensitive matching and outputs a unified diff preview.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"old_str":{"type":"string"},"new_str":{"type":"string"},"replace_all":{"type":"boolean"}},"required":["path","old_str","new_str"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "insert_after_line",
            description = "Surgically insert lines after a 1-based line number (use 0 to insert at top of file). Must read_file first.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"line":{"type":"integer"},"content":{"type":"string"}},"required":["path","line","content"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "replace_line_range",
            description = "Surgically replace 1-based inclusive line range [start_line, end_line] with content. Must read_file first.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"},"content":{"type":"string"}},"required":["path","start_line","end_line","content"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "write_file",
            description = "Create a BRAND-NEW file (or tiny <15-line config). Refuses full-file overwrites on existing files >15 lines — use apply_patch, edit_file, or replace_line_range instead.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "delete_file",
            description = "Delete a file or directory inside Termux.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "move_file",
            description = "Move or rename a file or directory inside Termux.",
            parametersJsonSchema = """
                {"type":"object","properties":{"from_path":{"type":"string"},"to_path":{"type":"string"}},"required":["from_path","to_path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "run_command",
            description = "Run a shell command in Termux via HTTP Bridge and capture stdout, stderr, and exit code.",
            parametersJsonSchema = """
                {"type":"object","properties":{"command":{"type":"string","description":"Shell command to execute"},"cwd":{"type":"string","description":"Working directory (default: Termux home)"},"timeout":{"type":"integer","description":"Max seconds to wait (default 120, max 1800). Never run interactive prompts or foreground servers; use nohup ... & for daemons."}},"required":["command"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "git_clone",
            description = "Clone a GitHub repository either into ~/forge/projects/<repo> (mode='project') or install it as a tool into ~/forge/tools/<repo> (mode='tool').",
            parametersJsonSchema = """
                {"type":"object","properties":{"url":{"type":"string"},"mode":{"type":"string","enum":["project","tool"]}},"required":["url"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "install_repo_tool",
            description = "Inspect, compatibility-check for Termux/ARM, install in ~/forge/tools/<repo>, smoke-test, and register a GitHub repo as a reusable CLI or MCP tool.",
            parametersJsonSchema = """
                {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "run_installed_tool",
            description = "Execute an installed tool, CLI, or skill from ~/forge/tools/ or Termux PATH by its name/ID with optional arguments.",
            parametersJsonSchema = """
                {"type":"object","properties":{"tool_name":{"type":"string","description":"Name or ID of the installed tool/skill"},"args":{"type":"string","description":"CLI arguments or subcommand to pass"}},"required":["tool_name"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "ask_user",
            description = "Pause and ask the user a clear question when a repo/skill/file cannot be found, permission is needed, or instructions are ambiguous. Provide 2-4 quick options (the user can also type any custom path or answer).",
            parametersJsonSchema = """
                {"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}},"required":["question"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "plan",
            description = "Record a short step-by-step checklist for a multi-step task.",
            parametersJsonSchema = """
                {"type":"object","properties":{"steps":{"type":"array","items":{"type":"string"}}},"required":["steps"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "update_memory",
            description = "Save a concise learned note (build command, stack detail, convention) into the project's AGENT.md file.",
            parametersJsonSchema = """
                {"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "finish",
            description = "Call when the task is completely done. Provide a concise summary of what was changed and why.",
            parametersJsonSchema = """
                {"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}
            """.trimIndent()
        )
    )
}
