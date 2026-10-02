package com.example.data.settings

import android.content.Context
import com.example.data.security.KeyStoreManager
import com.example.domain.model.ReasoningLevel
import com.example.ui.theme.ThemeMode

/**
 * Small persistent settings store (SharedPreferences). Read synchronously from anywhere.
 * The GitHub token is stored encrypted with the Android Keystore.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("codeforge_settings", Context.MODE_PRIVATE)

    var reasoningLevel: ReasoningLevel
        get() = try {
            ReasoningLevel.valueOf(prefs.getString("reasoning_level", ReasoningLevel.AUTO.name) ?: ReasoningLevel.AUTO.name)
        } catch (e: Exception) {
            ReasoningLevel.AUTO
        }
        set(value) { prefs.edit().putString("reasoning_level", value.name).apply() }

    var safeMode: Boolean
        get() = prefs.getBoolean("safe_mode", false)
        set(value) { prefs.edit().putBoolean("safe_mode", value).apply() }

    var language: String
        get() = prefs.getString("language", "English") ?: "English"
        set(value) { prefs.edit().putString("language", value).apply() }

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 25)
        set(value) { prefs.edit().putInt("max_steps", value.coerceIn(3, 100)).apply() }

    var globalSystemPrompt: String
        get() = prefs.getString("global_system_prompt", DEFAULT_GLOBAL_PROMPT) ?: DEFAULT_GLOBAL_PROMPT
        set(value) { prefs.edit().putString("global_system_prompt", value).apply() }

    var monthlyBudgetUsd: Double
        get() = prefs.getFloat("monthly_budget", 20f).toDouble()
        set(value) { prefs.edit().putFloat("monthly_budget", value.toFloat()).apply() }

    var budgetHardStop: Boolean
        get() = prefs.getBoolean("budget_hard_stop", true)
        set(value) { prefs.edit().putBoolean("budget_hard_stop", value).apply() }

    var githubRepo: String
        get() = prefs.getString("github_repo", "") ?: ""
        set(value) { prefs.edit().putString("github_repo", value.trim()).apply() }

    var githubBranch: String
        get() = prefs.getString("github_branch", "main") ?: "main"
        set(value) { prefs.edit().putString("github_branch", value.trim().ifEmpty { "main" }).apply() }

    var githubToken: String
        get() = KeyStoreManager.decrypt(prefs.getString("github_token", "") ?: "")
        set(value) {
            val enc = try { KeyStoreManager.encrypt(value.trim()) } catch (e: Exception) { "" }
            prefs.edit().putString("github_token", enc).apply()
        }

    /** After every agent run: push changes to GitHub, wait for the build and auto-fix compile errors. */
    var autoPushBuild: Boolean
        get() = prefs.getBoolean("auto_push_build", false)
        set(value) { prefs.edit().putBoolean("auto_push_build", value).apply() }

    var maxBuildFixAttempts: Int
        get() = prefs.getInt("max_build_fix_attempts", 3)
        set(value) { prefs.edit().putInt("max_build_fix_attempts", value.coerceIn(1, 8)).apply() }

    var activeProjectId: String?
        get() = prefs.getString("active_project_id", null)
        set(value) { prefs.edit().putString("active_project_id", value).apply() }

    var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
        set(value) { prefs.edit().putString("theme_mode", value.name).apply() }

    /** Provider chosen in the chat model picker. It is tried first; others are used as failover. */
    var preferredProviderId: String?
        get() = prefs.getString("preferred_provider_id", null)
        set(value) { prefs.edit().putString("preferred_provider_id", value).apply() }

    var activeConversationId: String?
        get() = prefs.getString("active_conversation_id", null)
        set(value) { prefs.edit().putString("active_conversation_id", value).apply() }

    val githubConfigured: Boolean
        get() = githubRepo.contains("/") && githubToken.isNotBlank()

    companion object {
        const val DEFAULT_GLOBAL_PROMPT =
            "Be concise, write production-grade Kotlin, follow modern Android patterns, and double-check imports."
        val LANGUAGES = listOf("English", "Hinglish", "Hindi", "Spanish", "French", "German")
    }
}
