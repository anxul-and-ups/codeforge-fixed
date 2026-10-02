package com.example.data.repository

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val changedFiles: Int,
    val deletedFiles: Int,
    val noChanges: Boolean
)

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
                val hint = when (res.code) {
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

    /**
     * Commits the differences between [files] (local project) and the branch head as ONE commit,
     * so only one workflow run is triggered. [deletedPaths] are removed from the repo if present.
     */
    suspend fun pushProject(
        pat: String,
        ownerRepo: String,
        branch: String,
        files: Map<String, File>,
        deletedPaths: Set<String>,
        commitMessage: String
    ): PushResult = withContext(Dispatchers.IO) {
        val ref = jsonOrThrow(builder(pat, "$api/repos/$ownerRepo/git/ref/heads/$branch").build(), "read branch '$branch'")
        val baseCommitSha = ref.getJSONObject("object").getString("sha")

        val baseCommit = jsonOrThrow(builder(pat, "$api/repos/$ownerRepo/git/commits/$baseCommitSha").build(), "read commit")
        val baseTreeSha = baseCommit.getJSONObject("tree").getString("sha")

        val tree = jsonOrThrow(builder(pat, "$api/repos/$ownerRepo/git/trees/$baseTreeSha?recursive=1").build(), "read tree")
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

        if (remoteSha.isNotEmpty() && files.keys.none { it in remoteSha }) {
            throw IllegalStateException(
                "Project files do not match the GitHub repo (no common file paths). " +
                    "Check the repository name/branch, or whether the ZIP has an extra top-level folder."
            )
        }

        val changed = files.filter { (path, file) ->
            file.length() <= 20L * 1024 * 1024 && remoteSha[path] != gitBlobSha(file)
        }
        val removed = deletedPaths.filter { it in remoteSha && it !in files }

        if (changed.isEmpty() && removed.isEmpty()) {
            return@withContext PushResult(baseCommitSha, 0, 0, true)
        }

        val entries = JSONArray()
        for ((path, file) in changed) {
            val content = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            val blobBody = JSONObject().put("content", content).put("encoding", "base64")
            val blob = jsonOrThrow(
                builder(pat, "$api/repos/$ownerRepo/git/blobs")
                    .post(blobBody.toString().toRequestBody(jsonMediaType)).build(),
                "upload $path"
            )
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", remoteMode[path] ?: if (path.endsWith("gradlew") || path.endsWith(".sh")) "100755" else "100644")
                    .put("type", "blob")
                    .put("sha", blob.getString("sha"))
            )
        }
        for (path in removed) {
            entries.put(
                JSONObject()
                    .put("path", path)
                    .put("mode", remoteMode[path] ?: "100644")
                    .put("type", "blob")
                    .put("sha", JSONObject.NULL)
            )
        }

        val newTree = jsonOrThrow(
            builder(pat, "$api/repos/$ownerRepo/git/trees")
                .post(JSONObject().put("base_tree", baseTreeSha).put("tree", entries).toString().toRequestBody(jsonMediaType))
                .build(),
            "create tree"
        )
        val commit = jsonOrThrow(
            builder(pat, "$api/repos/$ownerRepo/git/commits")
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
            builder(pat, "$api/repos/$ownerRepo/git/refs/heads/$branch")
                .patch(JSONObject().put("sha", newCommitSha).toString().toRequestBody(jsonMediaType)).build(),
            "update branch"
        )
        PushResult(newCommitSha, changed.size, removed.size, false)
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
