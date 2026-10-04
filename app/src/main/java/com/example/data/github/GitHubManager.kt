package com.example.data.github

import android.content.Context
import android.net.Uri
import com.example.data.local.entity.ProjectEntity
import com.example.data.logs.BuildLogEntry
import com.example.data.logs.BuildLogStore
import com.example.data.logs.GitLogStore
import com.example.data.repository.GhRepo
import com.example.data.repository.GhUser
import com.example.data.repository.GitHubRepository
import com.example.data.repository.ProjectRepository
import com.example.data.repository.PushPlan
import com.example.data.repository.PushResult
import com.example.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** One place for everything GitHub: connect, accounts, create/clone/push, with terminal-style logging. */
class GitHubManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val github: GitHubRepository,
    private val projects: ProjectRepository,
    val log: GitLogStore,
    private val buildLogs: BuildLogStore,
    private val scope: CoroutineScope
) {
    private val profileCache = HashMap<String, GhUser>()

    // ---------------------------------------------------------------------------------------
    // Accounts
    // ---------------------------------------------------------------------------------------

    /** Verifies the token, saves the account and makes it active. Throws IllegalStateException with a readable message. */
    suspend fun connect(username: String, token: String): GhUser {
        val typed = username.trim().removePrefix("@")
        log.cmd("github connect @$typed")
        val user = try {
            github.getUser(token)
        } catch (e: Exception) {
            log.error(e.message ?: "Could not connect to GitHub")
            throw e
        }
        if (typed.isNotEmpty() && !user.login.equals(typed, ignoreCase = true)) {
            val msg = "This token belongs to @${user.login}, not @$typed. Check the username or use a token of that account."
            log.error(msg)
            throw IllegalStateException(msg)
        }
        settings.addGithubAccount(user.login, token)
        profileCache[user.login.lowercase()] = user
        log.ok("GitHub connected")
        log.info("Account: @${user.login}" + (if (user.name.isNotBlank()) " (${user.name})" else ""))
        if (user.bio.isNotBlank()) log.info("Bio: ${user.bio}")
        log.info("Public repositories: ${user.publicRepos}, followers: ${user.followers}, following: ${user.following}")
        return user
    }

    suspend fun profile(login: String, force: Boolean = false): GhUser? {
        val key = login.lowercase()
        if (!force) profileCache[key]?.let { return it }
        val tok = settings.githubAccounts().firstOrNull { it.login.equals(login, ignoreCase = true) }?.token ?: return null
        return try {
            github.getUser(tok).also { profileCache[key] = it }
        } catch (e: Exception) {
            null
        }
    }

    fun switchAccount(login: String) {
        settings.githubActiveLogin = login
        log.cmd("github switch @$login")
        log.ok("Active account: @$login")
    }

    fun removeAccount(login: String) {
        settings.removeGithubAccount(login)
        log.cmd("github disconnect @$login")
        log.info("Account removed from this device.")
    }

    suspend fun avatar(url: String) = github.fetchAvatar(url)

    // ---------------------------------------------------------------------------------------
    // Repositories
    // ---------------------------------------------------------------------------------------

    suspend fun listRepos(): List<GhRepo> {
        val tok = settings.githubToken
        if (tok.isBlank()) throw IllegalStateException("Connect GitHub first (Settings → GitHub).")
        return github.listRepos(tok)
    }

    suspend fun createRepo(name: String, description: String, isPrivate: Boolean): GhRepo {
        val tok = settings.githubToken
        if (tok.isBlank()) throw IllegalStateException("Connect GitHub first (Settings → GitHub).")
        log.cmd("github repo create $name (${if (isPrivate) "private" else "public"})")
        return try {
            github.createRepo(tok, name, description, isPrivate).also {
                log.ok("Created repository ${it.fullName}")
            }
        } catch (e: Exception) {
            log.error(e.message ?: "Could not create the repository")
            throw e
        }
    }

    /** Downloads [repo] and turns it into a project linked to that repository. */
    suspend fun cloneRepo(repo: GhRepo): ProjectEntity {
        val tok = settings.githubToken
        if (tok.isBlank()) throw IllegalStateException("Connect GitHub first (Settings → GitHub).")
        log.cmd("git clone ${repo.fullName}")
        val tmp = File(context.cacheDir, "clone_${System.currentTimeMillis()}.zip")
        try {
            github.downloadRepoZip(tok, repo.fullName, tmp)
            val project = try {
                projects.importProjectFromZip(repo.name, Uri.fromFile(tmp))
            } catch (e: IllegalStateException) {
                // empty repository: start an empty project that can be pushed back later
                projects.createProject(repo.name, "Empty")
            }
            settings.linkRepo(project.id, repo.fullName)
            log.ok("Cloned ${repo.fullName} (${project.fileCount} files)")
            return project
        } catch (e: Exception) {
            log.error(e.message ?: "Clone failed")
            throw e
        } finally {
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Push
    // ---------------------------------------------------------------------------------------

    suspend fun plan(projectId: String, repo: String, deleteExtra: Boolean): PushPlan {
        val tok = settings.githubToken
        if (tok.isBlank()) throw IllegalStateException("Connect GitHub first (Settings → GitHub).")
        val files = projects.listFilesForPush(projectId)
        return github.planPush(tok, repo, files, deleteExtra)
    }

    fun describe(r: PushResult, repo: String): String {
        if (r.noChanges) return "Nothing to push: $repo already matches the project."
        val parts = ArrayList<String>()
        if (r.newFiles > 0) parts.add("${r.newFiles} new")
        if (r.modifiedFiles > 0) parts.add("${r.modifiedFiles} modified")
        if (r.deletedFiles > 0) parts.add("${r.deletedFiles} deleted")
        val total = r.newFiles + r.modifiedFiles + r.deletedFiles
        return "Pushed $total file${if (total == 1) "" else "s"} to $repo (${parts.joinToString(", ")}) → commit ${r.commitSha.take(7)} on ${r.branch}"
    }

    /**
     * Pushes the project to [repo]. [deleteExtra] true = repository becomes a mirror of the project.
     * Every step is written to the terminal log.
     */
    suspend fun push(
        projectId: String,
        repo: String,
        deleteExtra: Boolean,
        message: String,
        explicitDeletes: Set<String> = emptySet(),
        requireOverlap: Boolean = false
    ): PushResult {
        val tok = settings.githubToken
        if (tok.isBlank()) throw IllegalStateException("Connect GitHub first (Settings → GitHub).")
        log.cmd("git push $repo")
        val files = projects.listFilesForPush(projectId)
        log.info("Collected ${files.size} project files (secrets and build folders are skipped)")
        return try {
            val result = github.pushProject(
                pat = tok,
                repo = repo,
                files = files,
                deleteExtra = deleteExtra,
                explicitDeletes = explicitDeletes,
                commitMessage = message,
                requireOverlap = requireOverlap,
                onProgress = { log.info(it) }
            )
            if (result.noChanges) {
                log.info(describe(result, repo))
            } else {
                log.ok(describe(result, repo))
                if (result.skipped.isNotEmpty()) {
                    log.info("Skipped ${result.skipped.size} file(s) larger than 20 MB: ${result.skipped.take(3).joinToString(", ")}")
                }
            }
            result
        } catch (e: Exception) {
            log.error(e.message ?: "Push failed")
            throw e
        }
    }

    /** Watches the GitHub Actions run of [commitSha] in the background and logs the result (+ error log). */
    fun watchBuild(projectName: String, repo: String, commitSha: String) {
        val tok = settings.githubToken
        if (tok.isBlank()) return
        scope.launch {
            log.info("Waiting for the GitHub Actions build…")
            val run = github.waitForRun(tok, repo, commitSha) { }
            if (run == null) {
                log.info("No build run found for commit ${commitSha.take(7)}. Add .github/workflows/build.yml (on: push) to build automatically.")
                return@launch
            }
            val short = commitSha.take(7)
            if (run.conclusion == "success") {
                log.ok("Build succeeded for $short. Download the APK from Artifacts: ${run.htmlUrl}")
                buildLogs.add(
                    BuildLogEntry(
                        id = System.currentTimeMillis(), time = System.currentTimeMillis(), projectName = projectName,
                        repo = repo, commit = short, status = "SUCCESS", attempt = 1, runUrl = run.htmlUrl, log = "Build succeeded."
                    )
                )
            } else if (run.conclusion == "failure") {
                log.error("Build FAILED for $short. Reading the error log…")
                val text = github.fetchFailureLog(tok, repo, run.id)
                buildLogs.add(
                    BuildLogEntry(
                        id = System.currentTimeMillis(), time = System.currentTimeMillis(), projectName = projectName,
                        repo = repo, commit = short, status = "FAILED", attempt = 1, runUrl = run.htmlUrl, log = text
                    )
                )
                log.error("Error log saved. Open it below or send it to the AI from the Builds tab.")
            } else {
                log.info("Build finished: ${run.conclusion ?: run.status}")
            }
        }
    }
}
