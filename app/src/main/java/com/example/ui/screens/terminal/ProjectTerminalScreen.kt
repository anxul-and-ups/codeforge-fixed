package com.example.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CodeForgeApp
import com.example.ui.components.ProjectWorkspaceTopBar
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors

/**
 * Dedicated Project Terminal screen strictly matching the reference dark obsidian Terminal UI:
 * - Clean project header
 * - Project Terminal title row with warm coral-tinted terminal badge & clear trash button
 * - Bordered dark terminal box with monospace welcome banner, emerald prompt, and live Termux output
 * - Quick key & shortcut pill row (`↑`, `↓`, `←`, `→`, `ALT`, `ESC`, plus shell shortcuts) + command bar
 */
@Composable
fun ProjectTerminalScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenChatHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val engineHealth by chatViewModel.engineHealth.collectAsState()
    val terminalOutput by chatViewModel.terminalOutput.collectAsState()
    val providers by chatViewModel.providers.collectAsState()
    val preferredId by chatViewModel.preferredProviderId.collectAsState()

    val activeProject = uiState.activeProject
    val slug = activeProject?.slug?.ifBlank { activeProject.name.lowercase().replace(" ", "-") } ?: "workspace"
    val projectName = activeProject?.name ?: "CodeForge"
    val activeProvider = providers.firstOrNull { it.id == preferredId } ?: providers.firstOrNull()
    val providerLabel = activeProvider?.name ?: "Provider"
    val convLabel = uiState.activeConversation?.title?.ifBlank { "New chat" } ?: "New chat"

    var commandText by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyCursor by remember { mutableStateOf(-1) }
    val outputScroll = rememberScrollState()

    LaunchedEffect(terminalOutput.length) {
        outputScroll.animateScrollTo(outputScroll.maxValue)
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
                onClick = { chatViewModel.clearTerminal() },
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColors.border)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Main bordered dark terminal viewport
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
