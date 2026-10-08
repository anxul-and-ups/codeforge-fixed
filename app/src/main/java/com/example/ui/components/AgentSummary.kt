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

private enum class Cat { EXPLORE, CHANGE, CHECK }

private class Phase(val cat: Cat) {
    var title: String? = null
    val sentences = ArrayList<String>()
    val tools = ArrayList<ToolStepEntity>()
}

private fun noteText(step: ToolStepEntity): String =
    try { JSONObject(step.argumentsJson).optString("text") } catch (e: Exception) { "" }

private fun noteTitle(step: ToolStepEntity): String? =
    try { JSONObject(step.argumentsJson).optString("title").ifBlank { null } } catch (e: Exception) { null }

private fun stepPath(step: ToolStepEntity): String {
    val a = try { JSONObject(step.argumentsJson) } catch (e: Exception) { JSONObject() }
    return a.optString("path").ifBlank { a.optString("from_path") }
}

private fun shortName(path: String): String = path.substringAfterLast('/').ifBlank { path }

private fun listNames(names: List<String>): String {
    val distinct = names.filter { it.isNotBlank() }.distinct()
    return when {
        distinct.isEmpty() -> ""
        distinct.size == 1 -> distinct[0]
        distinct.size == 2 -> distinct[0] + " and " + distinct[1]
        distinct.size == 3 -> distinct[0] + ", " + distinct[1] + " and " + distinct[2]
        else -> distinct[0] + ", " + distinct[1] + " and " + (distinct.size - 2) + " more"
    }
}

private fun autoTitle(phase: Phase): String = when (phase.cat) {
    Cat.EXPLORE -> "Explore the codebase"
    Cat.CHANGE -> {
        val names = listNames(phase.tools.map { shortName(stepPath(it)) })
        if (names.isEmpty()) "Apply the changes" else "Update $names"
    }
    Cat.CHECK -> "Verify the result"
}

private fun autoSentence(phase: Phase): String {
    val tools = phase.tools
    return when (phase.cat) {
        Cat.EXPLORE -> {
            val reads = tools.filter { it.toolName == "read_file" }.map { shortName(stepPath(it)) }
            val searches = tools.count { it.toolName == "search_code" }
            val lists = tools.count { it.toolName == "list_files" }
            val parts = ArrayList<String>()
            if (lists > 0) parts.add("looked through the project files")
            if (searches > 0) parts.add("searched the code" + if (searches > 1) " $searches times" else "")
            if (reads.isNotEmpty()) parts.add("read " + listNames(reads))
            if (parts.isEmpty()) "Looked around the project." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        }
        Cat.CHANGE -> {
            val created = tools.filter { it.toolName == "write_file" }.map { shortName(stepPath(it)) }
            val edited = tools.filter { it.toolName == "edit_file" }.map { shortName(stepPath(it)) }
            val removed = tools.filter { it.toolName == "delete_file" }.map { shortName(stepPath(it)) }
            val parts = ArrayList<String>()
            if (edited.isNotEmpty()) parts.add("edited " + listNames(edited))
            if (created.isNotEmpty()) parts.add("wrote " + listNames(created))
            if (removed.isNotEmpty()) parts.add("deleted " + listNames(removed))
            if (parts.isEmpty()) "Updated the project." else parts.joinToString(" and ").replaceFirstChar { it.uppercase() } + "."
        }
        Cat.CHECK -> "Re-read " + listNames(tools.map { shortName(stepPath(it)) }).ifBlank { "the changes" } + " to make sure everything is correct."
    }
}

/** Groups the raw steps into titled phases (model notes are used when present, otherwise generated). */
private fun buildPhases(steps: List<ToolStepEntity>): List<Phase> {
    val phases = ArrayList<Phase>()
    var cur: Phase? = null
    var pending: String? = null
    var pendingTitle: String? = null
    var seenChange = false
    for (st in steps) {
        if (st.toolName == "finish") continue
        if (st.toolName == "note") {
            val t = noteText(st)
            if (t.isNotBlank()) pending = if (pending == null) t else pending + " " + t
            val ti = noteTitle(st)
            if (ti != null) pendingTitle = ti
            continue
        }
        val isChange = st.toolName == "edit_file" || st.toolName == "write_file" ||
            st.toolName == "delete_file" || st.toolName == "move_file"
        val cat = when {
            isChange -> Cat.CHANGE
            seenChange -> Cat.CHECK
            else -> Cat.EXPLORE
        }
        if (isChange) seenChange = true
        val existing = cur
        val phase = if (existing == null || existing.cat != cat || pendingTitle != null) {
            val np = Phase(cat)
            phases.add(np)
            cur = np
            np
        } else {
            existing
        }
        if (pendingTitle != null) {
            phase.title = pendingTitle
            pendingTitle = null
        }
        val p = pending
        if (p != null) {
            phase.sentences.add(p)
            pending = null
        }
        phase.tools.add(st)
    }
    val leftover = pending
    if (leftover != null) {
        val last = cur
        if (last != null) {
            last.sentences.add(leftover)
        } else {
            val np = Phase(Cat.EXPLORE)
            np.title = pendingTitle
            np.sentences.add(leftover)
            phases.add(np)
        }
    }
    for (ph in phases) {
        if (ph.title == null) ph.title = autoTitle(ph)
        if (ph.sentences.isEmpty()) ph.sentences.add(autoSentence(ph))
    }
    return phases
}

/** Title for the whole task. */
private fun overallTitle(steps: List<ToolStepEntity>, phases: List<Phase>): String {
    val modelTitle = steps.firstNotNullOfOrNull { if (it.toolName == "note") noteTitle(it) else null }
    if (modelTitle != null) return modelTitle
    val changed = steps.filter { it.toolName == "edit_file" || it.toolName == "write_file" }.map { shortName(stepPath(it)) }
    if (changed.isNotEmpty()) return "Update " + listNames(changed)
    return phases.firstOrNull()?.title ?: "Working on your request"
}

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
    liveStatus: String = "",
    fallbackTitle: String,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty() && !running) return
    var open by remember { mutableStateOf(false) }

    val sorted = remember(steps) { steps.sortedBy { it.stepIndex } }
    val notes = remember(sorted) { sorted.filter { it.toolName == "note" } }
    val tools = remember(sorted) { sorted.filter { it.toolName != "note" && it.toolName != "finish" } }
    val title = remember(sorted) { overallTitle(sorted, buildPhases(sorted)) }
    val latest: String = when {
        running && liveStatus.isNotBlank() -> liveStatus
        running -> {
            val lastTool = tools.lastOrNull()
            val lastNote = notes.lastOrNull { noteText(it).isNotBlank() }
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
    val phases = remember(steps) { buildPhases(steps) }

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
                .padding(horizontal = 20.dp)
        ) {
            items(phases.size) { index ->
                val ph = phases[index]
                val isLast = index == phases.size - 1
                Column {

                // Phase title with the ">_" badge
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
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
                        Box(modifier = Modifier.width(1.dp).weight(1f).background(AppColors.border))
                    }
                    Text(
                        text = if (index == 0) title else (ph.title ?: ""),
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 14.dp, top = 3.dp)
                    )
                }

                // Sentences (dots) and the terminal lines of this phase
                for ((si, sentence) in ph.sentences.withIndex()) {
                    val lastSentence = si == ph.sentences.size - 1
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Column(
                            modifier = Modifier.width(44.dp).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 9.dp)
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.border)
                            )
                            if (!(isLast && lastSentence && !running)) {
                                Box(modifier = Modifier.width(1.dp).weight(1f).background(AppColors.border))
                            }
                        }
                        Column(modifier = Modifier.padding(start = 6.dp, bottom = 16.dp)) {
                            Text(
                                text = sentence,
                                fontSize = 16.sp,
                                lineHeight = 23.sp,
                                color = AppColors.textSecondary
                            )
                            if (lastSentence && ph.tools.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ToolLines(ph.tools)
                            }
                        }
                    }
                }
                }
            }

            item {
                if (running) {
                    Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Spacer(modifier = Modifier.width(14.dp))
                        Text("Working…", fontSize = 15.sp, color = AppColors.textSecondary)
                    }
                } else {
                    Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AppColors.ok, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Text("Done", fontSize = 15.sp, color = AppColors.textSecondary)
                    }
                }
            }
        }
    }
}
