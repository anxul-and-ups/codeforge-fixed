package com.example.data.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ManualEditAction(val label: String) {
    EDITED("Edited"),
    UPLOADED("Uploaded"),
    REPLACED("Replaced"),
    PASTED("Pasted"),
    CREATED("Created"),
    DELETED("Deleted")
}

data class ManualEditRecord(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val relativePath: String,
    val action: ManualEditAction,
    val diffSummary: String,
    val diffPreview: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Tracks ONLY manual file edits made directly by the user (inline Codebase editor, Upload,
 * Long-Press Replace, Copy/Paste, or manual delete).
 *
 * AI-generated/agent-edited files MUST NEVER be recorded here so the "Manual Edit" card in Chat
 * strictly reflects user changes and feeds accurate manual diffs into the agent's context.
 */
class ManualEditTracker(context: Context? = null) {

    private val storageFile: File? = context?.let { File(it.filesDir, "manual_edits_state.json") }
    private val _records = MutableStateFlow<List<ManualEditRecord>>(loadFromDisk())
    val records: StateFlow<List<ManualEditRecord>> = _records.asStateFlow()

    fun recordManualEdit(
        projectId: String,
        relativePath: String,
        action: ManualEditAction,
        oldContent: String? = null,
        newContent: String? = null,
        customSummary: String? = null
    ): ManualEditRecord {
        val cleanPath = relativePath.trim().removePrefix("./").trimStart('/').replace('\\', '/')
        val computedDiff = if (oldContent != null && newContent != null && oldContent != newContent) {
            SurgicalPatchEngine.generateUnifiedDiff(cleanPath, oldContent, newContent).take(2200)
        } else ""
        val summary = customSummary ?: computeSummary(action, oldContent, newContent)

        val record = ManualEditRecord(
            projectId = projectId,
            relativePath = cleanPath,
            action = action,
            diffSummary = summary,
            diffPreview = computedDiff,
            timestamp = System.currentTimeMillis()
        )
        _records.update { current ->
            // Deduplicate by (projectId, relativePath): latest user edit replaces older entry for same file
            val filtered = current.filterNot { it.projectId == projectId && it.relativePath == cleanPath }
            (filtered + record).takeLast(50)
        }
        persistToDisk()
        return record
    }

    fun editsForProject(projectId: String): List<ManualEditRecord> =
        _records.value.filter { it.projectId == projectId }.sortedByDescending { it.timestamp }

    fun dismissRecord(id: String) {
        _records.update { list -> list.filterNot { it.id == id } }
        persistToDisk()
    }

    fun clearForProject(projectId: String) {
        _records.update { list -> list.filterNot { it.projectId == projectId } }
        persistToDisk()
    }

    /**
     * Builds a structured context block automatically injected into AgentEngine prompts so the AI
     * always stays synchronized with manual user modifications.
     */
    fun buildAgentContextBlock(projectId: String): String {
        val list = editsForProject(projectId)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("USER MANUAL EDITS SINCE LAST SYNC (Respect these user changes; do not overwrite them unless asked):")
        for (rec in list.take(12)) {
            sb.appendLine("- [${rec.action.label}] ${rec.relativePath} (${rec.diffSummary})")
            if (rec.diffPreview.isNotBlank()) {
                sb.appendLine("```diff")
                sb.appendLine(rec.diffPreview.take(900))
                sb.appendLine("```")
            }
        }
        return sb.toString().trim()
    }

    private fun computeSummary(action: ManualEditAction, oldContent: String?, newContent: String?): String {
        return when (action) {
            ManualEditAction.DELETED -> "Removed by user"
            ManualEditAction.UPLOADED -> {
                val lines = newContent?.lines()?.size ?: 0
                if (lines > 0) "Uploaded ($lines lines)" else "Uploaded file"
            }
            ManualEditAction.REPLACED -> {
                val lines = newContent?.lines()?.size ?: 0
                if (lines > 0) "Replaced via picker ($lines lines)" else "Replaced file"
            }
            ManualEditAction.PASTED -> {
                val lines = newContent?.lines()?.size ?: 0
                if (lines > 0) "Pasted ($lines lines)" else "Pasted file"
            }
            ManualEditAction.CREATED -> {
                val lines = newContent?.lines()?.size ?: 0
                "+$lines lines"
            }
            ManualEditAction.EDITED -> {
                if (oldContent != null && newContent != null) {
                    val oldL = oldContent.lines()
                    val newL = newContent.lines()
                    val added = (newL.size - oldL.size).coerceAtLeast(0)
                    val removed = (oldL.size - newL.size).coerceAtLeast(0)
                    if (added == 0 && removed == 0) "Modified lines"
                    else "+$added -$removed lines"
                } else "Modified by user"
            }
        }
    }

    private fun loadFromDisk(): List<ManualEditRecord> {
        val file = storageFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val actionName = o.optString("action", ManualEditAction.EDITED.name)
                    val action = ManualEditAction.entries.firstOrNull { it.name == actionName } ?: ManualEditAction.EDITED
                    add(
                        ManualEditRecord(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            projectId = o.optString("projectId"),
                            relativePath = o.optString("relativePath"),
                            action = action,
                            diffSummary = o.optString("diffSummary"),
                            diffPreview = o.optString("diffPreview"),
                            timestamp = o.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistToDisk() {
        val file = storageFile ?: return
        try {
            val arr = JSONArray()
            for (r in _records.value) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("projectId", r.projectId)
                        .put("relativePath", r.relativePath)
                        .put("action", r.action.name)
                        .put("diffSummary", r.diffSummary)
                        .put("diffPreview", r.diffPreview)
                        .put("timestamp", r.timestamp)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
            // ignore disk persistence failures
        }
    }
}
