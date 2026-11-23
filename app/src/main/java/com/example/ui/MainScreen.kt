package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.CodeForgeApp
import com.example.data.repository.ProjectRepository
import com.example.ui.navigation.Screen
import com.example.ui.screens.analytics.AnalyticsScreen
import com.example.ui.screens.builds.BuildLogScreen
import com.example.ui.screens.builds.GhBuildScreen
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.screens.codebase.CodebaseScreen
import com.example.ui.screens.diff.DiffReviewScreen
import com.example.ui.screens.files.FilesScreen
import com.example.ui.screens.github.GitHubScreen
import com.example.ui.screens.onboarding.OnboardingScreen
import com.example.ui.screens.preview.ProjectPreviewScreen
import com.example.ui.screens.projects.ProjectsHomeScreen
import com.example.ui.screens.providers.ProvidersScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.terminal.ProjectTerminalScreen
import com.example.ui.theme.AppColors

@Composable
fun MainScreen(
    app: CodeForgeApp,
    modifier: Modifier = Modifier
) {
    val chatViewModel: ChatViewModel = viewModel()
    val activeProject by chatViewModel.activeProject.collectAsState()
    val providers by chatViewModel.providers.collectAsState()
    val hasConfiguredProvider = remember(providers) { providers.any { it.isEnabled && it.selectedModel.isNotBlank() } }

    val isInProject = activeProject != null && activeProject?.id != ProjectRepository.SCRATCH_ID
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Projects) }

    BackHandler(enabled = currentScreen != Screen.Projects) {
        currentScreen = when (currentScreen) {
            Screen.Codebase, Screen.Terminal, Screen.Preview, Screen.GhBuild ->
                if (isInProject) Screen.Chat else Screen.Projects
            Screen.Chat -> {
                if (isInProject) {
                    chatViewModel.detachProjectFromChat()
                }
                Screen.Projects
            }
            Screen.Files, Screen.Changes -> if (isInProject) Screen.Codebase else Screen.Projects
            Screen.Analytics, Screen.GitHub, Screen.Providers, Screen.Builds, Screen.Onboarding ->
                if (isInProject) Screen.Chat else Screen.Settings
            else -> Screen.Projects
        }
    }

    var providersStartAdd by remember { mutableStateOf(false) }
    var providersStartEditId by remember { mutableStateOf<String?>(null) }
    var returnToChat by remember { mutableStateOf(false) }

    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Permission, Plan Card, ask_user, and "Save this source to a project?" modal
    val approvalState by chatViewModel.approval.collectAsState()
    val approval = approvalState
    if (approval != null) {
        var customReply by remember(approval.id) { mutableStateOf("") }
        val isAskUser = approval.toolName == "ask_user"
        AlertDialog(
            onDismissRequest = { chatViewModel.resolveApproval(false) },
            containerColor = AppColors.surface,
            title = { Text(approval.title, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(approval.summary, fontSize = 14.sp, color = AppColors.textPrimary)
                    if (approval.options.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        for (opt in approval.options) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.surfaceAlt,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { chatViewModel.resolveApprovalChoice(opt) }
                            ) {
                                Text(
                                    text = opt,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AppColors.accent,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                    if (isAskUser) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = customReply,
                            onValueChange = { customReply = it },
                            placeholder = { Text("Or type exact path / custom reply…", fontSize = 12.sp) },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("ask_user_custom_input")
                        )
                    }
                }
            },
            confirmButton = {
                when {
                    isAskUser && customReply.isNotBlank() -> {
                        TextButton(onClick = { chatViewModel.resolveApprovalChoice(customReply.trim()) }) {
                            Text("Send Reply", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    approval.options.isEmpty() -> {
                        TextButton(onClick = { chatViewModel.resolveApproval(true) }) {
                            Text(approval.allowLabel, color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { chatViewModel.resolveApproval(false) }) {
                    Text(approval.denyLabel, color = AppColors.error)
                }
            }
        )
    }

    // Distraction-Free Project Workspace bottom nav: [Chat | Files | Terminal | Preview | GH Build]
    // Outer Home mode bottom nav: [Projects | Agent | Settings]
    val showProjectNav = isInProject && currentScreen != Screen.Projects && currentScreen != Screen.Settings &&
        currentScreen != Screen.Providers && currentScreen != Screen.GitHub && currentScreen != Screen.Analytics
    val navItems = if (showProjectNav) Screen.projectBottomNavItems else Screen.bottomNavItems

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .statusBarsPadding(),
        containerColor = AppColors.bg,
        bottomBar = {
            if (!imeOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.surface)
                        .navigationBarsPadding()
                        .testTag("bottom_nav_bar")
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AppColors.border)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (screen in navItems) {
                            val isSelected = currentScreen == screen ||
                                (screen == Screen.Projects && (currentScreen == Screen.Files || currentScreen == Screen.Changes) && !showProjectNav) ||
                                (screen == Screen.Codebase && (currentScreen == Screen.Files || currentScreen == Screen.Changes) && showProjectNav) ||
                                (screen == Screen.GhBuild && currentScreen == Screen.Builds) ||
                                (screen == Screen.Settings && currentScreen in setOf(Screen.Analytics, Screen.GitHub, Screen.Providers, Screen.Builds, Screen.Onboarding))
                            val itemLabel = if (!showProjectNav) Screen.homeLabelFor(screen) else screen.title
                            val itemIcon = if (!showProjectNav) Screen.homeIconFor(screen) else screen.icon

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        providersStartAdd = false
                                        providersStartEditId = null
                                        returnToChat = false
                                        currentScreen = screen
                                    }
                                    .padding(vertical = 2.dp)
                                    .testTag("nav_item_${screen.route}")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(if (showProjectNav) 52.dp else 60.dp)
                                        .height(30.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(if (isSelected) AppColors.accentSoft else Color.Transparent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box {
                                        Icon(
                                            imageVector = itemIcon,
                                            contentDescription = itemLabel,
                                            tint = if (isSelected) AppColors.accent else AppColors.textSecondary,
                                            modifier = Modifier.size(21.dp)
                                        )
                                        if (screen == Screen.Settings && !hasConfiguredProvider && !showProjectNav) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 3.dp, y = (-2).dp)
                                                    .clip(CircleShape)
                                                    .background(AppColors.error)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = itemLabel,
                                    fontSize = if (showProjectNav) 10.5.sp else 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) AppColors.accent else AppColors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(AppColors.bg)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val dur = if (app.settingsStore.reduceMotion) 0 else 180
                    (fadeIn(animationSpec = tween(durationMillis = dur)) +
                        slideInVertically(animationSpec = tween(durationMillis = dur)) { it / 40 }) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = dur / 2))
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    Screen.Projects -> {
                        ProjectsHomeScreen(
                            app = app,
                            chatViewModel = chatViewModel,
                            onOpenChat = { currentScreen = Screen.Chat },
                            onOpenFiles = { proj ->
                                chatViewModel.selectProject(proj)
                                currentScreen = Screen.Codebase
                            },
                            onOpenChanges = { proj ->
                                chatViewModel.selectProject(proj)
                                currentScreen = Screen.Changes
                            },
                            onOpenGitHub = { currentScreen = Screen.GitHub },
                            onOpenTerminal = { currentScreen = Screen.Terminal },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Chat -> {
                        ChatScreen(
                            viewModel = chatViewModel,
                            modifier = Modifier.fillMaxSize(),
                            onAddProvider = {
                                providersStartAdd = true
                                providersStartEditId = null
                                returnToChat = true
                                currentScreen = Screen.Providers
                            },
                            onOpenGitHub = { currentScreen = Screen.GitHub },
                            onOpenDiffReview = { currentScreen = Screen.Changes },
                            onOpenSettings = { currentScreen = Screen.Settings },
                            onOpenCodebase = { currentScreen = Screen.Codebase },
                            onOpenGhBuild = { currentScreen = Screen.GhBuild },
                            onExitProjectToHome = {
                                chatViewModel.detachProjectFromChat()
                                currentScreen = Screen.Projects
                            },
                            githubManager = app.githubManager,
                            githubConnected = app.settingsStore.githubConnected,
                            onSetupProvider = { id ->
                                providersStartAdd = false
                                providersStartEditId = id
                                returnToChat = true
                                currentScreen = Screen.Providers
                            }
                        )
                    }
                    Screen.Codebase -> {
                        CodebaseScreen(
                            app = app,
                            chatViewModel = chatViewModel,
                            onOpenGhBuild = { currentScreen = Screen.GhBuild },
                            onSendToAgent = { prompt ->
                                currentScreen = Screen.Chat
                                chatViewModel.sendMessage(prompt)
                            },
                            onOpenGitHubSettings = { currentScreen = Screen.GitHub },
                            onBack = {
                                chatViewModel.detachProjectFromChat()
                                currentScreen = Screen.Projects
                            },
                            onOpenPreview = { currentScreen = Screen.Preview },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Terminal -> {
                        ProjectTerminalScreen(
                            app = app,
                            chatViewModel = chatViewModel,
                            onBack = {
                                if (isInProject) {
                                    chatViewModel.detachProjectFromChat()
                                }
                                currentScreen = Screen.Projects
                            },
                            onOpenChatHistory = { currentScreen = Screen.Chat },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Preview -> {
                        ProjectPreviewScreen(
                            app = app,
                            chatViewModel = chatViewModel,
                            onBack = {
                                chatViewModel.detachProjectFromChat()
                                currentScreen = Screen.Projects
                            },
                            onOpenChatHistory = { currentScreen = Screen.Chat },
                            onSendErrorsToAgent = { prompt ->
                                currentScreen = Screen.Chat
                                chatViewModel.sendMessage(prompt)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.GhBuild -> {
                        GhBuildScreen(
                            app = app,
                            chatViewModel = chatViewModel,
                            onSendErrorToAgent = { errPrompt ->
                                currentScreen = Screen.Chat
                                chatViewModel.sendMessage(errPrompt)
                            },
                            onOpenGitHubSettings = { currentScreen = Screen.GitHub },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Files -> {
                        FilesScreen(
                            projectRepository = app.projectRepository,
                            activeProject = activeProject,
                            onSelectProject = { proj ->
                                chatViewModel.selectProject(proj)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Changes -> {
                        DiffReviewScreen(
                            projectRepository = app.projectRepository,
                            activeProject = activeProject,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.GitHub -> {
                        GitHubScreen(
                            manager = app.githubManager,
                            settings = app.settingsStore,
                            chatViewModel = chatViewModel,
                            projectRepository = app.projectRepository,
                            onOpenBuilds = { currentScreen = Screen.GhBuild },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Builds -> {
                        BuildLogScreen(
                            store = app.buildLogStore,
                            manager = app.githubManager,
                            onSendToAi = { prompt ->
                                currentScreen = Screen.Chat
                                chatViewModel.sendMessage(prompt)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Providers -> {
                        ProvidersScreen(
                            providerRepository = app.providerRepository,
                            startWithAdd = providersStartAdd,
                            startEditId = providersStartEditId,
                            onFinished = {
                                providersStartAdd = false
                                providersStartEditId = null
                                if (returnToChat) {
                                    returnToChat = false
                                    currentScreen = Screen.Chat
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Analytics -> {
                        AnalyticsScreen(
                            usageDao = app.database.usageDao(),
                            settingsStore = app.settingsStore,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Onboarding -> {
                        OnboardingScreen(
                            app = app,
                            onOpenProviders = { currentScreen = Screen.Providers },
                            onFinish = { currentScreen = Screen.Projects },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Screen.Settings -> {
                        SettingsScreen(
                            gitHubRepository = app.gitHubRepository,
                            settingsStore = app.settingsStore,
                            onFeedBuildErrorToChat = { buildPrompt ->
                                currentScreen = Screen.Chat
                                chatViewModel.sendMessage(buildPrompt)
                            },
                            onOpenUsage = { currentScreen = Screen.Analytics },
                            onOpenGitHub = { currentScreen = Screen.GitHub },
                            onOpenProviders = { currentScreen = Screen.Providers },
                            onOpenBuilds = { currentScreen = Screen.GhBuild },
                            onReopenOnboarding = { currentScreen = Screen.Onboarding },
                            onPushAndBuild = {
                                currentScreen = Screen.Chat
                                chatViewModel.pushAndBuild()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
