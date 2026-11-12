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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.agent.AgentMode
import com.example.data.agent.WorkingFileEntry
import com.example.data.agent.WorkingFileState
import com.example.data.local.entity.MessageEntity
import com.example.data.local.entity.ToolStepEntity
import com.example.ui.components.AgentSummaryBar
import com.example.ui.components.MarkdownContentView
import com.example.ui.components.ProviderIcon
import com.example.ui.components.ThinkingBlockView
import com.example.ui.components.ThinkingRow
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onAddProvider: () -> Unit = {},
    onSetupProvider: (String) -> Unit = {},
    onOpenGitHub: () -> Unit = {},
    onOpenDiffReview: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    githubManager: com.example.data.github.GitHubManager? = null,
    githubConnected: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val toolStepsMap by viewModel.toolStepsMap.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val cachedModels by viewModel.cachedModels.collectAsState()
    val preferredId by viewModel.preferredProviderId.collectAsState()
    val workingFiles by viewModel.workingFiles.collectAsState()
    val engineHealth by viewModel.engineHealth.collectAsState()
    val terminalOutput by viewModel.terminalOutput.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var inputText by remember(uiState.activeConversation?.id) {
        mutableStateOf(viewModel.getDraftForCurrentChat())
    }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showRepoPicker by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showWorkingFilesSheet by remember { mutableStateOf(false) }
    var showTerminalSheet by remember { mutableStateOf(false) }
    val projectsList by viewModel.projectsList.collectAsState()
    val isScratch = uiState.activeProject?.id == com.example.data.repository.ProjectRepository.SCRATCH_ID

    val visibleMessages = messages.filter { m ->
        !(m.status == "STREAMING" && m.content.isBlank() && toolStepsMap[m.id].isNullOrEmpty())
    }
    val streamingMessageId = visibleMessages.lastOrNull { it.status == "STREAMING" }?.id

    val activeProvider = providers.firstOrNull { it.id == preferredId && isProviderReady(it) }
        ?: providers.firstOrNull { isProviderReady(it) }
    val providerPrefix = activeProvider?.id?.lowercase()?.takeIf { it.isNotBlank() }
    val rawModelId = activeProvider?.selectedModel?.ifBlank { "pick-model" } ?: "add-provider"
    val fullModelLabel = if (providerPrefix != null && !rawModelId.contains("/")) {
        "$providerPrefix/$rawModelId"
    } else {
        rawModelId
    }
    val shortModelLabel = rawModelId.substringAfterLast('/')

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

    val stepCount = toolStepsMap.values.sumOf { it.size }
    LaunchedEffect(messages.size, stepCount, uiState.streamingContent.length / 40, uiState.isAgentRunning) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
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

    if (showProjectPicker) {
        ProjectPickerDialog(
            projects = projectsList,
            activeProjectId = if (isScratch) null else uiState.activeProject?.id,
            onPick = { p ->
                showProjectPicker = false
                viewModel.attachProjectToChat(p)
            },
            onNone = {
                showProjectPicker = false
                viewModel.detachProjectFromChat()
            },
            onDismiss = { showProjectPicker = false }
        )
    }
    if (showRepoPicker && githubManager != null) {
        com.example.ui.screens.github.RepoPickerDialog(
            manager = githubManager,
            title = "Clone a repository",
            onDismiss = { showRepoPicker = false },
            onPick = { repo ->
                showRepoPicker = false
                viewModel.cloneRepo(repo)
            }
        )
    }
    if (showModelPicker) {
        ModelPickerDialog(
            providers = providers,
            activeProviderId = activeProvider?.id,
            cachedModels = cachedModels,
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
            onToggleFavorite = { pid, mid, fav -> viewModel.toggleFavoriteModel(pid, mid, fav) },
            onAddCustomModelId = { pid, mid -> viewModel.addCustomModelId(pid, mid) },
            onRefreshProviderModels = { pid -> viewModel.refreshProviderModels(pid) },
            onDismiss = { showModelPicker = false }
        )
    }
    if (showAgentPicker) {
        ChooseAgentBottomSheet(
            currentMode = uiState.mode,
            onSelectMode = { mode ->
                viewModel.setMode(mode)
                showAgentPicker = false
            },
            onDismiss = { showAgentPicker = false }
        )
    }
    if (showWorkingFilesSheet) {
        WorkingFilesBottomSheet(
            workingFiles = workingFiles,
            onOpenDiff = {
                showWorkingFilesSheet = false
                onOpenDiffReview()
            },
            onRevertFile = { path -> viewModel.revertSingleFile(path) },
            onRevertAll = { viewModel.revertAllChangesInLastRun() },
            onDismiss = { showWorkingFilesSheet = false }
        )
    }
    if (showTerminalSheet) {
        TerminalDrawerBottomSheet(
            output = terminalOutput,
            engineConnected = engineHealth.engineRunning,
            onRunCommand = { cmd -> viewModel.runTerminalCommand(cmd) },
            onClear = { viewModel.clearTerminal() },
            onRepairEngine = { viewModel.repairEngine() },
            onDismiss = { showTerminalSheet = false }
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
            // ---------------- Top Header (Matches Screenshot & Video) ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.bg)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Conversations",
                            tint = AppColors.textPrimary
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        // Project title row
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showProjectPicker = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isScratch) {
                                    uiState.activeConversation?.title?.takeIf { it != "New Chat" } ?: "CodeForge"
                                } else {
                                    uiState.activeProject?.name ?: "Project"
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "~/forge/projects/${if (isScratch) "scratch" else (uiState.activeProject?.slug ?: "app")}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Model dropdown pill + Agent dropdown pill (`[ provider/model ⌄ ]  [ build ⌄ ]`)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Model Dropdown Pill
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.surface,
                                modifier = Modifier
                                    .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showModelPicker = true }
                                    .testTag("model_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = fullModelLabel,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 195.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Select model",
                                        tint = AppColors.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Agent Mode Dropdown Pill (`build ⌄` / `plan ⌄`)
                            val modeLabel = if (uiState.mode == AgentMode.BUILD) "build" else "plan"
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.surface,
                                modifier = Modifier
                                    .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showAgentPicker = true }
                                    .testTag("agent_mode_pill")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = modeLabel,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppColors.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Choose agent",
                                        tint = AppColors.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Working Files Pill Badge
                            if (workingFiles.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = AppColors.accentSoft,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showWorkingFilesSheet = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Description,
                                            contentDescription = "Working files",
                                            tint = AppColors.accent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "${workingFiles.size}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.accent
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // GitHub Actions Cloud Build trigger button
                    if (!isScratch) {
                        IconButton(
                            onClick = { viewModel.pushAndBuild() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = "Push & Build on GitHub Actions",
                                tint = AppColors.accent,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // Terminal Drawer Button
                    IconButton(
                        onClick = { showTerminalSheet = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = "Open Terminal Drawer",
                            tint = if (engineHealth.engineRunning) AppColors.ok else AppColors.textSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Settings Button (matches top-right gear in screenshot)
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = AppColors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))

            // ---------------- Failover / Status Notice banner ----------------
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
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                if (visibleMessages.isEmpty() && !uiState.isAgentRunning) {
                    item {
                        EmptyChat(
                            projectName = if (isScratch) null else uiState.activeProject?.name,
                            onPrompt = {
                                inputText = it
                                viewModel.saveDraftForCurrentChat(it)
                            }
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
                        fallbackTitle = uiState.activeConversation?.title?.takeIf { it != "New Chat" } ?: "Working on your request",
                        activeModelShortLabel = shortModelLabel,
                        isChatMode = uiState.mode == AgentMode.CHAT,
                        onRetry = { viewModel.retryFailedMessage(message) },
                        onSwitchToBuild = { viewModel.setMode(AgentMode.BUILD) },
                        onOpenWorkingFiles = { showWorkingFilesSheet = true }
                    )
                }

                if ((uiState.isAgentRunning || uiState.isPreparing) && streamingMessageId == null) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CodeForgeAvatarBadge()
                            ThinkingRow(uiState.currentAgentStatus)
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }
            }

            // ---------------- Slash Command Suggestions Popup ----------------
            if (inputText.startsWith("/") && !inputText.contains(" ")) {
                val slashCommands = listOf(
                    "/mode" to "Switch Build / Plan agent",
                    "/model" to "Open live Model Picker",
                    "/build" to "Sync & trigger GitHub Actions cloud build",
                    "/files" to "Open Working Files panel",
                    "/terminal" to "Open Forge Terminal drawer",
                    "/clone " to "Clone a GitHub repository URL"
                ).filter { it.first.startsWith(inputText.lowercase()) }
                if (slashCommands.isNotEmpty()) {
                    Surface(
                        color = AppColors.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            for ((cmd, desc) in slashCommands) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            when (cmd.trim()) {
                                                "/mode" -> {
                                                    showAgentPicker = true
                                                    inputText = ""
                                                }
                                                "/model" -> {
                                                    showModelPicker = true
                                                    inputText = ""
                                                }
                                                "/build" -> {
                                                    viewModel.pushAndBuild()
                                                    inputText = ""
                                                }
                                                "/files" -> {
                                                    showWorkingFilesSheet = true
                                                    inputText = ""
                                                }
                                                "/terminal" -> {
                                                    showTerminalSheet = true
                                                    inputText = ""
                                                }
                                                else -> inputText = cmd
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(cmd.trim(), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                                    Text(desc, fontSize = 12.sp, color = AppColors.textSecondary)
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- Bottom Composer Pill (Matches Video & Screenshot) ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (uiState.pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (att in uiState.pendingAttachments) {
                            AttachmentChip(
                                name = att.name,
                                isImage = att.isImage,
                                onRemove = { viewModel.removeAttachment(att) }
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = AppColors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.border, RoundedCornerShape(28.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            IconButton(
                                onClick = { showAttachMenu = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("attach_button")
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Attach",
                                    tint = AppColors.textPrimary
                                )
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Photo / screenshot") },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("File, PDF, Project ZIP, or APK") },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        filePicker.launch(arrayOf("*/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clone GitHub Repository") },
                                    leadingIcon = {
                                        androidx.compose.foundation.Image(
                                            painter = androidx.compose.ui.res.painterResource(com.example.R.drawable.ic_github),
                                            contentDescription = null,
                                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AppColors.textSecondary),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        showAttachMenu = false
                                        if (githubConnected) showRepoPicker = true else onOpenGitHub()
                                    }
                                )
                            }
                        }

                        TextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                viewModel.saveDraftForCurrentChat(it)
                            },
                            placeholder = {
                                Text(
                                    text = "Type a message...",
                                    fontSize = 15.sp
                                )
                            },
                            maxLines = 5,
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
                                .weight(1f)
                                .testTag("chat_input_field")
                        )

                        val canSend = (inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) && !uiState.isPreparing
                        if (uiState.isAgentRunning) {
                            // Stop Button: Dark circle with Red Rounded Square inside (matches video at 0:13)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E222A))
                                    .border(1.5.dp, Color(0xFF3A3F4B), CircleShape)
                                    .clickable { viewModel.stopAgent() }
                                    .testTag("stop_agent_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFF4D4D))
                                )
                            }
                        } else {
                            // Send Button: Blue circle with paper-plane Send icon (matches screenshot)
                            val sendBg = if (canSend) Color(0xFF1868DB) else Color(0xFF1868DB).copy(alpha = 0.42f)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(sendBg)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                                    .clickable(enabled = canSend) {
                                        val text = inputText
                                        inputText = ""
                                        viewModel.sendMessage(text)
                                    }
                                    .testTag("send_message_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (uiState.isPreparing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Choose agent" Modal Bottom Sheet matching the screen recording (0:02 & 0:10):
 * - Search bar at top
 * - Build: "Full access agent for development work"
 * - Plan: "Read-only agent for exploring the codebase and planning..."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseAgentBottomSheet(
    currentMode: AgentMode,
    onSelectMode: (AgentMode) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.surface,
        contentColor = AppColors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Choose agent",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.textPrimary
            )

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppColors.textMuted) },
                placeholder = { Text("Search", fontSize = 14.sp) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.accent,
                    unfocusedBorderColor = AppColors.border
                )
            )

            val agents = listOf(
                Triple(
                    AgentMode.BUILD,
                    "Build",
                    "Full access agent for development work, editing files, running Termux shell commands, and triggering GitHub Actions builds."
                ),
                Triple(
                    AgentMode.CHAT,
                    "Plan",
                    "Read-only agent for exploring the codebase, inspecting files, and planning architecture before making changes."
                )
            ).filter {
                search.isBlank() ||
                    it.second.contains(search, ignoreCase = true) ||
                    it.third.contains(search, ignoreCase = true)
            }

            for ((mode, title, desc) in agents) {
                val isSelected = currentMode == mode
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) AppColors.surfaceAlt else Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) AppColors.accent else AppColors.border,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectMode(mode) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = desc,
                                fontSize = 13.sp,
                                color = AppColors.textSecondary,
                                lineHeight = 18.sp
                            )
                        }
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = AppColors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CodeForgeAvatarBadge() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xFF1868DB).copy(alpha = 0.16f))
            .border(1.dp, Color(0xFF1868DB).copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "</>",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF388BFD)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkingFilesBottomSheet(
    workingFiles: List<WorkingFileEntry>,
    onOpenDiff: () -> Unit,
    onRevertFile: (String) -> Unit,
    onRevertAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.surface,
        contentColor = AppColors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Working Files", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                    Text("Files read, edited, created, or deleted in this session", fontSize = 12.sp, color = AppColors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenDiff) {
                        Text("Full Diff", color = AppColors.accent, fontSize = 12.sp)
                    }
                    if (workingFiles.any { it.state != WorkingFileState.READING }) {
                        TextButton(onClick = onRevertAll) {
                            Text("Revert Run", color = AppColors.error, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(workingFiles, key = { it.path }) { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val badgeColor = when (item.state) {
                                WorkingFileState.READING -> AppColors.info
                                WorkingFileState.EDITED -> AppColors.warn
                                WorkingFileState.CREATED -> AppColors.ok
                                WorkingFileState.DELETED -> AppColors.error
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.16f)) {
                                Text(
                                    text = item.state.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.path,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!item.diffSummary.isNullOrBlank()) {
                                    Text(item.diffSummary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.ok)
                                }
                            }
                            if (item.state != WorkingFileState.READING) {
                                IconButton(onClick = { onRevertFile(item.path) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Restore, contentDescription = "Revert file", tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalDrawerBottomSheet(
    output: String,
    engineConnected: Boolean,
    onRunCommand: (String) -> Unit,
    onClear: () -> Unit,
    onRepairEngine: () -> Unit,
    onDismiss: () -> Unit
) {
    var cmdInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val app = context.applicationContext as com.example.CodeForgeApp
    val bridgeStatus by app.termuxBridge.healthState.collectAsState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.surface,
        contentColor = AppColors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Forge Terminal", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (engineConnected) AppColors.ok.copy(alpha = 0.16f) else AppColors.warn.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (engineConnected) "Termux Connected" else "Local Shell",
                            fontSize = 10.sp,
                            color = if (engineConnected) AppColors.ok else AppColors.warn,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                TextButton(onClick = onClear) {
                    Text("Clear", fontSize = 11.sp, color = AppColors.textMuted)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Clean "Termux Connection" option row (opens dialog when tapped)
            com.example.ui.components.TermuxQuickConnectPanel(
                bridge = app.termuxBridge,
                status = bridgeStatus,
                onAutoConnectClick = onRepairEngine,
                onRefreshHealthClick = {
                    scope.launch { app.termuxBridge.refreshHealth() }
                },
                compactDefault = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.codeBg)
                    .border(1.dp, AppColors.border, RoundedCornerShape(12.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = output.ifBlank { "$ Ready in ~/forge/projects workspace. Type a shell command below…" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("e.g. git status, ls -la, python3 --version", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val c = cmdInput.trim()
                        if (c.isNotEmpty()) {
                            onRunCommand(c)
                            cmdInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AppColors.accent)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run command", tint = AppColors.onAccent)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
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
private fun ChatMessage(
    message: MessageEntity,
    toolSteps: List<ToolStepEntity>,
    live: Boolean,
    liveStatus: String,
    liveContent: String,
    liveReasoning: String,
    fallbackTitle: String,
    activeModelShortLabel: String,
    isChatMode: Boolean,
    onRetry: () -> Unit,
    onSwitchToBuild: () -> Unit,
    onOpenWorkingFiles: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    if (message.sender == "USER") {
        val lines = message.content.lines()
        val attachNames = lines.filter { it.startsWith("📎 ") }.map { it.removePrefix("📎 ").trim() }
        val text = lines.filter { !it.startsWith("📎 ") }.joinToString("\n").trim()
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
                // Blue user message bubble matching screenshot (#1868DB)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1868DB),
                    modifier = Modifier
                        .widthIn(max = 330.dp)
                        .testTag("user_message_bubble")
                ) {
                    Text(
                        text = text,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            val sendingNow = message.status == "SENDING" && System.currentTimeMillis() - message.timestamp < 120_000L
            val sendFailed = message.status == "ERROR" || (message.status == "SENDING" && !sendingNow)
            if (sendingNow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = AppColors.textMuted)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sending…", fontSize = 11.sp, color = AppColors.textMuted)
                }
            } else if (sendFailed) {
                Text(
                    text = "Not sent · tap to retry",
                    fontSize = 11.sp,
                    color = AppColors.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onRetry() }
                        .padding(2.dp)
                )
            }
        }
        return
    }

    val isInterrupted = !live && (
        message.content.contains("Interrupted", ignoreCase = true) ||
            message.content.contains("Stopped by user", ignoreCase = true)
        )
    val cleanAssistantContent = message.content
        .replace("*(Stopped by user)*", "")
        .replace("(Stopped by user)", "")
        .trim()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("assistant_message_bubble"),
        verticalAlignment = Alignment.Top
    ) {
        CodeForgeAvatarBadge()
        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (toolSteps.isNotEmpty()) {
                AgentSummaryBar(
                    steps = toolSteps,
                    running = live,
                    fallbackTitle = fallbackTitle
                )
            }

            val reasoning = if (live && liveReasoning.isNotBlank()) liveReasoning else message.reasoning
            if (!reasoning.isNullOrBlank()) {
                ThinkingBlockView(reasoning = reasoning)
            }

            val text = if (live) (if (liveContent.length > 120) liveContent else "") else cleanAssistantContent
            if (text.isNotBlank()) {
                MarkdownContentView(content = text, textColor = AppColors.textPrimary)
            }

            if (live) {
                ThinkingRow(liveStatus)
            }

            // Red `[ ⚠ Interrupted ]` pill badge when stopped (matches video at 0:15)
            if (isInterrupted) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AppColors.error.copy(alpha = 0.14f),
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = AppColors.error.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(999.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Interrupted",
                            tint = AppColors.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Interrupted",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.error
                        )
                    }
                }
            }

            if (!live && isChatMode && message.content.contains("switch me to a different agent", ignoreCase = true) ||
                (!live && isChatMode && message.content.contains("build mode", ignoreCase = true))
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.accentSoft,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSwitchToBuild() }
                ) {
                    Text(
                        text = "Switch to Build agent",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.accent,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            if (!live) {
                val changed = remember(toolSteps) {
                    val out = LinkedHashSet<String>()
                    for (st in toolSteps) {
                        if (st.toolName in setOf("edit_file", "write_file", "insert_after_line", "replace_line_range", "delete_file")) {
                            val path = st.filePath ?: try { org.json.JSONObject(st.argumentsJson).optString("path") } catch (_: Exception) { "" }
                            if (path.isNotBlank() && !st.isError) out.add(path.substringAfterLast('/'))
                        }
                    }
                    out.toList()
                }
                if (changed.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .clickable { onOpenWorkingFiles() },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = AppColors.ok, modifier = Modifier.size(14.dp))
                        Text("Changed", fontSize = 12.sp, color = AppColors.textSecondary)
                        for (name in changed.take(8)) {
                            Surface(shape = RoundedCornerShape(8.dp), color = AppColors.surfaceAlt) {
                                Text(
                                    text = name,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (changed.size > 8) Text("+${changed.size - 8}", fontSize = 11.sp, color = AppColors.textMuted)
                    }
                }
            }

            if (!live) {
                val model = message.modelUsed?.substringAfterLast('/')?.ifBlank { activeModelShortLabel } ?: activeModelShortLabel
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model,
                        fontSize = 12.sp,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (cleanAssistantContent.isNotBlank()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Copy",
                            fontSize = 12.sp,
                            color = AppColors.textMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { clipboard.setText(AnnotatedString(cleanAssistantContent)) }
                                .padding(vertical = 2.dp, horizontal = 2.dp)
                        )
                    }
                    if (message.status == "ERROR" && !isInterrupted) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("failed", fontSize = 11.sp, color = AppColors.error)
                    }
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
            .padding(top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "What should we build or fix?",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (projectName != null) {
                "Active project: $projectName (~/forge/projects)"
            } else {
                "Pick a project above, attach a ZIP/APK with +, or clone a GitHub repository"
            },
            fontSize = 13.sp,
            color = AppColors.textMuted
        )
        Spacer(modifier = Modifier.height(22.dp))
        val prompts = listOf(
            "is code me kya kya hai",
            "Find and fix bugs in this project and trigger GitHub Actions build",
            "Inspect all project files and explain the architecture",
            "Add a new feature and push changes to GitHub"
        )
        for (p in prompts) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
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

@Composable
private fun ProjectPickerDialog(
    projects: List<com.example.data.local.entity.ProjectEntity>,
    activeProjectId: String?,
    onPick: (com.example.data.local.entity.ProjectEntity) -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = AppColors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Project for this chat",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.textPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Text(
                    "Each project is synced inside ~/forge/projects/<slug> and connected to GitHub Actions.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNone() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "No project (clean chat)",
                        fontSize = 14.sp,
                        color = if (activeProjectId == null) AppColors.accent else AppColors.textPrimary,
                        fontWeight = if (activeProjectId == null) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (activeProjectId == null) Icon(Icons.Default.Check, null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(projects, key = { it.id }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(p) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    p.name,
                                    fontSize = 14.sp,
                                    color = if (p.id == activeProjectId) AppColors.accent else AppColors.textPrimary,
                                    fontWeight = if (p.id == activeProjectId) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "~/forge/projects/${p.slug.ifBlank { p.name.lowercase() }} · ${p.fileCount} files",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textMuted
                                )
                            }
                            if (p.id == activeProjectId) Icon(Icons.Default.Check, null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
