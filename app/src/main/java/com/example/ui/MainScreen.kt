package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
    val chatUiState by chatViewModel.uiState.collectAsState()

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }

    // Handle back button on sub-screens
    BackHandler(enabled = currentScreen != Screen.Chat) {
        currentScreen = Screen.Chat
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF111827),
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("bottom_nav_bar")
            ) {
                for (screen in Screen.bottomNavItems) {
                    val isSelected = currentScreen == screen
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentScreen = screen },
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
                            unselectedIconColor = Color(0xFF64748B),
                            unselectedTextColor = Color(0xFF64748B),
                            indicatorColor = Color(0xFF1E293B)
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
                .background(Color(0xFF0B0F17))
        ) {
            when (currentScreen) {
                Screen.Chat -> {
                    ChatScreen(
                        viewModel = chatViewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Screen.Files -> {
                    FilesScreen(
                        projectRepository = app.projectRepository,
                        activeProject = chatUiState.activeProject,
                        onSelectProject = { proj ->
                            chatViewModel.selectProject(proj)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Screen.Changes -> {
                    DiffReviewScreen(
                        projectRepository = app.projectRepository,
                        activeProject = chatUiState.activeProject,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Screen.Providers -> {
                    ProvidersScreen(
                        providerRepository = app.providerRepository,
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
