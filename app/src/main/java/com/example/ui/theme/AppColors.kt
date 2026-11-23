package com.example.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class AccentPreset(val displayName: String, val darkColor: Color, val lightColor: Color, val softDark: Color, val softLight: Color) {
    AMBER("Coral", Color(0xFFF4945A), Color(0xFFD96B2B), Color(0xFF332319), Color(0xFFFFEFE5)),
    INDIGO("Indigo", Color(0xFF7C8CFF), Color(0xFF4B5EFF), Color(0xFF1E2440), Color(0xFFE8ECFF)),
    TEAL("Teal", Color(0xFF38D9A9), Color(0xFF0CA678), Color(0xFF14332C), Color(0xFFE0FAF2)),
    VIOLET("Violet", Color(0xFFA78BFA), Color(0xFF7C3AED), Color(0xFF281F42), Color(0xFFF0EBFF)),
    ROSE("Rose", Color(0xFFFF6B8B), Color(0xFFE11D48), Color(0xFF3A1A24), Color(0xFFFFE8EE));

    val label: String get() = displayName
}

/**
 * CodeForge Obsidian Dark & Warm Coral Design System:
 * - Deep obsidian dark canvas (`#0B0E14`), elevated slate cards (`#12161F`), hairline borders (`#232B3A`)
 * - Warm peach-coral primary accent (`#F4945A`) with translucent amber-brown active pills (`#332319`)
 * - Crisp terminal emerald (`#42E38A`) and high-contrast slate typography
 */
object AppColors {
    var mode by mutableStateOf(ThemeMode.DARK)
    var accentPreset by mutableStateOf(AccentPreset.AMBER)
    var systemDark by mutableStateOf(true)

    val isDark: Boolean
        get() = when (mode) {
            ThemeMode.DARK, ThemeMode.AMOLED -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }

    val isAmoled: Boolean
        get() = mode == ThemeMode.AMOLED

    val bg: Color
        get() = when {
            isAmoled -> Color(0xFF000000)
            isDark -> Color(0xFF0B0E14)
            else -> Color(0xFFF6F7F9)
        }

    val surface: Color
        get() = when {
            isAmoled -> Color(0xFF0A0D12)
            isDark -> Color(0xFF12161F)
            else -> Color(0xFFFFFFFF)
        }

    val surfaceAlt: Color
        get() = when {
            isAmoled -> Color(0xFF141821)
            isDark -> Color(0xFF181E29)
            else -> Color(0xFFEEF1F6)
        }

    val border: Color
        get() = if (isDark) Color(0xFF232B3A) else Color(0x16000000)

    val codeBg: Color
        get() = when {
            isAmoled -> Color(0xFF05070A)
            isDark -> Color(0xFF090C11)
            else -> Color(0xFFF0F2F6)
        }

    val textPrimary: Color get() = if (isDark) Color(0xFFF1F3F7) else Color(0xFF14171F)
    val textSecondary: Color get() = if (isDark) Color(0xFF8F98A8) else Color(0xFF525B68)
    val textMuted: Color get() = if (isDark) Color(0xFF646D7E) else Color(0xFF848E9C)

    val accent: Color get() = if (isDark) accentPreset.darkColor else accentPreset.lightColor
    val onAccent: Color get() = if (isDark) Color(0xFF140E0A) else Color(0xFFFFFFFF)
    val accentSoft: Color get() = if (isDark) accentPreset.softDark else accentPreset.softLight

    val ok: Color get() = if (isDark) Color(0xFF42E38A) else Color(0xFF129A66)
    val warn: Color get() = if (isDark) Color(0xFFF5B546) else Color(0xFFC47F0A)
    val error: Color get() = if (isDark) Color(0xFFFF6B7A) else Color(0xFFD93848)
    val info: Color get() = if (isDark) Color(0xFF6FB7FF) else Color(0xFF1A73E8)
    val reasoning: Color get() = if (isDark) Color(0xFFB7A3DD) else Color(0xFF6F52AE)

    val synKeyword: Color get() = if (isDark) Color(0xFFC49BF0) else Color(0xFF7A3E9D)
    val synString: Color get() = if (isDark) Color(0xFF8CE09A) else Color(0xFF2E7D32)
    val synComment: Color get() = if (isDark) Color(0xFF6B7480) else Color(0xFF7A7F87)
    val synNumber: Color get() = if (isDark) Color(0xFFF5B546) else Color(0xFFB45F06)
    val synType: Color get() = if (isDark) Color(0xFF6FB7FF) else Color(0xFF1F5FA8)
    val synAnnotation: Color get() = if (isDark) Color(0xFFD9BE7E) else Color(0xFF8A6D0B)

    val diffAddBg: Color get() = if (isDark) Color(0xFF10281E) else Color(0xFFE2F6EC)
    val diffAddText: Color get() = if (isDark) Color(0xFF42E38A) else Color(0xFF147A50)
    val diffRemoveBg: Color get() = if (isDark) Color(0xFF33181D) else Color(0xFFFDE8EA)
    val diffRemoveText: Color get() = if (isDark) Color(0xFFFF6B7A) else Color(0xFFB82838)
}
