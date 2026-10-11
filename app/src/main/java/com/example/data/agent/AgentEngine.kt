package com.example.data.agent

import android.content.Context
import com.example.data.api.LlmDocument
import com.example.data.api.LlmMessage
import com.example.data.github.GitHubManager
import com.example.data.repository.PushResult
import com.example.data.logs.BuildLogEntry
import com.example.data.logs.BuildLogStore
import kotlinx.coroutines.withTimeoutOrNull
import com.example.data.api.LlmRequest
import com.example.data.api.LlmResponse
import com.example.data.api.LlmTool
import com.example.data.api.LlmToolCall
import com.example.data.local.dao.ConversationDao
import com.example.data.local.dao.MessageDao
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.data.repository.GitHubRepository
import com.example.data.repository.ProjectRepository
import com.example.data.repository.ProviderRepository
import com.example.data.security.KeyStoreManager
import com.example.data.service.AgentForegroundService
import com.example.data.settings.SettingsStore
import com.example.domain.model.FileNode
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
import org.json.JSONObject
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

/** CHAT: talk, explain, answer, look at code (read-only). BUILD: may edit files. */
enum class AgentMode { CHAT, BUILD }

private val READ_ONLY_TOOLS = setOf("list_files", "read_file", "search_code")

data class RunRequest(
    val projectId: String,
    val conversationId: String,
    val userPrompt: String,
    val imagesBase64: List<String> = emptyList(),
    val docs: List<LlmDocument> = emptyList(),
    /** If true: skip the AI step and only push the project to GitHub, wait for the build and auto-fix. */
    val pushOnly: Boolean = false,
    /** A push that was already done (manually); only watch its build and fix errors if it fails. */
    val watchPush: PushResult? = null,
    val mode: AgentMode = AgentMode.BUILD
)

data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val summary: String,
    val title: String = "Allow this change?",
    val allowLabel: String = "Allow",
    val denyLabel: String = "Deny"
)

private data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val changed: Boolean = false,
    val touched: List<String> = emptyList(),
    val removed: List<String> = emptyList()
)

private data class LoopResult(
    val ok: Boolean,
    val changed: Boolean,
    val summary: String,
    val touched: Set<String> = emptySet(),
    val removed: Set<String> = emptySet()
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

    private var pendingDecision: CompletableDeferred<Boolean>? = null
    private var job: Job? = null

    fun start(request: RunRequest) {
        if (_isRunning.value) return
        _isRunning.value = true
        _runningConversationId.value = request.conversationId
        AgentForegroundService.startService(appContext, "Agent is working…")
        job = scope.launch {
            try {
                runFull(request)
            } catch (e: CancellationException) {
                // user stopped the run; message finalised inside the loop
            } catch (e: Exception) {
                _agentEvents.tryEmit(AgentEvent.Error(e.message ?: e.javaClass.simpleName))
            } finally {
                withContext(NonCancellable) {
                    _approvalRequest.value = null
                    pendingDecision = null
                    _isRunning.value = false
                    _runningConversationId.value = null
                    AgentForegroundService.stopService(appContext)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun resolveApproval(approved: Boolean) {
        pendingDecision?.complete(approved)
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
        if (req.watchPush != null) {
            // The user pushed manually from the GitHub screen: just follow that build.
            if (!settings.githubConfigured) return
            buildAndFix(req, "build check", null, emptySet(), req.watchPush)
            return
        }
        if (!req.pushOnly) {
            val result = runAgentLoop(req, req.userPrompt, req.imagesBase64, req.docs)
            changed = result.changed
            summary = result.summary
            touched = result.touched
            removed = result.removed
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
            buildAndFix(req, summary, touched, removed, null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // GitHub: push -> wait for build -> auto-fix loop
    // ---------------------------------------------------------------------------------------

    private suspend fun buildAndFix(
        req: RunRequest,
        summaryHint: String,
        firstTouched: Set<String>?,
        firstRemoved: Set<String>,
        initialPush: PushResult?
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
        // Only the files the AI changed are pushed (not the whole project).
        var touched: Set<String>? = firstTouched
        var removed: Set<String> = firstRemoved
        var preDone: PushResult? = initialPush

        while (true) {
            val push: PushResult
            if (preDone != null) {
                push = preDone
                preDone = null
            } else {
                status("Pushing changes to GitHub…")
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
                    postNote(req.conversationId, "⚠️ GitHub push failed: ${e.message}")
                    _agentEvents.tryEmit(AgentEvent.Finished("Push failed", 0))
                    return
                }
                if (push.noChanges) {
                    postNote(req.conversationId, "ℹ️ Nothing to push: $repo already has these changes.")
                    _agentEvents.tryEmit(AgentEvent.Finished("No changes to push", 0))
                    return
                }
                postNote(req.conversationId, "⬆️ " + githubManager.describe(push, repo))
            }
            status("Pushed ${push.changedFiles + push.deletedFiles} file(s). Waiting for the build…")
            githubManager.log.info("Waiting for the GitHub Actions build…")
            val run = gitHubRepository.waitForRun(pat, repo, push.commitSha) { status(it) }
            val short = push.commitSha.take(7)
            if (run == null) {
                githubManager.log.error("No build started for commit $short. Add .github/workflows/build.yml (Settings → GitHub → Add build workflow).")
                postNote(
                    req.conversationId,
                    "⚠️ Pushed commit $short, but GitHub did not start a build. The repository needs a workflow file `.github/workflows/build.yml` with `on: push`. " +
                        "Open Settings → GitHub and tap \"Add build workflow\", then push again."
                )
                _agentEvents.tryEmit(AgentEvent.Finished("No build run found", 0))
                return
            }
            val projectName = projectRepository.getProject(req.projectId)?.name ?: "Project"
            if (run.conclusion == "success") {
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
                    "✅ GitHub build succeeded for commit $short.\nDownload the APK from the Artifacts section: ${run.htmlUrl}"
                )
                _agentEvents.tryEmit(AgentEvent.Finished("Build succeeded", 0))
                return
            }
            if (run.conclusion != "failure") {
                postNote(req.conversationId, "ℹ️ Build finished with status: ${run.conclusion ?: run.status}. ${run.htmlUrl}")
                _agentEvents.tryEmit(AgentEvent.Finished("Build ${run.conclusion}", 0))
                return
            }

            attempt++
            githubManager.log.error("Build FAILED for $short (attempt $attempt). Reading the error log…")
            status("Build failed. Reading the error log…")
            val log = gitHubRepository.fetchFailureLog(pat, repo, run.id)
            // Always keep the error in the Builds tab so the user can read and copy it.
            buildLogStore.add(
                BuildLogEntry(
                    id = System.currentTimeMillis(), time = System.currentTimeMillis(), projectName = projectName,
                    repo = repo, commit = short, status = "FAILED", attempt = attempt,
                    runUrl = run.htmlUrl, log = log
                )
            )
            githubManager.log.error("Error log saved in the Builds tab.")
            val firstError = log.lineSequence()
                .firstOrNull { it.contains("error", ignoreCase = true) || it.startsWith("e: ") || it.contains("FAILED") }
                ?.trim()?.take(160) ?: "See the Builds tab for the full log."

            if (attempt > maxAttempts) {
                postNote(
                    req.conversationId,
                    "❌ Build is still failing after $maxAttempts auto-fix attempt(s). The error log is saved in the **Builds** tab.\nLast run: ${run.htmlUrl}"
                )
                _agentEvents.tryEmit(AgentEvent.Finished("Build still failing", 0))
                return
            }

            postNote(
                req.conversationId,
                "❌ GitHub build failed (attempt $attempt/$maxAttempts). The error log is saved in the **Builds** tab.\n`$firstError`"
            )
            if (settings.askBeforeBuildFix) {
                status("Build failed. Waiting for your permission to fix it…")
                val allowed = withTimeoutOrNull(15 * 60_000L) {
                    awaitDecision(
                        ApprovalRequest(
                            id = UUID.randomUUID().toString(),
                            toolName = "build_fix",
                            summary = "The GitHub build failed:\n$firstError\n\nLet the AI read the error log and try to fix the code?",
                            title = "Build failed",
                            allowLabel = "Fix with AI",
                            denyLabel = "Not now"
                        )
                    )
                } ?: false
                if (!allowed) {
                    postNote(req.conversationId, "OK, I will not change anything. You can copy the error from the Builds tab, or tell me to fix it later.")
                    _agentEvents.tryEmit(AgentEvent.Finished("Build failed; waiting", 0))
                    return
                }
            }
            insertUserNote(req.conversationId, "🔧 Fix the GitHub build error (attempt $attempt/$maxAttempts).")
            val prompt = "The GitHub Actions build FAILED after the last changes. Fix the compile/build errors.\n\n" +
                "Build log (errors extracted):\n```\n$log\n```\n\n" +
                "Read the affected files first, fix the root cause with minimal edits, and call finish when done."
            val result = runAgentLoop(req, prompt, emptyList())
            if (!result.ok) return
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
        val all = messageDao.getMessagesForConversationOnce(conversationId)
            .filter { (it.sender == "USER" || it.sender == "ASSISTANT") && it.content.isNotBlank() && it.status != "STREAMING" && it.status != "ERROR" }
            .toMutableList()
        // The newest USER message is the prompt of this run (added by the UI) - it is sent separately.
        if (all.isNotEmpty() && all.last().sender == "USER") all.removeAt(all.size - 1)

        val picked = ArrayList<MessageEntity>()
        var chars = 0
        for (m in all.asReversed()) {
            if (picked.size >= 30 || chars > 40_000) break
            picked.add(m)
            chars += m.content.length
        }
        picked.reverse()
        return picked
            .map { LlmMessage(role = if (it.sender == "USER") "user" else "assistant", content = it.content.take(6000)) }
            .dropWhile { it.role == "assistant" }
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
        val p = prompt.lowercase()
        val complex = prompt.length > 300 || listOf(
            "bug", "error", "crash", "fix", "refactor", "architecture", "build failed", "exception", "why", "not working"
        ).any { p.contains(it) }
        return when {
            step == 1 && complex -> ReasoningLevel.HIGH
            lastToolError -> ReasoningLevel.MEDIUM
            complex -> ReasoningLevel.MEDIUM
            prompt.length < 80 -> ReasoningLevel.LOW
            else -> ReasoningLevel.MEDIUM
        }
    }

    private fun buildSystemPrompt(projectName: String, projectPrompt: String?, mode: AgentMode, hasProject: Boolean): String {
        val sb = StringBuilder()
        val understand = """
HOW TO UNDERSTAND THE USER
- The user writes informally, often in Hinglish (Hindi written in English letters) or mixed languages, with typos. Work out what they REALLY want before you do anything.
- A message can be: (a) a question or a discussion ("what is the solution?", "why does this happen?"), (b) a bug report, (c) a request to change / add / remove / fix something, (d) a short follow-up like "done", "ok", "kar diya", "haan" which means: continue the task discussed earlier in this conversation.
- Use the earlier messages of this conversation. Do not start from zero when the user refers to "this", "it", "that feature" or "the file you mentioned".
- If something is unclear, ask ONE short, specific question instead of guessing or browsing files.
        """.trimIndent()

        if (mode == AgentMode.CHAT) {
            sb.append(
                """
You are CodeForge in CHAT MODE: a smart, friendly assistant like Claude, ChatGPT or Gemini that also understands code. The current project is "$projectName"${if (hasProject) "" else " (no project attached: this is a clean chat)"}.

$understand

CHAT MODE RULES
- Talk naturally: answer questions, explain, brainstorm, compare options, discuss bugs and solutions. Be direct, warm and concise. Greetings and general questions need NO tools.
- If a project is attached you may look at it with the READ-ONLY tools list_files, read_file and search_code, but only when the answer depends on the code (for example "which file contains the File Editor option?" -> search, then answer with the exact file path and the function or composable that holds it). Use at most 5 tool calls. Never read files just to look busy.
- You CANNOT modify files in chat mode. If the user asks you to change, add, remove, fix, rewrite or create something in the project, do NOT pretend you did it and do NOT call edit tools. Reply in the user's language and in your own words that they are currently in Chat mode, so please switch to Build mode with the Chat / Build switch next to the message box, and then tell you "done". If you already know what you would change, say it briefly (file and idea).
- You have no tool for running or compiling code. Do not claim you tested anything.
                """.trimIndent()
            )
        } else {
            sb.append(
                """
You are CodeForge in BUILD MODE: an expert coding agent working inside an Android app on the user's phone. The current project is "$projectName"${if (hasProject) "" else " (NO PROJECT ATTACHED)"}. You work like Claude Code: understand, locate, edit, verify, report.

$understand

${if (hasProject) "" else "NO PROJECT: there are no files to work on. Do not call tools. Tell the user (in their language) to attach a project ZIP with the + button, or to open a project, or to clone a GitHub repo, then continue. You may still answer questions in text."}

BUILD MODE WORKFLOW (for bug reports and change requests)
1. Plan in one sentence what the user wants (see PROGRESS NOTES). If you are not sure, ask a question and make NO tool calls.
2. Locate the code with at most 3 targeted search_code calls, then read only the files or line ranges you need. Never read the same file twice, never browse the whole tree. Do not repeat a tool call with the same arguments: its result is already in your context.
3. As soon as you know where the change belongs, EDIT. If after about 6 exploration calls you still cannot find it, STOP and ask the user which file or screen to look at. Do not keep searching.
4. Make the smallest correct change. Prefer edit_file (exact unique match). Use write_file only for new files or a full replacement; for big files use several small edit_file calls. If a feature must be removed, remove its UI and its now-unused code and imports completely, so the project still compiles.
5. read_file output has line-number prefixes ("   12<tab>code"). The prefix is NOT part of the file: never put it in old_str/new_str.
6. The project cannot be compiled on this device (builds run later on GitHub Actions), so double-check imports, nullability, signatures and brackets. After editing, re-read the edited region once to verify, then call finish.
7. If the user only asks a question or discusses, answer in text without editing.

FINAL REPORT (very important)
After your last tool call write a short report for the user in their language, like a colleague who just finished the work:
- What you did: 2-5 bullets with the file names that changed.
- If it was a bug: "The problem was ..." and "I fixed it by ...".
- What to test or check, and anything risky or left over.
No big code dumps. Then call finish with a one-line summary.
                """.trimIndent()
            )
        }

        sb.append(
            """


PROGRESS NOTES (shown in the "Summary" panel, ALWAYS in English)
- Every time you are about to call tools, first write ONE short plain-English sentence (max 14 words) saying what you are doing and why, for example: Looking for where the folder chips handle long press. No markdown, no code.
- In your first reply of a task, begin with a line "TITLE: " plus a 3-8 word English title (for example: TITLE: Remove reorder from folder chips), then the first progress sentence on the next line.

RULES
- Paths are relative to the project root. Do not touch build output folders.
- Never read or print secrets (.env, keystores, google-services.json). Those tools will refuse.
- Earlier chat turns only contain text summaries, not file contents; files may have changed since. Re-read before editing.
- If a tool returns an error, read the message and adjust. Do not repeat the same failing call.
            """.trimIndent()
        )
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

    /** Short English title for the Summary panel, produced by a tiny extra call (a few tokens). */
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
                    preferredProviderId = settings.preferredProviderId
                )
                val title = resp.content.trim().trim('"', '\'', '.', ' ', '\n').lineSequence().firstOrNull().orEmpty().take(70)
                if (title.length >= 3) {
                    messageDao.insertToolStep(
                        ToolStepEntity(
                            id = UUID.randomUUID().toString(), messageId = assistantId, stepIndex = 0,
                            toolName = "note", argumentsJson = JSONObject().put("title", title).put("text", "").toString(),
                            resultText = "", isError = false, status = "COMPLETED", durationMs = 0L
                        )
                    )
                    if (isFirstTurn) renameConversationIfDefault(req.conversationId, title)
                    _agentEvents.tryEmit(AgentEvent.NoteAdded(title))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the title is optional
            }
        }
    }

    /** Old file contents are the biggest token cost: keep only the newest tool results in full. */
    private fun compactToolResults(messages: MutableList<LlmMessage>) {
        val toolIdx = messages.indices.filter { messages[it].role == "tool" }
        val total = toolIdx.sumOf { messages[it].content.length }
        if (total < 60_000) return
        for (i in toolIdx.dropLast(6)) {
            val m = messages[i]
            if (m.content.length > 500) {
                messages[i] = m.copy(content = m.content.take(400) + "\n[older tool result trimmed to save tokens]")
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

        try {
            if (req.mode == AgentMode.BUILD && project.id != ProjectRepository.SCRATCH_ID) {
                val cp = projectRepository.createCheckpoint(req.projectId, "Before: ${prompt.lineSequence().firstOrNull().orEmpty().take(50)}")
                projectRepository.recordRunCheckpoint(req.projectId, cp.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // continue without checkpoint
        }

        val hasProject = project.id != ProjectRepository.SCRATCH_ID
        val systemPrompt = buildSystemPrompt(project.name, project.systemPrompt, req.mode, hasProject)
        // No project attached = nothing to read or edit, so no tools (the model just talks)
        val activeTools = when {
            !hasProject -> emptyList()
            req.mode == AgentMode.CHAT -> toolsList.filter { it.name in READ_ONLY_TOOLS }
            else -> toolsList
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
        // Loop guards: the agent must not wander through files forever and waste tokens
        val seenCalls = HashSet<String>()
        var exploreCalls = 0
        var dupCount = 0
        var forceFinal = false
        var titleRequested = false
        val softLimit = if (req.mode == AgentMode.BUILD) 8 else 4
        val hardLimit = if (req.mode == AgentMode.BUILD) 12 else 6

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
                status(if (step == 1) "Thinking…" else "Working (step $step)…")
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
                    onChunk = { t ->
                        stepText.append(t)
                        _agentEvents.tryEmit(AgentEvent.StreamingChunk(t))
                    },
                    onReasoning = { t -> _agentEvents.tryEmit(AgentEvent.ReasoningChunk(t)) },
                    onFailoverNotice = { ev ->
                        _agentEvents.tryEmit(AgentEvent.FailoverNotice(ev.fromProvider, ev.toProvider, ev.reason))
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
                if (forceFinal && response.toolCalls.isNotEmpty()) {
                    // The model ignored "stop exploring": end here with an honest message instead of burning more tokens.
                    if (fullText.isEmpty()) {
                        fullText.append(
                            "I looked through the project but could not reach a clear result, so I stopped to save your tokens. " +
                                "Please tell me the file or screen to look at (or describe the exact change), and I will continue from there."
                        )
                    }
                    break
                }
                if (response.toolCalls.isNotEmpty()) {
                    // Text written before tool calls = progress note for the Summary panel
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
                                toolName = "note", argumentsJson = noteArgs.toString(),
                                resultText = "", isError = false, status = "COMPLETED", durationMs = 0L
                            )
                        )
                        if (title != null && history.isEmpty()) {
                            renameConversationIfDefault(req.conversationId, title)
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

                var finished = false
                lastToolError = false
                for (call in response.toolCalls) {
                    val isExplore = call.name in READ_ONLY_TOOLS
                    val callKey = call.name + "|" + call.argumentsJson.trim()

                    // Same read/search again = going in circles: answer from memory instead of running it
                    if (isExplore && callKey in seenCalls) {
                        dupCount++
                        messages.add(
                            LlmMessage(
                                role = "tool",
                                content = "You already ran this exact call earlier in this run, its result is above. Do not repeat it. " +
                                    "Use what you know: make the edit, answer the user, or ask ONE clarifying question.",
                                toolCallId = call.id,
                                toolName = call.name
                            )
                        )
                        if (dupCount >= 3) forceFinal = true
                        continue
                    }
                    if (isExplore) seenCalls.add(callKey)

                    if (!titleRequested) {
                        titleRequested = true
                        requestTitle(req, assistantId, prompt, history.isEmpty())
                    }

                    toolIndex++
                    val stepId = UUID.randomUUID().toString()
                    _agentEvents.tryEmit(AgentEvent.ToolStarted(toolIndex, call.name, call.argumentsJson))
                    status(describeCall(call))
                    messageDao.insertToolStep(
                        ToolStepEntity(
                            id = stepId, messageId = assistantId, stepIndex = toolIndex,
                            toolName = call.name, argumentsJson = call.argumentsJson,
                            resultText = "", isError = false, status = "RUNNING", durationMs = 0L
                        )
                    )
                    val t0 = System.currentTimeMillis()
                    val result = if (call.name == "finish") {
                        val s = try { JSONObject(call.argumentsJson).optString("summary") } catch (e: Exception) { "" }
                        finishSummary = s
                        finished = true
                        ToolResult("Done.")
                    } else {
                        executeTool(req.projectId, call, req.mode)
                    }
                    if (result.changed) {
                        anyChange = true
                        seenCalls.clear() // files changed: reading them again is legitimate
                    }
                    touchedAll.addAll(result.touched)
                    removedAll.addAll(result.removed)
                    if (result.isError) lastToolError = true

                    var toolText = result.text
                    if (toolText.length > 14_000) toolText = toolText.take(14_000) + "\n[result truncated]"
                    if (isExplore) {
                        exploreCalls++
                        if (exploreCalls >= hardLimit) {
                            forceFinal = true
                            toolText += "\n\n[SYSTEM: exploration limit reached, you must stop browsing now. Reply to the user in plain text, in their language: " +
                                "what you found, what you could not find, and either the exact change you would make or ONE specific question for the user.]"
                        } else if (exploreCalls >= softLimit) {
                            toolText += "\n\n[SYSTEM: you have already used $exploreCalls exploration calls. Decide now: make the edit, answer the user, " +
                                "or ask a question. Do not keep browsing files.]"
                        }
                    }

                    messageDao.insertToolStep(
                        ToolStepEntity(
                            id = stepId, messageId = assistantId, stepIndex = toolIndex,
                            toolName = call.name, argumentsJson = call.argumentsJson,
                            resultText = result.text.take(4000), isError = result.isError,
                            status = if (result.isError) "FAILED" else "COMPLETED",
                            durationMs = System.currentTimeMillis() - t0
                        )
                    )
                    _agentEvents.tryEmit(AgentEvent.ToolFinished(toolIndex, call.name, result.text.take(500), result.isError))
                    messages.add(
                        LlmMessage(
                            role = "tool",
                            content = toolText,
                            toolCallId = call.id,
                            toolName = call.name
                        )
                    )
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
            val msg = e.message ?: e.javaClass.simpleName
            finalizeMessage(
                assistantId, req.conversationId,
                (fullText.toString() + "\n\n⚠️ $msg").trim(),
                reasoningAll.toString(), "ERROR", providerName, modelName, totalPrompt, totalCompletion, totalCached
            )
            _agentEvents.tryEmit(AgentEvent.Error(msg))
            return LoopResult(false, anyChange, "", touchedAll, removedAll)
        }

        val body = StringBuilder(fullText.toString())
        val fs = finishSummary
        if (!fs.isNullOrBlank() && !body.contains(fs.trim().take(40))) {
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(fs.trim())
        }
        if (body.isBlank() && anyChange) {
            val names = (touchedAll + removedAll).map { it.substringAfterLast('/') }.distinct().take(8)
            body.append("Done. I changed: ").append(names.joinToString(", ")).append(". Check the Changes tab to review or undo.")
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
        // a file edited and later deleted/moved in the same run is not "touched" any more
        touchedAll.removeAll(removedAll)
        return LoopResult(errorText == null, anyChange, fs ?: fullText.toString(), touchedAll, removedAll)
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

    private fun describeCall(call: LlmToolCall): String {
        val a = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
        return when (call.name) {
            "list_files" -> "Listing ${a.optString("path", ".")}"
            "read_file" -> "Reading ${a.optString("path")}"
            "search_code" -> "Searching for “${a.optString("query").take(40)}”"
            "edit_file" -> "Editing ${a.optString("path")}"
            "write_file" -> "Writing ${a.optString("path")}"
            "delete_file" -> "Deleting ${a.optString("path")}"
            "move_file" -> "Moving ${a.optString("from_path")}"
            "finish" -> "Finishing up…"
            else -> "Running ${call.name}"
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tools
    // ---------------------------------------------------------------------------------------

    /** Safe mode: ask before each file change. */
    private suspend fun awaitApproval(toolName: String, summary: String): Boolean {
        if (!settings.safeMode) return true
        return awaitDecision(ApprovalRequest(UUID.randomUUID().toString(), toolName, summary))
    }

    /** Always asks the user (dialog is shown on any screen) and waits for the answer. */
    private suspend fun awaitDecision(request: ApprovalRequest): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingDecision = d
        _approvalRequest.value = request
        try {
            return d.await()
        } finally {
            _approvalRequest.value = null
            pendingDecision = null
        }
    }

    private fun cleanPath(p: String): String = p.trim().removePrefix("./").trim('/').replace('\\', '/')

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

    private suspend fun executeTool(projectId: String, call: LlmToolCall, mode: AgentMode): ToolResult {
        if (mode == AgentMode.CHAT && call.name !in READ_ONLY_TOOLS) {
            return ToolResult(
                "Chat mode is read-only: you cannot use ${call.name}. Do not retry it. Tell the user (in their language) that they are in Chat mode " +
                    "and should switch to Build mode (the Chat / Build switch next to the message box) before you can change files, then wait for them to say done.",
                isError = true
            )
        }
        val args = try {
            JSONObject(if (call.argumentsJson.isBlank()) "{}" else call.argumentsJson)
        } catch (e: Exception) {
            return ToolResult(
                "Error: tool arguments were not valid JSON (your output was probably cut off). " +
                    "Retry with a smaller payload, e.g. several small edit_file calls instead of one huge write_file.",
                isError = true
            )
        }
        return try {
            when (call.name) {
                "list_files" -> {
                    val root = projectRepository.getFileTree(projectId)
                        ?: return ToolResult("Error: project folder not found", true)
                    val path = args.optString("path", ".")
                    val node = findNode(root, path) ?: return ToolResult("Error: path not found: $path", true)
                    if (!node.isDirectory) return ToolResult("${node.path} is a file (${node.sizeBytes} B)")
                    val sb = StringBuilder()
                    val counter = intArrayOf(0)
                    renderTree(node, 0, sb, counter)
                    if (counter[0] >= 500) sb.append("\n[Listing truncated at 500 entries. Narrow with a sub-path.]")
                    ToolResult(sb.toString().ifEmpty { "(empty directory)" })
                }
                "read_file" -> {
                    val path = args.getString("path")
                    if (KeyStoreManager.isPotentialSecretFile(path)) {
                        return ToolResult("Blocked: $path looks like a secrets/credentials file and will not be read.", true)
                    }
                    val start = if (args.has("start_line")) args.optInt("start_line") else null
                    val end = if (args.has("end_line")) args.optInt("end_line") else null
                    ToolResult(projectRepository.readFile(projectId, path, start, end))
                }
                "search_code" -> {
                    ToolResult(projectRepository.searchCode(projectId, args.getString("query"), args.optBoolean("regex", false)))
                }
                "edit_file" -> {
                    val path = args.getString("path")
                    if (!awaitApproval("edit_file", "Edit $path")) return ToolResult("The user denied this edit.", true)
                    ToolResult(
                        projectRepository.editFile(projectId, path, args.getString("old_str"), args.getString("new_str")),
                        changed = true,
                        touched = listOf(cleanPath(path))
                    )
                }
                "write_file" -> {
                    val path = args.getString("path")
                    if (!awaitApproval("write_file", "Write file $path")) return ToolResult("The user denied this write.", true)
                    ToolResult(
                        projectRepository.writeFile(projectId, path, args.getString("content")),
                        changed = true,
                        touched = listOf(cleanPath(path))
                    )
                }
                "delete_file" -> {
                    val path = args.getString("path")
                    if (!awaitApproval("delete_file", "Delete $path")) return ToolResult("The user denied this deletion.", true)
                    ToolResult(projectRepository.deleteFile(projectId, path), changed = true, removed = listOf(cleanPath(path)))
                }
                "move_file" -> {
                    val from = args.getString("from_path")
                    val to = args.getString("to_path")
                    if (!awaitApproval("move_file", "Move $from → $to")) return ToolResult("The user denied this move.", true)
                    ToolResult(
                        projectRepository.moveFile(projectId, from, to),
                        changed = true,
                        touched = listOf(cleanPath(to)),
                        removed = listOf(cleanPath(from))
                    )
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

    private val toolsList = listOf(
        LlmTool(
            name = "list_files",
            description = "List files and folders under a relative directory path (use '.' for the project root). Large folders are truncated.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string","description":"Relative directory, e.g. '.' or 'app/src/main'"}},"required":["path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "read_file",
            description = "Read a text file with line numbers. Optionally pass start_line/end_line (1-based). Long files are truncated unless a range is given.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "search_code",
            description = "Search (case-insensitive) for text or a regex across all project text files. Returns path:line: match.",
            parametersJsonSchema = """
                {"type":"object","properties":{"query":{"type":"string"},"regex":{"type":"boolean","description":"Treat query as a regex"}},"required":["query"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "edit_file",
            description = "Replace ONE exact, unique occurrence of old_str with new_str in a file. old_str must match exactly (indentation included, without line-number prefixes) and be unique; include surrounding lines if needed.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"old_str":{"type":"string"},"new_str":{"type":"string"}},"required":["path","old_str","new_str"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "write_file",
            description = "Create a new file or fully replace an existing one with the given content. For big files prefer several edit_file calls.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "delete_file",
            description = "Delete a file or directory.",
            parametersJsonSchema = """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
            """.trimIndent()
        ),
        LlmTool(
            name = "move_file",
            description = "Move or rename a file or directory.",
            parametersJsonSchema = """
                {"type":"object","properties":{"from_path":{"type":"string"},"to_path":{"type":"string"}},"required":["from_path","to_path"]}
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
