package com.example.data.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LocalhostServerInfo(
    val pid: Int,
    val port: Int,
    val url: String,
    val command: String,
    val cwd: String,
    val logPath: String,
    val startedAtMs: Long = System.currentTimeMillis()
)

/**
 * Manages background localhost servers started inside Termux, tracking their exact PIDs & ports
 * so the agent can cleanly inspect or stop them when requested by the user.
 */
class LocalhostServerManager {

    private val _servers = MutableStateFlow<List<LocalhostServerInfo>>(emptyList())
    val servers: StateFlow<List<LocalhostServerInfo>> = _servers.asStateFlow()

    fun registerServer(info: LocalhostServerInfo) {
        _servers.update { current ->
            current.filterNot { it.port == info.port || it.pid == info.pid } + info
        }
    }

    fun unregisterServer(portOrPid: Int): LocalhostServerInfo? {
        var removed: LocalhostServerInfo? = null
        _servers.update { current ->
            removed = current.firstOrNull { it.port == portOrPid || it.pid == portOrPid }
            if (removed != null) current.filterNot { it == removed } else current
        }
        return removed
    }

    fun findByPortOrPid(portOrPid: Int): LocalhostServerInfo? =
        _servers.value.firstOrNull { it.port == portOrPid || it.pid == portOrPid }

    /**
     * Starts a background server in Termux, captures its real PID, and verifies it is alive.
     */
    suspend fun startServer(
        bridge: TermuxBridge,
        port: Int,
        cwd: String,
        customCommand: String? = null
    ): Result<LocalhostServerInfo> {
        val safePort = port.coerceIn(1024, 65535)
        val logPath = "/tmp/forge_localhost_$safePort.log"
        val effectiveCmd = customCommand?.trim()?.ifBlank { null }
            ?: "python3 -m http.server $safePort --bind 127.0.0.1"

        // Stop any previously tracked server on the same port first
        findByPortOrPid(safePort)?.let { existing ->
            stopServer(bridge, existing.pid)
        }

        val escapedCwd = "'" + cwd.replace("'", "'\"'\"'") + "'"
        val launchCmd = """
            mkdir -p $escapedCwd && cd $escapedCwd &&
            nohup sh -c ${"'" + effectiveCmd.replace("'", "'\"'\"'") + "'"} > $logPath 2>&1 </dev/null &
            SRV_PID=$!
            sleep 0.6
            if kill -0 "${'$'}SRV_PID" 2>/dev/null; then
              echo "FORGE_SRV_PID=${'$'}SRV_PID"
            else
              echo "FORGE_SRV_DEAD"
              tail -n 30 $logPath 2>/dev/null
            fi
        """.trimIndent()

        val res = bridge.execCommand(command = launchCmd, cwd = cwd, timeoutSec = 20)
        if (res.bridgeError) {
            return Result.failure(IllegalStateException("Termux bridge offline: ${res.stderr}"))
        }
        val pidMatch = Regex("""FORGE_SRV_PID=(\d+)""").find(res.stdout)
        val pid = pidMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (pid == null) {
            val errDetail = (res.stdout + "\n" + res.stderr).trim().take(600)
            return Result.failure(IllegalStateException("Localhost server failed to stay alive on port $safePort:\n$errDetail"))
        }

        val info = LocalhostServerInfo(
            pid = pid,
            port = safePort,
            url = "http://localhost:$safePort",
            command = effectiveCmd,
            cwd = cwd,
            logPath = logPath
        )
        registerServer(info)
        return Result.success(info)
    }

    /**
     * Stops a tracked server by PID or port (or stops all tracked servers if [portOrPid] is null).
     */
    suspend fun stopServer(bridge: TermuxBridge, portOrPid: Int? = null): String {
        val targets = if (portOrPid != null) {
            val found = findByPortOrPid(portOrPid)
            if (found != null) listOf(found) else emptyList()
        } else {
            _servers.value
        }

        if (targets.isEmpty()) {
            if (portOrPid != null) {
                // Fallback: attempt direct kill if caller passed an untracked PID or port
                val cmd = "kill $portOrPid 2>/dev/null || fuser -k ${portOrPid}/tcp 2>/dev/null || true"
                bridge.execCommand(cmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 10)
                return "Sent stop signal to PID/port $portOrPid (was not in tracked registry)."
            }
            return "No active tracked localhost servers are running."
        }

        val stoppedLabels = mutableListOf<String>()
        for (srv in targets) {
            val killCmd = "kill ${srv.pid} 2>/dev/null || true; sleep 0.25; kill -9 ${srv.pid} 2>/dev/null || true; " +
                "pkill -P ${srv.pid} 2>/dev/null || true"
            bridge.execCommand(killCmd, cwd = TermuxBridge.TERMUX_HOME, timeoutSec = 10)
            unregisterServer(srv.pid)
            stoppedLabels.add("${srv.url} (PID ${srv.pid})")
        }
        return "Stopped localhost server(s): ${stoppedLabels.joinToString(", ")}"
    }

    fun formatActiveServers(): String {
        val list = _servers.value
        if (list.isEmpty()) return "No active tracked localhost servers."
        return list.joinToString("\n") { srv ->
            "- ${srv.url} (PID ${srv.pid}) | cmd=`${srv.command}` | cwd=`${srv.cwd}` | log=`${srv.logPath}`"
        }
    }
}
