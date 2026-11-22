package com.example.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String = "web"
)

data class WebSearchResponse(
    val query: String,
    val visitedUrls: List<String>,
    val hits: List<WebSearchHit>,
    val repoSummary: String? = null,
    val fetchedPageText: String? = null
) {
    fun formatForAgent(): String = buildString {
        appendLine("Web Search Results for: \"$query\"")
        if (visitedUrls.isNotEmpty()) {
            appendLine("Visited URLs:")
            visitedUrls.forEach { appendLine("  - $it") }
            appendLine()
        }
        if (!repoSummary.isNullOrBlank()) {
            appendLine("=== GitHub Repository Details ===")
            appendLine(repoSummary.trim())
            appendLine()
        }
        if (!fetchedPageText.isNullOrBlank()) {
            appendLine("=== Fetched Page Content ===")
            appendLine(fetchedPageText.trim())
            appendLine()
        }
        if (hits.isEmpty() && repoSummary.isNullOrBlank() && fetchedPageText.isNullOrBlank()) {
            appendLine("No online results found for \"$query\".")
        } else if (hits.isNotEmpty()) {
            appendLine("=== Search Matches ===")
            hits.forEachIndexed { idx, h ->
                appendLine("${idx + 1}. ${h.title} [${h.source}]")
                appendLine("   URL: ${h.url}")
                if (h.snippet.isNotBlank()) appendLine("   ${h.snippet}")
            }
        }
    }.trim()
}

/**
 * Dedicated Internet Search Client completely separate from local Termux file/repo search.
 * Supports:
 *  - Direct GitHub `owner/repo` lookup + README fetching (e.g., `morluto/rea`)
 *  - GitHub Repository Search API (`https://api.github.com/search/repositories`)
 *  - DuckDuckGo HTML web search (`https://html.duckduckgo.com/html/`)
 *  - Direct URL text fetch for documentation pages
 *
 * Emits every query and visited URL via [onTrace] so AgentEngine can surface them inside the
 * Thinking/Reasoning trace UI.
 */
class WebSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private val OWNER_REPO_REGEX = Regex("""\b([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\b""")
        private val EXCLUDED_SLASH_PAIRS = setOf("http/1", "utf/8", "android/os", "src/main", "app/src")

        fun extractGithubSlug(query: String): String? {
            val cleaned = query.trim()
                .replace("https://github.com/", "", ignoreCase = true)
                .replace("http://github.com/", "", ignoreCase = true)
                .replace("github.com/", "", ignoreCase = true)
            val match = OWNER_REPO_REGEX.find(cleaned) ?: return null
            val candidate = match.groupValues[1].removeSuffix(".git").trim('/')
            if (candidate.lowercase() in EXCLUDED_SLASH_PAIRS) return null
            val parts = candidate.split('/')
            if (parts.size != 2 || parts[0].length < 2 || parts[1].length < 2) return null
            if (parts[0].contains('.') || parts[1].endsWith(".kt") || parts[1].endsWith(".java") || parts[1].endsWith(".py")) {
                return null
            }
            return candidate
        }
    }

    suspend fun search(
        query: String,
        searchType: String = "auto",
        fetchUrl: String? = null,
        githubToken: String? = null,
        onTrace: (String) -> Unit = {}
    ): WebSearchResponse = withContext(Dispatchers.IO) {
        val visited = mutableListOf<String>()
        val hits = mutableListOf<WebSearchHit>()
        var repoSummary: String? = null
        var pageText: String? = null

        val cleanQuery = query.trim()
        onTrace("🔍 Web Search: \"$cleanQuery\"")

        // 1. Optional direct URL fetch
        if (!fetchUrl.isNullOrBlank() && (fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://"))) {
            visited.add(fetchUrl)
            onTrace("🌐 Fetching URL: $fetchUrl")
            pageText = fetchPlainText(fetchUrl, githubToken)
        }

        val slug = extractGithubSlug(cleanQuery)
        val wantGithub = searchType.equals("github", ignoreCase = true) ||
            slug != null ||
            cleanQuery.contains("github", ignoreCase = true) ||
            cleanQuery.contains("repo", ignoreCase = true)

        // 2. Direct GitHub owner/repo lookup + README
        if (slug != null) {
            val repoUrl = "https://api.github.com/repos/$slug"
            visited.add(repoUrl)
            onTrace("🌐 Checking GitHub repo: https://github.com/$slug")
            val direct = fetchGithubRepoDetails(slug, githubToken, visited, onTrace)
            if (direct != null) {
                repoSummary = direct
            }
        }

        // 3. GitHub Repository Search API
        if (wantGithub && repoSummary == null) {
            val encoded = URLEncoder.encode(cleanQuery.replace("github", "", ignoreCase = true).trim().ifBlank { cleanQuery }, "UTF-8")
            val apiUrl = "https://api.github.com/search/repositories?q=$encoded&per_page=6"
            visited.add(apiUrl)
            onTrace("🌐 Searching GitHub API: $apiUrl")
            hits.addAll(searchGithubRepos(apiUrl, githubToken))
        }

        // 4. General Web Search via DuckDuckGo HTML (unless strictly github-only with direct hit)
        if (!searchType.equals("github", ignoreCase = true) || (hits.isEmpty() && repoSummary == null)) {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val ddgUrl = "https://html.duckduckgo.com/html/?q=$encoded"
            visited.add(ddgUrl)
            onTrace("🌐 Searching Web: $ddgUrl")
            hits.addAll(searchDuckDuckGoHtml(ddgUrl))
        }

        WebSearchResponse(
            query = cleanQuery,
            visitedUrls = visited.distinct(),
            hits = hits.distinctBy { it.url }.take(10),
            repoSummary = repoSummary,
            fetchedPageText = pageText
        )
    }

    private fun requestBuilder(url: String, githubToken: String? = null): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) CodeForge/2.0")
        if (url.contains("api.github.com")) {
            b.header("Accept", "application/vnd.github+json")
            if (!githubToken.isNullOrBlank()) {
                b.header("Authorization", "Bearer ${githubToken.trim()}")
            }
        }
        return b
    }

    private fun fetchGithubRepoDetails(
        slug: String,
        githubToken: String?,
        visited: MutableList<String>,
        onTrace: (String) -> Unit
    ): String? {
        return try {
            val repoJson = client.newCall(requestBuilder("https://api.github.com/repos/$slug", githubToken).build())
                .execute().use { res ->
                    if (!res.isSuccessful) return null
                    JSONObject(res.body?.string().orEmpty())
                }
            val fullName = repoJson.optString("full_name", slug)
            val desc = repoJson.optString("description", "")
            val stars = repoJson.optInt("stargazers_count", 0)
            val lang = repoJson.optString("language", "unknown")
            val defaultBranch = repoJson.optString("default_branch", "main").ifBlank { "main" }
            val htmlUrl = repoJson.optString("html_url", "https://github.com/$fullName")
            val cloneUrl = repoJson.optString("clone_url", "https://github.com/$fullName.git")

            // Fetch raw README for accurate install & usage docs
            var readme = ""
            val readmeCandidates = listOf(
                "https://raw.githubusercontent.com/$fullName/$defaultBranch/README.md",
                "https://raw.githubusercontent.com/$fullName/$defaultBranch/readme.md"
            )
            for (rUrl in readmeCandidates) {
                visited.add(rUrl)
                onTrace("📄 Reading README: $rUrl")
                val txt = try {
                    client.newCall(requestBuilder(rUrl).build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.string().orEmpty() else ""
                    }
                } catch (_: Exception) { "" }
                if (txt.isNotBlank()) {
                    readme = txt.take(4500)
                    break
                }
            }

            buildString {
                appendLine("Repository: $fullName")
                appendLine("URL: $htmlUrl")
                appendLine("Clone URL: $cloneUrl")
                appendLine("Language: $lang | Stars: $stars | Default branch: $defaultBranch")
                if (desc.isNotBlank()) appendLine("Description: $desc")
                if (readme.isNotBlank()) {
                    appendLine("\n--- README.md (excerpt) ---")
                    appendLine(readme)
                }
            }.trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun searchGithubRepos(apiUrl: String, githubToken: String?): List<WebSearchHit> {
        return try {
            client.newCall(requestBuilder(apiUrl, githubToken).build()).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val obj = JSONObject(res.body?.string().orEmpty())
                val items = obj.optJSONArray("items") ?: return emptyList()
                buildList {
                    for (i in 0 until minOf(items.length(), 6)) {
                        val it = items.getJSONObject(i)
                        val name = it.optString("full_name")
                        val url = it.optString("html_url")
                        val desc = it.optString("description", "")
                        val stars = it.optInt("stargazers_count", 0)
                        val lang = it.optString("language", "")
                        add(
                            WebSearchHit(
                                title = name,
                                url = url,
                                snippet = "★$stars${if (lang.isNotBlank()) " · $lang" else ""} — $desc",
                                source = "GitHub"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun searchDuckDuckGoHtml(ddgUrl: String): List<WebSearchHit> {
        return try {
            val html = client.newCall(requestBuilder(ddgUrl).build()).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                res.body?.string().orEmpty()
            }
            val linkRegex = Regex(
                """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val snippetRegex = Regex(
                """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val links = linkRegex.findAll(html).toList()
            val snippets = snippetRegex.findAll(html).toList()
            links.take(7).mapIndexed { idx, m ->
                val rawHref = decodeHtmlEntities(m.groupValues[1])
                val resolvedUrl = unwrapDuckDuckGoUrl(rawHref)
                val title = stripHtml(m.groupValues[2])
                val snip = snippets.getOrNull(idx)?.let { stripHtml(it.groupValues[1]) }.orEmpty()
                WebSearchHit(title = title, url = resolvedUrl, snippet = snip, source = "Web")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchPlainText(url: String, githubToken: String?): String {
        return try {
            client.newCall(requestBuilder(url, githubToken).build()).execute().use { res ->
                if (!res.isSuccessful) return "HTTP ${res.code} while fetching $url"
                val raw = res.body?.string().orEmpty()
                val cleaned = if (raw.contains("<html", ignoreCase = true) || raw.contains("<body", ignoreCase = true)) {
                    stripHtml(raw)
                } else {
                    raw
                }
                cleaned.take(6000)
            }
        } catch (e: Exception) {
            "Failed to fetch $url: ${e.message}"
        }
    }

    private fun unwrapDuckDuckGoUrl(href: String): String {
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.getOrNull(1)
        return if (uddg != null) {
            try { java.net.URLDecoder.decode(uddg, "UTF-8") } catch (_: Exception) { href }
        } else if (href.startsWith("//")) {
            "https:$href"
        } else {
            href
        }
    }

    private fun stripHtml(html: String): String {
        val noScripts = html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        val noTags = noScripts.replace(Regex("<[^>]+>"), " ")
        return decodeHtmlEntities(noTags).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
}
