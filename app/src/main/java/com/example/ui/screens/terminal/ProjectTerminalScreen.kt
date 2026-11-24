package com.example.ui.screens.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CodeForgeApp
import com.example.data.termux.CommandExecState
import com.example.data.termux.TerminalExecutionRecord
import com.example.ui.components.ProjectWorkspaceTopBar
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated Project Terminal screen preserving the reference dark obsidian Terminal UI,
 * upgraded with Live Execution Transparency:
 * - Console view (live streaming shell output + quick key pills)
 * - Executions view (structured command cards showing exact command, working dir, lifecycle state,
 *   PID, start time, duration, exit code, stdout, red-highlighted stderr, copy command/output, and redacted secrets)
 */
@Composable
fun ProjectTerminalScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenChatHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by chatViewModel.uiState.collectAsState()
    val engineHealth by chatViewModel.engineHealth.collectAsState()
    val terminalOutput by chatViewModel.terminalOutput.collectAsState()
    val executions by app.terminalExecutionTracker.executions.collectAsState()
    val providers by chatViewModel.providers.collectAsState()
    val preferredId by chatViewModel.preferredProviderId.collectAsState()

    val activeProject = uiState.activeProject
    val slug = activeProject?.slug?.ifBlank { activeProject.name.lowercase().replace(" ", "-") } ?: "workspace"
    val projectName = activeProject?.name ?: "CodeForge"
    val activeProvider = providers.firstOrNull { it.id == preferredId } ?: providers.firstOrNull()
    val providerLabel = activeProvider?.name ?: "Provider"
    val convLabel = uiState.activeConversation?.title?.ifBlank { "New chat" } ?: "New chat"

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Live Console, 1 = Executions
    var commandText by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyCursor by remember { mutableIntStateOf(-1) }
    val outputScroll = rememberScrollState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(terminalOutput.length) {
        outputScroll.animateScrollTo(outputScroll.maxValue)
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    fun executeCommand() {
        val cmd = commandText.trim()
        if (cmd.isEmpty()) return
        history.add(cmd)
        historyCursor = history.size
        commandText = ""
        chatViewModel.runTerminalCommand(cmd)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .imePadding()
    ) {
        ProjectWorkspaceTopBar(
            title = projectName,
            subtitle = "$providerLabel · $convLabel",
            onBack = onBack,
            onHistoryClick = onOpenChatHistory
        )

        // Sub-header row: [ >_ ] Project Terminal / path · Termux   [ Trash ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AppColors.accentSoft)
                    .border(1.dp, AppColors.accent.copy(alpha = 0.25f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Terminal",
                    tint = AppColors.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Project Terminal",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textPrimary
                )
                Text(
                    text = "~/forge/projects/$slug · ${if (engineHealth.engineRunning) "Termux Bridge" else "Bridge Offline"}",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!engineHealth.engineRunning) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AppColors.accentSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { chatViewModel.repairEngine() }
                ) {
                    Text(
                        text = "Connect",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(
                onClick = {
                    chatViewModel.clearTerminal()
                    app.terminalExecutionTracker.clear()
                },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("terminal_clear_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Clear Terminal",
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(21.dp)
                )
            }
        }

        // Minimal mode switcher: Console | Executions (live transparency feed)
        val runningCount = executions.count {
            it.state == CommandExecState.RUNNING || it.state == CommandExecState.QUEUED || it.state == CommandExecState.WAITING
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalModeChip(
                label = "Console",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 }
            )
            TerminalModeChip(
                label = if (runningCount > 0) "Executions (${executions.size} · $runningCount live)" else "Executions (${executions.size})",
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 }
            )
            Spacer(modifier = Modifier.weight(1f))
            if (terminalOutput.isNotBlank() && selectedTab == 0) {
                Text(
                    text = "Copy log",
                    fontSize = 11.5.sp,
                    color = AppColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { copyToClipboard("terminal output", terminalOutput) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (selectedTab == 0) {
            // Main bordered dark terminal viewport matching reference screenshot
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.codeBg)
                    .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(outputScroll)
                ) {
                    Text(
                        text = "CodeForge Terminal ready.\nType a bash command below or tap a quick command chip.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        lineHeight = 22.sp,
                        color = AppColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "forge@termux:~/$slug#",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.ok
                    )
                    if (terminalOutput.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = terminalOutput,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = AppColors.textPrimary
                        )
                    }
                }
            }
        } else {
            // Live Execution Transparency Cards
            if (executions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppColors.codeBg)
                        .border(1.dp, AppColors.border, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No command executions recorded yet.\nCommands run by you or the AI agent appear here live with status, PID, duration, and logs.",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(executions, key = { it.id }) { rec ->
                        ExecutionRecordCard(
                            rec = rec,
                            timeFmt = timeFmt,
                            onCopyCommand = { copyToClipboard("command", rec.command) },
                            onCopyOutput = {
                                val combined = buildString {
                                    if (rec.stdout.isNotBlank()) appendLine(rec.stdout)
                                    if (rec.stderr.isNotBlank()) appendLine("STDERR:\n${rec.stderr}")
                                }.trim()
                                copyToClipboard("output", combined.ifBlank { "(no output)" })
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Quick-Key Pill Bar matching reference screenshot (`↑`, `↓`, `←`, `→`, `ALT`, `ESC`, plus shell commands)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalKeyPill(label = "↑") {
                if (history.isNotEmpty()) {
                    historyCursor = (historyCursor - 1).coerceIn(0, history.lastIndex)
                    commandText = history[historyCursor]
                }
            }
            TerminalKeyPill(label = "↓") {
                if (history.isNotEmpty()) {
                    historyCursor = (historyCursor + 1).coerceAtMost(history.size)
                    commandText = if (historyCursor < history.size) history[historyCursor] else ""
                }
            }
            IconButton(
                onClick = { commandText = commandText.dropLast(1) },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Backspace",
                    tint = AppColors.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = { executeCommand() },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Run",
                    tint = AppColors.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            TerminalKeyPill(label = "ALT") { commandText += " --" }
            TerminalKeyPill(label = "ESC") { commandText = "" }
            TerminalKeyPill(label = "ls -la") { chatViewModel.runTerminalCommand("ls -la") }
            TerminalKeyPill(label = "git status") { chatViewModel.runTerminalCommand("git status") }
            TerminalKeyPill(label = "pwd") { chatViewModel.runTerminalCommand("pwd") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Compact Bash Command Input Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AppColors.surface)
                .border(1.dp, AppColors.border, RoundedCornerShape(24.dp))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.ok
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (commandText.isEmpty()) {
                    Text(
                        text = "Enter bash command…",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        color = AppColors.textMuted
                    )
                }
                BasicTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.5.sp,
                        color = AppColors.textPrimary
                    ),
                    cursorBrush = SolidColor(AppColors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { executeCommand() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_command_input")
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (commandText.isNotBlank()) AppColors.accent else AppColors.surfaceAlt)
                    .clickable(enabled = commandText.isNotBlank()) { executeCommand() }
                    .testTag("terminal_send_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Execute",
                    tint = if (commandText.isNotBlank()) AppColors.onAccent else AppColors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ExecutionRecordCard(
    rec: TerminalExecutionRecord,
    timeFmt: SimpleDateFormat,
    onCopyCommand: () -> Unit,
    onCopyOutput: () -> Unit
) {
    var expanded by remember(rec.id) { mutableStateOf(rec.state == CommandExecState.RUNNING || rec.state == CommandExecState.FAILED) }
    val (statusColor, statusBg) = when (rec.state) {
        CommandExecState.QUEUED, CommandExecState.WAITING -> AppColors.warn to AppColors.surfaceAlt
        CommandExecState.RUNNING -> AppColors.accent to AppColors.accentSoft
        CommandExecState.COMPLETED -> AppColors.ok to AppColors.diffAddBg
        CommandExecState.FAILED -> AppColors.error to AppColors.diffRemoveBg
        CommandExecState.CANCELLED -> AppColors.textMuted to AppColors.surfaceAlt
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (rec.state == CommandExecState.FAILED) AppColors.error.copy(alpha = 0.45f) else AppColors.border, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(6.dp), color = statusBg) {
                    Text(
                        text = rec.state.label,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$ ${rec.command}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.textPrimary,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle details",
                    tint = AppColors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Metadata row: working dir, start time, duration, PID, exit code
            val meta = buildString {
                append(rec.workingDir.ifBlank { "~" })
                append(" · ")
                append(timeFmt.format(Date(rec.startedAtMs)))
                if (rec.durationMs > 0L) append(" · ${rec.durationMs}ms")
                if (rec.pid != null) append(" · PID ${rec.pid}")
                if (rec.exitCode != null) append(" · exit ${rec.exitCode}")
            }
            Text(
                text = meta,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = AppColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (expanded) {
                if (rec.stdout.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppColors.codeBg,
                        modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.border, RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = rec.stdout.takeLast(4000),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = AppColors.textPrimary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                if (rec.stderr.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppColors.diffRemoveBg,
                        modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = rec.stderr.takeLast(3000),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = AppColors.diffRemoveText,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCopyCommand)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy cmd", fontSize = 11.sp, color = AppColors.textSecondary)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCopyOutput)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy output", fontSize = 11.sp, color = AppColors.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) AppColors.accentSoft else AppColors.surface,
        modifier = Modifier
            .border(1.dp, if (selected) AppColors.accent else AppColors.border, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AppColors.accent else AppColors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun TerminalKeyPill(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = AppColors.bg,
        modifier = Modifier
            .border(1.dp, AppColors.border, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = AppColors.textPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}
