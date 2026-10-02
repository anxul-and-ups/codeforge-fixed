package com.example.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.MessageEntity
import com.example.ui.components.AgentActivityTimeline
import com.example.ui.components.MarkdownContentView
import com.example.ui.components.ThinkingBlockView
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ReasoningPurple
import com.example.ui.theme.RoseError

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val toolStepsMap by viewModel.toolStepsMap.collectAsState()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val visibleMessages = messages.filter { m ->
        !(m.status == "STREAMING" && m.content.isBlank() && toolStepsMap[m.id].isNullOrEmpty())
    }
    val context = LocalContext.current

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment ?: "image.jpg"
            viewModel.addAttachment(uri, name, isImage = true)
        }
    }

    // Document file launcher
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
            viewModel.addAttachment(uri, name, isImage = false)
        }
    }

    // Auto-scroll on new message
    LaunchedEffect(messages.size, uiState.streamingContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Secret file warning dialog
    if (uiState.warningSecretFile != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSecretWarning() },
            icon = { Icon(Icons.Default.Warning, contentDescription = "Security Alert", tint = ForgeAmber) },
            title = { Text("Sensitive File Warning") },
            text = {
                Text(
                    "The file \"${uiState.warningSecretFile}\" may contain sensitive credentials, keystores, or API tokens. Are you sure you want to attach it to the AI prompt?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSecretWarning() }) {
                    Text("Proceed Anyway", color = RoseError)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val item = uiState.pendingAttachments.find { it.name == uiState.warningSecretFile }
                    if (item != null) viewModel.removeAttachment(item)
                    viewModel.dismissSecretWarning()
                }) {
                    Text("Exclude File")
                }
            }
        )
    }

    // Safe mode: confirm file changes
    val approval = uiState.approval
    if (approval != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveApproval(false) },
            icon = { Icon(Icons.Default.Warning, contentDescription = "Confirm", tint = ForgeAmber) },
            title = { Text("Allow this change?") },
            text = { Text(approval.summary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveApproval(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveApproval(false) }) { Text("Deny", color = RoseError) }
            }
        )
    }

    if (showHistory) {
        HistoryDialog(viewModel = viewModel, onDismiss = { showHistory = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
            .imePadding()
    ) {
        // Top status chip bar
        Surface(
            color = Color(0xFF111827),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Project",
                        tint = CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.activeProject?.name ?: "No Project",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF1F5F9)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showHistory = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Chat history",
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.newConversation() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New chat",
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Auto-Failover Active",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = EmeraldSuccess
                        )
                    }
                }
            }
        }

        // Failover notification banner
        AnimatedVisibility(visible = uiState.failoverNotice != null) {
            Surface(
                color = ForgeAmber.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, ForgeAmber.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Failover",
                            tint = ForgeAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.failoverNotice ?: "",
                            fontSize = 12.sp,
                            color = Color(0xFFFDE68A)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.dismissFailoverNotice() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = ForgeAmber,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Empty state
            if (messages.isEmpty() && !uiState.isAgentRunning) {
                item {
                    ChatEmptyStateView(
                        onPromptClick = { prompt ->
                            inputText = prompt
                        }
                    )
                }
            }

            items(visibleMessages, key = { it.id }) { message ->
                val steps = toolStepsMap[message.id] ?: emptyList()
                MessageBubble(
                    message = message,
                    toolSteps = steps,
                    onEditClick = {
                        inputText = message.content
                    }
                )
            }

            // In-flight streaming message
            if (uiState.isAgentRunning) {
                item {
                    StreamingAgentBubble(
                        status = uiState.currentAgentStatus,
                        content = uiState.streamingContent,
                        reasoning = uiState.streamingReasoning
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // Quick prompts row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val chips = listOf(
                "Find & fix compilation bugs",
                "Add unit tests",
                "Explain project structure",
                "Optimize performance",
                "Add Dark Theme toggle"
            )
            for (chip in chips) {
                AssistChip(
                    onClick = { inputText = chip },
                    label = { Text(chip, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xFF161F30),
                        labelColor = Color(0xFF94A3B8)
                    ),
                    border = AssistChipDefaults.assistChipBorder(true)
                )
            }
        }

        // Pending attachments preview
        if (uiState.pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (att in uiState.pendingAttachments) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (att.isImage) Icons.Default.Image else Icons.Default.AttachFile,
                                contentDescription = "Attachment",
                                tint = CyberCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = att.name.take(16),
                                fontSize = 11.sp,
                                color = Color(0xFFE2E8F0)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { viewModel.removeAttachment(att) }
                            )
                        }
                    }
                }
            }
        }

        // Input bottom bar
        Surface(
            color = Color(0xFF111827),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Attach image
                IconButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("attach_image_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Attach Image",
                        tint = Color(0xFF94A3B8)
                    )
                }

                // Attach file
                IconButton(
                    onClick = {
                        docPickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("attach_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach File",
                        tint = Color(0xFF94A3B8)
                    )
                }

                // Text field
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            "Ask CodeForge to fix bugs, edit files, or add features…",
                            fontSize = 13.sp,
                            color = Color(0xFF64748B)
                        )
                    },
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0F172A),
                        focusedTextColor = Color(0xFFF1F5F9),
                        unfocusedTextColor = Color(0xFFF1F5F9)
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .testTag("chat_input_field")
                )

                // Stop / Send action
                if (uiState.isAgentRunning) {
                    IconButton(
                        onClick = { viewModel.stopAgent() },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(RoseError)
                            .testTag("stop_agent_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Agent",
                            tint = Color.White
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = inputText
                            inputText = ""
                            viewModel.sendMessage(text)
                        },
                        enabled = inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty(),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) CyberCyan else Color(0xFF1E293B))
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) Color(0xFF003549) else Color(0xFF64748B)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageEntity,
    toolSteps: List<com.example.data.local.entity.ToolStepEntity>,
    onEditClick: () -> Unit
) {
    val isUser = message.sender == "USER"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // User message bubble
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A5F)),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .testTag("user_message_bubble")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "You",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF93C5FD)
                        )
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit message",
                            tint = Color(0xFF93C5FD),
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onEditClick() }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.content,
                        fontSize = 14.sp,
                        color = Color(0xFFF1F5F9),
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            // Assistant message bubble
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2937)),
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .testTag("assistant_message_bubble")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header with avatar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(CyberCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "CodeForge",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CodeForge Agent",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                        }

                        if (message.status == "ERROR") {
                            Text(
                                text = "Failed",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RoseError
                            )
                        }
                    }

                    // Collapsible thinking block if available
                    if (!message.reasoning.isNullOrBlank()) {
                        ThinkingBlockView(reasoning = message.reasoning)
                    }

                    // Live collapsible tool steps
                    if (toolSteps.isNotEmpty()) {
                        AgentActivityTimeline(toolSteps = toolSteps)
                    }

                    // Main response markdown content
                    MarkdownContentView(
                        content = message.content,
                        textColor = Color(0xFFE2E8F0)
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingAgentBubble(
    status: String,
    content: String,
    reasoning: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .testTag("streaming_agent_bubble")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = status.ifEmpty { "Agent thinking…" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyberCyan
                    )
                }
            }

            if (reasoning.isNotBlank()) {
                ThinkingBlockView(reasoning = reasoning)
            }

            if (content.isNotBlank()) {
                MarkdownContentView(
                    content = content,
                    textColor = Color(0xFFE2E8F0)
                )
            }
        }
    }
}

@Composable
fun ChatEmptyStateView(
    onPromptClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFF161F30)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "CodeForge",
                tint = CyberCyan,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Welcome to CodeForge",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF1F5F9)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Your autonomous AI coding engineer on Android. Select or import a project ZIP, then ask to inspect code, edit files, or debug issues.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Try asking:",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFCBD5E1)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val suggestions = listOf(
            "Read MainActivity.kt and explain what it does",
            "Search the project for compose UI bugs",
            "Create a new utility file for network requests",
            "Review dependencies in build.gradle.kts"
        )

        for (s in suggestions) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF131D2E),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onPromptClick(s) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = s,
                        fontSize = 12.sp,
                        color = Color(0xFFBAE6FD)
                    )
                }
            }
        }
    }
}
