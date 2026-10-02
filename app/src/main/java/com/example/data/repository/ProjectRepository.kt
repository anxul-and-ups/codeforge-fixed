package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.dao.CheckpointDao
import com.example.data.local.dao.ProjectDao
import com.example.data.local.entity.CheckpointEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.security.KeyStoreManager
import com.example.domain.model.DiffLine
import com.example.domain.model.DiffLineType
import com.example.domain.model.FileDiff
import com.example.domain.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectRepository(
    private val context: Context,
    private val projectDao: ProjectDao,
    private val checkpointDao: CheckpointDao
) {
    val allProjects: Flow<List<ProjectEntity>> = projectDao.getAllProjects()

    suspend fun getProject(id: String): ProjectEntity? = projectDao.getProjectById(id)

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(id)
        if (project != null) {
            val dir = File(project.rootPath)
            if (dir.exists()) dir.deleteRecursively()
            for (cp in checkpointDao.getCheckpointsForProjectOnce(id)) {
                try { File(cp.snapshotDirPath).deleteRecursively() } catch (e: Exception) { }
            }
            checkpointDao.deleteCheckpointsForProject(id)
            projectDao.deleteProject(id)
        }
    }

    suspend fun createProject(name: String, starterType: String? = null): ProjectEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val projectsDir = File(context.filesDir, "projects")
        if (!projectsDir.exists()) projectsDir.mkdirs()

        val projectRoot = File(projectsDir, id)
        projectRoot.mkdirs()

        if (starterType != null) {
            populateStarterProject(projectRoot, starterType)
        } else {
            // Default minimal files
            val readme = File(projectRoot, "README.md")
            readme.writeText("# $name\n\nCreated with CodeForge on Android.\n")
        }

        val fileCount = countFiles(projectRoot)
        val entity = ProjectEntity(
            id = id,
            name = name,
            rootPath = projectRoot.absolutePath,
            fileCount = fileCount,
            updatedAt = System.currentTimeMillis()
        )
        projectDao.insertProject(entity)
        // Create initial baseline checkpoint
        createCheckpoint(id, "Initial State")
        entity
    }

    /** Human readable file name of a content Uri (e.g. "myapp.zip"), or null. */
    fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (e: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    suspend fun importProjectFromZip(name: String, zipUri: Uri): ProjectEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val projectsDir = File(context.filesDir, "projects")
        if (!projectsDir.exists()) projectsDir.mkdirs()

        val projectRoot = File(projectsDir, id)
        projectRoot.mkdirs()

        try {
            val input = context.contentResolver.openInputStream(zipUri)
                ?: throw IllegalStateException("Could not open the selected file")
            input.use { extractZipSafely(it, projectRoot) }
            flattenSingleRootFolder(projectRoot)
        } catch (e: Exception) {
            projectRoot.deleteRecursively()
            throw e
        }

        val fileCount = countFiles(projectRoot)
        if (fileCount == 0) {
            projectRoot.deleteRecursively()
            throw IllegalStateException("The ZIP has no files (is it a valid .zip?)")
        }
        val entity = ProjectEntity(
            id = id,
            name = name.trim().ifEmpty { "Imported Project" }.take(60),
            rootPath = projectRoot.absolutePath,
            fileCount = fileCount,
            updatedAt = System.currentTimeMillis()
        )
        projectDao.insertProject(entity)
        try {
            createCheckpoint(id, "Imported from ZIP")
        } catch (e: Exception) {
            // project is still usable without the baseline snapshot
        }
        entity
    }

    /** GitHub-style zips contain one top-level folder ("repo-main/..."). Move its content up. */
    private fun flattenSingleRootFolder(root: File) {
        val children = root.listFiles()?.filter { it.name != "__MACOSX" && it.name != ".DS_Store" } ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            val tmp = File(root, ".__flatten_tmp__")
            if (!inner.renameTo(tmp)) return
            tmp.listFiles()?.forEach { it.renameTo(File(root, it.name)) }
            tmp.deleteRecursively()
        }
        File(root, "__MACOSX").deleteRecursively()
    }

    private fun extractZipSafely(inputStream: InputStream, destinationDir: File) {
        val canonicalDest = destinationDir.canonicalPath
        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                count++
                val entryName = entry.name.replace('\\', '/')
                val newFile = File(destinationDir, entryName)
                val canonicalNewFile = newFile.canonicalPath

                // ZipSlip Protection Check!
                if (!canonicalNewFile.startsWith(canonicalDest + File.separator) && canonicalNewFile != canonicalDest) {
                    throw SecurityException("Zip Slip path traversal exploit detected in entry: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else if (!entryName.startsWith("__MACOSX/")) {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            if (count == 0) throw IllegalStateException("This file is not a valid ZIP archive")
        }
    }

    suspend fun getFileTree(projectId: String): FileNode? = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: return@withContext null
        val rootDir = File(project.rootPath)
        if (!rootDir.exists()) return@withContext null
        buildFileNode(rootDir, rootDir)
    }

    private fun buildFileNode(file: File, rootDir: File): FileNode {
        val relativePath = file.relativeTo(rootDir).path
        if (file.isDirectory) {
            val children = file.listFiles()
                ?.filter { !isIgnored(it.name) }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.map { buildFileNode(it, rootDir) } ?: emptyList()
            return FileNode(
                name = file.name,
                path = if (relativePath.isEmpty()) "." else relativePath,
                isDirectory = true,
                children = children
            )
        } else {
            return FileNode(
                name = file.name,
                path = relativePath,
                isDirectory = false,
                sizeBytes = file.length()
            )
        }
    }

    private val IGNORED_NAMES = setOf(
        ".git", ".gradle", "build", ".idea", ".DS_Store", "node_modules", ".cxx", ".externalNativeBuild"
    )

    private fun isIgnored(name: String): Boolean = IGNORED_NAMES.contains(name)

    /** Walks a directory tree but never descends into ignored folders (.git, build, ...). */
    private fun walkFiltered(root: File): Sequence<File> =
        root.walkTopDown().onEnter { it == root || !isIgnored(it.name) }.filter { it == root || !isIgnored(it.name) }

    private fun isBinaryFile(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8000)
                val n = fis.read(buf)
                for (i in 0 until n) if (buf[i] == 0.toByte()) return true
                false
            }
        } catch (e: Exception) {
            true
        }
    }

    suspend fun getRootDir(projectId: String): File? = withContext(Dispatchers.IO) {
        projectDao.getProjectById(projectId)?.let { File(it.rootPath) }
    }

    /** Files that may be pushed to GitHub: relative path -> file. Skips ignored folders and secrets. */
    suspend fun listFilesForPush(projectId: String): Map<String, File> = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: return@withContext emptyMap()
        val root = File(project.rootPath)
        val out = LinkedHashMap<String, File>()
        walkFiltered(root).filter { it.isFile }.forEach { f ->
            if (!KeyStoreManager.isPotentialSecretFile(f.name)) {
                out[f.relativeTo(root).path.replace(File.separatorChar, '/')] = f
            }
        }
        out
    }

    suspend fun readFile(projectId: String, relativePath: String, startLine: Int? = null, endLine: Int? = null): String = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val targetFile = resolveSafePath(File(project.rootPath), relativePath)
        if (!targetFile.exists()) throw NoSuchFileException(targetFile, reason = "File does not exist: $relativePath")
        if (targetFile.isDirectory) throw IllegalArgumentException("Path is a directory: $relativePath")
        if (isBinaryFile(targetFile)) {
            return@withContext "[Binary file: $relativePath (${targetFile.length() / 1024} KB) - cannot be shown as text]"
        }

        val allLines = targetFile.readLines()
        val rangeGiven = startLine != null || endLine != null
        val start = ((startLine ?: 1).coerceAtLeast(1)) - 1
        var end = (endLine ?: allLines.size).coerceAtMost(allLines.size)
        var truncated = false
        if (!rangeGiven && (targetFile.length() > 150_000L || allLines.size > 2500)) {
            end = minOf(allLines.size, 600)
            truncated = true
        }
        if (start >= allLines.size) return@withContext "[File has only ${allLines.size} lines]"
        val sb = StringBuilder()
        for (i in start until end.coerceAtLeast(start)) {
            sb.append(String.format("%6d\t", i + 1)).append(allLines[i]).append('\n')
        }
        if (truncated) {
            sb.append("\n[File truncated to first $end of ${allLines.size} lines. Use start_line/end_line to read other ranges.]")
        }
        sb.toString()
    }

    suspend fun writeFile(projectId: String, relativePath: String, content: String): String = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val targetFile = resolveSafePath(File(project.rootPath), relativePath)
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(content)
        updateProjectStats(project)
        "Successfully wrote ${content.length} characters to $relativePath"
    }

    suspend fun editFile(projectId: String, relativePath: String, oldStr: String, newStr: String): String = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val targetFile = resolveSafePath(File(project.rootPath), relativePath)
        if (!targetFile.exists()) throw NoSuchFileException(targetFile, reason = "File does not exist: $relativePath")

        val content = targetFile.readText()
        var oldStr = oldStr
        var newStr = newStr
        if (content.contains("\r\n") && !oldStr.contains("\r\n")) {
            oldStr = oldStr.replace("\n", "\r\n")
            newStr = newStr.replace("\n", "\r\n")
        }
        if (oldStr.isEmpty()) throw IllegalArgumentException("old_str must not be empty")
        val occurrences = countOccurrences(content, oldStr)
        if (occurrences == 0) {
            throw IllegalArgumentException("Target string was not found in $relativePath. Ensure exact indentation and content.")
        }
        if (occurrences > 1) {
            throw IllegalArgumentException("Target string matches $occurrences occurrences in $relativePath. Must match uniquely. Include more surrounding lines.")
        }

        val updated = content.replaceFirst(oldStr, newStr)
        targetFile.writeText(updated)
        updateProjectStats(project)
        "Successfully edited $relativePath (replaced unique match of ${oldStr.lines().size} lines)"
    }

    suspend fun deleteFile(projectId: String, relativePath: String): String = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val targetFile = resolveSafePath(File(project.rootPath), relativePath)
        if (!targetFile.exists()) return@withContext "File does not exist: $relativePath"
        val deleted = if (targetFile.isDirectory) targetFile.deleteRecursively() else targetFile.delete()
        updateProjectStats(project)
        if (deleted) "Deleted $relativePath" else "Could not delete $relativePath"
    }

    suspend fun moveFile(projectId: String, fromPath: String, toPath: String): String = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val src = resolveSafePath(File(project.rootPath), fromPath)
        val dst = resolveSafePath(File(project.rootPath), toPath)
        if (!src.exists()) throw NoSuchFileException(src, reason = "Source does not exist: $fromPath")
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
        updateProjectStats(project)
        "Moved $fromPath to $toPath"
    }

    suspend fun searchCode(projectId: String, query: String, isRegex: Boolean): String = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val rootDir = File(project.rootPath)
        val matches = mutableListOf<String>()
        val regex = if (isRegex) {
            try { query.toRegex(RegexOption.IGNORE_CASE) } catch (e: Exception) {
                return@withContext "Invalid regex: ${e.message}"
            }
        } else null

        walkFiltered(rootDir).forEach { file ->
            if (file.isFile && !KeyStoreManager.isPotentialSecretFile(file.name) &&
                file.length() < 1_000_000L && matches.size < 60 && !isBinaryFile(file)) {
                try {
                    val lines = file.readLines()
                    val relPath = file.relativeTo(rootDir).path
                    lines.forEachIndexed { index, line ->
                        val matched = if (regex != null) {
                            regex.containsMatchIn(line)
                        } else {
                            line.contains(query, ignoreCase = true)
                        }
                        if (matched && matches.size < 60) {
                            matches.add("$relPath:${index + 1}: ${line.trim()}")
                        }
                    }
                } catch (e: Exception) {
                    // skip binary / non-utf8
                }
            }
        }
        if (matches.isEmpty()) "No matches found for \"$query\"" else matches.joinToString("\n")
    }

    private fun countOccurrences(src: String, target: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = src.indexOf(target, idx)
            if (found == -1) break
            count++
            idx = found + target.length
        }
        return count
    }

    private fun resolveSafePath(rootDir: File, relativePath: String): File {
        val file = File(rootDir, relativePath)
        val canonicalDest = rootDir.canonicalPath
        val canonicalFile = file.canonicalPath
        if (canonicalFile != canonicalDest && !canonicalFile.startsWith(canonicalDest + File.separator)) {
            throw SecurityException("Path traversal prohibited: $relativePath")
        }
        return file
    }

    private suspend fun updateProjectStats(project: ProjectEntity) {
        val count = countFiles(File(project.rootPath))
        projectDao.updateProject(
            project.copy(
                fileCount = count,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun countFiles(dir: File): Int {
        var count = 0
        walkFiltered(dir).forEach {
            if (it.isFile) count++
        }
        return count
    }

    // Checkpoint & Diffing
    suspend fun createCheckpoint(projectId: String, title: String): CheckpointEntity = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val checkpointId = UUID.randomUUID().toString()
        val checkpointsDir = File(context.filesDir, "checkpoints")
        if (!checkpointsDir.exists()) checkpointsDir.mkdirs()

        val snapshotDir = File(checkpointsDir, checkpointId)
        snapshotDir.mkdirs()

        val rootDir = File(project.rootPath)
        copyDirectoryFiltered(rootDir, snapshotDir)

        val entity = CheckpointEntity(
            id = checkpointId,
            projectId = projectId,
            timestamp = System.currentTimeMillis(),
            title = title,
            snapshotDirPath = snapshotDir.absolutePath
        )
        checkpointDao.insertCheckpoint(entity)
        // keep only the newest 15 snapshots per project to save storage
        val all = checkpointDao.getCheckpointsForProjectOnce(projectId)
        if (all.size > 15) {
            for (old in all.drop(15)) {
                try { File(old.snapshotDirPath).deleteRecursively() } catch (e: Exception) { }
                checkpointDao.deleteCheckpoint(old.id)
            }
        }
        entity
    }

    fun getCheckpoints(projectId: String): Flow<List<CheckpointEntity>> =
        checkpointDao.getCheckpointsForProject(projectId)

    suspend fun restoreCheckpoint(checkpointId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpointDao.getCheckpointById(checkpointId) ?: return@withContext false
        val snapshotDir = File(checkpoint.snapshotDirPath)
        if (!snapshotDir.exists()) return@withContext false
        val project = projectDao.getProjectById(checkpoint.projectId) ?: return@withContext false
        val root = File(project.rootPath)

        // Safety net: snapshot the current state before overwriting it
        try {
            createCheckpoint(project.id, "Before restore: ${checkpoint.title}".take(80))
        } catch (e: Exception) {
            // continue even if the safety snapshot fails
        }

        root.mkdirs()
        root.listFiles()?.forEach { child ->
            if (!isIgnored(child.name)) child.deleteRecursively()
        }
        copyDirectoryFiltered(snapshotDir, root)
        updateProjectStats(project)
        true
    }

    suspend fun computeDiffsFromLastCheckpoint(projectId: String): List<FileDiff> = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: return@withContext emptyList()
        val currentDir = File(project.rootPath)
        if (!currentDir.exists()) return@withContext emptyList()

        val checkpoints = checkpointDao.getCheckpointsForProjectOnce(projectId)
        val lastCheckpoint = checkpoints.firstOrNull() ?: return@withContext emptyList()
        val snapshotDir = File(lastCheckpoint.snapshotDirPath)
        if (!snapshotDir.exists()) return@withContext emptyList()

        val currentFiles = walkFiltered(currentDir).filter { it.isFile && !isBinaryFile(it) }.toList()
        val snapshotFiles = walkFiltered(snapshotDir).filter { it.isFile && !isBinaryFile(it) }.toList()

        val currentMap = currentFiles.associateBy { it.relativeTo(currentDir).path }
        val snapshotMap = snapshotFiles.associateBy { it.relativeTo(snapshotDir).path }

        val allRelPaths = (currentMap.keys + snapshotMap.keys).sorted()
        val diffs = mutableListOf<FileDiff>()

        for (path in allRelPaths) {
            val cur = currentMap[path]
            val snap = snapshotMap[path]

            val curContent = cur?.readText() ?: ""
            val snapContent = snap?.readText() ?: ""

            if (curContent != snapContent) {
                val lines = computeLineDiffs(snapContent, curContent)
                val added = lines.count { it.type == DiffLineType.ADD }
                val removed = lines.count { it.type == DiffLineType.REMOVE }
                diffs.add(
                    FileDiff(
                        filePath = path,
                        oldContent = snapContent,
                        newContent = curContent,
                        isNewFile = snap == null,
                        isDeletedFile = cur == null,
                        linesAdded = added,
                        linesRemoved = removed,
                        diffLines = lines
                    )
                )
            }
        }
        diffs
    }

    suspend fun revertFileToLastCheckpoint(projectId: String, relativePath: String) = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: return@withContext
        val currentFile = File(project.rootPath, relativePath)
        val checkpoints = checkpointDao.getCheckpointsForProjectOnce(projectId)
        val lastCheckpoint = checkpoints.firstOrNull() ?: return@withContext
        val snapFile = File(lastCheckpoint.snapshotDirPath, relativePath)

        if (snapFile.exists()) {
            currentFile.parentFile?.mkdirs()
            snapFile.copyTo(currentFile, overwrite = true)
        } else {
            if (currentFile.exists()) currentFile.delete()
        }
        updateProjectStats(project)
    }

    suspend fun exportProjectZip(projectId: String): File = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val zipFile = File(exportDir, "${project.name.replace("\\s+".toRegex(), "_")}_fixed.zip")
        if (zipFile.exists()) zipFile.delete()

        val rootDir = File(project.rootPath)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            walkFiltered(rootDir).forEach { file ->
                if (!isIgnored(file.name)) {
                    val relPath = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                    if (relPath.isNotEmpty()) {
                        if (file.isDirectory) {
                            zos.putNextEntry(ZipEntry("$relPath/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(relPath))
                            FileInputStream(file).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
        }
        zipFile
    }

    suspend fun exportPatchZip(projectId: String): File = withContext(Dispatchers.IO) {
        val project = projectDao.getProjectById(projectId) ?: throw IllegalArgumentException("Project not found")
        val diffs = computeDiffsFromLastCheckpoint(projectId)
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val zipFile = File(exportDir, "${project.name.replace("\\s+".toRegex(), "_")}_patch.zip")
        if (zipFile.exists()) zipFile.delete()

        val rootDir = File(project.rootPath)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (diff in diffs) {
                if (!diff.isDeletedFile) {
                    val file = File(rootDir, diff.filePath)
                    if (file.exists()) {
                        zos.putNextEntry(ZipEntry(diff.filePath))
                        FileInputStream(file).use { fis -> fis.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        zipFile
    }

    private fun copyDirectoryFiltered(src: File, dst: File) {
        walkFiltered(src).forEach { file ->
            if (!isIgnored(file.name)) {
                val relPath = file.relativeTo(src).path
                if (relPath.isNotEmpty()) {
                    val target = File(dst, relPath)
                    if (file.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        file.copyTo(target, overwrite = true)
                    }
                }
            }
        }
    }

    private fun computeLineDiffs(oldText: String, newText: String): List<DiffLine> {
        val a = if (oldText.isEmpty()) emptyList() else oldText.lines()
        val b = if (newText.isEmpty()) emptyList() else newText.lines()

        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }

        val full = mutableListOf<DiffLine>()
        for (i in 0 until start) {
            full.add(DiffLine(DiffLineType.UNCHANGED, i + 1, i + 1, a[i]))
        }

        val n = endA - start
        val m = endB - start
        if (n.toLong() * m.toLong() > 4_000_000L) {
            for (i in start until endA) full.add(DiffLine(DiffLineType.REMOVE, i + 1, null, a[i]))
            for (j in start until endB) full.add(DiffLine(DiffLineType.ADD, null, j + 1, b[j]))
        } else {
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    lcs[i][j] = if (a[start + i] == b[start + j]) {
                        lcs[i + 1][j + 1] + 1
                    } else {
                        maxOf(lcs[i + 1][j], lcs[i][j + 1])
                    }
                }
            }
            var i = 0
            var j = 0
            while (i < n || j < m) {
                if (i < n && j < m && a[start + i] == b[start + j]) {
                    full.add(DiffLine(DiffLineType.UNCHANGED, start + i + 1, start + j + 1, a[start + i]))
                    i++
                    j++
                } else if (j < m && (i >= n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                    full.add(DiffLine(DiffLineType.ADD, null, start + j + 1, b[start + j]))
                    j++
                } else {
                    full.add(DiffLine(DiffLineType.REMOVE, start + i + 1, null, a[start + i]))
                    i++
                }
            }
        }
        for (k in endA until a.size) {
            full.add(DiffLine(DiffLineType.UNCHANGED, k + 1, endB + (k - endA) + 1, a[k]))
        }

        // Keep only changed lines with 3 lines of context so big files do not flood the UI
        val keep = BooleanArray(full.size)
        for (idx in full.indices) {
            if (full[idx].type != DiffLineType.UNCHANGED) {
                for (k in maxOf(0, idx - 3)..minOf(full.size - 1, idx + 3)) keep[k] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        for (idx in full.indices) if (keep[idx]) result.add(full[idx])
        return result
    }

    private fun populateStarterProject(root: File, type: String) {
        when (type) {
            "Android Starter" -> {
                File(root, "app/src/main/java/com/example/app").mkdirs()
                File(root, "app/src/main/java/com/example/app/MainActivity.kt").writeText(
                    """
                    package com.example.app

                    import android.os.Bundle
                    import androidx.activity.ComponentActivity
                    import androidx.activity.compose.setContent
                    import androidx.compose.material3.Text
                    import androidx.compose.runtime.Composable

                    class MainActivity : ComponentActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContent {
                                Greeting("CodeForge Android Starter")
                            }
                        }
                    }

                    @Composable
                    fun Greeting(name: String) {
                        Text("Hello, ${'$'}name!")
                    }
                    """.trimIndent()
                )
                File(root, "app/build.gradle.kts").writeText(
                    """
                    plugins {
                        alias(libs.plugins.android.application)
                        alias(libs.plugins.kotlin.compose)
                    }
                    android {
                        namespace = "com.example.app"
                        compileSdk = 35
                    }
                    """.trimIndent()
                )
                File(root, "README.md").writeText(
                    "# Android Starter Project\n\nReady for CodeForge AI agent to inspect, fix bugs, and add features!"
                )
            }
            "Kotlin CLI Tool" -> {
                File(root, "src/main/kotlin").mkdirs()
                File(root, "src/main/kotlin/Main.kt").writeText(
                    """
                    fun main(args: Array<String>) {
                        println("CodeForge CLI Tool running!")
                        if (args.isEmpty()) {
                            println("Usage: run <command>")
                        }
                    }
                    """.trimIndent()
                )
                File(root, "build.gradle.kts").writeText(
                    """
                    plugins {
                        kotlin("jvm") version "2.1.0"
                        application
                    }
                    application {
                        mainClass.set("MainKt")
                    }
                    """.trimIndent()
                )
                File(root, "README.md").writeText(
                    "# Kotlin CLI Tool\n\nModify Main.kt or ask CodeForge to add features and unit tests."
                )
            }
            else -> {
                File(root, "README.md").writeText(
                    "# $type\n\nEmpty project initialized by CodeForge."
                )
            }
        }
    }
}
