package com.example.data.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class GlobalTaskStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class ActiveTaskState(
    val taskId: String = UUID.randomUUID().toString(),
    val title: String = "",
    val status: GlobalTaskStatus = GlobalTaskStatus.IDLE,
    val activeModel: String = "",
    val startedAtMs: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val completedSteps: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
    val lastError: String? = null
)

data class ToolActionDigest(
    val timestampMs: Long = System.currentTimeMillis(),
    val modelUsed: String,
    val toolName: String,
    val targetPath: String = "",
    val summary: String,
    val isError: Boolean = false
)

data class SessionMemorySnapshot(
    val key: String,
    val projectId: String,
    val projectName: String,
    val projectRootPath: String,
    val conversationId: String,
    val linkedRepo: String = "",
    val gitBranch: String = "",
    val lastModelUsed: String = "",
    val lastUserIntent: String = "",
    val activeTask: ActiveTaskState = ActiveTaskState(),
    val recentToolDigests: List<ToolActionDigest> = emptyList(),
    val discoveredPaths: List<String> = emptyList(),
    val recentlyModifiedFiles: List<String> = emptyList(),
    val pinnedNotes: List<String> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Model-independent persistent global memory & concurrency lock manager.
 *
 * Ensures switching between Gemini, Claude, GPT, DeepSeek, Groq, OpenRouter, or custom models
 * inside the same conversation NEVER loses project root, discovered files, uploaded assets,
 * cloned repos, active task state, or prior tool execution outcomes.
 */
class GlobalMemoryStore(private val context: Context) {

    private val storeFile: File by lazy { File(context.filesDir, "global_memory_v1.json") }
    private val ioLock = Any()
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    private val _sessions = MutableStateFlow<Map<String, SessionMemorySnapshot>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionMemorySnapshot>> = _sessions.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun sessionKey(projectId: String, conversationId: String): String = "$projectId::$conversationId"

    /** Per-file coroutine mutex preventing concurrent writes/corruptions across model switches or tasks. */
    suspend fun <T> withFileLock(projectId: String, relativePath: String, block: suspend () -> T): T {
        val norm = relativePath.trim().removePrefix("./").trim('/').lowercase()
        val mutex = fileLocks.computeIfAbsent("$projectId::$norm") { Mutex() }
        return mutex.withLock { block() }
    }

    fun getSession(projectId: String, conversationId: String): SessionMemorySnapshot? =
        _sessions.value[sessionKey(projectId, conversationId)]

    fun beginTask(
        projectId: String,
        projectName: String,
        projectRootPath: String,
        conversationId: String,
        userPrompt: String,
        modelName: String,
        linkedRepo: String = "",
        gitBranch: String = ""
    ) {
        val key = sessionKey(projectId, conversationId)
        val now = System.currentTimeMillis()
        val title = userPrompt.lineSequence().firstOrNull().orEmpty().trim().take(120)
        synchronized(ioLock) {
            val prev = _sessions.value[key]
            val next = (prev ?: SessionMemorySnapshot(
                key = key,
                projectId = projectId,
                projectName = projectName,
                projectRootPath = projectRootPath,
                conversationId = conversationId
            )).copy(
                projectName = projectName,
                projectRootPath = projectRootPath,
                linkedRepo = linkedRepo.ifBlank { prev?.linkedRepo.orEmpty() },
                gitBranch = gitBranch.ifBlank { prev?.gitBranch.orEmpty() },
                lastModelUsed = modelName.ifBlank { prev?.lastModelUsed.orEmpty() },
                lastUserIntent = title.ifBlank { prev?.lastUserIntent.orEmpty() },
                activeTask = ActiveTaskState(
                    taskId = UUID.randomUUID().toString(),
                    title = title,
                    status = GlobalTaskStatus.RUNNING,
                    activeModel = modelName,
                    startedAtMs = now,
                    updatedAtMs = now,
                    completedSteps = emptyList(),
                    modifiedFiles = emptyList(),
                    lastError = null
                ),
                updatedAtMs = now
            )
            val updated = _sessions.value.toMutableMap()
            updated[key] = next
            _sessions.value = updated
            persistToDisk(updated)
        }
    }

    fun recordToolAction(
        projectId: String,
        projectName: String,
        projectRootPath: String,
        conversationId: String,
        modelUsed: String,
        toolName: String,
        targetPath: String = "",
        summary: String,
        isError: Boolean = false,
        modifiedPaths: List<String> = emptyList(),
        discoveredPathList: List<String> = emptyList()
    ) {
        val key = sessionKey(projectId, conversationId)
        val now = System.currentTimeMillis()
        synchronized(ioLock) {
            val prev = _sessions.value[key] ?: SessionMemorySnapshot(
                key = key,
                projectId = projectId,
                projectName = projectName,
                projectRootPath = projectRootPath,
                conversationId = conversationId
            )
            val digest = ToolActionDigest(
                timestampMs = now,
                modelUsed = modelUsed.ifBlank { prev.lastModelUsed },
                toolName = toolName,
                targetPath = targetPath,
                summary = summary.replace("\n", " ").trim().take(240),
                isError = isError
            )
            val nextDigests = (prev.recentToolDigests + digest).takeLast(30)
            val nextModified = LinkedHashSet<String>().apply {
                addAll(prev.recentlyModifiedFiles)
                modifiedPaths.filter { it.isNotBlank() }.forEach { add(it) }
            }.toList().takeLast(40)
            val nextDiscovered = LinkedHashSet<String>().apply {
                addAll(prev.discoveredPaths)
                if (targetPath.isNotBlank()) add(targetPath)
                discoveredPathList.filter { it.isNotBlank() }.forEach { add(it) }
            }.toList().takeLast(60)

            val task = prev.activeTask
            val nextSteps = if (!isError) {
                (task.completedSteps + "$toolName${if (targetPath.isNotBlank()) "($targetPath)" else ""}").takeLast(25)
            } else task.completedSteps
            val nextTaskFiles = (task.modifiedFiles + modifiedPaths).distinct().takeLast(30)

            val next = prev.copy(
                projectName = projectName,
                projectRootPath = projectRootPath,
                lastModelUsed = modelUsed.ifBlank { prev.lastModelUsed },
                recentToolDigests = nextDigests,
                recentlyModifiedFiles = nextModified,
                discoveredPaths = nextDiscovered,
                activeTask = task.copy(
                    activeModel = modelUsed.ifBlank { task.activeModel },
                    updatedAtMs = now,
                    completedSteps = nextSteps,
                    modifiedFiles = nextTaskFiles,
                    lastError = if (isError) summary.take(200) else task.lastError
                ),
                updatedAtMs = now
            )
            val updated = _sessions.value.toMutableMap()
            updated[key] = next
            _sessions.value = updated
            persistToDisk(updated)
        }
    }

    fun finishTask(
        projectId: String,
        conversationId: String,
        status: GlobalTaskStatus,
        modelUsed: String = "",
        errorMessage: String? = null
    ) {
        val key = sessionKey(projectId, conversationId)
        val now = System.currentTimeMillis()
        synchronized(ioLock) {
            val prev = _sessions.value[key] ?: return
            val next = prev.copy(
                lastModelUsed = modelUsed.ifBlank { prev.lastModelUsed },
                activeTask = prev.activeTask.copy(
                    status = status,
                    activeModel = modelUsed.ifBlank { prev.activeTask.activeModel },
                    updatedAtMs = now,
                    lastError = errorMessage ?: if (status == GlobalTaskStatus.COMPLETED) null else prev.activeTask.lastError
                ),
                updatedAtMs = now
            )
            val updated = _sessions.value.toMutableMap()
            updated[key] = next
            _sessions.value = updated
            persistToDisk(updated)
        }
    }

    fun recordManualFileChange(projectId: String, relativePath: String) {
        val now = System.currentTimeMillis()
        synchronized(ioLock) {
            val updated = _sessions.value.toMutableMap()
            var changed = false
            for ((k, snap) in updated) {
                if (snap.projectId == projectId) {
                    val mods = (snap.recentlyModifiedFiles + relativePath).distinct().takeLast(40)
                    updated[k] = snap.copy(recentlyModifiedFiles = mods, updatedAtMs = now)
                    changed = true
                }
            }
            if (changed) {
                _sessions.value = updated
                persistToDisk(updated)
            }
        }
    }

    /**
     * Builds the model-independent shared context block injected into EVERY LLM turn,
     * guaranteeing full continuity when the user switches providers or models mid-session.
     */
    fun buildSharedContextPrompt(
        projectId: String,
        conversationId: String,
        currentModelLabel: String = ""
    ): String {
        val snap = getSession(projectId, conversationId)
            ?: _sessions.value.values
                .filter { it.projectId == projectId }
                .maxByOrNull { it.updatedAtMs }
            ?: return ""

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.appendLine("GLOBAL SHARED SESSION MEMORY (MODEL-INDEPENDENT — PRESERVED ACROSS MODEL SWITCHES):")
        sb.appendLine("- Active Project: \"${snap.projectName}\" (id=${snap.projectId})")
        sb.appendLine("- Local Project Root: ${snap.projectRootPath}")
        if (snap.linkedRepo.isNotBlank()) {
            sb.appendLine("- Linked GitHub Repo: ${snap.linkedRepo}${if (snap.gitBranch.isNotBlank()) " (branch: ${snap.gitBranch})" else ""}")
        }
        if (snap.lastModelUsed.isNotBlank() && currentModelLabel.isNotBlank() && snap.lastModelUsed != currentModelLabel) {
            sb.appendLine("- Model Handoff Detected: previous turns ran on `${snap.lastModelUsed}`; you are now `$currentModelLabel`. Continue seamlessly using the verified project state below—NEVER reset context or re-guess paths.")
        }
        val t = snap.activeTask
        if (t.title.isNotBlank()) {
            sb.appendLine("- Last/Active Task: \"${t.title}\" | Status: ${t.status.name}")
            if (t.modifiedFiles.isNotEmpty()) {
                sb.appendLine("  Files touched in task: ${t.modifiedFiles.takeLast(15).joinToString(", ")}")
            }
            if (!t.lastError.isNullOrBlank() && t.status != GlobalTaskStatus.COMPLETED) {
                sb.appendLine("  Last error: ${t.lastError}")
            }
        }
        if (snap.recentlyModifiedFiles.isNotEmpty()) {
            sb.appendLine("- Recently Modified Project Files: ${snap.recentlyModifiedFiles.takeLast(20).joinToString(", ")}")
        }
        if (snap.recentToolDigests.isNotEmpty()) {
            sb.appendLine("- Recent Verified Tool Actions (chronological across all models):")
            snap.recentToolDigests.takeLast(14).forEach { d ->
                val tag = if (d.isError) "ERR" else "OK"
                val pathPart = if (d.targetPath.isNotBlank()) " [${d.targetPath}]" else ""
                sb.appendLine("  • [${fmt.format(Date(d.timestampMs))}] ($tag) ${d.toolName}$pathPart: ${d.summary}")
            }
        }
        return sb.toString().trim()
    }

    private fun loadFromDisk() {
        try {
            if (!storeFile.exists()) return
            val root = JSONObject(storeFile.readText())
            val arr = root.optJSONArray("sessions") ?: return
            val map = LinkedHashMap<String, SessionMemorySnapshot>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key")
                if (key.isBlank()) continue
                val taskObj = o.optJSONObject("activeTask") ?: JSONObject()
                val taskStatus = try {
                    GlobalTaskStatus.valueOf(taskObj.optString("status", "IDLE"))
                } catch (_: Exception) {
                    GlobalTaskStatus.IDLE
                }
                val digestsArr = o.optJSONArray("recentToolDigests") ?: JSONArray()
                val digests = ArrayList<ToolActionDigest>(digestsArr.length())
                for (j in 0 until digestsArr.length()) {
                    val d = digestsArr.optJSONObject(j) ?: continue
                    digests.add(
                        ToolActionDigest(
                            timestampMs = d.optLong("timestampMs", 0L),
                            modelUsed = d.optString("modelUsed"),
                            toolName = d.optString("toolName"),
                            targetPath = d.optString("targetPath"),
                            summary = d.optString("summary"),
                            isError = d.optBoolean("isError", false)
                        )
                    )
                }
                map[key] = SessionMemorySnapshot(
                    key = key,
                    projectId = o.optString("projectId"),
                    projectName = o.optString("projectName"),
                    projectRootPath = o.optString("projectRootPath"),
                    conversationId = o.optString("conversationId"),
                    linkedRepo = o.optString("linkedRepo"),
                    gitBranch = o.optString("gitBranch"),
                    lastModelUsed = o.optString("lastModelUsed"),
                    lastUserIntent = o.optString("lastUserIntent"),
                    activeTask = ActiveTaskState(
                        taskId = taskObj.optString("taskId"),
                        title = taskObj.optString("title"),
                        status = if (taskStatus == GlobalTaskStatus.RUNNING) GlobalTaskStatus.PAUSED else taskStatus,
                        activeModel = taskObj.optString("activeModel"),
                        startedAtMs = taskObj.optLong("startedAtMs", 0L),
                        updatedAtMs = taskObj.optLong("updatedAtMs", 0L),
                        completedSteps = jsonToStringList(taskObj.optJSONArray("completedSteps")),
                        modifiedFiles = jsonToStringList(taskObj.optJSONArray("modifiedFiles")),
                        lastError = taskObj.optString("lastError").ifBlank { null }
                    ),
                    recentToolDigests = digests,
                    discoveredPaths = jsonToStringList(o.optJSONArray("discoveredPaths")),
                    recentlyModifiedFiles = jsonToStringList(o.optJSONArray("recentlyModifiedFiles")),
                    pinnedNotes = jsonToStringList(o.optJSONArray("pinnedNotes")),
                    updatedAtMs = o.optLong("updatedAtMs", 0L)
                )
            }
            _sessions.value = map
        } catch (_: Exception) {
            // start fresh if corrupted
        }
    }

    private fun persistToDisk(map: Map<String, SessionMemorySnapshot>) {
        try {
            // Keep 40 most recent sessions
            val keep = map.values.sortedByDescending { it.updatedAtMs }.take(40)
            val arr = JSONArray()
            for (s in keep) {
                val taskObj = JSONObject()
                    .put("taskId", s.activeTask.taskId)
                    .put("title", s.activeTask.title)
                    .put("status", s.activeTask.status.name)
                    .put("activeModel", s.activeTask.activeModel)
                    .put("startedAtMs", s.activeTask.startedAtMs)
                    .put("updatedAtMs", s.activeTask.updatedAtMs)
                    .put("completedSteps", JSONArray(s.activeTask.completedSteps))
                    .put("modifiedFiles", JSONArray(s.activeTask.modifiedFiles))
                    .put("lastError", s.activeTask.lastError ?: "")

                val digestsArr = JSONArray()
                for (d in s.recentToolDigests) {
                    digestsArr.put(
                        JSONObject()
                            .put("timestampMs", d.timestampMs)
                            .put("modelUsed", d.modelUsed)
                            .put("toolName", d.toolName)
                            .put("targetPath", d.targetPath)
                            .put("summary", d.summary)
                            .put("isError", d.isError)
                    )
                }

                arr.put(
                    JSONObject()
                        .put("key", s.key)
                        .put("projectId", s.projectId)
                        .put("projectName", s.projectName)
                        .put("projectRootPath", s.projectRootPath)
                        .put("conversationId", s.conversationId)
                        .put("linkedRepo", s.linkedRepo)
                        .put("gitBranch", s.gitBranch)
                        .put("lastModelUsed", s.lastModelUsed)
                        .put("lastUserIntent", s.lastUserIntent)
                        .put("activeTask", taskObj)
                        .put("recentToolDigests", digestsArr)
                        .put("discoveredPaths", JSONArray(s.discoveredPaths))
                        .put("recentlyModifiedFiles", JSONArray(s.recentlyModifiedFiles))
                        .put("pinnedNotes", JSONArray(s.pinnedNotes))
                        .put("updatedAtMs", s.updatedAtMs)
                )
            }
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            tmp.writeText(JSONObject().put("sessions", arr).toString())
            if (!tmp.renameTo(storeFile)) {
                storeFile.writeText(JSONObject().put("sessions", arr).toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            // non-fatal persistence error
        }
    }

    private fun jsonToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }
}
