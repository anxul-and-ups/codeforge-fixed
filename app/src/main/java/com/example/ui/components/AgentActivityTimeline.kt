package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ToolStepEntity
import com.example.ui.theme.AppColors
import org.json.JSONObject

private data class StepView(val verb: String, val target: String, val badge: String? = null)

private fun describe(step: ToolStepEntity): StepView {
    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
    return when (step.toolName) {
        "list_files" -> StepView("Listing", a.optString("path", "."))
        "read_file" -> {
            val range = if (a.has("start_line")) " :${a.optInt("start_line")}–${a.optInt("end_line", 0)}" else ""
            StepView("Reading", a.optString("path") + range)
        }
        "search_code" -> StepView("Searching", "“${a.optString("query")}”")
        "edit_file" -> StepView("Editing", a.optString("path"), step.diffSummary)
        "insert_after_line" -> StepView("Inserting", "${a.optString("path")} :${a.optInt("line")}", step.diffSummary)
        "replace_line_range" -> StepView("Replacing", "${a.optString("path")} :${a.optInt("start_line")}–${a.optInt("end_line")}", step.diffSummary)
        "write_file" -> StepView("Writing", a.optString("path"), step.diffSummary)
        "delete_file" -> StepView("Deleting", a.optString("path"))
        "move_file" -> StepView("Moving", a.optString("from_path") + " → " + a.optString("to_path"))
        "run_command" -> StepView("Running", step.commandText ?: a.optString("command"))
        "git_clone" -> StepView("Cloning", a.optString("url"))
        "install_repo_tool" -> StepView("Installing tool", a.optString("url"))
        "plan" -> StepView("Plan", "Step-by-step checklist")
        "ask_user" -> StepView("Asking", a.optString("question"))
        "update_memory" -> StepView("Memory", "AGENT.md")
        "finish" -> StepView("Done", "")
        else -> StepView(step.toolName, "")
    }
}

private fun firstLines(text: String, maxLines: Int): String {
    val lines = text.lines()
    val shown = lines.take(maxLines).joinToString("\n") { it.take(160) }
    return if (lines.size > maxLines) shown + "\n… (+${lines.size - maxLines} more lines)" else shown
}

@Composable
fun AgentActivityTimeline(
    toolSteps: List<ToolStepEntity>,
    modifier: Modifier = Modifier
) {
    if (toolSteps.isEmpty()) return
    val visible = toolSteps.filter { it.toolName != "finish" }
    if (visible.isEmpty()) return

    var collapsed by remember { mutableStateOf(false) }
    val running = visible.any { it.status == "RUNNING" }
    val failed = visible.count { it.isError }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.codeBg)
            .border(1.dp, AppColors.border, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = (if (running) "Working · " else "Activity · ") + "${visible.size} step" + (if (visible.size == 1) "" else "s") +
                    (if (failed > 0) " · $failed failed" else ""),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.textSecondary
            )
            Icon(
                imageVector = if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = AppColors.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        if (!collapsed) {
            for (step in visible) {
                StepLine(step)
            }
        }
    }
}

@Composable
fun ToolLines(steps: List<ToolStepEntity>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.codeBg)
            .border(1.dp, AppColors.border, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp)
    ) {
        for (step in steps) {
            StepLine(step)
        }
    }
}

@Composable
private fun StepLine(step: ToolStepEntity) {
    var open by remember { mutableStateOf(false) }
    var showExactCommand by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val view = describe(step)
    val running = step.status == "RUNNING"
    val isCommandStep = step.toolName in setOf("run_command", "git_clone", "install_repo_tool") || !step.commandText.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                when {
                    running -> CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                        color = AppColors.accent
                    )
                    step.isError -> Icon(Icons.Default.Close, contentDescription = "Failed", tint = AppColors.error, modifier = Modifier.size(13.dp))
                    else -> Icon(Icons.Default.Check, contentDescription = "Done", tint = AppColors.ok, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = view.verb,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = if (step.isError) AppColors.error else AppColors.accent
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = view.target,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!view.badge.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(shape = RoundedCornerShape(6.dp), color = AppColors.surfaceAlt) {
                    Text(
                        text = view.badge,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.ok,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (isCommandStep) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AppColors.surfaceAlt,
                    modifier = Modifier.clickable {
                        showExactCommand = !showExactCommand
                        if (showExactCommand) open = true
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(11.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (showExactCommand) "Hide cmd" else "Show command",
                            fontSize = 10.sp,
                            color = AppColors.accent
                        )
                    }
                }
            }
            if (!running && step.durationMs > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (step.durationMs >= 1000) "${step.durationMs / 100 / 10.0}s" else "${step.durationMs}ms",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textMuted
                )
            }
        }

        if (showExactCommand && isCommandStep) {
            val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
            val cmdStr = step.commandText ?: a.optString("command").ifBlank { a.optString("url") }
            val cwdStr = step.cwdText ?: a.optString("cwd", ".")
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .padding(start = 22.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.surface)
                    .border(1.dp, AppColors.border, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "cwd: $cwdStr",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { clipboard.setText(AnnotatedString(cmdStr)) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy command", tint = AppColors.textSecondary, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Copy", fontSize = 10.sp, color = AppColors.textSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$ $cmdStr",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textPrimary
                )
            }
        }

        if (open) {
            Column(modifier = Modifier.padding(start = 22.dp, top = 4.dp, bottom = 4.dp)) {
                if (step.toolName == "edit_file") {
                    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
                    DiffSnippet(a.optString("old_str"), a.optString("new_str"))
                } else if (step.toolName == "write_file") {
                    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
                    val content = a.optString("content")
                    Text(
                        text = "${content.lines().size} lines written",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textSecondary
                    )
                }
                if (step.resultText.isNotBlank() && step.toolName != "edit_file") {
                    Text(
                        text = firstLines(step.resultText, 12),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (step.isError) AppColors.error else AppColors.textSecondary
                    )
                } else if (step.isError && step.resultText.isNotBlank()) {
                    Text(
                        text = firstLines(step.resultText, 8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffSnippet(oldText: String, newText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.surface)
    ) {
        val oldLines = oldText.lines().take(8)
        val newLines = newText.lines().take(8)
        for (l in oldLines) {
            Text(
                text = "- " + l.take(140),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.diffRemoveText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.diffRemoveBg)
                    .padding(horizontal = 6.dp)
            )
        }
        for (l in newLines) {
            Text(
                text = "+ " + l.take(140),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.diffAddText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.diffAddBg)
                    .padding(horizontal = 6.dp)
            )
        }
        if (oldText.lines().size > 8 || newText.lines().size > 8) {
            Text(
                text = "…",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.textMuted,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
    }
}
