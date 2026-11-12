package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Projects : Screen("projects", "Projects", Icons.Default.Workspaces)
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    // Sub-screens reachable from Projects, Chat, or Settings
    object Files : Screen("files", "Files", Icons.Default.Folder)
    object Changes : Screen("changes", "Changes", Icons.Default.CompareArrows)
    object Builds : Screen("builds", "Builds", Icons.Default.BugReport)
    object Providers : Screen("providers", "Providers", Icons.Default.Dns)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.Analytics)
    object GitHub : Screen("github", "GitHub", Icons.Default.Settings)
    object Onboarding : Screen("onboarding", "Setup", Icons.Default.Settings)

    companion object {
        val bottomNavItems: List<Screen>
            get() = listOf(Projects, Chat, Settings)
    }
}
