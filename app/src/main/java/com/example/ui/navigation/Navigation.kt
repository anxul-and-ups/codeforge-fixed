package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    // Home mode tabs (Projects | Agent | Settings)
    object Projects : Screen("projects", "Projects", Icons.Default.Folder)
    object Chat : Screen("chat", "Chat", Icons.Default.AutoAwesome)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    // Project workspace tabs (Chat | Files | Terminal | Preview | GH Build)
    object Codebase : Screen("codebase", "Files", Icons.Default.Folder)
    object Terminal : Screen("terminal", "Terminal", Icons.Default.Terminal)
    object Preview : Screen("preview", "Preview", Icons.Default.Preview)
    object GhBuild : Screen("gh_build", "GH Build", Icons.Default.Build)

    // Sub-screens reachable from Projects, Chat, or Settings
    object Files : Screen("files", "Files", Icons.Default.Folder)
    object Changes : Screen("changes", "Changes", Icons.Default.CompareArrows)
    object Builds : Screen("builds", "Builds", Icons.Default.BugReport)
    object Providers : Screen("providers", "Providers", Icons.Default.Dns)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.Analytics)
    object GitHub : Screen("github", "GitHub", Icons.Default.Settings)
    object Onboarding : Screen("onboarding", "Setup", Icons.Default.Settings)

    companion object {
        /** Home navigation when on outer Projects / Agent / Settings view. */
        val bottomNavItems: List<Screen>
            get() = listOf(Projects, Chat, Settings)

        /** Distraction-Free Project Workspace bottom navigation. */
        val projectBottomNavItems: List<Screen>
            get() = listOf(Chat, Codebase, Terminal, Preview, GhBuild)

        fun homeLabelFor(screen: Screen): String = when (screen) {
            Chat -> "Agent"
            else -> screen.title
        }

        fun homeIconFor(screen: Screen): ImageVector = when (screen) {
            Chat -> Icons.Default.SmartToy
            else -> screen.icon
        }
    }
}
