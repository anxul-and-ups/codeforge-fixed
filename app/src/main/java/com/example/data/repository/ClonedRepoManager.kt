package com.example.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ClonedRepoRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val description: String = "",
    val localPath: String,
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    val clonedAtMs: Long = System.currentTimeMillis(),
    val lastModifiedMs: Long = System.currentTimeMillis(),
    val branch: String = "main",
    val projectId: String? = null
)

data class RepoImportReport(
    val repoName: String,
    val targetSubdir: String,
    val copiedCount: Int,
    val overwrittenCount: Int,
    val skippedCount: Int,
    val samplePaths: List<String>
) {
    fun toSummary(): String = buildString {
        appendLine("Imported repository '$repoName' into `${targetSubdir.ifBlank { "." }}`:")
        appendLine("- Copied: $copiedCount file(s)")
        appendLine("- Overwritten: $overwrittenCount file(s)")
        if (skippedCount > 0) appendLine("- Skipped existing: $skippedCount file(s)")
        if (samplePaths.isNotEmpty()) {
            appendLine("- Sample paths: ${samplePaths.take(12).joinToString(", ")}")
        }
    }.trim()
}

/**
 * Persistent "CLONED" Repositories Manager storing every cloned repository (via GitHub tab,
 * Chat, or Termux git_clone) with real disk size, branch detection, rename, delete, refresh,
 * and direct import into any active project.
 */
class ClonedRepoManager(private val context: Context) {

    private val storeFile: File by lazy { File(context.filesDir, "cloned_repos_v1.json") }
    private val lock = Any()

    private val _repos = MutableStateFlow<List<ClonedRepoRecord>>(emptyList())
    val repos: StateFlow<List<ClonedRepoRecord>> = _repos.asStateFlow()

    init {
        loadFromDisk()
    }

    suspend fun registerClonedRepo(
        name: String,
        url: String,
        localPath: String,
        description: String = "",
        branch: String = "",
        projectId: String? = null
    ): ClonedRepoRecord = withContext(Dispatchers.IO) {
        val dir = File(localPath)
        val (size, count, lastMod) = measureDirectory(dir)
        val detectedBranch = branch.ifBlank { detectGitBranch(dir) ?: "main" }
        val now = System.currentTimeMillis()
        val record = ClonedRepoRecord(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { dir.name },
            url = url.trim(),
            description = description.trim(),
            localPath = dir.absolutePath,
            sizeBytes = size,
            fileCount = count,
            clonedAtMs = now,
            lastModifiedMs = if (lastMod > 0L) lastMod else now,
            branch = detectedBranch,
            projectId = projectId
        )
        synchronized(lock) {
            val filtered = _repos.value.filterNot {
                it.localPath == record.localPath ||
                    (record.projectId != null && it.projectId == record.projectId)
            }
            val next = listOf(record) + filtered
            _repos.value = next
            saveToDisk(next)
        }
        record
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val current = _repos.value
        val updated = current.map { r ->
            val dir = File(r.localPath)
            if (dir.exists() && dir.isDirectory) {
                val (size, count, lastMod) = measureDirectory(dir)
                val branch = detectGitBranch(dir) ?: r.branch
                r.copy(
                    sizeBytes = size,
                    fileCount = count,
                    lastModifiedMs = if (lastMod > 0L) lastMod else r.lastModifiedMs,
                    branch = branch
                )
            } else {
                r
            }
        }
        synchronized(lock) {
            _repos.value = updated
            saveToDisk(updated)
        }
    }

    suspend fun renameRepo(repoId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        if (clean.isBlank()) return@withContext false
        synchronized(lock) {
            val next = _repos.value.map { if (it.id == repoId) it.copy(name = clean) else it }
            _repos.value = next
            saveToDisk(next)
        }
        true
    }

    suspend fun deleteRepo(repoId: String, deleteFilesOnDisk: Boolean = true): ClonedRepoRecord? = withContext(Dispatchers.IO) {
        val target = _repos.value.firstOrNull { it.id == repoId } ?: return@withContext null
        if (deleteFilesOnDisk) {
            try {
                val dir = File(target.localPath)
                if (dir.exists() && dir.canonicalPath.startsWith(context.filesDir.canonicalPath)) {
                    dir.deleteRecursively()
                }
            } catch (_: Exception) {
                // keep metadata cleanup moving
            }
        }
        synchronized(lock) {
            val next = _repos.value.filterNot { it.id == repoId }
            _repos.value = next
            saveToDisk(next)
        }
        target
    }

    fun findByIdOrName(query: String): ClonedRepoRecord? {
        val q = query.trim()
        if (q.isBlank()) return _repos.value.firstOrNull()
        return _repos.value.firstOrNull { it.id == q }
            ?: _repos.value.firstOrNull { it.name.equals(q, ignoreCase = true) }
            ?: _repos.value.firstOrNull { it.url.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
    }

    /**
     * Copies real files from a cloned repository into [targetProjectRoot] (optionally inside [targetSubdir]).
     * Skips `.git`, `build`, `node_modules`, and secret files automatically.
     */
    suspend fun importIntoProject(
        repoIdOrName: String,
        targetProjectRoot: File,
        targetSubdir: String = "",
        overwrite: Boolean = true
    ): RepoImportReport = withContext(Dispatchers.IO) {
        val repo = findByIdOrName(repoIdOrName)
            ?: throw IllegalArgumentException("Cloned repository '$repoIdOrName' not found.")
        val srcDir = File(repo.localPath)
        if (!srcDir.exists() || !srcDir.isDirectory) {
            throw IllegalStateException("Local directory for '${repo.name}' no longer exists: ${repo.localPath}")
        }
        val cleanSub = targetSubdir.trim().trim('/')
        val destBase = if (cleanSub.isBlank()) targetProjectRoot else File(targetProjectRoot, cleanSub)
        val canonicalTarget = targetProjectRoot.canonicalPath
        if (destBase.canonicalPath != canonicalTarget && !destBase.canonicalPath.startsWith(canonicalTarget + File.separator)) {
            throw SecurityException("Target subdir escapes project root: $targetSubdir")
        }
        destBase.mkdirs()

        val ignored = setOf(".git", ".gradle", "build", "node_modules", ".idea", "__pycache__", ".DS_Store")
        var copied = 0
        var overwritten = 0
        var skipped = 0
        val sample = mutableListOf<String>()

        srcDir.walkTopDown()
            .onEnter { it == srcDir || it.name !in ignored }
            .filter { it.isFile && it.name !in ignored }
            .forEach { srcFile ->
                val rel = srcFile.relativeTo(srcDir).path.replace(File.separatorChar, '/')
                val outFile = File(destBase, rel)
                val existed = outFile.exists()
                if (existed && !overwrite) {
                    skipped++
                } else {
                    outFile.parentFile?.mkdirs()
                    srcFile.copyTo(outFile, overwrite = true)
                    if (existed) overwritten++ else copied++
                    if (sample.size < 20) {
                        val shown = if (cleanSub.isBlank()) rel else "$cleanSub/$rel"
                        sample.add(shown)
                    }
                }
            }

        RepoImportReport(
            repoName = repo.name,
            targetSubdir = cleanSub,
            copiedCount = copied,
            overwrittenCount = overwritten,
            skippedCount = skipped,
            samplePaths = sample
        )
    }

    fun buildAgentSummary(): String {
        val list = _repos.value
        if (list.isEmpty()) return ""
        val sb = StringBuilder("CLONED REPOSITORIES AVAILABLE (use `import_cloned_repo` to integrate into active project):\n")
        for (r in list.take(12)) {
            val sizeStr = formatBytes(r.sizeBytes)
            sb.appendLine("- `${r.name}` (id=${r.id.take(8)}, branch=${r.branch}, ${r.fileCount} files, $sizeStr) | url=${r.url} | path=${r.localPath}")
        }
        return sb.toString().trim()
    }

    private fun measureDirectory(dir: File): Triple<Long, Int, Long> {
        if (!dir.exists() || !dir.isDirectory) return Triple(0L, 0, 0L)
        var bytes = 0L
        var count = 0
        var latest = dir.lastModified()
        val skip = setOf(".git", ".gradle", "build", "node_modules")
        dir.walkTopDown()
            .onEnter { it == dir || it.name !in skip }
            .forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    count++
                    if (f.lastModified() > latest) latest = f.lastModified()
                }
            }
        return Triple(bytes, count, latest)
    }

    private fun detectGitBranch(dir: File): String? {
        return try {
            val head = File(dir, ".git/HEAD")
            if (!head.exists()) return null
            val text = head.readText().trim()
            if (text.startsWith("ref: refs/heads/")) text.removePrefix("ref: refs/heads/").trim()
            else text.take(8)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadFromDisk() {
        try {
            if (!storeFile.exists()) return
            val arr = JSONObject(storeFile.readText()).optJSONArray("repos") ?: return
            val out = ArrayList<ClonedRepoRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    ClonedRepoRecord(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        url = o.optString("url"),
                        description = o.optString("description"),
                        localPath = o.optString("localPath"),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        fileCount = o.optInt("fileCount", 0),
                        clonedAtMs = o.optLong("clonedAtMs", 0L),
                        lastModifiedMs = o.optLong("lastModifiedMs", 0L),
                        branch = o.optString("branch", "main"),
                        projectId = o.optString("projectId").ifBlank { null }
                    )
                )
            }
            _repos.value = out
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun saveToDisk(list: List<ClonedRepoRecord>) {
        try {
            val arr = JSONArray()
            for (r in list) {
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("url", r.url)
                        .put("description", r.description)
                        .put("localPath", r.localPath)
                        .put("sizeBytes", r.sizeBytes)
                        .put("fileCount", r.fileCount)
                        .put("clonedAtMs", r.clonedAtMs)
                        .put("lastModifiedMs", r.lastModifiedMs)
                        .put("branch", r.branch)
                        .put("projectId", r.projectId ?: "")
                )
            }
            storeFile.writeText(JSONObject().put("repos", arr).toString())
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
