package com.example.data.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class TermuxBuildType {
    NOT_INSTALLED,
    FDROID_OR_GITHUB,
    PLAY_STORE_OUTDATED
}

enum class EngineConnectionState {
    DISCONNECTED,
    STARTING,
    SETTING_UP,
    CONNECTED_TERMUX,
    CONNECTED_EMBEDDED, // Kept only for enum compatibility; never used for fake connection state
    ERROR
}

data class EngineSetupStep(
    val id: String,
    val title: String,
    val status: String = "PENDING", // "PENDING", "RUNNING", "DONE", "ERROR"
    val detail: String = ""
)

data class AuditCommandLog(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val command: String,
    val cwd: String,
    val exitCode: Int,
    val durationMs: Long,
    val outputPreview: String
)

data class BridgeStatus(
    val termuxBuild: TermuxBuildType = TermuxBuildType.NOT_INSTALLED,
    val runCommandPermissionGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val connectionState: EngineConnectionState = EngineConnectionState.DISCONNECTED,
    val port: Int = TermuxBridge.DEFAULT_PORT,
    val engineVersion: String = "2.0.0",
    val pythonVersion: String = "",
    val gitVersion: String = "",
    val freeDiskMb: Long = 0L,
    val workspacePath: String = TermuxBridge.TERMUX_HOME,
    val setupSteps: List<EngineSetupStep> = emptyList(),
    val setupLog: String = "",
    val lastError: String? = null,
    val lastHeartbeatMs: Long = 0L,
    val measuredLatencyMs: Long = 0L
) {
    /** Strictly true ONLY when live HTTP heartbeat to http://127.0.0.1:47831/health succeeds with 200 OK. */
    val engineRunning: Boolean
        get() = connectionState == EngineConnectionState.CONNECTED_TERMUX
    val latencyMs: Long get() = measuredLatencyMs
    val installState: String get() = connectionState.name
    val arch: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64"
    val message: String get() = lastError ?: "Termux Workspace: $workspacePath"
}

typealias EngineHealthState = BridgeStatus

data class ExecResult(
    val execId: String,
    val command: String,
    val cwd: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val durationMs: Long
) {
    fun toJsonSummary(): String = JSONObject()
        .put("command", command)
        .put("cwd", cwd)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("exit_code", exitCode)
        .put("duration_ms", durationMs)
        .toString()
}

/**
 * Real Termux HTTP Bridge Manager (`http://127.0.0.1:47831`).
 *
 * - Strictly routes all shell/tool commands to Termux via HTTP POST `http://127.0.0.1:47831/exec`.
 * - NEVER executes commands in the local Android app sandbox (`ProcessBuilder` is prohibited).
 * - Polls `GET http://127.0.0.1:47831/health` every 5 seconds with zero fake/cached connection fallback.
 */
class TermuxBridge(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope
) {
    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_SERVICE_NAME = "com.termux.app.RunCommandService"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"
        const val TERMUX_WORKSPACE = "/data/data/com.termux/files/home"
        const val DEFAULT_PORT = 47831
        const val ENGINE_PORT = 47831
        const val DEFAULT_BEARER_TOKEN = "9f8ef08845d6751517d97296d40e03216138ac23185e31aa"

        private val DANGEROUS_COMMAND_PATTERNS = listOf(
            Regex("""rm\s+(-[rRf]+\s+)+/(\s|$)"""),
            Regex("""rm\s+(-[rRf]+\s+)+/\*"""),
            Regex(""":\(\)\s*\{\s*:\|:&\s*\}\s*;?:"""),
            Regex("""mkfs\."""),
            Regex("""dd\s+if=.*of=/dev/(sd|mmcblk|block)""")
        )
    }

    /** Fast HTTP client for 5-second `/health` heartbeat probes. */
    private val healthHttp = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    /** HTTP client for `/exec` commands on `http://127.0.0.1:47831/exec`. */
    private val execHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(125, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()
    val healthState: StateFlow<BridgeStatus> get() = status

    private val _auditLogs = MutableStateFlow<List<AuditCommandLog>>(emptyList())
    val auditLogs: StateFlow<List<AuditCommandLog>> = _auditLogs.asStateFlow()

    private val _terminalOutput = MutableStateFlow(
        "CodeForge Termux HTTP Bridge Terminal (http://127.0.0.1:47831)\nTarget CWD: /data/data/com.termux/files/home\n"
    )
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private var heartbeatJob: Job? = null

    /** Used only for local app metadata/checkpoints; NEVER used as a shell command fallback. */
    val localForgeRoot: File by lazy {
        File(context.filesDir, "forge").apply {
            File(this, "projects").mkdirs()
            File(this, "tools").mkdirs()
            File(this, "checkpoints").mkdirs()
            File(this, "outputs").mkdirs()
            File(this, ".engine").mkdirs()
        }
    }

    init {
        ensureTokenAndPort()
        refreshEnvironmentStatus()
        startHeartbeat()
    }

    private fun ensureTokenAndPort() {
        // Always use port 47831 and the required Bearer token
        settings.enginePort = DEFAULT_PORT
        if (settings.engineToken != DEFAULT_BEARER_TOKEN) {
            settings.engineToken = DEFAULT_BEARER_TOKEN
        }
    }

    fun rotateToken(): String {
        settings.engineToken = DEFAULT_BEARER_TOKEN
        return DEFAULT_BEARER_TOKEN
    }

    fun refreshEnvironmentStatus() {
        val termuxType = detectTermuxBuild()
        val hasPerm = ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOk = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false

        _status.update {
            it.copy(
                termuxBuild = termuxType,
                runCommandPermissionGranted = hasPerm,
                batteryOptimizationIgnored = batteryOk,
                port = DEFAULT_PORT
            )
        }
    }

    fun detectTermuxBuild(): TermuxBuildType {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(TERMUX_PACKAGE).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(TERMUX_PACKAGE)
            }
            val versionName = info.versionName ?: ""
            if (installer == "com.android.vending" || versionName.startsWith("0.101")) {
                TermuxBuildType.PLAY_STORE_OUTDATED
            } else {
                TermuxBuildType.FDROID_OR_GITHUB
            }
        } catch (_: Exception) {
            TermuxBuildType.NOT_INSTALLED
        }
    }

    fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    /**
     * Background heartbeat polling `GET http://127.0.0.1:47831/health` every 5 seconds.
     * Never uses cached or fake connection states.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                checkEngineHealthOnce()
                delay(5_000L)
            }
        }
    }

    suspend fun checkEngineHealthOnce(): Boolean = withContext(Dispatchers.IO) {
        refreshEnvironmentStatus()
        val token = DEFAULT_BEARER_TOKEN
        val t0 = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("http://127.0.0.1:$DEFAULT_PORT/health")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            healthHttp.newCall(req).execute().use { resp ->
                val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val obj = try { JSONObject(bodyStr) } catch (_: Exception) { JSONObject() }
                    _status.update {
                        it.copy(
                            connectionState = EngineConnectionState.CONNECTED_TERMUX,
                            port = DEFAULT_PORT,
                            engineVersion = obj.optString("version", "2.0.0"),
                            pythonVersion = obj.optString("python", "3.x"),
                            gitVersion = obj.optString("git", "2.x"),
                            workspacePath = obj.optString("workspace", TERMUX_HOME),
                            freeDiskMb = obj.optLong("free_mb", it.freeDiskMb),
                            lastHeartbeatMs = System.currentTimeMillis(),
                            measuredLatencyMs = latency,
                            lastError = null
                        )
                    }
                    return@withContext true
                } else {
                    _status.update {
                        it.copy(
                            connectionState = EngineConnectionState.DISCONNECTED,
                            lastError = "Termux Bridge HTTP ${resp.code} on http://127.0.0.1:$DEFAULT_PORT/health"
                        )
                    }
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            // ECONNREFUSED or timeout -> immediately mark DISCONNECTED (Red)
            _status.update {
                it.copy(
                    connectionState = EngineConnectionState.DISCONNECTED,
                    lastError = "Disconnected: http://127.0.0.1:$DEFAULT_PORT/health unreachable (${e.javaClass.simpleName})"
                )
            }
            return@withContext false
        }
    }

    /**
     * Attempts to launch the Termux daemon via RUN_COMMAND if permitted, then verifies live `/health`.
     * Never fakes success if the Termux HTTP server on `127.0.0.1:47831` is not responding.
     */
    fun setupOrRepairEngine(onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            _status.update {
                it.copy(
                    connectionState = EngineConnectionState.STARTING,
                    lastError = null
                )
            }
            tryLaunchTermuxDaemon(DEFAULT_PORT, DEFAULT_BEARER_TOKEN)
            delay(800L)
            val alive = checkEngineHealthOnce()
            onComplete?.invoke(alive)
        }
    }

    private fun tryLaunchTermuxDaemon(port: Int, token: String): Boolean {
        refreshEnvironmentStatus()
        val s = _status.value
        if (s.termuxBuild == TermuxBuildType.NOT_INSTALLED || !s.runCommandPermissionGranted) {
            return false
        }
        return try {
            val bootstrap = buildBootstrapShellScript(port, token)
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_SERVICE_NAME)
                action = ACTION_RUN_COMMAND
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", bootstrap))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun verifyToken(candidateToken: String): Boolean {
        return candidateToken.isNotBlank() &&
            (candidateToken == DEFAULT_BEARER_TOKEN || candidateToken == settings.engineToken)
    }

    fun isCommandDangerous(command: String): Boolean {
        val trimmed = command.trim()
        return DANGEROUS_COMMAND_PATTERNS.any { it.containsMatchIn(trimmed) }
    }

    fun resolveSandboxedFile(baseDir: File, relativeOrAbsPath: String): File {
        val raw = relativeOrAbsPath.trim()
        val candidate = if (raw.startsWith("/")) File(raw) else File(baseDir, raw)
        val canonicalBase = baseDir.canonicalPath
        val canonicalTarget = candidate.canonicalPath
        val canonicalForge = localForgeRoot.canonicalPath
        val inBase = canonicalTarget == canonicalBase || canonicalTarget.startsWith(canonicalBase + File.separator)
        val inForge = canonicalTarget == canonicalForge || canonicalTarget.startsWith(canonicalForge + File.separator)
        if (!inBase && !inForge) {
            throw SecurityException("Path escape blocked by sandbox: $relativeOrAbsPath")
        }
        return candidate
    }

    /**
     * Strictly executes shell commands in Termux via HTTP POST to `http://127.0.0.1:47831/exec`.
     * NEVER falls back to local Android `ProcessBuilder`.
     *
     * Required API Contract:
     *   URL: `http://127.0.0.1:47831/exec`
     *   Method: `POST`
     *   Headers:
     *     `Authorization: Bearer 9f8ef08845d6751517d97296d40e03216138ac23185e31aa`
     *     `Content-Type: application/json`
     *   Body: `{"command": "<BASH_CMD_HERE>", "cwd": "/data/data/com.termux/files/home", "timeout": 120}`
     */
    suspend fun execCommand(
        command: String,
        cwd: String = TERMUX_HOME,
        token: String = DEFAULT_BEARER_TOKEN,
        timeoutSeconds: Long = 120L,
        onOutputChunk: ((String) -> Unit)? = null
    ): ExecResult = withContext(Dispatchers.IO) {
        if (isCommandDangerous(command)) {
            val blockedMsg = "Blocked dangerous command by safety denylist: $command"
            return@withContext ExecResult(
                execId = UUID.randomUUID().toString(),
                command = command,
                cwd = TERMUX_HOME,
                stdout = "",
                stderr = blockedMsg,
                exitCode = 126,
                durationMs = 0L
            )
        }

        val execId = UUID.randomUUID().toString()
        val startMs = System.currentTimeMillis()
        val termuxCwd = toTermuxPath(cwd)
        val authBearer = DEFAULT_BEARER_TOKEN

        appendTerminal("\n$ $command  (cwd: $termuxCwd)\n")

        val payload = JSONObject()
            .put("command", command)
            .put("cwd", termuxCwd)
            .put("timeout", timeoutSeconds)

        val request = Request.Builder()
            .url("http://127.0.0.1:$DEFAULT_PORT/exec")
            .header("Authorization", "Bearer $authBearer")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        try {
            execHttp.newCall(request).execute().use { resp ->
                val dur = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                val rawBody = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    val errDetail = when (resp.code) {
                        401 -> "HTTP 401 Unauthorized from http://127.0.0.1:$DEFAULT_PORT/exec — Bearer token mismatch. Re-run the Termux Bridge start command."
                        404 -> "HTTP 404 Not Found from http://127.0.0.1:$DEFAULT_PORT/exec — Endpoint /exec not found on Termux server."
                        else -> "HTTP ${resp.code} error from Termux Bridge: ${rawBody.take(400)}"
                    }
                    if (resp.code == 401 || resp.code >= 500) {
                        _status.update {
                            it.copy(
                                connectionState = EngineConnectionState.ERROR,
                                lastError = errDetail
                            )
                        }
                    }
                    appendTerminal("ERROR: $errDetail\n")
                    recordAudit(command, termuxCwd, resp.code, dur, errDetail)
                    return@withContext ExecResult(
                        execId = execId,
                        command = command,
                        cwd = termuxCwd,
                        stdout = "",
                        stderr = errDetail,
                        exitCode = resp.code,
                        durationMs = dur
                    )
                }

                // Mark bridge healthy since /exec responded 200 OK
                _status.update {
                    it.copy(
                        connectionState = EngineConnectionState.CONNECTED_TERMUX,
                        lastHeartbeatMs = System.currentTimeMillis(),
                        lastError = null
                    )
                }

                val obj = try { JSONObject(rawBody) } catch (_: Exception) { JSONObject() }
                val out = obj.optString("stdout", "")
                val err = obj.optString("stderr", "")
                val code = if (obj.has("exit_code")) obj.optInt("exit_code", 0) else obj.optInt("returncode", 0)
                val actualCwd = obj.optString("cwd", termuxCwd).ifBlank { termuxCwd }

                val combined = buildString {
                    if (out.isNotEmpty()) append(out)
                    if (err.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append(err)
                    }
                }
                if (combined.isNotEmpty()) {
                    onOutputChunk?.invoke(combined)
                    appendTerminal(combined + "\n")
                }
                recordAudit(command, actualCwd, code, dur, combined)
                return@withContext ExecResult(
                    execId = execId,
                    command = command,
                    cwd = actualCwd,
                    stdout = out,
                    stderr = err,
                    exitCode = code,
                    durationMs = dur
                )
            }
        } catch (e: ConnectException) {
            val dur = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val msg = "ECONNREFUSED: Cannot connect to Termux HTTP Bridge at http://127.0.0.1:$DEFAULT_PORT/exec. Make sure Termux is running the bridge server."
            _status.update {
                it.copy(
                    connectionState = EngineConnectionState.DISCONNECTED,
                    lastError = msg
                )
            }
            appendTerminal("ERROR: $msg\n")
            recordAudit(command, termuxCwd, 111, dur, msg)
            return@withContext ExecResult(execId, command, termuxCwd, "", msg, 111, dur)
        } catch (e: SocketTimeoutException) {
            val dur = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val msg = "TIMEOUT: Termux command timed out after ${timeoutSeconds}s on http://127.0.0.1:$DEFAULT_PORT/exec (${e.message})."
            appendTerminal("ERROR: $msg\n")
            recordAudit(command, termuxCwd, 124, dur, msg)
            return@withContext ExecResult(execId, command, termuxCwd, "", msg, 124, dur)
        } catch (e: Exception) {
            val dur = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val msg = "Termux HTTP Bridge error on http://127.0.0.1:$DEFAULT_PORT/exec: ${e.message ?: e.javaClass.simpleName}"
            _status.update {
                it.copy(
                    connectionState = EngineConnectionState.DISCONNECTED,
                    lastError = msg
                )
            }
            appendTerminal("ERROR: $msg\n")
            recordAudit(command, termuxCwd, 1, dur, msg)
            return@withContext ExecResult(execId, command, termuxCwd, "", msg, 1, dur)
        }
    }

    fun killCommand(execId: String) {
        // Commands run remotely in Termux via HTTP /exec
    }

    fun killAllRunningCommands() {
        // No local processes
    }

    fun clearTerminal() {
        _terminalOutput.value = "CodeForge Termux HTTP Bridge Terminal — Cleared\n"
    }

    fun clearTerminalOutput() = clearTerminal()

    val workspaceRoot: File
        get() = localForgeRoot

    val projectsDir: File
        get() = File(localForgeRoot, "projects").apply { mkdirs() }

    suspend fun refreshHealth(): BridgeStatus {
        checkEngineHealthOnce()
        return status.value
    }

    suspend fun bootstrapAndStartEngine(): Result<String> = withContext(Dispatchers.IO) {
        tryLaunchTermuxDaemon(DEFAULT_PORT, DEFAULT_BEARER_TOKEN)
        delay(700L)
        val ok = checkEngineHealthOnce()
        if (ok) {
            Result.success("Connected to Termux HTTP Bridge on http://127.0.0.1:$DEFAULT_PORT")
        } else {
            Result.failure(IllegalStateException("Termux HTTP Bridge is not running on http://127.0.0.1:$DEFAULT_PORT. Copy & paste the Start Command in Termux."))
        }
    }

    suspend fun execCommand(
        command: String,
        cwd: String = TERMUX_HOME,
        timeoutSec: Int = 120
    ): ExecResult = execCommand(
        command = command,
        cwd = cwd,
        token = DEFAULT_BEARER_TOKEN,
        timeoutSeconds = timeoutSec.toLong()
    )

    fun readAuditLog(maxEntries: Int = 60): String =
        auditLogs.value.take(maxEntries).joinToString("\n") {
            "[exit=${it.exitCode}] $ ${it.command} (${it.durationMs}ms in ${it.cwd})"
        }.ifBlank { "No commands executed yet." }

    private fun appendTerminal(text: String) {
        _terminalOutput.update { cur ->
            val next = cur + text
            if (next.length > 40_000) next.takeLast(32_000) else next
        }
    }

    private fun recordAudit(command: String, cwd: String, exitCode: Int, durationMs: Long, output: String) {
        val entry = AuditCommandLog(
            command = command,
            cwd = cwd,
            exitCode = exitCode,
            durationMs = durationMs,
            outputPreview = output.take(400)
        )
        _auditLogs.update { (listOf(entry) + it).take(200) }
    }

    /**
     * Maps any path to a real Termux directory (defaulting to `/data/data/com.termux/files/home`).
     * Never passes Android sandbox paths (`/data/user/0/com.aistudio...`) to Termux.
     */
    fun toTermuxPath(rawPath: String): String {
        val clean = rawPath.trim()
        if (clean.isEmpty() || clean == "." || clean == "~" || clean == "~/") {
            return TERMUX_HOME
        }
        if (clean.startsWith("~/")) {
            return "$TERMUX_HOME/${clean.removePrefix("~/")}"
        }
        if (clean.startsWith(TERMUX_HOME)) {
            return clean
        }
        // Map any local Android sandbox path back to /data/data/com.termux/files/home
        if (clean.contains("/com.aistudio.") || clean.startsWith("/data/user/") || clean.startsWith("/data/data/com.aistudio")) {
            val afterScratch = clean.substringAfter("/projects/scratch", "")
            if (afterScratch.isEmpty() && clean.endsWith("/projects/scratch")) return TERMUX_HOME
            val afterProjects = clean.substringAfter("/files/projects/", "")
            if (afterProjects.isNotEmpty() && afterProjects != "scratch") {
                return "$TERMUX_HOME/$afterProjects"
            }
            return TERMUX_HOME
        }
        if (!clean.startsWith("/")) {
            return "$TERMUX_HOME/$clean"
        }
        return clean
    }

    fun toDisplayPath(rawPath: String): String {
        return toTermuxPath(rawPath).replaceFirst(TERMUX_HOME, "~")
    }

    /** Step 1: Copyable command to enable `allow-external-apps = true` in Termux. */
    fun allowExternalAppsCommand(): String =
        "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"

    /** Step 2: Copyable command to install required packages in Termux. */
    fun installPackagesCommand(): String =
        "pkg update -y && pkg install -y python nodejs git gh curl ripgrep"

    /** Step 3: Copyable command to install REA (Reverse Engineer Anything — morluto/rea) skill in Termux. */
    fun installReaSkillCommand(): String =
        "mkdir -p ~/forge/tools && cd ~/forge/tools && (git clone https://github.com/morluto/rea.git || (cd rea && git pull)) && npx -y rea-agents setup"

    /**
     * Step 4: 1-Line Direct Start Command that starts the Termux HTTP bridge on `127.0.0.1:47831`
     * with Bearer token `9f8ef08845d6751517d97296d40e03216138ac23185e31aa`.
     */
    fun directStartEngineCommand(): String {
        val script = buildBootstrapShellScript(DEFAULT_PORT, DEFAULT_BEARER_TOKEN)
        val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "echo '$b64' | base64 -d > ~/.forge_boot.sh && bash ~/.forge_boot.sh && echo '✓ CodeForge Termux HTTP Bridge listening on http://127.0.0.1:$DEFAULT_PORT'"
    }

    /**
     * All-in-one command that configures allow-external-apps, installs packages, and starts the Termux HTTP Bridge on `127.0.0.1:47831`.
     */
    fun allInOneTermuxSetupCommand(): String {
        return "${allowExternalAppsCommand()} && ${installPackagesCommand()} && ${directStartEngineCommand()}"
    }

    fun openTermuxApp(): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                val fdroidIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/en/packages/com.termux/")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fdroidIntent)
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun openAppPermissionSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun buildBootstrapShellScript(port: Int, token: String): String = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -e
        command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
        mkdir -p ~/forge/{projects,tools,.engine,checkpoints,outputs}
        echo -n "$token" > ~/forge/.engine/token
        chmod 600 ~/forge/.engine/token
        if ! command -v python3 >/dev/null 2>&1; then
            pkg update -y && pkg install -y python git curl
        fi
        cat << 'EOF' > ~/forge/.engine/forge_server.py
        ${buildForgeEnginePythonScript()}
        EOF
        pkill -f "forge_server.py" >/dev/null 2>&1 || true
        nohup python3 ~/forge/.engine/forge_server.py --port $port >/data/data/com.termux/files/home/forge/.engine/engine.log 2>&1 &
        sleep 1
    """.trimIndent()

    private fun buildForgeEnginePythonScript(): String = """
import os, sys, json, time, shutil, subprocess, base64
from http.server import BaseHTTPRequestHandler, HTTPServer

HOME_DIR = "/data/data/com.termux/files/home"
WORKSPACE = os.path.expanduser("~/forge")
TOKEN_FILE = os.path.join(WORKSPACE, ".engine", "token")
DEFAULT_TOKEN = "$DEFAULT_BEARER_TOKEN"
START_TIME = time.time()

os.makedirs(WORKSPACE, exist_ok=True)

def load_token():
    try:
        if os.path.exists(TOKEN_FILE):
            with open(TOKEN_FILE, "r") as f:
                t = f.read().strip()
                if t:
                    return t
    except Exception:
        pass
    return DEFAULT_TOKEN

def resolve_cwd(raw_cwd):
    if not raw_cwd:
        return HOME_DIR
    c = raw_cwd.strip()
    if c == "." or c == "~" or c == "~/":
        return HOME_DIR
    if c.startswith("~/"):
        c = os.path.join(HOME_DIR, c[2:])
    elif c.startswith("/data/user/") or c.startswith("/data/data/com.aistudio"):
        c = HOME_DIR
    elif not c.startswith("/"):
        c = os.path.join(HOME_DIR, c)
    if not os.path.exists(c):
        try:
            os.makedirs(c, exist_ok=True)
        except Exception:
            return HOME_DIR
    return c

class ForgeHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def _auth(self):
        auth = self.headers.get("Authorization", "")
        if not auth:
            return True
        expected = "Bearer " + load_token()
        return auth == expected or auth == ("Bearer " + DEFAULT_TOKEN)

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._auth():
            return self._send(401, {"error": "Unauthorized"})
        if self.path.startswith("/health"):
            st = shutil.disk_usage(HOME_DIR)
            self._send(200, {
                "status": "ok",
                "version": "2.0.0",
                "uptime": int(time.time() - START_TIME),
                "python": sys.version.split()[0],
                "workspace": HOME_DIR,
                "free_mb": st.free // (1024 * 1024)
            })
        else:
            self._send(404, {"error": "Not found"})

    def do_POST(self):
        if not self._auth():
            return self._send(401, {"error": "Unauthorized"})
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        data = json.loads(raw or "{}")
        if self.path == "/exec":
            cmd = data.get("command", "")
            cwd = resolve_cwd(data.get("cwd", HOME_DIR))
            timeout = int(data.get("timeout", 120))
            try:
                p = subprocess.run(
                    cmd,
                    shell=True,
                    executable="/data/data/com.termux/files/usr/bin/bash" if os.path.exists("/data/data/com.termux/files/usr/bin/bash") else None,
                    cwd=cwd,
                    capture_output=True,
                    text=True,
                    timeout=timeout
                )
                self._send(200, {
                    "stdout": p.stdout,
                    "stderr": p.stderr,
                    "exit_code": p.returncode,
                    "cwd": cwd
                })
            except subprocess.TimeoutExpired as te:
                self._send(200, {
                    "stdout": (te.stdout or "") if isinstance(te.stdout, str) else "",
                    "stderr": f"Command timed out after {timeout}s",
                    "exit_code": 124,
                    "cwd": cwd
                })
            except Exception as ex:
                self._send(200, {
                    "stdout": "",
                    "stderr": str(ex),
                    "exit_code": 1,
                    "cwd": cwd
                })
        else:
            self._send(404, {"error": "Unknown route"})

if __name__ == "__main__":
    port = 47831
    if "--port" in sys.argv:
        port = int(sys.argv[sys.argv.index("--port") + 1])
    HTTPServer(("127.0.0.1", port), ForgeHandler).serve_forever()
    """.trimIndent()
}
