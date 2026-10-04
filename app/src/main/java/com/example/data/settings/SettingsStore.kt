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

    /** Legacy single repository (kept for old installs). New code uses [repoFor]. */
    var githubRepo: String
        get() = prefs.getString("github_repo", "") ?: ""
        set(value) { prefs.edit().putString("github_repo", normalizeRepo(value)).apply() }

    // ---- GitHub accounts (several accounts, one active) ----
    data class GhAccount(val login: String, val token: String)

    private fun readAccounts(): MutableList<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>() // login to encrypted token
        try {
            val arr = org.json.JSONArray(prefs.getString("gh_accounts", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getString("login"), o.getString("token")))
            }
        } catch (e: Exception) {
            // ignore
        }
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

    /** Token of the active account (empty when not connected). */
    val githubToken: String
        get() {
            val accounts = githubAccounts()
            val active = accounts.firstOrNull { it.login.equals(githubActiveLogin, ignoreCase = true) } ?: accounts.firstOrNull()
            if (active != null) return active.token
            // old installs stored a single token
            return KeyStoreManager.decrypt(prefs.getString("github_token", "") ?: "")
        }

    /** Login of the active account. Cheap: does not touch the Keystore. */
    val githubLogin: String
        get() {
            val logins = readAccounts().map { it.first }
            return logins.firstOrNull { it.equals(githubActiveLogin, ignoreCase = true) } ?: logins.firstOrNull() ?: ""
        }

    /** True when at least one account is saved. Cheap: does not touch the Keystore. */
    val githubConnected: Boolean
        get() = readAccounts().isNotEmpty() || !prefs.getString("github_token", "").isNullOrEmpty()

    /** Repository (owner/repo) this project pushes to. */
    fun repoFor(projectId: String): String? =
        prefs.getString("repo_$projectId", null)?.takeIf { it.contains("/") }
            ?: githubRepo.takeIf { it.contains("/") }

    fun linkRepo(projectId: String, repo: String) {
        prefs.edit().putString("repo_$projectId", normalizeRepo(repo)).apply()
    }

    /** After every agent run: push changes to GitHub, wait for the build and auto-fix compile errors. */
    var autoPushBuild: Boolean
        get() = prefs.getBoolean("auto_push_build", false)
        set(value) { prefs.edit().putBoolean("auto_push_build", value).apply() }

    var maxBuildFixAttempts: Int
        get() = prefs.getInt("max_build_fix_attempts", 3)
        set(value) { prefs.edit().putInt("max_build_fix_attempts", value.coerceIn(1, 8)).apply() }

    /** Ask the user before the AI tries to fix a failed GitHub build. */
    var askBeforeBuildFix: Boolean
        get() = prefs.getBoolean("ask_before_build_fix", true)
        set(value) { prefs.edit().putBoolean("ask_before_build_fix", value).apply() }

    var ollamaMigrated: Boolean
        get() = prefs.getBoolean("ollama_migrated", false)
        set(value) { prefs.edit().putBoolean("ollama_migrated", value).apply() }

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
        get() = githubToken.isNotBlank()

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
            "Be concise, write production-grade Kotlin, follow modern Android patterns, and double-check imports."
        val LANGUAGES = listOf("English", "Hinglish", "Hindi", "Spanish", "French", "German")
    }
}
