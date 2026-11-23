package com.example.data.settings

import android.content.Context
import com.example.data.security.KeyStoreManager
import com.example.domain.model.FallbackStrategy
import com.example.domain.model.PermissionLevel
import com.example.domain.model.ReasoningLevel
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode

/**
 * Persistent settings store for CodeForge v2.
 * Secrets (GitHub token, Termux Engine Bearer token) are stored encrypted with Android Keystore.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("codeforge_settings", Context.MODE_PRIVATE)

    // ---- Onboarding & Termux Engine (Section 5 & 14.2) ----
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed_v2", false)
        set(value) { prefs.edit().putBoolean("onboarding_completed_v2", value).apply() }

    var engineSetupCompleted: Boolean
        get() = prefs.getBoolean("engine_setup_completed_v2", false)
        set(value) { prefs.edit().putBoolean("engine_setup_completed_v2", value).apply() }

    var enginePort: Int
        get() = prefs.getInt("engine_port", 47831)
        set(value) { prefs.edit().putInt("engine_port", value).apply() }

    var engineToken: String
        get() = KeyStoreManager.decrypt(prefs.getString("engine_token_enc", "") ?: "")
        set(value) { prefs.edit().putString("engine_token_enc", KeyStoreManager.encrypt(value)).apply() }

    // ---- Agent Behavior & Memory (Section 6 & 14.7) ----
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

    var defaultPermissionLevel: PermissionLevel
        get() = PermissionLevel.fromId(prefs.getString("default_permission_level", PermissionLevel.ASK_RISKY.id))
        set(value) { prefs.edit().putString("default_permission_level", value.id).apply() }

    var language: String
        get() {
            if (!prefs.getBoolean("language_v2", false)) {
                prefs.edit().putBoolean("language_v2", true).apply()
                if ((prefs.getString("language", "English") ?: "English") == "English") {
                    prefs.edit().putString("language", "Auto").apply()
                }
            }
            return prefs.getString("language", "Auto") ?: "Auto"
        }
        set(value) { prefs.edit().putString("language", value).apply() }

    var agentMode: String
        get() = prefs.getString("agent_mode", "CHAT") ?: "CHAT"
        set(value) { prefs.edit().putString("agent_mode", value).apply() }

    var importFreeModelsOnly: Boolean
        get() = prefs.getBoolean("import_free_only", false)
        set(value) { prefs.edit().putBoolean("import_free_only", value).apply() }

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 40)
        set(value) { prefs.edit().putInt("max_steps", value.coerceIn(5, 120)).apply() }

    var maxRunTimeMinutes: Int
        get() = prefs.getInt("max_run_time_min", 15)
        set(value) { prefs.edit().putInt("max_run_time_min", value.coerceIn(2, 60)).apply() }

    var autoRunTestsAfterEdits: Boolean
        get() = prefs.getBoolean("auto_run_tests", true)
        set(value) { prefs.edit().putBoolean("auto_run_tests", value).apply() }

    var reviewModeEnabled: Boolean
        get() = prefs.getBoolean("review_mode_enabled", false)
        set(value) { prefs.edit().putBoolean("review_mode_enabled", value).apply() }

    var planFirstEnabled: Boolean
        get() = prefs.getBoolean("plan_first_enabled", true)
        set(value) { prefs.edit().putBoolean("plan_first_enabled", value).apply() }

    var autoUpdateAgentMd: Boolean
        get() = prefs.getBoolean("auto_update_agent_md", true)
        set(value) { prefs.edit().putBoolean("auto_update_agent_md", value).apply() }

    var contextSummarizeThresholdPercent: Int
        get() = prefs.getInt("context_summarize_pct", 70)
        set(value) { prefs.edit().putInt("context_summarize_pct", value.coerceIn(50, 90)).apply() }

    var globalSystemPrompt: String
        get() = prefs.getString("global_system_prompt", DEFAULT_GLOBAL_PROMPT) ?: DEFAULT_GLOBAL_PROMPT
        set(value) { prefs.edit().putString("global_system_prompt", value).apply() }

    // ---- Fallback & Models Configuration (Section 8 & 9) ----
    var fallbackEnabled: Boolean
        get() = prefs.getBoolean("fallback_enabled", true)
        set(value) { prefs.edit().putBoolean("fallback_enabled", value).apply() }

    var fallbackStrategy: FallbackStrategy
        get() = try {
            FallbackStrategy.valueOf(prefs.getString("fallback_strategy", FallbackStrategy.PRIORITY.name) ?: FallbackStrategy.PRIORITY.name)
        } catch (e: Exception) {
            FallbackStrategy.PRIORITY
        }
        set(value) { prefs.edit().putString("fallback_strategy", value.name).apply() }

    var notifyOnFallbackSwitch: Boolean
        get() = prefs.getBoolean("notify_on_fallback", true)
        set(value) { prefs.edit().putBoolean("notify_on_fallback", value).apply() }

    var switchBackToPrimary: Boolean
        get() = prefs.getBoolean("switch_back_primary", true)
        set(value) { prefs.edit().putBoolean("switch_back_primary", value).apply() }

    var maxSwitchesPerMessage: Int
        get() = prefs.getInt("max_switches_per_msg", 5)
        set(value) { prefs.edit().putInt("max_switches_per_msg", value.coerceIn(1, 15)).apply() }

    var defaultChatProviderId: String?
        get() = prefs.getString("default_chat_provider_id", null)
        set(value) { prefs.edit().putString("default_chat_provider_id", value).apply() }

    var defaultBuildProviderId: String?
        get() = prefs.getString("default_build_provider_id", null)
        set(value) { prefs.edit().putString("default_build_provider_id", value).apply() }

    // ---- Appearance & Display (Section 13 & 14.7) ----
    var themeMode: ThemeMode
        get() = try {
            ThemeMode.valueOf(prefs.getString("theme_mode_v4", ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        } catch (e: Exception) {
            ThemeMode.DARK
        }
        set(value) { prefs.edit().putString("theme_mode_v4", value.name).apply() }

    var accentPreset: AccentPreset
        get() = try {
            AccentPreset.valueOf(prefs.getString("accent_preset_v4", AccentPreset.AMBER.name) ?: AccentPreset.AMBER.name)
        } catch (e: Exception) {
            AccentPreset.AMBER
        }
        set(value) { prefs.edit().putString("accent_preset_v4", value.name).apply() }

    var codeFontSizeSp: Int
        get() = prefs.getInt("code_font_size_sp", 13)
        set(value) { prefs.edit().putInt("code_font_size_sp", value.coerceIn(10, 20)).apply() }

    var codeSoftWrap: Boolean
        get() = prefs.getBoolean("code_soft_wrap", true)
        set(value) { prefs.edit().putBoolean("code_soft_wrap", value).apply() }

    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduce_motion", false)
        set(value) { prefs.edit().putBoolean("reduce_motion", value).apply() }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean("haptics_enabled", true)
        set(value) { prefs.edit().putBoolean("haptics_enabled", value).apply() }

    var showTimestamps: Boolean
        get() = prefs.getBoolean("show_timestamps", true)
        set(value) { prefs.edit().putBoolean("show_timestamps", value).apply() }

    var checkpointRetentionCount: Int
        get() = prefs.getInt("checkpoint_retention", 15)
        set(value) { prefs.edit().putInt("checkpoint_retention", value.coerceIn(5, 50)).apply() }

    // ---- Budget & Usage ----
    var monthlyBudgetUsd: Double
        get() = prefs.getFloat("monthly_budget", 20f).toDouble()
        set(value) { prefs.edit().putFloat("monthly_budget", value.toFloat()).apply() }

    var budgetHardStop: Boolean
        get() = prefs.getBoolean("budget_hard_stop", true)
        set(value) { prefs.edit().putBoolean("budget_hard_stop", value).apply() }

    // ---- GitHub accounts & repository links ----
    var githubRepo: String
        get() = prefs.getString("github_repo", "") ?: ""
        set(value) { prefs.edit().putString("github_repo", normalizeRepo(value)).apply() }

    data class GhAccount(val login: String, val token: String)

    private fun readAccounts(): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val arr = org.json.JSONArray(prefs.getString("gh_accounts", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getString("login"), o.getString("token")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun writeAccounts(list: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        for ((login, tok) in list) arr.put(org.json.JSONObject().put("login", login).put("token", tok))
        prefs.edit().putString("gh_accounts", arr.toString()).apply()
    }

    fun githubAccounts(): List<GhAccount> =
        readAccounts().map { GhAccount(it.first, KeyStoreManager.decrypt(it.second)) }.filter { it.token.isNotBlank() }

    var githubActiveLogin: String?
        get() = prefs.getString("gh_active", null)
        set(value) { prefs.edit().putString("gh_active", value).apply() }

    fun addGithubAccount(login: String, token: String) {
        val list = readAccounts().filter { !it.first.equals(login, ignoreCase = true) }.toMutableList()
        list.add(Pair(login, KeyStoreManager.encrypt(token.trim())))
        writeAccounts(list)
        githubActiveLogin = login
    }

    fun removeGithubAccount(login: String) {
        val list = readAccounts().filter { !it.first.equals(login, ignoreCase = true) }
        writeAccounts(list)
        if (githubActiveLogin.equals(login, ignoreCase = true)) githubActiveLogin = list.firstOrNull()?.first
    }

    val githubToken: String
        get() {
            val accounts = githubAccounts()
            val active = accounts.firstOrNull { it.login.equals(githubActiveLogin, ignoreCase = true) } ?: accounts.firstOrNull()
            if (active != null) return active.token
            return KeyStoreManager.decrypt(prefs.getString("github_token", "") ?: "")
        }

    val githubLogin: String
        get() {
            val logins = readAccounts().map { it.first }
            return logins.firstOrNull { it.equals(githubActiveLogin, ignoreCase = true) } ?: logins.firstOrNull() ?: ""
        }

    val githubConnected: Boolean
        get() = readAccounts().isNotEmpty() || !prefs.getString("github_token", "").isNullOrEmpty()

    fun repoFor(projectId: String): String? =
        prefs.getString("repo_$projectId", null)?.takeIf { it.contains("/") }
            ?: githubRepo.takeIf { it.contains("/") }

    fun linkRepo(projectId: String, repo: String) {
        prefs.edit().putString("repo_$projectId", normalizeRepo(repo)).apply()
    }

    var autoPushBuild: Boolean
        get() = prefs.getBoolean("auto_push_build_v3", true)
        set(value) { prefs.edit().putBoolean("auto_push_build_v3", value).apply() }

    var maxBuildFixAttempts: Int
        get() = prefs.getInt("max_build_fix_attempts", 3)
        set(value) { prefs.edit().putInt("max_build_fix_attempts", value.coerceIn(1, 8)).apply() }

    var askBeforeBuildFix: Boolean
        get() = prefs.getBoolean("ask_before_build_fix", true)
        set(value) { prefs.edit().putBoolean("ask_before_build_fix", value).apply() }

    var ollamaMigrated: Boolean
        get() = prefs.getBoolean("ollama_migrated", false)
        set(value) { prefs.edit().putBoolean("ollama_migrated", value).apply() }

    var activeProjectId: String?
        get() = prefs.getString("active_project_id", null)
        set(value) { prefs.edit().putString("active_project_id", value).apply() }

    var preferredProviderId: String?
        get() = prefs.getString("preferred_provider_id", null)
        set(value) { prefs.edit().putString("preferred_provider_id", value).apply() }

    var activeConversationId: String?
        get() = prefs.getString("active_conversation_id", null)
        set(value) { prefs.edit().putString("active_conversation_id", value).apply() }

    val githubConfigured: Boolean
        get() = githubToken.isNotBlank()

    /** Draft text per conversation ID (Section 14.4). */
    fun getChatDraft(conversationId: String): String =
        prefs.getString("draft_$conversationId", "") ?: ""

    fun setChatDraft(conversationId: String, draft: String) {
        prefs.edit().putString("draft_$conversationId", draft).apply()
    }

    fun getDraft(conversationId: String): String = getChatDraft(conversationId)

    fun saveDraft(conversationId: String, draft: String) = setChatDraft(conversationId, draft)

    var autoReadAgentMd: Boolean
        get() = prefs.getBoolean("auto_read_agent_md", true)
        set(value) { prefs.edit().putBoolean("auto_read_agent_md", value).apply() }

    var loopGuardEnabled: Boolean
        get() = prefs.getBoolean("loop_guard_enabled", true)
        set(value) { prefs.edit().putBoolean("loop_guard_enabled", value).apply() }

    var autoCheckpointBeforeBuild: Boolean
        get() = prefs.getBoolean("auto_checkpoint_before_build", true)
        set(value) { prefs.edit().putBoolean("auto_checkpoint_before_build", value).apply() }

    var confirmBeforeDestructiveCmd: Boolean
        get() = prefs.getBoolean("confirm_before_destructive_cmd", true)
        set(value) { prefs.edit().putBoolean("confirm_before_destructive_cmd", value).apply() }

    var commandTimeoutSeconds: Int
        get() = prefs.getInt("command_timeout_sec", 120)
        set(value) { prefs.edit().putInt("command_timeout_sec", value.coerceIn(10, 600)).apply() }

    var maxProviderSwitchesPerTurn: Int
        get() = maxSwitchesPerMessage
        set(value) { maxSwitchesPerMessage = value }

    var showProviderSwitchNotice: Boolean
        get() = notifyOnFallbackSwitch
        set(value) { notifyOnFallbackSwitch = value }

    var autoCompactOnContextTooLong: Boolean
        get() = prefs.getBoolean("auto_compact_context", true)
        set(value) { prefs.edit().putBoolean("auto_compact_context", value).apply() }

    var softWrapCode: Boolean
        get() = codeSoftWrap
        set(value) { codeSoftWrap = value }

    companion object {
        fun normalizeRepo(input: String): String {
            var s = input.trim()
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            s = s.removePrefix("github.com/").removePrefix("github.com:")
            s = s.removeSuffix("/").removeSuffix(".git").trim('/')
            val parts = s.split('/').filter { it.isNotBlank() }
            return if (parts.size >= 2) parts[0] + "/" + parts[1] else s
        }

        const val DEFAULT_GLOBAL_PROMPT =
            "Work inside the active project folder only. Read before editing, prefer small targeted edits, verify with commands when possible, and reply concisely in the user's language (Hinglish/English)."
        val LANGUAGES = listOf("Auto", "English", "Hinglish", "Hindi", "Spanish", "French", "German")
    }
}
