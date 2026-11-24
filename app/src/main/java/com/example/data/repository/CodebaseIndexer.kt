package com.example.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CodebaseSymbolHit(
    val path: String,
    val lineNumber: Int,
    val kind: String,
    val signature: String
)

data class CodebaseIndexReport(
    val rootPath: String,
    val totalFiles: Int,
    val detectedStacks: List<String>,
    val entryPoints: List<String>,
    val configFiles: List<String>,
    val directorySummary: List<String>,
    val symbolHits: List<CodebaseSymbolHit> = emptyList()
) {
    fun formatForAgent(): String = buildString {
        appendLine("CODEBASE INDEX (VERIFIED REAL FILESYSTEM — SOURCE OF TRUTH):")
        appendLine("- Root: $rootPath")
        appendLine("- Total indexed files: $totalFiles")
        if (detectedStacks.isNotEmpty()) {
            appendLine("- Detected stack/build system: ${detectedStacks.joinToString(", ")}")
        }
        if (entryPoints.isNotEmpty()) {
            appendLine("- Entry points: ${entryPoints.joinToString(", ")}")
        }
        if (configFiles.isNotEmpty()) {
            appendLine("- Config / Dependency manifests: ${configFiles.joinToString(", ")}")
        }
        if (directorySummary.isNotEmpty()) {
            appendLine("- Top directories & files:")
            directorySummary.take(40).forEach { appendLine("  • $it") }
        }
        if (symbolHits.isNotEmpty()) {
            appendLine("- Symbol matches (${symbolHits.size}):")
            symbolHits.take(45).forEach { hit ->
                appendLine("  • ${hit.path}:${hit.lineNumber} [${hit.kind}] ${hit.signature}")
            }
        }
    }.trim()
}

/**
 * Fast, real-filesystem codebase indexer that maps project structure, frameworks, entry points,
 * dependency manifests, and symbol declarations (classes, functions, composables, exports, imports)
 * so the agent never hallucinates file paths or symbol names.
 */
object CodebaseIndexer {

    private val IGNORED_DIRS = setOf(
        ".git", ".gradle", "build", ".idea", "node_modules", "__pycache__", ".venv", "venv",
        ".cxx", ".externalNativeBuild", "dist", ".next", "target"
    )

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss",
        "json", "xml", "yml", "yaml", "dart", "c", "cpp", "h", "hpp", "rs", "go", "php", "sh", "sql"
    )

    private val SYMBOL_REGEXES = listOf(
        "class" to Regex("""^\s*(?:public\s+|private\s+|internal\s+|open\s+|abstract\s+|sealed\s+|data\s+|export\s+default\s+|export\s+)*(?:class|interface|object|enum\s+class|struct|trait)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "function" to Regex("""^\s*(?:public\s+|private\s+|internal\s+|protected\s+|suspend\s+|override\s+|export\s+|async\s+)*(?:fun|function|def|fn)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        "export" to Regex("""^\s*export\s+(?:const|let|var|type|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")
    )

    suspend fun indexProject(
        projectRoot: File,
        symbolQuery: String? = null
    ): CodebaseIndexReport = withContext(Dispatchers.IO) {
        if (!projectRoot.exists() || !projectRoot.isDirectory) {
            return@withContext CodebaseIndexReport(
                rootPath = projectRoot.absolutePath,
                totalFiles = 0,
                detectedStacks = emptyList(),
                entryPoints = emptyList(),
                configFiles = emptyList(),
                directorySummary = emptyList()
            )
        }

        val allFiles = mutableListOf<File>()
        projectRoot.walkTopDown()
            .onEnter { it == projectRoot || it.name !in IGNORED_DIRS }
            .filter { it.isFile }
            .forEach { allFiles.add(it) }

        val relPaths = allFiles.map { it.relativeTo(projectRoot).path.replace(File.separatorChar, '/') }.sorted()
        val stacks = LinkedHashSet<String>()
        val entryPoints = mutableListOf<String>()
        val configs = mutableListOf<String>()

        for (rel in relPaths) {
            val name = rel.substringAfterLast('/')
            val lower = name.lowercase()
            when {
                lower == "build.gradle.kts" || lower == "build.gradle" || lower == "settings.gradle.kts" -> {
                    stacks.add("Android / Gradle")
                    configs.add(rel)
                }
                lower == "androidmanifest.xml" -> {
                    stacks.add("Android App")
                    configs.add(rel)
                }
                lower == "package.json" -> {
                    stacks.add("Node.js / JS")
                    configs.add(rel)
                }
                lower == "tsconfig.json" -> {
                    stacks.add("TypeScript")
                    configs.add(rel)
                }
                lower == "vite.config.ts" || lower == "vite.config.js" -> {
                    stacks.add("Vite")
                    configs.add(rel)
                }
                lower == "pubspec.yaml" -> {
                    stacks.add("Flutter / Dart")
                    configs.add(rel)
                }
                lower == "requirements.txt" || lower == "pyproject.toml" -> {
                    stacks.add("Python")
                    configs.add(rel)
                }
                lower == "cargo.toml" -> {
                    stacks.add("Rust")
                    configs.add(rel)
                }
                lower == "go.mod" -> {
                    stacks.add("Go")
                    configs.add(rel)
                }
                lower == "index.html" -> {
                    stacks.add("Web (HTML/CSS/JS)")
                    entryPoints.add(rel)
                }
            }

            if (lower in setOf(
                    "mainactivity.kt", "mainactivity.java", "main.kt", "app.tsx", "app.jsx",
                    "main.tsx", "main.ts", "main.js", "index.js", "server.js", "main.py", "app.py", "main.dart"
                )
            ) {
                entryPoints.add(rel)
            }
        }

        // Top-level directory summary with file counts
        val topGroups = LinkedHashMap<String, Int>()
        for (rel in relPaths) {
            val top = if ('/' in rel) rel.substringBefore('/') + "/" else rel
            topGroups[top] = (topGroups[top] ?: 0) + 1
        }
        val dirSummary = topGroups.entries.map { (k, c) ->
            if (k.endsWith("/")) "$k ($c files)" else k
        }

        val hits = mutableListOf<CodebaseSymbolHit>()
        val cleanQuery = symbolQuery?.trim().orEmpty()
        val scanLimit = if (cleanQuery.isNotBlank()) 120 else 35

        for (file in allFiles) {
            if (hits.size >= scanLimit) break
            val ext = file.extension.lowercase()
            if (ext !in CODE_EXTENSIONS || file.length() > 350_000L) continue
            val rel = file.relativeTo(projectRoot).path.replace(File.separatorChar, '/')
            try {
                file.useLines { seq ->
                    seq.forEachIndexed { idx, rawLine ->
                        if (hits.size >= scanLimit) return@forEachIndexed
                        for ((kind, regex) in SYMBOL_REGEXES) {
                            val m = regex.find(rawLine)
                            if (m != null) {
                                val symName = m.groupValues[1]
                                if (cleanQuery.isBlank() ||
                                    symName.contains(cleanQuery, ignoreCase = true) ||
                                    rawLine.contains(cleanQuery, ignoreCase = true)
                                ) {
                                    hits.add(
                                        CodebaseSymbolHit(
                                            path = rel,
                                            lineNumber = idx + 1,
                                            kind = kind,
                                            signature = rawLine.trim().take(120)
                                        )
                                    )
                                }
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // skip unreadable files
            }
        }

        CodebaseIndexReport(
            rootPath = projectRoot.absolutePath,
            totalFiles = allFiles.size,
            detectedStacks = stacks.toList(),
            entryPoints = entryPoints.distinct().take(12),
            configFiles = configs.distinct().take(15),
            directorySummary = dirSummary,
            symbolHits = hits
        )
    }
}
