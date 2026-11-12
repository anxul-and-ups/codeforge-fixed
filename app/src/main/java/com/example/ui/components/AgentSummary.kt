package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ToolStepEntity
import com.example.ui.theme.AppColors
import org.json.JSONArray
import org.json.JSONObject

fun describeStepSentence(step: ToolStepEntity): String {
    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
    fun name(p: String) = p.substringAfterLast('/').ifBlank { p }
    return when (step.toolName) {
        "list_files" -> "Checking files"
        "read_file" -> "Checking files (${name(a.optString("path"))})"
        "search_code" -> "Searching code"
        "edit_file", "insert_after_line", "replace_line_range", "write_file" -> "Writing code"
        "delete_file", "move_file" -> "Updating files"
        "run_command" -> "Working"
        "building", "github_build" -> "Building on GitHub"
        "git_clone" -> "Cloning repository"
        "install_repo_tool" -> "Installing tool"
        "finish" -> "Wrapping up"
        else -> "Working"
    }
}

private fun shortToolBadge(toolName: String): String = when (toolName) {
    "run_command" -> "shell"
    "read_file" -> "read"
    "edit_file", "insert_after_line", "replace_line_range" -> "edit"
    "write_file" -> "write"
    "list_files" -> "list"
    "search_code" -> "search"
    "delete_file" -> "delete"
    "move_file" -> "move"
    "git_clone" -> "git"
    "building", "github_build" -> "building"
    else -> toolName
}

private fun toolHeaderSummary(step: ToolStepEntity): String {
    val a = try { JSONObject(step.argumentsJson) } catch (_: Exception) { JSONObject() }
    return when (step.toolName) {
        "run_command" -> (step.commandText ?: a.optString("command")).trim().ifBlank { "sh" }
        "building", "github_build" -> {
            val repo = a.optString("repo").ifBlank { step.commandText.orEmpty() }
            if (repo.isNotBlank()) repo else "GitHub Actions workflow"
        }
        "read_file", "edit_file", "write_file", "insert_after_line", "replace_line_range", "delete_file" -> {
            val p = (step.filePath ?: a.optString("path")).trim()
            val diff = step.diffSummary?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "$p$diff"
        }
        "list_files" -> a.optString("path", ".")
        "search_code" -> a.optString("query")
        "git_clone", "install_repo_tool" -> a.optString("url")
        else -> step.commandText ?: step.filePath ?: ""
    }
}

private fun formatInputJson(step: ToolStepEntity): String {
    return try {
        val obj = JSONObject(step.argumentsJson)
        if (step.toolName == "building") {
            val clean = JSONObject()
            clean.put("repo", obj.optString("repo"))
            clean.put("workflow", obj.optString("workflow", ".github/workflows/build.yml"))
            val files = obj.optJSONArray("changed_files")
            if (files != null && files.length() > 0) {
                clean.put("changed_files", files)
            }
            clean.toString(2)
        } else {
            obj.toString(2)
        }
    } catch (_: Exception) {
        step.argumentsJson.ifBlank { "{}" }
    }
}

private fun formatOutputText(step: ToolStepEntity): String {
    val raw = step.resultText.trim()
    if (raw.isBlank()) {
        return if (step.status == "RUNNING") "Running…" else "(no output)"
    }
    // Strip redundant "Exit code: ...\nSTDOUT:\n" wrapper if present so output looks clean like the video/screenshot
    val lines = raw.lines()
    if (lines.firstOrNull()?.startsWith("Exit code:") == true && lines.size > 1) {
        val body = lines.drop(1)
            .filter { it != "STDOUT:" && it != "STDERR:" }
            .joinToString("\n")
            .trim()
        if (body.isNotEmpty()) return body
    }
    return raw
}

/**
 * Renders the list of interactive, expandable tool action cards directly inside the chat stream
 * (matching the screenshot & screen recording: `✕ shell ls -R ⌄` -> expands to `input` and `output`,
 * and `building` -> expands to each step-by-step GitHub + Termux build sub-process).
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (step in visibleSteps) {
            ExpandableActionStepCard(step = step)
        }
    }
}

@Composable
private fun ExpandableActionStepCard(step: ToolStepEntity) {
    var expanded by remember(step.id) {
        mutableStateOf(step.toolName == "building")
    }
    val isRunning = step.status == "RUNNING"
    val isError = step.isError || step.status == "FAILED"
    val badge = remember(step.toolName) { shortToolBadge(step.toolName) }
    val summary = remember(step.argumentsJson, step.commandText, step.filePath, step.diffSummary) {
        toolHeaderSummary(step)
    }

    val cardBg = if (AppColors.isDark) Color(0xFF13171F) else AppColors.surface
    val innerBlockBg = if (AppColors.isDark) Color(0xFF0A0D12) else AppColors.codeBg
    val toolBlue = if (AppColors.isDark) Color(0xFF4C8DFF) else Color(0xFF1967D2)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, AppColors.border, RoundedCornerShape(12.dp))
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
                        tint = AppColors.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Succeeded",
                        tint = AppColors.ok,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = badge,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = toolBlue
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = summary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(6.dp))

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = AppColors.textSecondary,
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
                // If this is a `building` card, show each small build process sub-step first!
                if (step.toolName == "building") {
                    val subSteps = remember(step.argumentsJson) {
                        val list = mutableListOf<JSONObject>()
                        try {
                            val arr = JSONObject(step.argumentsJson).optJSONArray("sub_steps") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                arr.optJSONObject(i)?.let { list.add(it) }
                            }
                        } catch (_: Exception) {}
                        list
                    }
                    if (subSteps.isNotEmpty()) {
                        Text(
                            text = "build workflow steps",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.textSecondary
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(innerBlockBg)
                                .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (sub in subSteps) {
                                val st = sub.optString("state", "DONE")
                                val label = sub.optString("label", "")
                                val detail = sub.optString("detail", "")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when (st) {
                                        "RUNNING" -> CircularProgressIndicator(
                                            modifier = Modifier.size(13.dp),
                                            strokeWidth = 1.8.dp,
                                            color = toolBlue
                                        )
                                        "ERROR" -> Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            tint = AppColors.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        else -> Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = AppColors.ok,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppColors.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (detail.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = AppColors.surfaceAlt
                                        ) {
                                            Text(
                                                text = detail.take(28),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = AppColors.textSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // `input` section (just like screenshot)
                Text(
                    text = "input",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textSecondary
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(innerBlockBg)
                        .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = formatInputJson(step),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textPrimary,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }

                // `output` section (just like screenshot)
                Text(
                    text = "output",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textSecondary
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(innerBlockBg)
                        .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = formatOutputText(step),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isError) AppColors.error else AppColors.textPrimary,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
