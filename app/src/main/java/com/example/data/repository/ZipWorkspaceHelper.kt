package com.example.data.repository

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipReplaceReport(
    val replacedFiles: List<String>,
    val addedFiles: List<String>,
    val skippedFiles: List<String>
) {
    val totalWritten: Int get() = replacedFiles.size + addedFiles.size

    fun toReadableSummary(): String = buildString {
        appendLine("ZIP extraction complete: ${replacedFiles.size} replaced, ${addedFiles.size} added (${skippedFiles.size} skipped).")
        if (replacedFiles.isNotEmpty()) {
            appendLine("\nReplaced matching files:")
            replacedFiles.take(40).forEach { appendLine("  • $it") }
            if (replacedFiles.size > 40) appendLine("  … and ${replacedFiles.size - 40} more")
        }
        if (addedFiles.isNotEmpty()) {
            appendLine("\nAdded new files:")
            addedFiles.take(40).forEach { appendLine("  • $it") }
            if (addedFiles.size > 40) appendLine("  … and ${addedFiles.size - 40} more")
        }
    }.trim()
}

/**
 * ZipSlip-safe ZIP extraction, intelligent file replacement, and Codebase export utilities.
 */
object ZipWorkspaceHelper {

    private val ignoredSegments = setOf(".git", ".gradle", "build", "node_modules", "__MACOSX", ".idea")

    /**
     * Extracts [zipFile] into [projectRoot] (or optional [targetSubdir]), inspecting all entries first.
     * - Prevents ZipSlip path traversal.
     * - Automatically strips a single wrapper top-level directory when all files live inside one folder
     *   AND stripping it matches existing project paths (or creates a clean root structure).
     * - Accurately replaces matching files in [projectRoot] and records both replaced and added files.
     */
    fun extractAndReplaceZip(
        zipFile: File,
        projectRoot: File,
        targetSubdir: String? = null
    ): ZipReplaceReport {
        val entries = readZipEntriesSafely(FileInputStream(zipFile))
        return applyEntriesToDirectory(entries, projectRoot, targetSubdir)
    }

    fun extractAndReplaceZipStream(
        inputStream: InputStream,
        projectRoot: File,
        targetSubdir: String? = null
    ): ZipReplaceReport {
        val entries = readZipEntriesSafely(inputStream)
        return applyEntriesToDirectory(entries, projectRoot, targetSubdir)
    }

    private fun readZipEntriesSafely(inputStream: InputStream): List<Pair<String, ByteArray>> {
        val list = mutableListOf<Pair<String, ByteArray>>()
        var totalBytes = 0L
        val maxTotalBytes = 120L * 1024L * 1024L // 120 MB zip-bomb safety cap
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val norm = entry.name.replace('\\', '/').trimStart('/')
                    val segments = norm.split('/').filter { it.isNotBlank() }
                    val hasTraversal = segments.any { it == ".." || it == "." }
                    val isIgnored = segments.any { it in ignoredSegments } || norm.endsWith(".DS_Store")
                    if (!hasTraversal && !isIgnored && segments.isNotEmpty()) {
                        val bytes = zis.readBytes()
                        totalBytes += bytes.size
                        if (totalBytes > maxTotalBytes) {
                            throw IllegalStateException("ZIP archive exceeds 120 MB extracted safety limit.")
                        }
                        list.add(segments.joinToString("/") to bytes)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return list
    }

    private fun applyEntriesToDirectory(
        entries: List<Pair<String, ByteArray>>,
        projectRoot: File,
        targetSubdir: String?
    ): ZipReplaceReport {
        val destRoot = if (targetSubdir.isNullOrBlank()) {
            projectRoot
        } else {
            val cleanSub = targetSubdir.trim().removePrefix("./").trim('/')
            File(projectRoot, cleanSub)
        }
        val canonicalRoot = projectRoot.canonicalPath
        if (!destRoot.canonicalPath.startsWith(canonicalRoot)) {
            throw SecurityException("Target subdirectory escapes project root: $targetSubdir")
        }
        destRoot.mkdirs()

        // Detect single top-level wrapper directory (e.g. `my-repo-main/src/App.kt` -> `src/App.kt`)
        val topSegments = entries.map { it.first.substringBefore('/') }.distinct()
        val hasNestedPaths = entries.all { it.first.contains('/') }
        val shouldStripWrapper = if (topSegments.size == 1 && hasNestedPaths) {
            val wrapper = topSegments.first()
            // Don't strip if project already has that exact top folder (e.g., `app/` or `src/`),
            // unless stripping matches more existing files in destRoot.
            val directMatches = entries.count { File(destRoot, it.first).exists() }
            val strippedMatches = entries.count { File(destRoot, it.first.removePrefix("$wrapper/")).exists() }
            val standardProjectDirs = setOf("app", "src", "res", "lib", "public", "assets", "components", "pages", "gradle", ".github")
            strippedMatches > directMatches || (directMatches == 0 && wrapper.lowercase() !in standardProjectDirs)
        } else false

        val wrapperPrefix = if (shouldStripWrapper) "${topSegments.first()}/" else ""

        val replaced = mutableListOf<String>()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for ((rawPath, bytes) in entries) {
            val relPath = if (wrapperPrefix.isNotEmpty()) rawPath.removePrefix(wrapperPrefix) else rawPath
            if (relPath.isBlank()) {
                skipped.add(rawPath)
                continue
            }
            val targetFile = File(destRoot, relPath)
            val canonicalTarget = targetFile.canonicalPath
            if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                skipped.add(rawPath)
                continue
            }
            val existed = targetFile.exists()
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(bytes)
            val displayRel = targetFile.relativeTo(projectRoot).path.replace(File.separatorChar, '/')
            if (existed) replaced.add(displayRel) else added.add(displayRel)
        }
        return ZipReplaceReport(
            replacedFiles = replaced.sorted(),
            addedFiles = added.sorted(),
            skippedFiles = skipped.sorted()
        )
    }

    /** Writes an entire directory tree to [outputStream] as a clean ZIP archive (excluding build/git artifacts). */
    fun writeDirectoryToZipStream(rootDir: File, outputStream: OutputStream): Int {
        var count = 0
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
            rootDir.walkTopDown()
                .onEnter { dir -> dir == rootDir || dir.name !in ignoredSegments }
                .forEach { file ->
                    if (file.isFile && file.name != ".DS_Store") {
                        val rel = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
                        if (rel.isNotEmpty()) {
                            zos.putNextEntry(ZipEntry(rel))
                            FileInputStream(file).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                            count++
                        }
                    }
                }
        }
        return count
    }
}
