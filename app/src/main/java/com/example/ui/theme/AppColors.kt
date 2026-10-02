package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Single source of truth for all colours. Plain getters that read Compose state, so they work in
 * any context (composables, modifiers, default parameters) and update automatically when the
 * theme mode changes.
 */
object AppColors {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
    var systemDark by mutableStateOf(true)

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }

    val bg: Color get() = if (isDark) Color(0xFF111214) else Color(0xFFF6F6F7)
    val surface: Color get() = if (isDark) Color(0xFF1A1B1E) else Color(0xFFFFFFFF)
    val surfaceAlt: Color get() = if (isDark) Color(0xFF25262A) else Color(0xFFEDEEF0)
    val border: Color get() = if (isDark) Color(0xFF2E3035) else Color(0xFFE1E2E5)
    val codeBg: Color get() = if (isDark) Color(0xFF0F1012) else Color(0xFFF0F1F3)

    val textPrimary: Color get() = if (isDark) Color(0xFFECECEF) else Color(0xFF1B1C1F)
    val textSecondary: Color get() = if (isDark) Color(0xFFA3A4AB) else Color(0xFF5D5F67)
    val textMuted: Color get() = if (isDark) Color(0xFF74757D) else Color(0xFF8A8C94)

    val accent: Color get() = if (isDark) Color(0xFF8FA8F5) else Color(0xFF3558D6)
    val onAccent: Color get() = if (isDark) Color(0xFF0E1630) else Color(0xFFFFFFFF)
    val accentSoft: Color get() = if (isDark) Color(0xFF232C48) else Color(0xFFE5EBFC)

    val warn: Color get() = if (isDark) Color(0xFFE2AD5B) else Color(0xFFA9680F)
    val ok: Color get() = if (isDark) Color(0xFF68C493) else Color(0xFF237A4B)
    val error: Color get() = if (isDark) Color(0xFFEA7C78) else Color(0xFFC8423D)
    val reasoning: Color get() = if (isDark) Color(0xFFB7A3DD) else Color(0xFF6F52AE)

    val diffAddBg: Color get() = if (isDark) Color(0xFF173225) else Color(0xFFE2F4E9)
    val diffAddText: Color get() = if (isDark) Color(0xFF86D7A8) else Color(0xFF1C7343)
    val diffRemoveBg: Color get() = if (isDark) Color(0xFF3A1D1F) else Color(0xFFFBE6E5)
    val diffRemoveText: Color get() = if (isDark) Color(0xFFF2A3A0) else Color(0xFFAE312C)
}
