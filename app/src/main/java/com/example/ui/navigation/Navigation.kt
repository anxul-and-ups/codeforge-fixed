package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    object Files : Screen("files", "Files", Icons.Default.Folder)
    object Changes : Screen("changes", "Changes", Icons.Default.CompareArrows)
    object Builds : Screen("builds", "Builds", Icons.Default.BugReport)
    object Providers : Screen("providers", "Models", Icons.Default.Dns)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.Analytics)
    object GitHub : Screen("github", "GitHub", Icons.Default.Settings)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)

    companion object {
        // Must be a getter: a stored val is built while the Screen base class is still initialising,
        // which made the objects null (static initialisation cycle) and crashed on startup.
        val bottomNavItems: List<Screen>
            get() = listOf(Chat, Files, Changes, Builds, Providers, Settings)
    }
}
