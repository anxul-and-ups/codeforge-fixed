package com.example.data.termux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class CommandExecState(val label: String) {
    QUEUED("Queued"),
    RUNNING("Running"),
    WAITING("Waiting"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

data class TerminalExecutionRecord(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val workingDir: String,
    val state: CommandExecState = CommandExecState.QUEUED,
    val pid: Int? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null,
    val durationMs: Long = 0L,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null
)

/**
 * Live terminal execution tracker with explicit command lifecycle states, live output capture,
 * PID/timing metadata, and automatic secret/token redaction for UI display safety.
 */
class TerminalExecutionTracker {

    companion object {
        private val SECRET_PATTERNS = listOf(
            Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-]{12,}"""),
            Regex("""(ghp_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,})"""),
            Regex("""(sk-[A-Za-z0-9\-_]{16,})"""),
            Regex("""(AIza[0-9A-Za-z\-_]{20,})"""),
            Regex("""(?i)((?:api[_-]?key|token|secret|password)\s*[=:]\s*["']?)([^\s"']{8,})""")
        )

        fun redactSecrets(input: String): String {
            if (input.isBlank()) return input
            var out = input
            out = SECRET_PATTERNS[0].replace(out) { "${it.groupValues[1]}[REDACTED]" }
            out = SECRET_PATTERNS[1].replace(out, "[REDACTED_GH_TOKEN]")
            out = SECRET_PATTERNS[2].replace(out, "[REDACTED_API_KEY]")
            out = SECRET_PATTERNS[3].replace(out, "[REDACTED_GOOGLE_KEY]")
            out = SECRET_PATTERNS[4].replace(out) { "${it.groupValues[1]}[REDACTED]" }
            return out
        }
    }

    private val _executions = MutableStateFlow<List<TerminalExecutionRecord>>(emptyList())
    val executions: StateFlow<List<TerminalExecutionRecord>> = _executions.asStateFlow()

    fun onQueued(id: String, command: String, workingDir: String) {
        val safeCmd = redactSecrets(command)
        val rec = TerminalExecutionRecord(
            id = id,
            command = safeCmd,
            workingDir = workingDir,
            state = CommandExecState.QUEUED,
            startedAtMs = System.currentTimeMillis()
        )
        _executions.update { (listOf(rec) + it).take(80) }
    }

    fun onRunning(id: String, pid: Int? = null) {
        _executions.update { list ->
            list.map {
                if (it.id == id) it.copy(state = CommandExecState.RUNNING, pid = pid ?: it.pid)
                else it
            }
        }
    }

    fun onWaiting(id: String) {
        _executions.update { list ->
            list.map { if (it.id == id) it.copy(state = CommandExecState.WAITING) else it }
        }
    }

    fun onOutputChunk(id: String, chunk: String) {
        if (chunk.isEmpty()) return
        val safe = redactSecrets(chunk)
        _executions.update { list ->
            list.map {
                if (it.id == id) it.copy(stdout = (it.stdout + safe).takeLast(24_000))
                else it
            }
        }
    }

    fun onFinished(
        id: String,
        stdout: String,
        stderr: String,
        exitCode: Int,
        durationMs: Long,
        cancelled: Boolean = false
    ) {
        val finalState = when {
            cancelled || exitCode == 137 -> CommandExecState.CANCELLED
            exitCode == 0 -> CommandExecState.COMPLETED
            else -> CommandExecState.FAILED
        }
        val safeOut = redactSecrets(stdout).takeLast(24_000)
        val safeErr = redactSecrets(stderr).takeLast(12_000)
        val now = System.currentTimeMillis()
        _executions.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(
                        state = finalState,
                        finishedAtMs = now,
                        durationMs = durationMs,
                        stdout = safeOut,
                        stderr = safeErr,
                        exitCode = exitCode
                    )
                } else it
            }
        }
    }

    fun markAllRunningCancelled() {
        val now = System.currentTimeMillis()
        _executions.update { list ->
            list.map {
                if (it.state == CommandExecState.RUNNING || it.state == CommandExecState.QUEUED || it.state == CommandExecState.WAITING) {
                    it.copy(
                        state = CommandExecState.CANCELLED,
                        finishedAtMs = now,
                        durationMs = (now - it.startedAtMs).coerceAtLeast(1L),
                        exitCode = 137,
                        stderr = (it.stderr + "\nCancelled by user.").trim()
                    )
                } else it
            }
        }
    }

    fun clear() {
        _executions.value = emptyList()
    }
}
