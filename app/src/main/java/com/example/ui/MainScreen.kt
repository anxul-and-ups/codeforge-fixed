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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.CodeForgeApp
import com.example.ui.navigation.Screen
import com.example.ui.screens.analytics.AnalyticsScreen
import com.example.ui.screens.builds.BuildLogScreen
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.screens.diff.DiffReviewScreen
import com.example.ui.screens.files.FilesScreen
import com.example.ui.screens.github.GitHubScreen
import com.example.ui.screens.onboarding.OnboardingScreen
import com.example.ui.screens.projects.ProjectsHomeScreen
import com.example.ui.screens.providers.ProvidersScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.theme.AppColors

@Composable
fun MainScreen(
    app: CodeForgeApp,
    modifier: Modifier = Modifier
) {
    val chatViewModel: ChatViewModel = viewModel()
    val activeProject by chatViewModel.activeProject.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }

    BackHandler(enabled = currentScreen != Screen.Chat) {
        currentScreen = when (currentScreen) {
            Screen.Files, Screen.Changes -> Screen.Projects
            Screen.Analytics, Screen.GitHub, Screen.Providers, Screen.Builds, Screen.Onboarding -> Screen.Settings
            else -> Screen.Chat
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
            title = { Text(approval.title, fontWeight = FontWeight.SemiBold) },
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

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .statusBarsPadding(),
        bottomBar = {
            if (!imeOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.bg)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AppColors.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AppColors.border, RoundedCornerShape(999.dp))
                            .testTag("bottom_nav_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (screen in Screen.bottomNavItems) {
                                val isSelected = currentScreen == screen ||
                                    (screen == Screen.Projects && (currentScreen == Screen.Files || currentScreen == Screen.Changes)) ||
                                    (screen == Screen.Settings && currentScreen in setOf(Screen.Analytics, Screen.GitHub, Screen.Providers, Screen.Builds, Screen.Onboarding))
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (isSelected) AppColors.accentSoft else Color.Transparent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            providersStartAdd = false
                                            providersStartEditId = null
                                            returnToChat = false
                                            currentScreen = screen
                                        }
                                        .testTag("nav_item_${screen.route}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = screen.title,
                                            tint = if (isSelected) AppColors.accent else AppColors.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = screen.title,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) AppColors.accent else AppColors.textMuted
                                        )
                                    }
                                }
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
                    val dur = if (app.settingsStore.reduceMotion) 0 else 220
                    (fadeIn(animationSpec = tween(durationMillis = dur)) +
                        slideInVertically(animationSpec = tween(durationMillis = dur)) { it / 32 }) togetherWith
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
                                currentScreen = Screen.Files
                            },
                            onOpenChanges = { proj ->
                                chatViewModel.selectProject(proj)
                                currentScreen = Screen.Changes
                            },
                            onOpenGitHub = { currentScreen = Screen.GitHub },
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
                            onOpenBuilds = { currentScreen = Screen.Builds },
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
                            onFinish = { currentScreen = Screen.Chat },
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
                            onOpenBuilds = { currentScreen = Screen.Builds },
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
