package com.example.data.logs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogKind { CMD, INFO, OK, ERROR }

data class LogLine(val time: Long, val kind: LogKind, val text: String)

/** Terminal-style activity log for everything GitHub related (connect, push, builds). English, copyable. */
class GitLogStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "github_terminal.log")
    private val _lines = MutableStateFlow(load())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private fun load(): List<LogLine> {
        return try {
            if (!file.exists()) emptyList()
            else file.readLines().mapNotNull { l ->
                val p = l.split("\t", limit = 3)
                if (p.size == 3) {
                    val kind = try { LogKind.valueOf(p[1]) } catch (e: Exception) { LogKind.INFO }
                    LogLine(p[0].toLongOrNull() ?: 0L, kind, p[2].replace("\\n", "\n"))
                } else null
            }.takeLast(300)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun add(kind: LogKind, text: String) {
        val line = LogLine(System.currentTimeMillis(), kind, text)
        val updated = (_lines.value + line).takeLast(300)
        _lines.value = updated
        try {
            file.writeText(updated.joinToString("\n") { "${it.time}\t${it.kind}\t${it.text.replace("\n", "\\n")}" })
        } catch (e: Exception) {
            // ignore storage problems
        }
    }

    fun cmd(text: String) = add(LogKind.CMD, text)
    fun info(text: String) = add(LogKind.INFO, text)
    fun ok(text: String) = add(LogKind.OK, text)
    fun error(text: String) = add(LogKind.ERROR, text)

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        try { file.delete() } catch (e: Exception) { }
    }

    fun asText(): String {
        val f = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return _lines.value.joinToString("\n") { l ->
            val prefix = when (l.kind) {
                LogKind.CMD -> "$ "
                LogKind.OK -> "✓ "
                LogKind.ERROR -> "✗ "
                LogKind.INFO -> "  "
            }
            "[" + f.format(Date(l.time)) + "] " + prefix + l.text
        }
    }
}
