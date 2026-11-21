package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ToolStepEntity
import org.json.JSONObject

fun describeStepSentence(step: ToolStepEntity): String {
    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
    fun name(p: String) = p.substringAfterLast('/').ifBlank { p }
    return when (step.toolName) {
        "list_files" -> "Listing Termux files"
        "find_files" -> "Discovering files in Termux"
        "read_file" -> "Reading Termux file (${name(a.optString("path"))})"
        "search_code" -> "Searching code via regex in Termux"
        "discover_skills_and_repos" -> "Scanning installed Termux skills & repos"
        "apply_patch" -> "Applying surgical diff patch (${name(a.optString("path"))})"
        "edit_file", "insert_after_line", "replace_line_range" -> "Patching file (${name(a.optString("path"))})"
        "write_file" -> "Creating file (${name(a.optString("path"))})"
        "delete_file", "move_file" -> "Updating files in Termux"
        "run_command", "exec", "run_installed_tool" -> "Executing command in Termux (/exec)"
        "building", "github_build" -> "Building on GitHub"
        "git_clone" -> "Cloning repository in Termux"
        "install_repo_tool" -> "Installing tool in Termux"
        "finish" -> "Wrapping up"
        else -> "Executing ${step.toolName}"
    }
}

private fun shortToolBadge(toolName: String): String = when (toolName) {
    "run_command", "exec", "run_installed_tool" -> "termux /exec"
    "apply_patch" -> "patch"
    "edit_file", "insert_after_line", "replace_line_range" -> "edit"
    "write_file" -> "create"
    "read_file" -> "read"
    "list_files" -> "list"
    "find_files" -> "find"
    "search_code" -> "rg"
    "discover_skills_and_repos" -> "skills"
    "delete_file" -> "delete"
    "move_file" -> "move"
    "git_clone" -> "git"
    "building", "github_build" -> "building"
    else -> toolName
}

private fun toolHeaderSummary(step: ToolStepEntity): String {
    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
    return when (step.toolName) {
        "run_command", "exec", "run_installed_tool" -> {
            (step.commandText ?: a.optString("command")).trim().ifBlank { a.optString("tool_name", "bash") }
        }
        "building", "github_build" -> {
            val repo = a.optString("repo").ifBlank { step.commandText.orEmpty() }
            if (repo.isNotBlank()) repo else "GitHub Actions workflow"
        }
        "apply_patch", "read_file", "edit_file", "write_file", "insert_after_line", "replace_line_range", "delete_file" -> {
            val p = (step.filePath ?: a.optString("path")).trim()
            val diff = step.diffSummary?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "$p$diff"
        }
        "list_files" -> a.optString("path", "/data/data/com.termux/files/home")
        "find_files" -> {
            val pat = a.optString("pattern").trim()
            val root = a.optString("path", "~")
            if (pat.isNotBlank()) "$pat in $root" else root
        }
        "search_code" -> a.optString("query")
        "discover_skills_and_repos" -> a.optString("query").ifBlank { "All Termux skills & repos" }
        "git_clone", "install_repo_tool" -> a.optString("url")
        else -> step.commandText ?: step.filePath ?: ""
    }
}

data class ParsedExecBlock(
    val command: String,
    val cwd: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val durationMs: Long?,
    val diffPreview: String = "",
    val filePath: String = "",
    val addedLines: Int = 0,
    val removedLines: Int = 0
)

/**
 * Parses either a JSON `/exec` or surgical-patch result into structured terminal + diff fields.
 */
fun parseExecResultBlock(step: ToolStepEntity): ParsedExecBlock {
    val argsObj = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
    val defaultCmd = step.commandText?.takeIf { it.isNotBlank() }
        ?: argsObj.optString("command").ifBlank { argsObj.optString("path") }
    val defaultCwd = step.cwdText?.takeIf { it.isNotBlank() }
        ?: argsObj.optString("cwd", "/data/data/com.termux/files/home")
    val defaultFile = step.filePath?.takeIf { it.isNotBlank() } ?: argsObj.optString("path", "")

    val raw = step.resultText.trim()
    if (raw.startsWith("{") && raw.endsWith("}")) {
        try {
            val obj = JSONObject(raw)
            return ParsedExecBlock(
                command = obj.optString("command", defaultCmd).ifBlank { defaultCmd },
                cwd = obj.optString("cwd", defaultCwd).ifBlank { defaultCwd },
                stdout = obj.optString("stdout", ""),
                stderr = obj.optString("stderr", ""),
                exitCode = if (obj.has("exit_code")) obj.optInt("exit_code") else null,
                durationMs = if (obj.has("duration_ms")) obj.optLong("duration_ms") else step.durationMs,
                diffPreview = obj.optString("diff_preview", ""),
                filePath = obj.optString("file_path", defaultFile).ifBlank { defaultFile },
                addedLines = obj.optInt("added_lines", 0),
                removedLines = obj.optInt("removed_lines", 0)
            )
        } catch (_: Exception) {}
    }

    var exitCode: Int? = null
    val stdoutLines = mutableListOf<String>()
    val stderrLines = mutableListOf<String>()
    var section = "STDOUT"

    for (line in raw.lines()) {
        when {
            line.startsWith("Exit code:") -> {
                val numStr = line.removePrefix("Exit code:").trim().substringBefore(" ").trim()
                exitCode = numStr.toIntOrNull()
            }
            line.trim() == "STDOUT:" -> section = "STDOUT"
            line.trim() == "STDERR:" -> section = "STDERR"
            else -> {
                if (section == "STDERR") stderrLines.add(line) else stdoutLines.add(line)
            }
        }
    }

    return ParsedExecBlock(
        command = defaultCmd,
        cwd = defaultCwd,
        stdout = stdoutLines.joinToString("\n").trim(),
        stderr = stderrLines.joinToString("\n").trim(),
        exitCode = exitCode ?: if (step.status == "RUNNING") null else (if (step.isError) 1 else 0),
        durationMs = step.durationMs.takeIf { it > 0 },
        filePath = defaultFile
    )
}

/**
 * Renders tool execution blocks directly inside the Chat UI as VS Code Cloud terminal + diff cards.
 */
@Composable
fun AgentSummaryBar(
    steps: List<ToolStepEntity>,
    running: Boolean,
    fallbackTitle: String,
    modifier: Modifier = Modifier
) {
    val visibleSteps = remember(steps) {
        steps.sortedBy { it.stepIndex }
            .filter { it.toolName != "note" && it.toolName != "finish" }
    }
    if (visibleSteps.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (step in visibleSteps) {
            ExpandableActionStepCard(step = step)
        }
    }
}

@Composable
private fun ExpandableActionStepCard(step: ToolStepEntity) {
    val parsedExec = remember(step.resultText, step.argumentsJson, step.commandText, step.cwdText, step.status, step.isError) {
        parseExecResultBlock(step)
    }
    val hasDiff = parsedExec.diffPreview.isNotBlank()
    val isExecStep = step.toolName in setOf(
        "run_command", "exec", "run_installed_tool", "git_clone", "install_repo_tool",
        "apply_patch", "edit_file", "replace_line_range", "insert_after_line"
    ) || step.type == "CMD" || hasDiff

    // Auto-expand command steps, diff patches, build steps, and errors
    var expanded by remember(step.id) {
        mutableStateOf(isExecStep || step.toolName == "building" || step.isError || step.status == "FAILED")
    }
    var copied by remember(step.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val isRunning = step.status == "RUNNING"
    val isError = step.isError || step.status == "FAILED"
    val badge = remember(step.toolName) { shortToolBadge(step.toolName) }
    val summary = remember(step.argumentsJson, step.commandText, step.filePath, step.diffSummary) {
        toolHeaderSummary(step)
    }

    val terminalCardBg = Color(0xFF0D1117)
    val terminalInnerBg = Color(0xFF06090F)
    val terminalBorder = if (isError) Color(0xFFE5534B).copy(alpha = 0.65f) else Color(0xFF30363D)
    val toolBlue = Color(0xFF58A6FF)
    val termGreen = Color(0xFF3FB950)
    val termRed = Color(0xFFF85149)
    val patchPurple = Color(0xFFD2A8FF)
    val badgeTint = if (hasDiff || step.type == "EDIT") patchPurple else toolBlue

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(terminalCardBg)
            .border(1.dp, terminalBorder, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("tool_step_card_${step.stepIndex}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isRunning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = toolBlue
                    )
                }
                isError -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Failed",
                        tint = termRed,
                        modifier = Modifier.size(16.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Succeeded",
                        tint = termGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeTint.copy(alpha = 0.16f)
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = badgeTint,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = summary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFE6EDF3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Diff delta pills in header if patch
            if (hasDiff && !isRunning) {
                if (parsedExec.addedLines > 0) {
                    Text(
                        text = "+${parsedExec.addedLines}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = termGreen
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (parsedExec.removedLines > 0) {
                    Text(
                        text = "-${parsedExec.removedLines}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = termRed
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }

            // Exit Code Pill right in header
            if (parsedExec.exitCode != null && !isRunning) {
                val exitZero = parsedExec.exitCode == 0
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (exitZero) termGreen.copy(alpha = 0.18f) else termRed.copy(alpha = 0.22f)
                ) {
                    Text(
                        text = "Exit: ${parsedExec.exitCode}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (exitZero) termGreen else termRed,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // VS Code Cloud Diff Preview Box for surgical patches
                if (hasDiff) {
                    SurgicalDiffPreviewBox(
                        filePath = parsedExec.filePath.ifBlank { summary },
                        diffText = parsedExec.diffPreview,
                        addedLines = parsedExec.addedLines,
                        removedLines = parsedExec.removedLines
                    )
                } else {
                    // 1. Command Executed + Working Directory Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(terminalInnerBg)
                            .border(1.dp, Color(0xFF21262D), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "COMMAND EXECUTED (cwd: ${parsedExec.cwd})",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8B949E),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF161B22),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val copyPayload = buildString {
                                            appendLine("$ ${parsedExec.command.ifBlank { summary }}")
                                            if (parsedExec.stdout.isNotBlank()) appendLine(parsedExec.stdout)
                                            if (parsedExec.stderr.isNotBlank()) appendLine(parsedExec.stderr)
                                        }.trim()
                                        clipboard.setText(AnnotatedString(copyPayload))
                                        copied = true
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copy output",
                                        tint = if (copied) termGreen else Color(0xFF8B949E),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (copied) "Copied" else "Copy",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (copied) termGreen else Color(0xFF8B949E)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "$ ${parsedExec.command.ifBlank { summary }}",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF79C0FF),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }

                    // 2. STDOUT (Output) Box
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(terminalInnerBg)
                            .border(1.dp, Color(0xFF21262D), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "STDOUT (OUTPUT)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = termGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                isRunning && parsedExec.stdout.isBlank() -> "Running on http://127.0.0.1:47831/exec…"
                                parsedExec.stdout.isBlank() -> "(empty stdout)"
                                else -> parsedExec.stdout
                            },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFE6EDF3),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }

                // 3. STDERR (Errors) Box — shown whenever stderr is non-empty or step failed
                if (parsedExec.stderr.isNotBlank() || isError) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A0D10))
                            .border(1.dp, termRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "STDERR (ERRORS)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = termRed
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = parsedExec.stderr.ifBlank { "Command failed with exit code ${parsedExec.exitCode ?: 1}" },
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFFA198),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }

                // 4. Exit Code & Duration Footer Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val codeVal = parsedExec.exitCode
                    val codeLabel = if (codeVal != null) "Exit Code: $codeVal" else "Exit Code: running…"
                    val codeColor = if (codeVal == 0) termGreen else if (codeVal != null) termRed else toolBlue
                    Text(
                        text = codeLabel,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = codeColor
                    )
                    if (parsedExec.durationMs != null && parsedExec.durationMs > 0) {
                        Text(
                            text = "${parsedExec.durationMs} ms · ${if (hasDiff) "surgical diff" else "http://127.0.0.1:47831/exec"}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8B949E)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SurgicalDiffPreviewBox(
    filePath: String,
    diffText: String,
    addedLines: Int,
    removedLines: Int
) {
    val lines = remember(diffText) { diffText.lines().take(80) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF06090F))
            .border(1.dp, Color(0xFF21262D), RoundedCornerShape(8.dp))
            .testTag("surgical_diff_preview")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DIFF · $filePath",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC9D1D9),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "+$addedLines",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3FB950)
                )
                Text(
                    text = "-$removedLines",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF85149)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            for (line in lines) {
                val (bg, fg) = when {
                    line.startsWith("+++") || line.startsWith("---") ->
                        Color.Transparent to Color(0xFF8B949E)
                    line.startsWith("@@") ->
                        Color(0xFF102033) to Color(0xFF79C0FF)
                    line.startsWith("+") ->
                        Color(0xFF12261E) to Color(0xFF3FB950)
                    line.startsWith("-") ->
                        Color(0xFF2D1418) to Color(0xFFF85149)
                    else ->
                        Color.Transparent to Color(0xFFC9D1D9)
                }
                Text(
                    text = if (line.isEmpty()) " " else line,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = fg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 1.dp)
                )
            }
        }
    }
}
