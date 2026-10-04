package com.example.ui

import androidx.activity.compose.BackHandler
import com.example.ui.theme.AppColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalDensity
import com.example.ui.screens.builds.BuildLogScreen
import com.example.ui.screens.github.GitHubScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.CodeForgeApp
import com.example.ui.navigation.Screen
import com.example.ui.screens.analytics.AnalyticsScreen
import com.example.ui.screens.chat.ChatScreen
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.screens.diff.DiffReviewScreen
import com.example.ui.screens.files.FilesScreen
import com.example.ui.screens.providers.ProvidersScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.theme.CyberCyan

@Composable
fun MainScreen(
    app: CodeForgeApp,
    modifier: Modifier = Modifier
) {
    val chatViewModel: ChatViewModel = viewModel()
    val activeProject by chatViewModel.activeProject.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }

    // Handle back button on sub-screens
    BackHandler(enabled = currentScreen != Screen.Chat) {
        currentScreen = if (currentScreen == Screen.Analytics || currentScreen == Screen.GitHub) Screen.Settings else Screen.Chat
    }

    // "Add provider" / "Set up key" from the chat model picker: go to Models, then come back to the chat
    var providersStartAdd by remember { mutableStateOf(false) }
    var providersStartEditId by remember { mutableStateOf<String?>(null) }
    var returnToChat by remember { mutableStateOf(false) }

    // Hide the bottom bar while typing: it caused a big empty gap between keyboard and input box
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Permission requests from the agent (safe mode, "fix this build error?") on ANY screen
    val approvalState by chatViewModel.approval.collectAsState()
    val approval = approvalState
    if (approval != null) {
        AlertDialog(
            onDismissRequest = { chatViewModel.resolveApproval(false) },
            title = { Text(approval.title) },
            text = { Text(approval.summary, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { chatViewModel.resolveApproval(true) }) { Text(approval.allowLabel) }
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
            if (!imeOpen) NavigationBar(
                containerColor = AppColors.surface,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("bottom_nav_bar")
            ) {
                for (screen in Screen.bottomNavItems) {
                    val isSelected = currentScreen == screen ||
                        (screen == Screen.Settings && (currentScreen == Screen.Analytics || currentScreen == Screen.GitHub))
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            providersStartAdd = false
                            providersStartEditId = null
                            returnToChat = false
                            currentScreen = screen
                        },
                        alwaysShowLabel = false,
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title
                            )
                        },
                        label = {
                            Text(
                                text = screen.title,
                                fontSize = 10.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyberCyan,
                            selectedTextColor = CyberCyan,
                            unselectedIconColor = AppColors.textMuted,
                            unselectedTextColor = AppColors.textMuted,
                            indicatorColor = AppColors.surfaceAlt
                        ),
                        modifier = Modifier.testTag("nav_item_${screen.route}")
                    )
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
            when (currentScreen) {
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
