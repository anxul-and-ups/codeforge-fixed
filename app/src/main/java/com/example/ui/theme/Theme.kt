package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun CodeForgeTheme(content: @Composable () -> Unit) {
    val dark = AppColors.isDark
    val c = AppColors

    val colorScheme = if (dark) {
        darkColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = c.accentSoft,
            onPrimaryContainer = c.textPrimary,
            secondary = c.accent,
            onSecondary = c.onAccent,
            secondaryContainer = c.accentSoft,
            onSecondaryContainer = c.accent,
            tertiary = c.reasoning,
            background = c.bg,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceAlt,
            onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.bg,
            surfaceContainerLow = c.surface,
            surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surfaceAlt,
            outline = c.border,
            outlineVariant = c.border,
            error = c.error,
            onError = c.onAccent
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            onPrimary = c.onAccent,
            primaryContainer = c.accentSoft,
            onPrimaryContainer = c.textPrimary,
            secondary = c.accent,
            onSecondary = c.onAccent,
            secondaryContainer = c.accentSoft,
            onSecondaryContainer = c.accent,
            tertiary = c.reasoning,
            background = c.bg,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceAlt,
            onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.bg,
            surfaceContainerLow = c.surface,
            surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface,
            surfaceContainerHighest = c.surfaceAlt,
            outline = c.border,
            outlineVariant = c.border,
            error = c.error,
            onError = c.onAccent
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    CodeForgeTheme(content = content)
}
