package com.example.ui.screens.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.ui.components.AgentSummaryBar
import com.example.ui.components.MarkdownContentView
import com.example.ui.components.ProviderIcon
import com.example.ui.components.ThinkingBlockView
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onAddProvider: () -> Unit = {},
    onSetupProvider: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val toolStepsMap by viewModel.toolStepsMap.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val preferredId by viewModel.preferredProviderId.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var inputText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }

    val visibleMessages = messages.filter { m ->
        !(m.status == "STREAMING" && m.content.isBlank() && toolStepsMap[m.id].isNullOrEmpty())
    }
    val streamingMessageId = visibleMessages.lastOrNull { it.status == "STREAMING" }?.id

    val activeProvider = providers.firstOrNull { it.id == preferredId && isProviderReady(it) }
        ?: providers.firstOrNull { isProviderReady(it) }
    val modelLabel = activeProvider?.selectedModel ?: "Add a provider"
    val modelReady = activeProvider != null

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.addAttachment(uri, uri.lastPathSegment ?: "image.jpg", isImage = true)
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addAttachment(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "file", isImage = false)
    }

    // Keep the newest content in view
    val stepCount = toolStepsMap.values.sumOf { it.size }
    LaunchedEffect(messages.size, stepCount, uiState.streamingContent.length / 40, uiState.isAgentRunning) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    if (uiState.warningSecretFile != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSecretWarning() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = AppColors.warn) },
            title = { Text("Sensitive file") },
            text = {
                Text(
                    "\"${uiState.warningSecretFile}\" looks like it contains secrets (keys or passwords). " +
                        "Its content will be sent to the AI provider. Remove it if you do not want that.",
                    fontSize = 14.sp
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissSecretWarning() }) { Text("Keep") } },
            dismissButton = {
                TextButton(onClick = {
                    uiState.pendingAttachments.lastOrNull()?.let { viewModel.removeAttachment(it) }
                    viewModel.dismissSecretWarning()
                }) { Text("Remove", color = AppColors.error) }
            }
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            providers = providers,
            activeProviderId = activeProvider?.id,
            onSelect = { pid, model ->
                viewModel.selectModel(pid, model)
                showModelPicker = false
            },
            onSetupProvider = { id ->
                showModelPicker = false
                onSetupProvider(id)
            },
            onAddProvider = {
                showModelPicker = false
                onAddProvider()
            },
            onDismiss = { showModelPicker = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(310.dp),
                drawerContainerColor = AppColors.surface
            ) {
                ChatDrawerContent(
                    viewModel = viewModel,
                    onClose = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.bg)
                .imePadding()
        ) {
            // ---------------- Header ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.bg)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Default.Menu, contentDescription = "Chats", tint = AppColors.textPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.activeConversation?.title?.takeIf { it != "New Chat" } ?: "New chat",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.activeProject?.name ?: "No project",
                        fontSize = 12.sp,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { viewModel.newConversation() }) {
                    Icon(Icons.Default.Add, contentDescription = "New chat", tint = AppColors.textPrimary)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))

            // ---------------- Notice banner ----------------
            val notice = uiState.failoverNotice
            if (notice != null) {
                Surface(color = AppColors.accentSoft, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = notice,
                            fontSize = 12.sp,
                            color = AppColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.dismissFailoverNotice() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ---------------- Messages ----------------
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                if (visibleMessages.isEmpty() && !uiState.isAgentRunning) {
                    item {
                        EmptyChat(
                            projectName = uiState.activeProject?.name,
                            onPrompt = { inputText = it }
                        )
                    }
                }

                items(visibleMessages, key = { it.id }) { message ->
                    val live = uiState.isAgentRunning && message.id == streamingMessageId
                    ChatMessage(
                        message = message,
                        toolSteps = toolStepsMap[message.id] ?: emptyList(),
                        live = live,
                        liveStatus = if (live) uiState.currentAgentStatus else "",
                        liveContent = if (live) uiState.streamingContent else "",
                        liveReasoning = if (live) uiState.streamingReasoning else "",
                        fallbackTitle = uiState.activeConversation?.title?.takeIf { it != "New Chat" } ?: "Working on your request"
                    )
                }

                if (uiState.isAgentRunning && streamingMessageId == null) {
                    item { StatusLine(uiState.currentAgentStatus.ifBlank { "Starting…" }) }
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }
            }

            // ---------------- Input (attach, text, model, send in one box) ----------------
            Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(AppColors.surface)
                        .border(1.dp, AppColors.border, RoundedCornerShape(24.dp))
                ) {
                    if (uiState.pendingAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (att in uiState.pendingAttachments) {
                                AttachmentChip(name = att.name, isImage = att.isImage, onRemove = { viewModel.removeAttachment(att) })
                            }
                        }
                    }

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Describe the bug or the change…", fontSize = 15.sp) },
                        maxLines = 6,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = AppColors.accent,
                            focusedTextColor = AppColors.textPrimary,
                            unfocusedTextColor = AppColors.textPrimary,
                            focusedPlaceholderColor = AppColors.textMuted,
                            unfocusedPlaceholderColor = AppColors.textMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_input_field")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            IconButton(onClick = { showAttachMenu = true }, modifier = Modifier.testTag("attach_button")) {
                                Icon(Icons.Default.Add, contentDescription = "Attach", tint = AppColors.textSecondary)
                            }
                            DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Photo / screenshot") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("File, PDF or project ZIP") },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        filePicker.launch(arrayOf("*/*"))
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Model selector, right next to the send button
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showModelPicker = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("model_chip"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderIcon(
                                providerId = activeProvider?.id,
                                model = activeProvider?.selectedModel,
                                size = 16.dp,
                                providerName = activeProvider?.name
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = modelLabel,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (modelReady) AppColors.textSecondary else AppColors.warn,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 170.dp)
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Change model",
                                tint = AppColors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val canSend = inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()
                        if (uiState.isAgentRunning) {
                            IconButton(
                                onClick = { viewModel.stopAgent() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.error)
                                    .testTag("stop_agent_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val text = inputText
                                    inputText = ""
                                    viewModel.sendMessage(text)
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) AppColors.accent else AppColors.surfaceAlt)
                                    .testTag("send_message_button")
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Send",
                                    tint = if (canSend) AppColors.onAccent else AppColors.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(name: String, isImage: Boolean, onRemove: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(10.dp), color = AppColors.surfaceAlt) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = if (onRemove != null) 2.dp else 8.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(
                imageVector = if (isImage) Icons.Default.Image else Icons.Default.AttachFile,
                contentDescription = null,
                tint = AppColors.textSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(name.take(26), fontSize = 12.sp, color = AppColors.textPrimary, maxLines = 1)
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = AppColors.textMuted, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.accent)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = AppColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChatMessage(
    message: MessageEntity,
    toolSteps: List<ToolStepEntity>,
    live: Boolean,
    liveStatus: String,
    liveContent: String,
    liveReasoning: String,
    fallbackTitle: String
) {
    val clipboard = LocalClipboardManager.current
    if (message.sender == "USER") {
        val lines = message.content.lines()
        val attachNames = lines.filter { it.startsWith("📎 ") }.map { it.removePrefix("📎 ").trim() }
        val text = lines.filter { !it.startsWith("📎 ") }.joinToString("\n").trim()
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (attachNames.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (n in attachNames) {
                        val isImg = n.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
                        AttachmentChip(name = n, isImage = isImg, onRemove = null)
                    }
                }
            }
            if (text.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = AppColors.surfaceAlt,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .testTag("user_message_bubble")
                ) {
                    Text(
                        text = text,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = AppColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("assistant_message_bubble"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (toolSteps.isNotEmpty()) {
            AgentSummaryBar(steps = toolSteps, running = live, fallbackTitle = fallbackTitle)
        }

        val reasoning = if (live && liveReasoning.isNotBlank()) liveReasoning else message.reasoning
        if (!reasoning.isNullOrBlank()) {
            ThinkingBlockView(reasoning = reasoning)
        }

        // While working, short streamed text is only a progress note (shown in the Summary), so hide it.
        val text = if (live) (if (liveContent.length > 220) liveContent else "") else message.content
        if (text.isNotBlank()) {
            MarkdownContentView(content = text, textColor = AppColors.textPrimary)
        }

        if (live && toolSteps.isEmpty()) {
            StatusLine(liveStatus.ifBlank { "Working…" })
        }

        if (!live) {
            val model = message.modelUsed
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.content.isNotBlank()) {
                    Text(
                        text = "Copy",
                        fontSize = 12.sp,
                        color = AppColors.textMuted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { clipboard.setText(AnnotatedString(message.content)) }
                            .padding(vertical = 2.dp, horizontal = 2.dp)
                    )
                }
                if (!model.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    ProviderIcon(providerId = null, model = model, size = 14.dp)
                    Text(
                        text = "  $model",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (message.status == "ERROR") {
                    Text("   failed", fontSize = 11.sp, color = AppColors.error)
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(projectName: String?, onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "What should we work on?",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (projectName != null) "Project: $projectName" else "Attach a .zip with + to load a project",
            fontSize = 13.sp,
            color = AppColors.textMuted
        )
        Spacer(modifier = Modifier.height(24.dp))
        val prompts = listOf(
            "Find and fix bugs in this project",
            "Explain the project structure",
            "Build a new app: a simple notes app with dark mode",
            "Review the code for performance problems"
        )
        for (p in prompts) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(1.dp, AppColors.border, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPrompt(p) }
            ) {
                Text(
                    text = p,
                    fontSize = 14.sp,
                    color = AppColors.textPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
    }
}
