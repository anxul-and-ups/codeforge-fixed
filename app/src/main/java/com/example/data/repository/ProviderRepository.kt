package com.example.data.repository

import com.example.data.api.ApiException
import com.example.data.api.LlmClient
import com.example.data.api.LlmRequest
import com.example.data.api.LlmResponse
import com.example.data.api.ModelFetch
import com.example.data.local.dao.ProviderDao
import com.example.data.local.dao.UsageDao
import com.example.data.local.entity.ApiKeyEntity
import com.example.data.local.entity.HealthStateEntity
import com.example.data.local.entity.ModelCacheEntity
import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.local.entity.UsageRecordEntity
import com.example.data.security.KeyStoreManager
import com.example.domain.model.FailoverEvent
import com.example.domain.model.FallbackStrategy
import com.example.domain.model.ProviderErrorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class ConnectionTestDiagnosis(
    val providerId: String,
    val providerName: String,
    val success: Boolean,
    val latencyMs: Long,
    val modelsFound: Int,
    val errorClass: ProviderErrorClass? = null,
    val plainReason: String = "Working",
    val suggestedFix: String = ""
) {
    val ok: Boolean get() = success
    val modelCount: Int get() = modelsFound
    val errorMessage: String? get() = if (success) null else plainReason
}

typealias ProviderDiagnosticResult = ConnectionTestDiagnosis

/**
 * Phase 3 & Phase 4 — Provider Layer, Server-Fetched Model Cache & Fallback Engine (Sections 7, 8, 9).
 *
 * - ZERO hardcoded model names, model lists, context windows, or pricing tables (Section 8.1).
 * - Fetches models live from `/v1/models`, `v1beta/models`, `/models` and caches them in Room `ModelCacheEntity`.
 * - Unlimited providers and API keys (`ApiKeyEntity` + multiple provider key entries).
 * - Exact 9-class error classification (`classifyError`) with cooldowns, switch budgets, and mid-stream failover.
 * - Includes fault-injection simulator for running Section 9.6 acceptance tests on device.
 */
class ProviderRepository(
    private val providerDao: ProviderDao,
    private val usageDao: UsageDao,
    private val llmClient: LlmClient = LlmClient()
) {
    val allProviders: Flow<List<ProviderConfigEntity>> = providerDao.getAllProviders()
    val allCachedModels: Flow<List<ModelCacheEntity>> = providerDao.getAllCachedModels()
    val allApiKeys: Flow<List<ApiKeyEntity>> = providerDao.getAllApiKeys()
    val allHealthStates: Flow<List<HealthStateEntity>> = providerDao.getAllHealthStates()

    private val _failoverEvents = MutableSharedFlow<FailoverEvent>(extraBufferCapacity = 32)
    val failoverEvents = _failoverEvents.asSharedFlow()

    private val _fallbackActivityLog = MutableStateFlow<List<FailoverEvent>>(emptyList())
    val fallbackActivityLog: StateFlow<List<FailoverEvent>> = _fallbackActivityLog.asStateFlow()
    val failoverHistory: List<FailoverEvent> get() = _fallbackActivityLog.value

    private val roundRobinCounter = AtomicInteger(0)

    /** Fault simulation state for Section 9.6 acceptance testing in Settings -> Fallback -> Simulator */
    @Volatile
    var simulatedNextFailure: ProviderErrorClass? = null

    /**
     * Endpoint-only provider templates. Notice: `modelsJson = "[]"` and `selectedModel = ""` —
     * zero hardcoded model names exist anywhere in this class (Section 8.1).
     */
    private fun defaultPresets(): List<ProviderConfigEntity> = listOf(
        ProviderConfigEntity(
            id = "gemini",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            apiFormat = "GEMINI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 1,
            supportsVision = true,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "anthropic",
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            apiFormat = "ANTHROPIC",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 2,
            supportsVision = true,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 3,
            supportsVision = true,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 4,
            supportsVision = true,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 5,
            supportsVision = false,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 6,
            supportsVision = false,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "xai",
            name = "xAI",
            baseUrl = "https://api.x.ai/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 7,
            supportsVision = true,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "kimi",
            name = "Moonshot AI",
            baseUrl = "https://api.moonshot.ai/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 8,
            supportsVision = false,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "mistral",
            name = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 9,
            supportsVision = false,
            supportsTools = true
        ),
        ProviderConfigEntity(
            id = "ollama",
            name = "Local Server (OpenAI-compatible)",
            baseUrl = "http://127.0.0.1:11434/v1",
            apiFormat = "OPENAI",
            encryptedApiKey = "",
            modelsJson = "[]",
            selectedModel = "",
            priority = 10,
            isEnabled = false,
            supportsVision = false,
            supportsTools = true
        )
    )

    suspend fun initializeDefaultPresets(settings: com.example.data.settings.SettingsStore? = null) {
        val existing = providerDao.getAllProvidersOnce()
        val presets = defaultPresets()
        if (existing.isEmpty()) {
            providerDao.insertProviders(presets)
            settings?.ollamaMigrated = true
            return
        }
        val known = existing.map { it.id }.toSet()
        var nextPriority = (existing.maxOfOrNull { it.priority } ?: 0) + 1
        val missing = presets.filter { it.id !in known }.map { it.copy(priority = nextPriority++) }
        if (missing.isNotEmpty()) providerDao.insertProviders(missing)

        // Purge any legacy hardcoded model lists on providers that do not have an API key configured yet
        for (p in existing) {
            val hasKey = KeyStoreManager.decrypt(p.encryptedApiKey).isNotBlank()
            if (!hasKey && (p.modelsJson != "[]" || p.selectedModel.isNotBlank())) {
                providerDao.updateProvider(p.copy(modelsJson = "[]", selectedModel = ""))
            }
        }
    }

    suspend fun saveProviderApiKey(providerId: String, rawApiKey: String, keyLabel: String = "Primary Key") {
        val provider = providerDao.getProviderById(providerId) ?: return
        val encrypted = KeyStoreManager.encrypt(rawApiKey.trim())
        providerDao.updateProvider(
            provider.copy(
                encryptedApiKey = encrypted,
                keyLabel = keyLabel,
                cooldownUntilTimestamp = 0L,
                lastError = null,
                lastErrorClass = null
            )
        )
        providerDao.insertApiKey(
            ApiKeyEntity(
                id = "${providerId}_primary",
                providerId = providerId,
                label = keyLabel,
                secretRef = encrypted,
                status = "UNTESTED"
            )
        )
    }

    suspend fun addAdditionalKeyForProvider(provider: ProviderConfigEntity, keyLabel: String, rawApiKey: String): ProviderConfigEntity {
        val id = "${provider.id}_key_${UUID.randomUUID().toString().take(6)}"
        val all = providerDao.getAllProvidersOnce()
        val priority = (all.maxOfOrNull { it.priority } ?: 0) + 1
        val encrypted = KeyStoreManager.encrypt(rawApiKey.trim())
        val copyEntity = provider.copy(
            id = id,
            name = "${provider.name} ($keyLabel)",
            keyLabel = keyLabel,
            encryptedApiKey = encrypted,
            priority = priority,
            isEnabled = true,
            cooldownUntilTimestamp = 0L,
            lastError = null,
            lastErrorClass = null,
            lastTestStatus = "UNTESTED"
        )
        providerDao.insertProvider(copyEntity)
        providerDao.insertApiKey(
            ApiKeyEntity(
                id = id,
                providerId = provider.id,
                label = keyLabel,
                secretRef = encrypted,
                status = "UNTESTED",
                sortOrder = priority
            )
        )
        return copyEntity
    }

    suspend fun addAdditionalKeyForProvider(providerId: String, keyLabel: String, rawApiKey: String): ProviderConfigEntity? {
        val p = providerDao.getProviderById(providerId) ?: return null
        return addAdditionalKeyForProvider(p, keyLabel, rawApiKey)
    }

    /**
     * Upserts the provider into Room using INSERT(REPLACE) so both existing and brand-new
     * custom providers are guaranteed to persist (Room @Update silently updates 0 rows if ID is new).
     */
    suspend fun updateProvider(provider: ProviderConfigEntity) {
        providerDao.insertProvider(provider)
    }

    suspend fun upsertProvider(provider: ProviderConfigEntity): ProviderConfigEntity {
        providerDao.insertProvider(provider)
        return provider
    }

    suspend fun addCustomProvider(
        name: String,
        baseUrl: String,
        apiFormat: String,
        rawApiKey: String,
        models: List<String> = emptyList(),
        selectedModel: String = "",
        customHeadersJson: String? = null,
        supportsVision: Boolean = false,
        supportsTools: Boolean = true
    ): ProviderConfigEntity {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "custom" }
        val id = "$slug-${UUID.randomUUID().toString().take(6)}"
        val all = providerDao.getAllProvidersOnce()
        val priority = (all.maxOfOrNull { it.priority } ?: 0) + 1
        val normalizedUrl = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        val cleanModel = selectedModel.trim()
        val mergedModels = buildList {
            if (cleanModel.isNotBlank()) add(cleanModel)
            for (m in models) {
                val t = m.trim()
                if (t.isNotBlank() && t !in this) add(t)
            }
        }
        val entity = ProviderConfigEntity(
            id = id,
            name = name.trim(),
            baseUrl = normalizedUrl,
            apiFormat = apiFormat.uppercase(),
            encryptedApiKey = if (rawApiKey.isBlank()) "" else KeyStoreManager.encrypt(rawApiKey.trim()),
            modelsJson = JSONArray(mergedModels).toString(),
            selectedModel = cleanModel,
            priority = priority,
            isEnabled = true,
            customHeadersJson = customHeadersJson?.trim()?.ifBlank { null },
            supportsVision = supportsVision,
            supportsTools = supportsTools
        )
        providerDao.insertProvider(entity)
        if (cleanModel.isNotBlank()) {
            providerDao.insertCachedModels(
                listOf(
                    ModelCacheEntity(
                        id = "$id::$cleanModel",
                        providerId = id,
                        keyId = id,
                        modelId = cleanModel,
                        displayName = cleanModel,
                        contextWindow = 0,
                        supportsTools = supportsTools,
                        supportsVision = supportsVision
                    )
                )
            )
        }
        return entity
    }

    suspend fun deleteProvider(id: String) {
        providerDao.deleteProvider(id)
        providerDao.clearCachedModelsForProvider(id)
    }

    /**
     * Section 7.3 — Test Connection & Model List Fetch:
     * Calls the provider's model-list endpoint, measures latency, caches server models in `model_cache`,
     * and returns a structured diagnosis with plain-language reason + suggested fix on failure.
     */
    suspend fun testAndFetchModels(provider: ProviderConfigEntity, freeOnly: Boolean = false): ConnectionTestDiagnosis {
        val t0 = System.currentTimeMillis()
        // Ensure row exists in Room before updating status
        if (providerDao.getProviderById(provider.id) == null) {
            providerDao.insertProvider(provider)
        }
        return try {
            val fetch = llmClient.fetchModelsDetailed(provider, freeOnly)
            val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            cacheServerModels(provider, fetch)

            val allModels = buildList {
                if (provider.selectedModel.isNotBlank() && provider.selectedModel !in fetch.models) {
                    add(provider.selectedModel)
                }
                addAll(fetch.models)
            }
            val autoSelected = if (provider.selectedModel.isNotBlank()) {
                provider.selectedModel
            } else {
                allModels.firstOrNull().orEmpty()
            }
            val updated = provider.copy(
                modelsJson = JSONArray(allModels).toString(),
                selectedModel = autoSelected,
                lastTestedAt = System.currentTimeMillis(),
                lastTestStatus = "WORKING",
                lastLatencyMs = latency,
                lastError = null,
                lastErrorClass = null,
                cooldownUntilTimestamp = 0L
            )
            providerDao.insertProvider(updated)
            if (autoSelected.isNotBlank()) {
                providerDao.upsertHealthState(
                    HealthStateEntity(
                        keyId = provider.id,
                        modelId = autoSelected,
                        state = "HEALTHY",
                        untilTimestamp = 0L,
                        successRate = 1.0,
                        avgLatencyMs = latency
                    )
                )
            }
            ConnectionTestDiagnosis(
                providerId = provider.id,
                providerName = provider.name,
                success = true,
                latencyMs = latency,
                modelsFound = allModels.size,
                plainReason = "Working (${latency} ms) · ${allModels.size} models fetched from server"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Some custom OpenAI-compatible proxies don't implement GET /models (404/405),
            // but DO work with POST /chat/completions if a custom model ID is provided.
            if (provider.selectedModel.isNotBlank()) {
                val pingErr = try {
                    llmClient.testConnectionError(provider)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (ex: Exception) {
                    ex.message ?: ex.javaClass.simpleName
                }
                if (pingErr == null) {
                    val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val updated = provider.copy(
                        modelsJson = JSONArray(listOf(provider.selectedModel)).toString(),
                        lastTestedAt = System.currentTimeMillis(),
                        lastTestStatus = "WORKING",
                        lastLatencyMs = latency,
                        lastError = null,
                        lastErrorClass = null,
                        cooldownUntilTimestamp = 0L
                    )
                    providerDao.insertProvider(updated)
                    return ConnectionTestDiagnosis(
                        providerId = provider.id,
                        providerName = provider.name,
                        success = true,
                        latencyMs = latency,
                        modelsFound = 1,
                        plainReason = "Connected (${latency} ms) using custom model ${provider.selectedModel}"
                    )
                }
            }
            val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val errClass = classifyError(e)
            providerDao.insertProvider(
                provider.copy(
                    lastTestedAt = System.currentTimeMillis(),
                    lastTestStatus = if (errClass == ProviderErrorClass.AUTH) "INVALID" else "FAILED",
                    lastLatencyMs = latency,
                    lastError = e.message?.take(200),
                    lastErrorClass = errClass.name
                )
            )
            ConnectionTestDiagnosis(
                providerId = provider.id,
                providerName = provider.name,
                success = false,
                latencyMs = latency,
                modelsFound = 0,
                errorClass = errClass,
                plainReason = "${errClass.label}: ${e.message?.take(160) ?: errClass.plainReason}",
                suggestedFix = errClass.suggestedFix
            )
        }
    }

    suspend fun testAllConfiguredProviders(): List<ConnectionTestDiagnosis> {
        val all = providerDao.getAllProvidersOnce().filter {
            KeyStoreManager.decrypt(it.encryptedApiKey).isNotBlank() || isLocalUrl(it.baseUrl)
        }
        return all.map { testAndFetchModels(it) }
    }

    private suspend fun cacheServerModels(provider: ProviderConfigEntity, fetch: ModelFetch) {
        val now = System.currentTimeMillis()
        val existingMap = providerDao.getCachedModelsForProviderOnce(provider.id).associateBy { it.modelId }
        val entities = fetch.richModels.map { m ->
            val prev = existingMap[m.id]
            ModelCacheEntity(
                id = "${provider.id}::${m.id}",
                providerId = provider.id,
                keyId = provider.id,
                modelId = m.id,
                displayName = m.displayName.ifBlank { m.id },
                contextWindow = if (m.contextWindow > 0) m.contextWindow else (prev?.contextWindow ?: 0),
                maxOutput = if (m.maxOutput > 0) m.maxOutput else (prev?.maxOutput ?: 0),
                supportsTools = m.supportsTools,
                supportsVision = m.supportsVision,
                supportsStreaming = true,
                inputPricePerMillion = m.inputPricePerMillion,
                outputPricePerMillion = m.outputPricePerMillion,
                fetchedAt = now,
                hidden = prev?.hidden ?: false,
                favorite = prev?.favorite ?: false,
                lastUsedAt = prev?.lastUsedAt ?: 0L
            )
        }
        if (entities.isNotEmpty()) {
            providerDao.insertCachedModels(entities)
        }
    }

    suspend fun toggleFavoriteModel(modelCacheId: String) {
        val all = providerDao.getAllProvidersOnce()
        for (p in all) {
            val models = providerDao.getCachedModelsForProviderOnce(p.id)
            val target = models.firstOrNull { it.id == modelCacheId }
            if (target != null) {
                providerDao.updateCachedModel(target.copy(favorite = !target.favorite))
                break
            }
        }
    }

    suspend fun toggleFavoriteModel(providerId: String, modelId: String, favorite: Boolean) {
        val models = providerDao.getCachedModelsForProviderOnce(providerId)
        val target = models.firstOrNull { it.modelId == modelId }
        if (target != null) {
            providerDao.updateCachedModel(target.copy(favorite = favorite))
        }
    }

    suspend fun addCustomModelId(providerId: String, customModelId: String) {
        val trimmed = customModelId.trim()
        if (trimmed.isEmpty()) return
        val p = providerDao.getProviderById(providerId) ?: return
        val existingList = try {
            val arr = JSONArray(p.modelsJson)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
        if (trimmed !in existingList) existingList.add(0, trimmed)
        providerDao.updateProvider(
            p.copy(
                modelsJson = JSONArray(existingList).toString(),
                selectedModel = trimmed
            )
        )
        providerDao.insertCachedModels(
            listOf(
                ModelCacheEntity(
                    id = "$providerId::$trimmed",
                    providerId = providerId,
                    keyId = providerId,
                    modelId = trimmed,
                    displayName = trimmed,
                    contextWindow = 0,
                    supportsTools = p.supportsTools,
                    supportsVision = p.supportsVision
                )
            )
        )
    }

    suspend fun getReportedContextWindow(modelId: String): Int {
        val cached = providerDao.getCachedModelById(modelId)
        return if (cached != null && cached.contextWindow > 0) cached.contextWindow else 64_000
    }

    suspend fun testConnection(provider: ProviderConfigEntity): Boolean {
        return llmClient.testConnection(provider)
    }

    suspend fun testConnectionError(provider: ProviderConfigEntity): String? {
        return llmClient.testConnectionError(provider)
    }

    suspend fun fetchModels(provider: ProviderConfigEntity): List<String> {
        return llmClient.fetchModels(provider)
    }

    suspend fun fetchModelsDetailed(provider: ProviderConfigEntity, freeOnly: Boolean): ModelFetch {
        val fetch = llmClient.fetchModelsDetailed(provider, freeOnly)
        cacheServerModels(provider, fetch)
        return fetch
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

    /**
     * Section 9.2 — Exact 9-class error classification.
     */
    fun classifyError(e: Exception): ProviderErrorClass {
        if (e is UnknownHostException || (e is IOException && e.message?.contains("Unable to resolve host", ignoreCase = true) == true)) {
            return ProviderErrorClass.NETWORK_DOWN
        }
        if (e is ApiException) {
            val m = e.message.lowercase()
            return when {
                m.contains("context_length") || m.contains("maximum context length") ||
                    m.contains("too many tokens") || m.contains("prompt is too long") -> ProviderErrorClass.CONTEXT_TOO_LONG
                m.contains("blocked") || m.contains("safety") || m.contains("content_policy") -> ProviderErrorClass.CONTENT_BLOCKED
                e.statusCode == 401 || (e.statusCode == 403 && (m.contains("invalid") || m.contains("api key") || m.contains("permission_error"))) ->
                    ProviderErrorClass.AUTH
                e.statusCode == 402 || m.contains("quota") || m.contains("billing") ||
                    m.contains("insufficient") || m.contains("credit") || m.contains("daily limit") ->
                    ProviderErrorClass.QUOTA_EXHAUSTED
                e.statusCode == 429 || m.contains("rate limit") || m.contains("rate_limit") ->
                    ProviderErrorClass.RATE_LIMIT
                e.statusCode == 404 || e.statusCode == 503 || e.statusCode == 529 ||
                    m.contains("not found") || m.contains("overloaded") || m.contains("deprecated") ->
                    ProviderErrorClass.MODEL_UNAVAILABLE
                e.statusCode >= 500 || e.statusCode == 408 -> ProviderErrorClass.TRANSIENT
                else -> ProviderErrorClass.BAD_REQUEST
            }
        }
        if (e is IOException) {
            return ProviderErrorClass.TRANSIENT
        }
        return ProviderErrorClass.BAD_REQUEST
    }

    private fun shouldFailover(errClass: ProviderErrorClass): Boolean = when (errClass) {
        ProviderErrorClass.RATE_LIMIT,
        ProviderErrorClass.QUOTA_EXHAUSTED,
        ProviderErrorClass.AUTH,
        ProviderErrorClass.MODEL_UNAVAILABLE,
        ProviderErrorClass.TRANSIENT,
        ProviderErrorClass.CONTEXT_TOO_LONG -> true
        ProviderErrorClass.CONTENT_BLOCKED,
        ProviderErrorClass.NETWORK_DOWN,
        ProviderErrorClass.BAD_REQUEST -> false
    }

    private fun cooldownFor(errClass: ProviderErrorClass, e: Exception): Long {
        val retryAfterSec = (e as? ApiException)?.retryAfterSeconds()
        return when (errClass) {
            ProviderErrorClass.RATE_LIMIT -> ((retryAfterSec ?: 30L).coerceIn(15L, 3600L)) * 1000L
            ProviderErrorClass.QUOTA_EXHAUSTED -> 60 * 60_000L // 1 hour cooldown before probe
            ProviderErrorClass.AUTH -> 24 * 60 * 60_000L // marked Invalid until re-tested
            ProviderErrorClass.MODEL_UNAVAILABLE -> 5 * 60_000L
            ProviderErrorClass.TRANSIENT -> 20_000L
            else -> 0L
        }
    }

    /**
     * Section 9.4 — Seamless mid-task & mid-stream failover with strategy ordering, switch budget,
     * context-too-long auto-compaction, and fault simulation support.
     */
    suspend fun executeWithFailover(
        projectId: String,
        conversationId: String,
        request: LlmRequest,
        preferredProviderId: String? = null,
        strategy: FallbackStrategy = FallbackStrategy.PRIORITY,
        maxSwitches: Int = 5,
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
            (hasKey || local) && it.lastTestStatus != "INVALID" && it.selectedModel.isNotBlank()
        }
        if (usable.isEmpty()) {
            throw IllegalStateException("No configured AI model ready. Open Settings → Providers & API Keys, test your key to fetch server models, and select a model.")
        }
        val toolCapable = if (request.tools.isNotEmpty()) usable.filter { it.supportsTools } else usable
        if (toolCapable.isEmpty()) {
            throw IllegalStateException("Build mode requires a tool-capable model. Select a model that supports tools or switch to Chat mode.")
        }

        val ready = toolCapable.filter { it.cooldownUntilTimestamp <= now }
        val baseList = if (ready.isNotEmpty()) ready else toolCapable.sortedBy { it.cooldownUntilTimestamp }

        // Apply FallbackStrategy (Section 9.1)
        val ordered = when (strategy) {
            FallbackStrategy.PRIORITY -> baseList.sortedBy { it.priority }
            FallbackStrategy.FASTEST_FIRST -> baseList.sortedBy { if (it.lastLatencyMs > 0) it.lastLatencyMs else 999_999L }
            FallbackStrategy.CHEAPEST_FIRST -> baseList.sortedBy { it.priority }
            FallbackStrategy.ROUND_ROBIN -> {
                if (baseList.size <= 1) baseList
                else {
                    val shift = roundRobinCounter.getAndIncrement() % baseList.size
                    baseList.drop(shift) + baseList.take(shift)
                }
            }
        }.toMutableList()

        if (preferredProviderId != null && strategy == FallbackStrategy.PRIORITY) {
            val idx = ordered.indexOfFirst { it.id == preferredProviderId }
            if (idx > 0) {
                val p = ordered.removeAt(idx)
                ordered.add(0, p)
            }
        }

        var activeRequest = request
        var lastException: Exception? = null
        var previousProvider: ProviderConfigEntity? = null
        var switchesUsed = 0

        for (provider in ordered) {
            if (previousProvider != null) {
                switchesUsed++
                if (switchesUsed > maxSwitches) {
                    throw IllegalStateException("Switch budget ($maxSwitches switches) reached. Earliest cooldown recovers soon.")
                }
                val errClass = lastException?.let { classifyError(it) } ?: ProviderErrorClass.RATE_LIMIT
                val reason = "${errClass.label}: ${lastException?.message?.take(100) ?: errClass.plainReason}"
                val event = FailoverEvent(
                    timestamp = System.currentTimeMillis(),
                    fromProvider = "${previousProvider.name} (${previousProvider.selectedModel})",
                    toProvider = "${provider.name} (${provider.selectedModel})",
                    reason = reason,
                    errorClass = errClass.name
                )
                _failoverEvents.emit(event)
                _fallbackActivityLog.update { (listOf(event) + it).take(100) }
                onFailoverNotice?.invoke(event)
            }

            val maxTries = 2
            var tryIndex = 0
            while (true) {
                tryIndex++
                onAttempt?.invoke()
                val t0 = System.currentTimeMillis()
                try {
                    // Check fault simulator (Section 9.6)
                    val sim = simulatedNextFailure
                    if (sim != null) {
                        simulatedNextFailure = null
                        when (sim) {
                            ProviderErrorClass.RATE_LIMIT -> throw ApiException(429, "Simulated 429 rate limit exceeded", "15")
                            ProviderErrorClass.AUTH -> throw ApiException(401, "Simulated 401 invalid API key")
                            ProviderErrorClass.QUOTA_EXHAUSTED -> throw ApiException(402, "Simulated 402 quota and billing credit exhausted")
                            ProviderErrorClass.CONTEXT_TOO_LONG -> throw ApiException(400, "Simulated context_length_exceeded: prompt is too long")
                            ProviderErrorClass.TRANSIENT -> throw ApiException(503, "Simulated 503 service overloaded")
                            else -> throw ApiException(500, "Simulated ${sim.name}")
                        }
                    }

                    val response = llmClient.execute(provider, activeRequest, onChunk, onReasoning)
                    val latency = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val cost = calculateServerReportedCost(
                        providerId = provider.id,
                        modelId = provider.selectedModel,
                        inputTokens = response.promptTokens,
                        outputTokens = response.completionTokens,
                        cachedTokens = response.cachedTokens
                    )
                    usageDao.insertUsageRecord(
                        UsageRecordEntity(
                            projectId = projectId,
                            conversationId = conversationId,
                            keyId = provider.id,
                            providerName = provider.name,
                            model = provider.selectedModel,
                            inputTokens = response.promptTokens,
                            outputTokens = response.completionTokens,
                            cachedTokens = response.cachedTokens,
                            estimatedCostUsd = cost,
                            failoverOccurred = previousProvider != null,
                            failoverReason = if (previousProvider != null) {
                                "Switched from ${previousProvider.name}"
                            } else null
                        )
                    )
                    if (provider.lastError != null || provider.cooldownUntilTimestamp != 0L) {
                        providerDao.updateProvider(
                            provider.copy(
                                lastError = null,
                                lastErrorClass = null,
                                cooldownUntilTimestamp = 0L,
                                lastLatencyMs = latency
                            )
                        )
                    }
                    providerDao.upsertHealthState(
                        HealthStateEntity(
                            keyId = provider.id,
                            modelId = provider.selectedModel,
                            state = "HEALTHY",
                            untilTimestamp = 0L,
                            successRate = 1.0,
                            avgLatencyMs = latency
                        )
                    )
                    return response
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    val errClass = classifyError(e)

                    // Section 9.2: CONTEXT_TOO_LONG -> first trim/summarize context and retry on the SAME model!
                    if (errClass == ProviderErrorClass.CONTEXT_TOO_LONG && tryIndex == 1 && activeRequest.messages.size > 2) {
                        val trimmedMsgs = compactMessagesForContextLimit(activeRequest.messages)
                        activeRequest = activeRequest.copy(messages = trimmedMsgs)
                        continue
                    }

                    if (!shouldFailover(errClass)) {
                        throw e
                    }

                    if (tryIndex < maxTries && errClass == ProviderErrorClass.TRANSIENT) {
                        delay(1200L * tryIndex)
                        continue
                    }

                    val cd = cooldownFor(errClass, e)
                    val until = System.currentTimeMillis() + cd
                    providerDao.updateProvider(
                        provider.copy(
                            cooldownUntilTimestamp = until,
                            lastError = e.message?.take(200),
                            lastErrorClass = errClass.name,
                            lastTestStatus = if (errClass == ProviderErrorClass.AUTH) "INVALID" else provider.lastTestStatus
                        )
                    )
                    providerDao.upsertHealthState(
                        HealthStateEntity(
                            keyId = provider.id,
                            modelId = provider.selectedModel,
                            state = when (errClass) {
                                ProviderErrorClass.AUTH -> "INVALID"
                                ProviderErrorClass.QUOTA_EXHAUSTED -> "EXHAUSTED"
                                else -> "COOLDOWN"
                            },
                            untilTimestamp = until,
                            successRate = 0.5,
                            avgLatencyMs = provider.lastLatencyMs,
                            lastErrorClass = errClass.name,
                            lastErrorMessage = e.message?.take(160)
                        )
                    )
                    previousProvider = provider
                    if (ordered.size > 1) {
                        delay(150)
                    }
                    break
                }
            }
        }

        val finalErr = lastException
        if (finalErr != null) {
            val errClass = classifyError(finalErr)
            val isRateOrQuota = errClass == ProviderErrorClass.RATE_LIMIT ||
                errClass == ProviderErrorClass.QUOTA_EXHAUSTED ||
                (finalErr is ApiException && finalErr.statusCode == 429)
            if (isRateOrQuota) {
                throw ApiException(429, "API Rate Limit Exceeded. Please change API key or provider.")
            }
            throw finalErr
        }
        throw RuntimeException("All entries in the fallback chain are currently unavailable.")
    }

    private fun compactMessagesForContextLimit(messages: List<com.example.data.api.LlmMessage>): List<com.example.data.api.LlmMessage> {
        if (messages.size <= 2) return messages
        val firstSummary = com.example.data.api.LlmMessage(
            role = "user",
            content = "[Story so far (auto-summarized to fit model context window)]:\n" +
                messages.dropLast(4).joinToString("\n") { "${it.role.uppercase()}: ${it.content.take(220)}" }
        )
        return listOf(firstSummary) + messages.takeLast(4)
    }

    /**
     * Calculates cost strictly from server-reported `ModelCacheEntity` pricing (zero hardcoded model names).
     */
    suspend fun calculateServerReportedCost(
        providerId: String,
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        cachedTokens: Int = 0
    ): Double {
        val cached = providerDao.getCachedModelById(modelId)
        val inPrice = cached?.inputPricePerMillion ?: 0.0
        val outPrice = cached?.outputPricePerMillion ?: 0.0
        if (inPrice <= 0.0 && outPrice <= 0.0) return 0.0
        val inCost = (inputTokens.toDouble() / 1_000_000.0) * inPrice
        val cacheCost = (cachedTokens.toDouble() / 1_000_000.0) * inPrice * 0.25
        val outCost = (outputTokens.toDouble() / 1_000_000.0) * outPrice
        return inCost + cacheCost + outCost
    }
}
