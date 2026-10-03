package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ToolStepEntity
import com.example.ui.theme.AppColors
import org.json.JSONObject

private data class SummaryGroup(val note: String?, val tools: List<ToolStepEntity>)

private fun noteText(step: ToolStepEntity): String =
    try { JSONObject(step.argumentsJson).optString("text") } catch (e: Exception) { "" }

private fun noteTitle(step: ToolStepEntity): String? =
    try { JSONObject(step.argumentsJson).optString("title").ifBlank { null } } catch (e: Exception) { null }

/** English one-sentence description of a tool step (used when the model wrote no note). */
fun describeStepSentence(step: ToolStepEntity): String {
    val a = try { JSONObject(step.argumentsJson) } catch (e: Exception) { JSONObject() }
    fun name(p: String) = p.substringAfterLast('/').ifBlank { p }
    return when (step.toolName) {
        "list_files" -> "Looking at the files in ${a.optString("path", ".")}"
        "read_file" -> "Reading ${name(a.optString("path"))}"
        "search_code" -> "Searching the project for “${a.optString("query").take(40)}”"
        "edit_file" -> "Editing ${name(a.optString("path"))}"
        "write_file" -> "Writing ${name(a.optString("path"))}"
        "delete_file" -> "Deleting ${name(a.optString("path"))}"
        "move_file" -> "Moving ${name(a.optString("from_path"))}"
        "finish" -> "Wrapping up"
        else -> step.toolName
    }
}

/** Collapsed one-line summary shown in the chat (tap to open the full Summary sheet). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSummaryBar(
    steps: List<ToolStepEntity>,
    running: Boolean,
    fallbackTitle: String,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    val sorted = steps.sortedBy { it.stepIndex }
    val notes = sorted.filter { it.toolName == "note" }
    val tools = sorted.filter { it.toolName != "note" && it.toolName != "finish" }
    val title = notes.firstNotNullOfOrNull { noteTitle(it) } ?: fallbackTitle
    val latest: String = when {
        running -> {
            val lastTool = tools.lastOrNull()
            val lastNote = notes.lastOrNull()
            when {
                lastTool != null && (lastNote == null || lastTool.stepIndex > lastNote.stepIndex) -> describeStepSentence(lastTool)
                lastNote != null -> noteText(lastNote)
                else -> "Working…"
            }
        }
        else -> title
    }
    val failed = tools.count { it.isError }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
        } else {
            Icon(Icons.Default.History, contentDescription = null, tint = AppColors.textMuted, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = latest,
            fontSize = 14.sp,
            color = AppColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (!running) {
            Text(
                text = "  ·  ${tools.size} step" + (if (tools.size == 1) "" else "s") + (if (failed > 0) " · $failed failed" else ""),
                fontSize = 12.sp,
                color = AppColors.textMuted,
                maxLines = 1
            )
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Open summary", tint = AppColors.textMuted, modifier = Modifier.size(18.dp))
    }

    if (open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = AppColors.surface,
            contentColor = AppColors.textPrimary
        ) {
            SummarySheetContent(title = title, steps = sorted, running = running, onClose = { open = false })
        }
    }
}

@Composable
private fun SummarySheetContent(
    title: String,
    steps: List<ToolStepEntity>,
    running: Boolean,
    onClose: () -> Unit
) {
    // Group tool lines under the progress sentence that came before them
    val groups = ArrayList<SummaryGroup>()
    var currentNote: String? = null
    var currentTools = ArrayList<ToolStepEntity>()
    var started = false
    for (s in steps) {
        if (s.toolName == "finish") continue
        if (s.toolName == "note") {
            if (started || currentTools.isNotEmpty()) groups.add(SummaryGroup(currentNote, currentTools))
            currentNote = noteText(s)
            currentTools = ArrayList()
            started = true
        } else {
            currentTools.add(s)
        }
    }
    if (started || currentTools.isNotEmpty()) groups.add(SummaryGroup(currentNote, currentTools))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = AppColors.textPrimary)
            }
            Text(
                text = "Summary",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(vertical = 12.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Task title with the ">_" badge
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Column(
                        modifier = Modifier.width(44.dp).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, AppColors.border, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(">_", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AppColors.textPrimary)
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .weight(1f)
                                .background(AppColors.border)
                        )
                    }
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 20.dp)
                    )
                }
            }

            items(groups.size) { index ->
                val g = groups[index]
                val last = index == groups.size - 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    Column(
                        modifier = Modifier.width(44.dp).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AppColors.border)
                        )
                        if (!last || running) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .weight(1f)
                                    .background(AppColors.border)
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 6.dp, bottom = 20.dp)) {
                        if (!g.note.isNullOrBlank()) {
                            Text(
                                text = g.note,
                                fontSize = 17.sp,
                                lineHeight = 24.sp,
                                color = AppColors.textPrimary
                            )
                        }
                        if (g.tools.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            ToolLines(g.tools)
                        }
                    }
                }
            }

            if (running) {
                item {
                    Row(modifier = Modifier.padding(start = 44.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Working…", fontSize = 14.sp, color = AppColors.textSecondary)
                    }
                }
            } else {
                item {
                    Row(modifier = Modifier.padding(start = 12.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AppColors.ok, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Done", fontSize = 15.sp, color = AppColors.textSecondary)
                    }
                }
            }
        }
    }
}
