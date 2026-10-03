package com.example.data.logs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

data class BuildLogEntry(
    val id: Long,
    val time: Long,
    val projectName: String,
    val repo: String,
    val commit: String,
    val status: String, // "FAILED" or "SUCCESS"
    val attempt: Int,
    val runUrl: String,
    val log: String
)

/** Keeps GitHub build results (and their error logs) as small JSON files so users can read and copy them. */
class BuildLogStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "build_logs").apply { mkdirs() }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<BuildLogEntry>> = _entries.asStateFlow()

    private fun load(): List<BuildLogEntry> {
        return try {
            (dir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".json") }
                .mapNotNull { f ->
                    try {
                        val o = JSONObject(f.readText())
                        BuildLogEntry(
                            id = o.getLong("id"),
                            time = o.getLong("time"),
                            projectName = o.optString("projectName"),
                            repo = o.optString("repo"),
                            commit = o.optString("commit"),
                            status = o.optString("status"),
                            attempt = o.optInt("attempt"),
                            runUrl = o.optString("runUrl"),
                            log = o.optString("log")
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedByDescending { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(entry: BuildLogEntry) {
        try {
            val o = JSONObject()
                .put("id", entry.id)
                .put("time", entry.time)
                .put("projectName", entry.projectName)
                .put("repo", entry.repo)
                .put("commit", entry.commit)
                .put("status", entry.status)
                .put("attempt", entry.attempt)
                .put("runUrl", entry.runUrl)
                .put("log", entry.log)
            File(dir, "${entry.id}.json").writeText(o.toString())
        } catch (e: Exception) {
            // ignore storage problems; the entry still shows until restart
        }
        val updated = (listOf(entry) + _entries.value).sortedByDescending { it.time }
        // keep the newest 60 entries
        if (updated.size > 60) {
            updated.drop(60).forEach { File(dir, "${it.id}.json").delete() }
        }
        _entries.value = updated.take(60)
    }

    @Synchronized
    fun delete(id: Long) {
        File(dir, "$id.json").delete()
        _entries.value = _entries.value.filter { it.id != id }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        _entries.value = emptyList()
    }
}
