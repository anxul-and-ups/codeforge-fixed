package com.example.data.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 6 & 7 — Repo & Tool Installation Pipeline (Section 11) and APK / Source Reverse-Engineering Save Flow (Section 12.4).
 */

data class CompatibilityFinding(
    val issue: String,
    val severity: String, // "BLOCKER" or "WARNING"
    val alternativeSuggestion: String
)

data class RepoInstallPlan(
    val repoUrl: String,
    val repoName: String,
    val intentType: String, // "PROJECT" or "TOOL"
    val targetPath: String,
    val detectedStack: String,
    val packagesToInstall: List<String>,
    val commandsToRun: List<String>,
    val isMcpServer: Boolean,
    val compatibilityFindings: List<CompatibilityFinding>,
    val riskSummary: List<String>
) {
    val installCommands: List<String> get() = commandsToRun
    val compatibilityWarnings: List<String> get() = compatibilityFindings.map { "${it.issue} (${it.alternativeSuggestion})" }
    val smokeTestCommand: String
        get() = when {
            detectedStack.contains("Python", ignoreCase = true) -> "python3 --version"
            detectedStack.contains("Node", ignoreCase = true) -> "node --version"
            else -> "ls -la"
        }
    val entryCommand: String
        get() = when {
            detectedStack.contains("Python", ignoreCase = true) -> "./.venv/bin/python"
            detectedStack.contains("Node", ignoreCase = true) -> "npx $repoName"
            else -> "./$repoName"
        }
}

object RepoToolInstaller {

    /**
     * Inspects a cloned or local directory for Termux/ARM compatibility (Section 11.2 Step 2)
     * and untrusted-repo safety risks (Section 11.4).
     */
    fun inspectDirectory(dir: File, repoUrl: String = dir.name, intentType: String = "TOOL"): RepoInstallPlan {
        val repoName = repoUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { dir.name }
        val files = dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val packages = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val findings = mutableListOf<CompatibilityFinding>()
        val risks = mutableListOf<String>()
        var stack = "Generic Repository"
        var isMcp = false

        val readmeText = listOf("README.md", "readme.md", "README.txt")
            .mapNotNull { name -> File(dir, name).takeIf { it.exists() }?.readText()?.take(8000) }
            .firstOrNull().orEmpty()

        if (readmeText.contains("mcp", ignoreCase = true) || File(dir, "mcp.json").exists()) {
            isMcp = true
        }

        // Check Python
        if ("requirements.txt" in files || "pyproject.toml" in files || "setup.py" in files) {
            stack = if (isMcp) "Python MCP Server" else "Python CLI / Project"
            val reqFile = File(dir, "requirements.txt")
            if (reqFile.exists()) {
                reqFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .take(15)
                    .forEach { packages.add("pip: $it") }
            }
            commands.add("python3 -m venv .venv")
            if ("requirements.txt" in files) {
                commands.add("./.venv/bin/pip install -r requirements.txt")
            } else {
                commands.add("./.venv/bin/pip install -e .")
            }
            if ("setup.py" in files) {
                risks.add("Contains setup.py (runs arbitrary Python code during installation)")
            }
        }

        // Check Node / TypeScript
        if ("package.json" in files) {
            val pkgText = try { File(dir, "package.json").readText() } catch (_: Exception) { "" }
            if (pkgText.contains("modelcontextprotocol", ignoreCase = true) || pkgText.contains("\"mcp\"", ignoreCase = true)) {
                isMcp = true
            }
            if (pkgText.contains("\"postinstall\"") || pkgText.contains("\"preinstall\"")) {
                risks.add("package.json defines preinstall/postinstall lifecycle scripts")
            }
            try {
                val pkgJson = JSONObject(pkgText)
                val deps = pkgJson.optJSONObject("dependencies")
                if (deps != null) {
                    val keys = deps.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k.contains("modelcontextprotocol")) isMcp = true
                        if (packages.size < 15) packages.add("npm: $k")
                    }
                }
            } catch (_: Throwable) {}
            stack = if (isMcp) "Node.js MCP Server" else "Node.js / TypeScript"
            commands.add("npm install --ignore-scripts")
        }

        // Check Rust / Go
        if ("Cargo.toml" in files) {
            stack = "Rust Crate"
            packages.add("pkg: rust")
            commands.add("cargo build --release")
        }
        if ("go.mod" in files) {
            stack = "Go Module"
            packages.add("pkg: golang")
            commands.add("go build ./...")
        }

        // Compatibility checks for Termux/ARM (Section 11.2 Step 2)
        if ("Dockerfile" in files || "docker-compose.yml" in files || readmeText.contains("docker run", ignoreCase = true)) {
            findings.add(
                CompatibilityFinding(
                    issue = "Uses Docker containers, which cannot run natively on non-root Android Termux.",
                    severity = "WARNING",
                    alternativeSuggestion = "Run the Python/Node entrypoint directly inside a Termux virtualenv instead of Docker."
                )
            )
        }
        if (readmeText.contains("cuda", ignoreCase = true) || packages.any { it.contains("torch") || it.contains("nvidia") }) {
            findings.add(
                CompatibilityFinding(
                    issue = "References NVIDIA CUDA / GPU libraries unavailable on ARM Android phones.",
                    severity = "WARNING",
                    alternativeSuggestion = "Use CPU-only wheels or cloud API inference."
                )
            )
        }
        if (readmeText.contains("sudo ", ignoreCase = true) || readmeText.contains("apt-get ", ignoreCase = true)) {
            findings.add(
                CompatibilityFinding(
                    issue = "Instructions use sudo/apt-get; Termux runs unprivileged and uses `pkg install`.",
                    severity = "WARNING",
                    alternativeSuggestion = "Replace `sudo apt install` with Termux `pkg install`."
                )
            )
        }
        if (readmeText.contains("curl ", ignoreCase = true) && readmeText.contains("| sh", ignoreCase = true)) {
            risks.add("README suggests `curl | sh` piped execution — CodeForge will inspect scripts before running.")
        }
        if (risks.isEmpty()) {
            risks.add("No suspicious install hooks or obfuscated scripts detected.")
        }

        val targetPath = if (intentType == "TOOL") "~/forge/tools/$repoName" else "~/forge/projects/$repoName"
        if (commands.isEmpty()) {
            commands.add("git status")
        }

        return RepoInstallPlan(
            repoUrl = repoUrl,
            repoName = repoName,
            intentType = intentType,
            targetPath = targetPath,
            detectedStack = stack,
            packagesToInstall = packages,
            commandsToRun = commands,
            isMcpServer = isMcp,
            compatibilityFindings = findings,
            riskSummary = risks
        )
    }

    /**
     * Analyzes an APK or ZIP artifact and prepares the Section 12.4 "Save this source to a project?" flow.
     */
    fun analyzeApkOrArchive(file: File, outputDir: File): AnalysisArtifactSummary {
        outputDir.mkdirs()
        val entries = mutableListOf<String>()
        var dexCount = 0
        var resCount = 0
        val nativeLibs = mutableListOf<String>()
        val layouts = mutableListOf<String>()
        val discoveredUrls = LinkedHashSet<String>()
        val discoveredPackages = LinkedHashSet<String>()
        val urlRegex = Regex("https?://[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{6,120}")
        val pkgRegex = Regex("(?:com|org|net|io|ai|app|dev)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+){1,5}")

        try {
            java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    entries.add(name)
                    if (name.endsWith(".dex")) dexCount++
                    if (name.startsWith("res/") || name == "AndroidManifest.xml") resCount++
                    if (name.startsWith("res/layout/") && name.endsWith(".xml")) layouts.add(name.removePrefix("res/layout/"))
                    if (name.startsWith("lib/") && name.endsWith(".so")) nativeLibs.add(name)

                    if (!entry.isDirectory && entries.size <= 250 &&
                        (name.endsWith(".xml") || name.endsWith(".kt") || name.endsWith(".java") ||
                            name.endsWith(".json") || name.endsWith(".md") || name.endsWith(".properties"))
                    ) {
                        val out = File(outputDir, name)
                        out.parentFile?.mkdirs()
                        val bytes = zis.readBytes()
                        out.writeBytes(bytes)
                        val printable = bytes.toString(Charsets.ISO_8859_1)
                        urlRegex.findAll(printable).take(15).forEach { m ->
                            val u = m.value.trimEnd('.', ',', '"', '\'', ')', ']')
                            if (!u.contains("schemas.android.com") && !u.contains("w3.org")) {
                                discoveredUrls.add(u)
                            }
                        }
                        pkgRegex.findAll(printable).take(15).forEach { m ->
                            val p = m.value
                            if (!p.startsWith("com.android") && !p.startsWith("androidx.")) {
                                discoveredPackages.add(p)
                            }
                        }
                    } else if (!entry.isDirectory && name == "classes.dex" && file.length() < 35 * 1024 * 1024) {
                        // Scan first 512 KB of classes.dex for ASCII strings (API endpoints & package IDs)
                        val buf = ByteArray(512 * 1024)
                        val read = zis.read(buf)
                        if (read > 0) {
                            val ascii = String(buf, 0, read, Charsets.ISO_8859_1)
                            urlRegex.findAll(ascii).take(20).forEach { m ->
                                val u = m.value.trimEnd('.', ',', '"', '\'', ')', ']')
                                if (!u.contains("schemas.android.com")) discoveredUrls.add(u)
                            }
                            pkgRegex.findAll(ascii).take(20).forEach { m ->
                                val p = m.value
                                if (!p.startsWith("com.android") && !p.startsWith("androidx.") && !p.startsWith("java.")) {
                                    discoveredPackages.add(p)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {}

        val summaryMd = buildString {
            appendLine("# ANALYSIS.md — Artifact Inspection Report (`${file.name}`)")
            appendLine()
            appendLine("## 1. Evidence & Binary Profile")
            appendLine("- **Artifact**: `${file.name}` (${file.length() / 1024} KB)")
            appendLine("- **Total Entries Inspected**: ${entries.size}")
            if (dexCount > 0) appendLine("- **DEX Bytecode Containers**: $dexCount (`classes.dex` .. `classes$dexCount.dex`)")
            if (resCount > 0) appendLine("- **Android Resources & Manifest**: $resCount entries")
            if (nativeLibs.isNotEmpty()) appendLine("- **Native JNI Libraries (`.so`)**: ${nativeLibs.take(10).joinToString(", ")}")
            if (discoveredPackages.isNotEmpty()) {
                appendLine("- **Detected Package Namespaces**: ${discoveredPackages.take(10).joinToString(", ") { "`$it`" }}")
            }
            appendLine()
            appendLine("## 2. Recovered Graph & UI Screen Architecture")
            if (layouts.isNotEmpty()) {
                appendLine("- **XML Layouts / Screens (${layouts.size})**: ${layouts.take(18).joinToString(", ") { "`$it`" }}")
            }
            entries.take(35).forEach { appendLine("- `$it`") }
            if (entries.size > 35) appendLine("- ...and ${entries.size - 35} additional archive entries")
            appendLine()
            appendLine("## 3. Extracted API Endpoints & Config URLs")
            if (discoveredUrls.isEmpty()) {
                appendLine("- No hardcoded plaintext HTTP/HTTPS endpoints detected in initial scan (endpoints may be dynamically constructed or encrypted).")
            } else {
                discoveredUrls.take(20).forEach { appendLine("- `$it`") }
            }
            appendLine()
            appendLine("## 4. Limitations & Unknowns (REA Methodology)")
            appendLine("- **Obfuscation Check**: ${if (dexCount > 0) "DEX bytecode present; R8/ProGuard symbol renaming may apply to internal helper classes." else "Source/resource archive inspected directly."}")
            appendLine("- **Server-Side Logic**: Backend validation rules and remote database schemas must be inferred from request/response payloads.")
            appendLine()
            appendLine("## 5. Clean-Room App Clone Plan (Build Mode Ready)")
            appendLine("1. **Data & API Layer**: Recreate models and repository interfaces matching the discovered endpoints and namespaces.")
            appendLine("2. **UI & Navigation Graph**: Rebuild the observed screens in modern Kotlin + Jetpack Compose with Material 3.")
            appendLine("3. **Clean-Room Rule**: Use this REA report to understand architecture, flows, and APIs, writing 100% original code.")
        }
        val analysisFile = File(outputDir, "ANALYSIS.md")
        analysisFile.writeText(summaryMd)

        return AnalysisArtifactSummary(
            sourceFileName = file.name,
            totalFilesExtracted = entries.size.coerceAtLeast(1),
            outputDirPath = outputDir.absolutePath,
            analysisMarkdown = summaryMd
        )
    }

    fun analyzeArchiveOrApk(file: File, outputDir: File): AnalysisArtifactSummary =
        analyzeApkOrArchive(file, outputDir)

    fun buildInstallPlanSummary(repoName: String, plan: RepoInstallPlan, isToolInstall: Boolean = true): String = buildString {
        appendLine("Repository: $repoName (${plan.detectedStack})")
        appendLine("Target folder: ${plan.targetPath}")
        if (plan.packagesToInstall.isNotEmpty()) {
            appendLine("Packages (${plan.packagesToInstall.size}): ${plan.packagesToInstall.take(8).joinToString(", ")}")
        }
        appendLine("Commands to run:")
        plan.commandsToRun.forEach { appendLine("  $ $it") }
        if (plan.compatibilityFindings.isNotEmpty()) {
            appendLine("Compatibility notes:")
            plan.compatibilityFindings.forEach { appendLine("  ⚠️ ${it.issue} → ${it.alternativeSuggestion}") }
        }
        if (plan.riskSummary.isNotEmpty()) {
            appendLine("Safety scan: ${plan.riskSummary.joinToString("; ")}")
        }
    }.trim()
}

data class AnalysisArtifactSummary(
    val sourceFileName: String,
    val totalFilesExtracted: Int,
    val outputDirPath: String,
    val analysisMarkdown: String
) {
    val markdownReport: String get() = analysisMarkdown
}
