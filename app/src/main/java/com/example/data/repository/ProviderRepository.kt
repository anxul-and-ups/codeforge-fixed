package com.example.data.repository

import com.example.data.api.ApiException
import com.example.data.api.LlmClient
import com.example.data.api.LlmRequest
import com.example.data.api.LlmResponse
import com.example.data.local.dao.ProviderDao
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.local.entity.UsageRecordEntity
import com.example.data.security.KeyStoreManager
import com.example.domain.model.FailoverEvent
import com.example.domain.model.TokenPricing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import java.io.IOException
import java.util.UUID

class ProviderRepository(
    private val providerDao: ProviderDao,
    private val usageDao: UsageDao,
    private val llmClient: LlmClient = LlmClient()
) {
    val allProviders: Flow<List<ProviderConfigEntity>> = providerDao.getAllProviders()

    private val _failoverEvents = MutableSharedFlow<FailoverEvent>(extraBufferCapacity = 10)
    val failoverEvents = _failoverEvents.asSharedFlow()

    private fun models(vararg names: String): String = JSONArray(names.toList()).toString()

    private fun defaultPresets(): List<ProviderConfigEntity> = listOf(
        ProviderConfigEntity(
            id = "anthropic", name = "Anthropic (Claude)",
            baseUrl = "https://api.anthropic.com", apiFormat = "ANTHROPIC", encryptedApiKey = "",
            modelsJson = models("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5-20251001"),
            selectedModel = "claude-sonnet-5-5", priority = 1,
            supportsVision = true, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "openai", name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", apiFormat = "OPENAI", encryptedApiKey = "",
            modelsJson = models("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "o4-mini"),
            selectedModel = "gpt-4.1", priority = 2,
            supportsVision = true, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "gemini", name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com", apiFormat = "GEMINI", encryptedApiKey = "",
            modelsJson = models("gemini-2.5-flash", "gemini-2.5-pro"),
            selectedModel = "gemini-2.5-flash", priority = 3,
            supportsVision = true, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "deepseek", name = "DeepSeek",
            baseUrl = "https://api.deepseek.com", apiFormat = "OPENAI", encryptedApiKey = "",
            modelsJson = models("deepseek-chat", "deepseek-reasoner"),
            selectedModel = "deepseek-chat", priority = 4,
            supportsVision = false, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "groq", name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1", apiFormat = "OPENAI", encryptedApiKey = "",
            modelsJson = models("llama-3.3-70b-versatile"),
            selectedModel = "llama-3.3-70b-versatile", priority = 5,
            supportsVision = false, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "openrouter", name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1", apiFormat = "OPENAI", encryptedApiKey = "",
            modelsJson = models("anthropic/claude-sonnet-4.5", "openai/gpt-4.1", "deepseek/deepseek-chat"),
            selectedModel = "anthropic/claude-sonnet-4.5", priority = 6,
            supportsVision = true, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "mistral", name = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1", apiFormat = "OPENAI", encryptedApiKey = "",
            modelsJson = models("mistral-large-latest", "codestral-latest"),
            selectedModel = "codestral-latest", priority = 7,
            supportsVision = false, supportsTools = true
        ),
        ProviderConfigEntity(
            id = "ollama", name = "Ollama / Local LM",
            baseUrl = "http://localhost:11434/v1", apiFormat = "OPENAI", encryptedApiKey = "",
            modelsJson = models("qwen2.5-coder:7b", "llama3.2:latest"),
            selectedModel = "qwen2.5-coder:7b", priority = 8,
            supportsVision = false, supportsTools = true
        )
    )

    private val DEPRECATED_MODELS = setOf(
        "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229",
        "claude-3-5-sonnet-latest", "claude-3-7-sonnet-20250219",
        "gemini-1.5-pro", "gemini-1.5-flash", "anthropic/claude-3.5-sonnet", "mixtral-8x7b-32768"
    )

    suspend fun initializeDefaultPresets() {
        val existing = providerDao.getAllProvidersOnce()
        val presets = defaultPresets()
        if (existing.isEmpty()) {
            providerDao.insertProviders(presets)
            return
        }
        // Existing install: never overwrite keys/settings. Only fix stale model names / local URL.
        for (p in existing) {
            val preset = presets.firstOrNull { it.id == p.id } ?: continue
            var updated = p
            if (p.selectedModel in DEPRECATED_MODELS) {
                updated = updated.copy(selectedModel = preset.selectedModel, modelsJson = preset.modelsJson)
            }
            if (p.id == "ollama" && p.baseUrl.contains("10.0.2.2")) {
                updated = updated.copy(baseUrl = preset.baseUrl)
            }
            if (updated != p) providerDao.updateProvider(updated)
        }
    }

    suspend fun saveProviderApiKey(providerId: String, rawApiKey: String) {
        val provider = providerDao.getProviderById(providerId) ?: return
        val encrypted = KeyStoreManager.encrypt(rawApiKey.trim())
        providerDao.updateProvider(provider.copy(encryptedApiKey = encrypted, cooldownUntilTimestamp = 0L, lastError = null))
    }

    suspend fun updateProvider(provider: ProviderConfigEntity) {
        providerDao.updateProvider(provider)
    }

    suspend fun addCustomProvider(
        name: String,
        baseUrl: String,
        apiFormat: String,
        rawApiKey: String,
        models: List<String>,
        selectedModel: String,
        customHeadersJson: String?,
        supportsVision: Boolean,
        supportsTools: Boolean
    ): ProviderConfigEntity {
        val id = UUID.randomUUID().toString()
        val all = providerDao.getAllProvidersOnce()
        val priority = (all.maxOfOrNull { it.priority } ?: 0) + 1
        val entity = ProviderConfigEntity(
            id = id,
            name = name.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiFormat = apiFormat,
            encryptedApiKey = KeyStoreManager.encrypt(rawApiKey.trim()),
            modelsJson = JSONArray(models).toString(),
            selectedModel = selectedModel.trim(),
            priority = priority,
            isEnabled = true,
            customHeadersJson = customHeadersJson,
            supportsVision = supportsVision,
            supportsTools = supportsTools
        )
        providerDao.insertProvider(entity)
        return entity
    }

    suspend fun deleteProvider(id: String) {
        providerDao.deleteProvider(id)
    }

    suspend fun testConnection(provider: ProviderConfigEntity): Boolean {
        return llmClient.testConnection(provider)
    }

    /** null = success, otherwise a readable error. */
    suspend fun testConnectionError(provider: ProviderConfigEntity): String? {
        return llmClient.testConnectionError(provider)
    }

    /** Fetches the list of models from the provider API. Throws on failure. */
    suspend fun fetchModels(provider: ProviderConfigEntity): List<String> {
        return llmClient.fetchModels(provider)
    }

    suspend fun reorderProviders(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id ->
            val p = providerDao.getProviderById(id)
            if (p != null) {
                providerDao.updateProvider(p.copy(priority = index + 1))
            }
        }
    }

    private fun isLocalUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("localhost") || u.contains("127.0.0.1") || u.contains("10.0.2.2")
    }

    private fun isTransient(e: Exception): Boolean = when (e) {
        is ApiException -> e.statusCode == 429 || e.statusCode == 408 || e.statusCode >= 500
        is IOException -> true
        else -> false
    }

    /** Only errors that are specific to this provider/key trigger failover. Bad requests do not. */
    private fun shouldFailover(e: Exception): Boolean {
        if (e is IOException) return true
        if (e !is ApiException) return false
        val m = e.message.lowercase()
        return when {
            e.statusCode in listOf(401, 402, 403, 404, 408, 429) -> true
            e.statusCode >= 500 -> true
            e.statusCode == 400 && (m.contains("credit") || m.contains("billing") || m.contains("quota") || m.contains("overloaded")) -> true
            else -> false
        }
    }

    private fun cooldownFor(e: Exception): Long {
        if (e is ApiException) {
            val m = e.message.lowercase()
            val retry = e.retryAfterSeconds()
            return when {
                m.contains("credit") || m.contains("billing") || m.contains("insufficient") ||
                    (m.contains("quota") && retry == null) || e.statusCode == 402 -> 30 * 60_000L
                e.statusCode == 401 || e.statusCode == 403 -> 10 * 60_000L
                e.statusCode == 404 -> 10 * 60_000L
                e.statusCode == 429 -> ((retry ?: 90L).coerceIn(10L, 3600L)) * 1000L
                e.statusCode >= 500 -> 45_000L
                else -> 60_000L
            }
        }
        return 20_000L
    }

    /**
     * Executes a request through the failover chain. On rate limits, exhausted quota, auth/billing problems,
     * overload or network errors it switches to the next provider and continues the SAME conversation.
     * Bad requests (HTTP 400/422...) are NOT failed over because every provider would reject them too.
     */
    suspend fun executeWithFailover(
        projectId: String,
        conversationId: String,
        request: LlmRequest,
        preferredProviderId: String? = null,
        onChunk: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
        onFailoverNotice: ((FailoverEvent) -> Unit)? = null,
        onAttempt: (() -> Unit)? = null
    ): LlmResponse {
        val candidates = providerDao.getEnabledProvidersOnce()
        val now = System.currentTimeMillis()

        val usable = candidates.filter {
            val hasKey = KeyStoreManager.decrypt(it.encryptedApiKey).isNotEmpty()
            val local = it.id == "ollama" || isLocalUrl(it.baseUrl)
            hasKey || local
        }
        if (usable.isEmpty()) {
            throw IllegalStateException("No AI providers configured with an API key. Please add an API key in the Providers tab.")
        }
        val toolCapable = if (request.tools.isNotEmpty()) usable.filter { it.supportsTools } else usable
        if (toolCapable.isEmpty()) {
            throw IllegalStateException("None of your enabled providers support tool calling, which the agent needs. Enable tools on a provider.")
        }

        val ready = toolCapable.filter { it.cooldownUntilTimestamp <= now }
        val queue: MutableList<ProviderConfigEntity> =
            if (ready.isNotEmpty()) ready.toMutableList()
            else toolCapable.sortedBy { it.cooldownUntilTimestamp }.toMutableList()

        if (preferredProviderId != null) {
            val idx = queue.indexOfFirst { it.id == preferredProviderId }
            if (idx > 0) {
                val p = queue.removeAt(idx)
                queue.add(0, p)
            }
        }

        var lastException: Exception? = null
        var previousProvider: ProviderConfigEntity? = null

        for (provider in queue) {
            if (previousProvider != null) {
                val reason = lastException?.message?.take(140) ?: "Automatic failover"
                val event = FailoverEvent(
                    timestamp = System.currentTimeMillis(),
                    fromProvider = previousProvider.name,
                    toProvider = provider.name,
                    reason = reason
                )
                _failoverEvents.emit(event)
                onFailoverNotice?.invoke(event)
            }

            val maxTries = if (queue.size == 1) 3 else 1
            var tryIndex = 0
            while (true) {
                tryIndex++
                onAttempt?.invoke()
                try {
                    val response = llmClient.execute(provider, request, onChunk, onReasoning)
                    val cost = calculateCost(provider.selectedModel, response.promptTokens, response.completionTokens, response.cachedTokens)
                    usageDao.insertUsageRecord(
                        UsageRecordEntity(
                            projectId = projectId,
                            conversationId = conversationId,
                            providerName = provider.name,
                            model = provider.selectedModel,
                            inputTokens = response.promptTokens,
                            outputTokens = response.completionTokens,
                            cachedTokens = response.cachedTokens,
                            estimatedCostUsd = cost,
                            failoverOccurred = previousProvider != null,
                            failoverReason = if (previousProvider != null) {
                                "Failover from ${previousProvider.name}: ${lastException?.message?.take(80)}"
                            } else null
                        )
                    )
                    if (provider.lastError != null || provider.cooldownUntilTimestamp != 0L) {
                        providerDao.updateProvider(provider.copy(lastError = null, cooldownUntilTimestamp = 0L))
                    }
                    return response
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    if (!shouldFailover(e)) {
                        // A broken request or local bug: other providers would fail the same way.
                        throw e
                    }
                    if (tryIndex < maxTries && isTransient(e)) {
                        val wait = ((e as? ApiException)?.retryAfterSeconds() ?: (tryIndex * 3L)).coerceIn(1L, 30L)
                        delay(wait * 1000L)
                        continue
                    }
                    providerDao.updateProvider(
                        provider.copy(
                            cooldownUntilTimestamp = System.currentTimeMillis() + cooldownFor(e),
                            lastError = e.message?.take(200)
                        )
                    )
                    previousProvider = provider
                    delay(600)
                    break
                }
            }
        }

        throw lastException ?: RuntimeException("All providers in the failover chain are exhausted.")
    }

    // Estimated prices in USD per million tokens (editable here). Most specific patterns first.
    private val PRICING_TABLE = listOf(
        TokenPricing("claude-haiku-4", 1.0, 5.0),
        TokenPricing("claude-3-5-haiku", 0.8, 4.0),
        TokenPricing("claude-opus-5", 5.0, 25.0),
        TokenPricing("claude-opus-4-5", 5.0, 25.0),
        TokenPricing("claude-opus", 15.0, 75.0),
        TokenPricing("claude-sonnet", 3.0, 15.0),
        TokenPricing("claude-3-5-sonnet", 3.0, 15.0),
        TokenPricing("claude", 3.0, 15.0),
        TokenPricing("gpt-4.1-mini", 0.40, 1.60),
        TokenPricing("gpt-4.1", 2.00, 8.00),
        TokenPricing("gpt-4o-mini", 0.15, 0.60),
        TokenPricing("gpt-4o", 2.50, 10.00),
        TokenPricing("o4-mini", 1.10, 4.40),
        TokenPricing("o3-mini", 1.10, 4.40),
        TokenPricing("gemini-2.5-flash", 0.30, 2.50),
        TokenPricing("gemini-2.5-pro", 1.25, 10.00),
        TokenPricing("deepseek", 0.28, 0.42),
        TokenPricing("llama", 0.20, 0.50),
        TokenPricing("codestral", 0.30, 0.90)
    )

    fun calculateCost(model: String, inputTokens: Int, outputTokens: Int, cachedTokens: Int = 0): Double {
        val lower = model.lowercase()
        val pricing = PRICING_TABLE.firstOrNull { lower.contains(it.modelPattern) }
            ?: TokenPricing("default", 1.0, 3.0)
        val cacheFactor = when {
            lower.contains("claude") -> 0.1
            lower.contains("gemini") -> 0.25
            else -> 0.5
        }
        val inCost = (inputTokens.toDouble() / 1_000_000.0) * pricing.inputCostPerMillion
        val cachedCost = (cachedTokens.toDouble() / 1_000_000.0) * pricing.inputCostPerMillion * cacheFactor
        val outCost = (outputTokens.toDouble() / 1_000_000.0) * pricing.outputCostPerMillion
        return inCost + cachedCost + outCost
    }
}
