package com.example.data.repository

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class WorkflowRunInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val htmlUrl: String,
    val createdAt: String,
    val headSha: String = ""
)

data class PushResult(
    val commitSha: String,
    val newFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int,
    val noChanges: Boolean,
    val branch: String = "main",
    val skipped: List<String> = emptyList(),
    val paths: List<String> = emptyList()
) {
    val changedFiles: Int get() = newFiles + modifiedFiles
}

data class GhUser(
    val login: String,
    val name: String,
    val bio: String,
    val avatarUrl: String,
    val publicRepos: Int,
    val followers: Int,
    val following: Int,
    val company: String,
    val location: String,
    val htmlUrl: String
)

data class GhRepo(
    val fullName: String,
    val name: String,
    val isPrivate: Boolean,
    val description: String,
    val defaultBranch: String,
    val updatedAt: String,
    val isFork: Boolean,
    val sizeKb: Int
)

/** What a push would do, shown to the user before anything is uploaded. */
data class PushPlan(
    val repo: String,
    val branch: String,
    val repoIsEmpty: Boolean,
    val newFiles: List<String>,
    val modifiedFiles: List<String>,
    val deletedFiles: List<String>,
    val protectedKept: List<String>
) {
    val isEmpty: Boolean get() = newFiles.isEmpty() && modifiedFiles.isEmpty() && deletedFiles.isEmpty()
}

class GitHubRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val api = "https://api.github.com"

    private fun builder(pat: String, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $pat")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "CodeForge-Android")

    private fun jsonOrThrow(request: Request, what: String): JSONObject {
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("message", body) } catch (e: Exception) { body }
                val hint = if (msg.contains("workflow", ignoreCase = true)) {
                    " (your token needs the 'workflow' scope for classic tokens, or 'Workflows: Read and write' for fine-grained tokens, to change .github/workflows files)"
                } else when (res.code) {
                    401 -> " (token invalid or expired)"
                    403 -> " (token lacks permission: needs Contents read/write, Actions read, and Workflows if you change workflow files)"
                    404 -> " (repo/branch not found or token has no access)"
                    else -> ""
                }
                throw IllegalStateException("GitHub: $what failed [${res.code}] ${msg.take(300)}$hint")
            }
            return try { JSONObject(body) } catch (e: Exception) { JSONObject() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Account, repositories
    // ---------------------------------------------------------------------------------------

    private fun parseUser(o: JSONObject) = GhUser(
        login = o.optString("login"),
        name = if (o.isNull("name")) "" else o.optString("name"),
        bio = if (o.isNull("bio")) "" else o.optString("bio"),
        avatarUrl = o.optString("avatar_url"),
        publicRepos = o.optInt("public_repos"),
        followers = o.optInt("followers"),
        following = o.optInt("following"),
        company = if (o.isNull("company")) "" else o.optString("company"),
        location = if (o.isNull("location")) "" else o.optString("location"),
        htmlUrl = o.optString("html_url")
    )

    private fun parseRepo(o: JSONObject) = GhRepo(
        fullName = o.optString("full_name"),
        name = o.optString("name"),
        isPrivate = o.optBoolean("private"),
        description = if (o.isNull("description")) "" else o.optString("description"),
        defaultBranch = o.optString("default_branch", "main").ifBlank { "main" },
        updatedAt = o.optString("updated_at"),
        isFork = o.optBoolean("fork"),
        sizeKb = o.optInt("size")
    )

    private fun friendlyHttpError(code: Int, message: String, what: String): String = when (code) {
        401 -> "$what failed: the token is wrong, expired or revoked (401)."
        403 -> "$what failed: access denied (403) $message"
        404 -> "$what failed: not found (404). For a fine-grained token, make sure the repository is selected in the token settings."
        409 -> "$what failed: $message (409)"
        422 -> "$what failed: $message (422)"
        else -> "$what failed [$code] $message"
    }

    /** Reads the account that belongs to [pat]. Throws IllegalStateException with a readable message. */
    suspend fun getUser(pat: String): GhUser = withContext(Dispatchers.IO) {
        try {
            client.newCall(builder(pat.trim(), "$api/user").build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                    throw IllegalStateException(friendlyHttpError(res.code, msg, "Connecting to GitHub"))
                }
                parseUser(JSONObject(body))
            }
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("No internet connection.")
        }
    }

    /** All repositories of the account (own, collaborator, organisation), newest first. */
    suspend fun listRepos(pat: String): List<GhRepo> = withContext(Dispatchers.IO) {
        val out = ArrayList<GhRepo>()
        for (page in 1..6) {
            val url = "$api/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator,organization_member"
            client.newCall(builder(pat.trim(), url).build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                    throw IllegalStateException(friendlyHttpError(res.code, msg, "Loading repositories"))
                }
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) out.add(parseRepo(arr.getJSONObject(i)))
                if (arr.length() < 100) return@withContext out
            }
        }
        out
    }

    suspend fun getRepo(pat: String, fullName: String): GhRepo = withContext(Dispatchers.IO) {
        client.newCall(builder(pat.trim(), "$api/repos/$fullName").build()).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                throw IllegalStateException(friendlyHttpError(res.code, msg, "Reading $fullName"))
            }
            parseRepo(JSONObject(body))
        }
    }

    suspend fun createRepo(pat: String, name: String, description: String, isPrivate: Boolean): GhRepo = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("name", name.trim())
            .put("description", description.trim())
            .put("private", isPrivate)
            .put("auto_init", false)
        client.newCall(
            builder(pat.trim(), "$api/user/repos").post(payload.toString().toRequestBody(jsonMediaType)).build()
        ).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                var msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                if (res.code == 422) {
                    msg = "A repository with this name already exists, or the name is not allowed. $msg"
                }
                throw IllegalStateException(friendlyHttpError(res.code, msg, "Creating the repository"))
            }
            parseRepo(JSONObject(body))
        }
    }

    /** Downloads the default branch of a repository as a ZIP file. */
    suspend fun downloadRepoZip(pat: String, fullName: String, dest: File) = withContext(Dispatchers.IO) {
        client.newCall(builder(pat.trim(), "$api/repos/$fullName/zipball").build()).execute().use { res ->
            if (!res.isSuccessful) {
                val body = res.body?.string().orEmpty()
                val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                throw IllegalStateException(friendlyHttpError(res.code, msg, "Downloading $fullName"))
            }
            res.body?.byteStream()?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("GitHub returned an empty download.")
        }
    }

    suspend fun fetchAvatar(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val bytes = res.body?.bytes() ?: return@withContext null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Accepts "owner/repo", full URLs, ".git" suffixes etc. and returns "owner/repo". */
    fun normalizeRepo(input: String): String {
        var s = input.trim()
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.removePrefix("github.com/").removePrefix("github.com:")
        s = s.removeSuffix("/").removeSuffix(".git").trim('/')
        val parts = s.split('/').filter { it.isNotBlank() }
        return if (parts.size >= 2) parts[0] + "/" + parts[1] else s
    }

    /**
     * Checks token, repository access and branch. Returns null when everything works,
     * otherwise a readable explanation of what is wrong.
     */
    suspend fun checkConnection(pat: String, repoInput: String, branch: String): String? = withContext(Dispatchers.IO) {
        val repo = normalizeRepo(repoInput)
        if (pat.isBlank()) return@withContext "Enter your GitHub token."
        if (!repo.contains("/")) return@withContext "Repository must look like owner/repo (for example anshul/my-app)."
        try {
            client.newCall(builder(pat.trim(), "$api/repos/$repo").build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                    return@withContext when (res.code) {
                        401 -> "Token rejected (401). It is wrong, expired or revoked. Create a new token."
                        403 -> "Access denied (403): $msg"
                        404 -> "Repository $repo not found (404). Check the name. For a fine-grained token, open the token settings and select this repository under 'Repository access'."
                        else -> "GitHub error ${res.code}: $msg"
                    }
                }
                val perms = try { JSONObject(body).optJSONObject("permissions") } catch (e: Exception) { null }
                if (perms != null && !perms.optBoolean("push", false)) {
                    return@withContext "Connected, but this token cannot write to $repo. Give it 'Contents: Read and write'."
                }
            }
            val b = branch.trim().ifEmpty { "main" }
            client.newCall(builder(pat.trim(), "$api/repos/$repo/branches/$b").build()).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext if (res.code == 404) {
                        "Branch '$b' not found. The repository may be empty or use another branch name (for example master). Upload at least one file first."
                    } else {
                        "Could not read branch '$b' (HTTP ${res.code})."
                    }
                }
            }
            null
        } catch (e: java.net.UnknownHostException) {
            "No internet connection."
        } catch (e: Exception) {
            "Connection problem: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    suspend fun testToken(pat: String, ownerRepo: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(builder(pat, "$api/repos/$ownerRepo").build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun parseRun(item: JSONObject): WorkflowRunInfo = WorkflowRunInfo(
        id = item.getLong("id"),
        name = item.optString("name", "Workflow"),
        status = item.optString("status", ""),
        conclusion = if (item.isNull("conclusion")) null else item.optString("conclusion"),
        branch = item.optString("head_branch", "main"),
        htmlUrl = item.optString("html_url", ""),
        createdAt = item.optString("created_at", ""),
        headSha = item.optString("head_sha", "")
    )

    suspend fun getRecentWorkflowRuns(pat: String, ownerRepo: String): List<WorkflowRunInfo> = withContext(Dispatchers.IO) {
        try {
            val obj = jsonOrThrow(builder(pat, "$api/repos/$ownerRepo/actions/runs?per_page=10").build(), "list runs")
            val arr = obj.optJSONArray("workflow_runs") ?: JSONArray()
            (0 until arr.length()).map { parseRun(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Push (single commit via Git Data API)
    // ---------------------------------------------------------------------------------------

    private fun gitBlobSha(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val protectedPrefixes = listOf(".github/")

    private class RemoteState(
        val defaultBranch: String,
        val isEmpty: Boolean,
        val baseCommitSha: String?,
        val baseTreeSha: String?,
        val remoteSha: Map<String, String>,
        val remoteMode: Map<String, String>
    )

    private fun readRemote(pat: String, repo: String): RemoteState {
        val info = client.newCall(builder(pat, "$api/repos/$repo").build()).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
                throw IllegalStateException(friendlyHttpError(res.code, msg, "Reading $repo"))
            }
            JSONObject(body)
        }
        val branch = info.optString("default_branch", "main").ifBlank { "main" }

        val refRes = client.newCall(builder(pat, "$api/repos/$repo/git/ref/heads/$branch").build()).execute()
        val refBody = refRes.use { r -> Pair(r.code, r.body?.string().orEmpty()) }
        if (refBody.first == 404 || refBody.first == 409) {
            return RemoteState(branch, true, null, null, emptyMap(), emptyMap())
        }
        if (refBody.first !in 200..299) {
            val msg = try { JSONObject(refBody.second).optString("message", "") } catch (e: Exception) { "" }
            throw IllegalStateException(friendlyHttpError(refBody.first, msg, "Reading branch '$branch'"))
        }
        val baseCommitSha = JSONObject(refBody.second).getJSONObject("object").getString("sha")
        val baseCommit = jsonOrThrow(builder(pat, "$api/repos/$repo/git/commits/$baseCommitSha").build(), "read commit")
        val baseTreeSha = baseCommit.getJSONObject("tree").getString("sha")
        val tree = jsonOrThrow(builder(pat, "$api/repos/$repo/git/trees/$baseTreeSha?recursive=1").build(), "read file list")
        val remoteSha = HashMap<String, String>()
        val remoteMode = HashMap<String, String>()
        val arr = tree.optJSONArray("tree") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optString("type") == "blob") {
                remoteSha[t.getString("path")] = t.getString("sha")
                remoteMode[t.getString("path")] = t.optString("mode", "100644")
            }
        }
        return RemoteState(branch, false, baseCommitSha, baseTreeSha, remoteSha, remoteMode)
    }

    private fun isProtected(path: String, localHasWorkflows: Boolean): Boolean =
        !localHasWorkflows && protectedPrefixes.any { path.startsWith(it) }

    /** Compares the project with the repository (nothing is uploaded). */
    suspend fun planPush(
        pat: String,
        repo: String,
        files: Map<String, File>,
        deleteExtra: Boolean
    ): PushPlan = withContext(Dispatchers.IO) {
        val remote = readRemote(pat.trim(), repo)
        val newF = ArrayList<String>()
        val modF = ArrayList<String>()
        for ((path, file) in files) {
            if (file.length() > 20L * 1024 * 1024) continue
            val rs = remote.remoteSha[path]
            if (rs == null) newF.add(path) else if (rs != gitBlobSha(file)) modF.add(path)
        }
        val localHasWorkflows = files.keys.any { it.startsWith(".github/") }
        val delF = ArrayList<String>()
        val kept = ArrayList<String>()
        if (deleteExtra) {
            for (path in remote.remoteSha.keys) {
                if (path in files) continue
                if (isProtected(path, localHasWorkflows)) kept.add(path) else delF.add(path)
            }
        }
        PushPlan(repo, remote.defaultBranch, remote.isEmpty, newF.sorted(), modF.sorted(), delF.sorted(), kept.sorted())
    }

    /**
     * Makes the repository's default branch contain [files] as ONE commit (one build is triggered).
     * Works for empty repositories too (the first file is created through the Contents API).
     * Files only in the repo are removed when [deleteExtra] is true (workflow files are kept if the project has none).
     */
    suspend fun pushProject(
        pat: String,
        repo: String,
        files: Map<String, File>,
        deleteExtra: Boolean,
        explicitDeletes: Set<String>,
        commitMessage: String,
        requireOverlap: Boolean,
        onlyPaths: Set<String>? = null,
        onProgress: (String) -> Unit = {}
    ): PushResult = withContext(Dispatchers.IO) {
        val token = pat.trim()
        var remote = readRemote(token, repo)
        val branch = remote.defaultBranch

        // When only specific files should be pushed (e.g. the files the AI just fixed), everything else is left alone.
        // An empty repository always gets the whole project.
        val restrict = onlyPaths != null && !remote.isEmpty
        val candidates = if (restrict) files.filterKeys { k -> onlyPaths!!.any { k == it || k.startsWith("$it/") } } else files
        val usable = candidates.filter { it.value.length() <= 20L * 1024 * 1024 }
        val skipped = candidates.filter { it.value.length() > 20L * 1024 * 1024 }.keys.toList()

        if (requireOverlap && !remote.isEmpty && remote.remoteSha.isNotEmpty() && usable.keys.none { it in remote.remoteSha }) {
            throw IllegalStateException(
                "Project files do not match the repo (no common file paths). Use 'Push project' in the GitHub screen to replace the repo content."
            )
        }
        if (usable.isEmpty() && !restrict) {
            throw IllegalStateException("The project has no files to push.")
        }

        if (remote.isEmpty) {
            onProgress("Repository is empty. Creating the first commit…")
            // The Git Data API refuses empty repositories, so seed it with the smallest file via the Contents API.
            val seedPath = usable.minByOrNull { it.value.length() }!!.key
            val seedFile = usable.getValue(seedPath)
            val seedBody = JSONObject()
                .put("message", "Initial commit")
                .put("content", Base64.encodeToString(seedFile.readBytes(), Base64.NO_WRAP))
                .put("branch", branch)
            jsonOrThrow(
                builder(token, "$api/repos/$repo/contents/" + seedPath.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
                    .put(seedBody.toString().toRequestBody(jsonMediaType)).build(),
                "create the first file"
            )
            remote = readRemote(token, repo)
        }

        val baseCommitSha = remote.baseCommitSha ?: throw IllegalStateException("Could not read the repository after creating it.")
        val baseTreeSha = remote.baseTreeSha ?: throw IllegalStateException("Could not read the repository tree.")
        val remoteSha = remote.remoteSha

        val newPaths = ArrayList<String>()
        val modPaths = ArrayList<String>()
        for ((path, file) in usable) {
            val rs = remoteSha[path]
            if (rs == null) newPaths.add(path) else if (rs != gitBlobSha(file)) modPaths.add(path)
        }
        val localHasWorkflows = files.keys.any { it.startsWith(".github/") }
        val removed = ArrayList<String>()
        for (path in remoteSha.keys) {
            if (path in files) continue
            if (!restrict && deleteExtra && !isProtected(path, localHasWorkflows)) removed.add(path)
            else if (explicitDeletes.any { path == it || path.startsWith("$it/") }) removed.add(path)
        }

        val total = newPaths.size + modPaths.size
        if (total == 0 && removed.isEmpty()) {
            return@withContext PushResult(baseCommitSha, 0, 0, 0, true, branch, skipped)
        }
        val touchedPaths = (newPaths + modPaths + removed).toList()

        val entries = JSONArray()
        // Upload the file contents in parallel (HTTP/2 multiplexes them over one connection).
        // One-by-one uploading was the reason for slow pushes on a phone connection.
        val done = AtomicInteger(0)
        val gate = Semaphore(8)
        val allPaths = newPaths + modPaths
        val uploaded: List<JSONObject> = coroutineScope {
            allPaths.map { path ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val file = usable.getValue(path)
                        val blobBody = JSONObject()
                            .put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                            .put("encoding", "base64")
                            .toString()
                        var attempt = 0
                        var blob: JSONObject? = null
                        while (blob == null) {
                            attempt++
                            try {
                                blob = jsonOrThrow(
                                    builder(token, "$api/repos/$repo/git/blobs")
                                        .post(blobBody.toRequestBody(jsonMediaType)).build(),
                                    "upload $path"
                                )
                            } catch (e: java.io.IOException) {
                                if (attempt >= 3) throw IllegalStateException("Upload of $path failed: ${e.message}")
                                delay(800L * attempt)
                            } catch (e: IllegalStateException) {
                                // secondary rate limit / temporary server error: wait and retry
                                val m = e.message.orEmpty()
                                val transient = m.contains("[403]") || m.contains("[429]") || m.contains("[500]") ||
                                    m.contains("[502]") || m.contains("[503]")
                                if (!transient || attempt >= 3) throw e
                                delay(1500L * attempt)
                            }
                        }
                        val n = done.incrementAndGet()
                        if (n % 15 == 0 || n == allPaths.size) onProgress("Uploaded $n of ${allPaths.size} files…")
                        JSONObject()
                            .put("path", path)
                            .put("mode", remote.remoteMode[path] ?: if (path.endsWith("gradlew") || path.endsWith(".sh")) "100755" else "100644")
                            .put("type", "blob")
                            .put("sha", blob.getString("sha"))
                    }
                }
            }.awaitAll()
        }
        for (o in uploaded) entries.put(o)
        for (path in removed) {
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", remote.remoteMode[path] ?: "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
        }

        val newTree = jsonOrThrow(
            builder(token, "$api/repos/$repo/git/trees")
                .post(JSONObject().put("base_tree", baseTreeSha).put("tree", entries).toString().toRequestBody(jsonMediaType))
                .build(),
            "create tree"
        )
        val commit = jsonOrThrow(
            builder(token, "$api/repos/$repo/git/commits")
                .post(
                    JSONObject()
                        .put("message", commitMessage)
                        .put("tree", newTree.getString("sha"))
                        .put("parents", JSONArray().put(baseCommitSha))
                        .toString().toRequestBody(jsonMediaType)
                ).build(),
            "create commit"
        )
        val newCommitSha = commit.getString("sha")
        jsonOrThrow(
            builder(token, "$api/repos/$repo/git/refs/heads/$branch")
                .patch(JSONObject().put("sha", newCommitSha).toString().toRequestBody(jsonMediaType)).build(),
            "update branch"
        )
        PushResult(newCommitSha, newPaths.size, modPaths.size, removed.size, false, branch, skipped, touchedPaths)
    }

    // ---------------------------------------------------------------------------------------
    // Wait for build + logs
    // ---------------------------------------------------------------------------------------

    /**
     * Waits until the workflow run for [headSha] completes. Returns null if no run ever started
     * (workflow missing, or `on: push` not configured for this branch).
     */
    suspend fun waitForRun(
        pat: String,
        ownerRepo: String,
        headSha: String,
        timeoutMs: Long = 20 * 60_000L,
        onStatus: (String) -> Unit = {}
    ): WorkflowRunInfo? {
        val start = System.currentTimeMillis()
        var lastStatus = ""
        while (System.currentTimeMillis() - start < timeoutMs) {
            val run = withContext(Dispatchers.IO) {
                try {
                    val obj = jsonOrThrow(
                        builder(pat, "$api/repos/$ownerRepo/actions/runs?head_sha=$headSha&per_page=10").build(),
                        "find run"
                    )
                    val runs = obj.optJSONArray("workflow_runs") ?: JSONArray()
                    (0 until runs.length()).map { parseRun(runs.getJSONObject(it)) }.firstOrNull()
                } catch (e: Exception) {
                    null
                }
            }
            if (run == null) {
                if (System.currentTimeMillis() - start > 120_000L) return null
                if (lastStatus != "queued-wait") {
                    lastStatus = "queued-wait"
                    onStatus("Waiting for GitHub to start the build...")
                }
            } else {
                if (run.status == "completed") return run
                if (run.status != lastStatus) {
                    lastStatus = run.status
                    onStatus("GitHub build ${run.status.replace('_', ' ')}...")
                }
            }
            delay(10_000L)
        }
        return null
    }

    suspend fun fetchFailureLog(pat: String, ownerRepo: String, runId: Long): String = withContext(Dispatchers.IO) {
        try {
            val jobs = jsonOrThrow(builder(pat, "$api/repos/$ownerRepo/actions/runs/$runId/jobs").build(), "list jobs")
            val jobsArr = jobs.optJSONArray("jobs") ?: JSONArray()
            var failedJobId: Long? = null
            for (i in 0 until jobsArr.length()) {
                val job = jobsArr.getJSONObject(i)
                if (job.optString("conclusion") == "failure") {
                    failedJobId = job.getLong("id")
                    break
                }
            }
            if (failedJobId == null) return@withContext "No failed job found for run #$runId"

            val logReq = builder(pat, "$api/repos/$ownerRepo/actions/jobs/$failedJobId/logs").build()
            client.newCall(logReq).execute().use { res ->
                if (!res.isSuccessful) return@withContext "Could not download log (HTTP ${res.code})"
                extractErrors(res.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            "Could not fetch build log: ${e.message}"
        }
    }

    private val timestampPrefix = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")
    private val errorMarkers = listOf(
        "e: ", "error:", "error ", "FAILURE:", "What went wrong", "Execution failed", "FAILED",
        "AAPT", "Caused by", "Unresolved reference", "Could not resolve", "Could not find", "Keystore file"
    )

    /** Pulls compile errors (with a little context) out of a huge Gradle log. */
    private fun extractErrors(log: String): String {
        if (log.isBlank()) return "Log empty."
        val lines = log.lines().map { it.replace(timestampPrefix, "") }
        val hit = BooleanArray(lines.size)
        var any = false
        for (i in lines.indices) {
            val l = lines[i]
            if (errorMarkers.any { l.contains(it) }) {
                any = true
                for (k in maxOf(0, i - 1)..minOf(lines.size - 1, i + 2)) hit[k] = true
            }
        }
        val out = StringBuilder()
        if (any) {
            var count = 0
            for (i in lines.indices) {
                if (hit[i]) {
                    out.append(lines[i]).append('\n')
                    count++
                    if (count >= 200 || out.length > 14_000) break
                }
            }
        } else {
            out.append(lines.takeLast(120).joinToString("\n"))
        }
        return out.toString().take(14_000)
    }

    suspend fun triggerWorkflowDispatch(pat: String, ownerRepo: String, branch: String = "main"): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().put("ref", branch)
            val req = builder(pat, "$api/repos/$ownerRepo/actions/workflows/build.yml/dispatches")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
