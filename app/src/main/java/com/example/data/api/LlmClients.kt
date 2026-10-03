package com.example.data.api

import com.example.data.local.entity.ProviderConfigEntity
import com.example.data.security.KeyStoreManager
import com.example.domain.model.ReasoningLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Talks to Anthropic, OpenAI-compatible and Gemini APIs with real streaming, correct tool-calling
 * message formats and cancellation support (cancelling the coroutine cancels the HTTP call).
 */
class LlmClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private data class Flags(val reasoning: Boolean = true, val streamOptions: Boolean = true)

    suspend fun execute(
        provider: ProviderConfigEntity,
        request: LlmRequest,
        onChunk: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null
    ): LlmResponse {
        val apiKey = KeyStoreManager.decrypt(provider.encryptedApiKey)
        var flags = Flags()
        var attempt = 0
        while (true) {
            try {
                return dispatch(provider, apiKey, request, flags, onChunk, onReasoning)
            } catch (e: ApiException) {
                val m = e.message.lowercase()
                if (e.statusCode == 400 && attempt < 3) {
                    if (flags.streamOptions && m.contains("stream_options")) {
                        flags = flags.copy(streamOptions = false)
                        attempt++
                        continue
                    }
                    val mentionsReasoning = m.contains("thinking") || m.contains("reasoning") || m.contains("thinkingconfig")
                    if (flags.reasoning && request.reasoningLevel != ReasoningLevel.OFF && mentionsReasoning) {
                        flags = flags.copy(reasoning = false)
                        attempt++
                        continue
                    }
                }
                throw e
            }
        }
    }

    private suspend fun dispatch(
        provider: ProviderConfigEntity,
        apiKey: String,
        request: LlmRequest,
        flags: Flags,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val req = if (provider.supportsVision) request else request.copy(
            messages = request.messages.map {
                if (it.imagesBase64.isEmpty() && it.docs.isEmpty()) it else it.copy(imagesBase64 = emptyList(), docs = emptyList())
            }
        )
        return when (provider.apiFormat.uppercase()) {
            "ANTHROPIC" -> executeAnthropic(provider, apiKey, req, flags, onChunk, onReasoning)
            "GEMINI" -> executeGemini(provider, apiKey, req, flags, onChunk, onReasoning)
            else -> executeOpenAiCompatible(provider, apiKey, req, flags, onChunk, onReasoning)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    /** Runs a blocking OkHttp call; cancelling the calling coroutine cancels the HTTP call. */
    private suspend fun <T> runCall(request: Request, block: (Response) -> T): T = coroutineScope {
        val call = okHttpClient.newCall(request)
        val watcher = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } catch (e: IOException) {
            ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private fun apiError(response: Response, label: String): ApiException {
        val body = try { response.body?.string().orEmpty() } catch (e: Exception) { "" }
        val rawDetail: String = try {
            val err = JSONObject(body).opt("error")
            when (err) {
                is JSONObject -> err.optString("message").ifEmpty { body }
                is String -> err
                else -> body
            }
        } catch (e: Exception) {
            body
        }
        val detail = rawDetail.take(700)
        return ApiException(response.code, "$label error (${response.code}): $detail", response.header("Retry-After"))
    }

    private fun applyCustomHeaders(builder: Request.Builder, provider: ProviderConfigEntity) {
        val json = provider.customHeadersJson
        if (json.isNullOrBlank()) return
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                builder.header(k, obj.getString(k))
            }
        } catch (e: Exception) {
            // ignore malformed header JSON
        }
    }

    private fun parseObj(json: String): JSONObject = try {
        JSONObject(if (json.isBlank()) "{}" else json)
    } catch (e: Exception) {
        JSONObject()
    }

    private fun splitDataUri(img: String): Pair<String, String> {
        if (!img.startsWith("data:")) return Pair("image/jpeg", img)
        val mime = img.substringAfter("data:").substringBefore(";").ifEmpty { "image/jpeg" }
        val data = img.substringAfter(",", "")
        return Pair(mime, data)
    }

    private fun budgetFor(level: ReasoningLevel): Int = when (level) {
        ReasoningLevel.OFF -> 0
        ReasoningLevel.LOW -> 1500
        ReasoningLevel.MEDIUM, ReasoningLevel.AUTO -> 4000
        ReasoningLevel.HIGH -> 12000
    }

    // ---------------------------------------------------------------------------------------
    // Anthropic
    // ---------------------------------------------------------------------------------------

    private class AnthBlock {
        var type = ""
        val text = StringBuilder()
        val thinking = StringBuilder()
        var signature = ""
        var id = ""
        var name = ""
        val json = StringBuilder()
        var raw: JSONObject? = null
    }

    private suspend fun executeAnthropic(
        provider: ProviderConfigEntity,
        apiKey: String,
        req: LlmRequest,
        flags: Flags,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val base = provider.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/v1/messages")) base else "$base/v1/messages"

        // Extended thinking + tool use requires the previous thinking blocks to be echoed back.
        val canThink = flags.reasoning && req.reasoningLevel != ReasoningLevel.OFF &&
            req.messages.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
                .all { it.rawFormat == "ANTHROPIC" && it.rawContentJson != null }
        val budget = if (canThink) budgetFor(req.reasoningLevel) else 0

        val root = JSONObject()
        root.put("model", provider.selectedModel)
        root.put("stream", true)
        root.put("max_tokens", req.maxTokens + budget)
        if (canThink) {
            root.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
        }
        if (req.systemPrompt.isNotEmpty()) {
            root.put(
                "system",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", req.systemPrompt)
                        .put("cache_control", JSONObject().put("type", "ephemeral"))
                )
            )
        }

        val msgs = JSONArray()
        var lastToolResultMsg: JSONObject? = null
        for (m in req.messages) {
            when (m.role) {
                "tool" -> {
                    val block = JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.toolCallId ?: "")
                        .put("content", m.content.ifEmpty { "(empty)" })
                    val target = lastToolResultMsg
                    if (target != null) {
                        target.getJSONArray("content").put(block)
                    } else {
                        val o = JSONObject().put("role", "user").put("content", JSONArray().put(block))
                        msgs.put(o)
                        lastToolResultMsg = o
                    }
                }
                "assistant" -> {
                    lastToolResultMsg = null
                    val content = JSONArray()
                    if (m.rawFormat == "ANTHROPIC" && m.rawContentJson != null) {
                        val raw = JSONArray(m.rawContentJson)
                        for (i in 0 until raw.length()) {
                            val b = raw.getJSONObject(i)
                            val t = b.optString("type")
                            if (!canThink && (t == "thinking" || t == "redacted_thinking")) continue
                            content.put(b)
                        }
                    } else {
                        if (m.content.isNotBlank()) {
                            content.put(JSONObject().put("type", "text").put("text", m.content))
                        }
                        m.toolCalls?.forEach { tc ->
                            content.put(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", tc.id)
                                    .put("name", tc.name)
                                    .put("input", parseObj(tc.argumentsJson))
                            )
                        }
                    }
                    if (content.length() == 0) {
                        content.put(JSONObject().put("type", "text").put("text", "(no output)"))
                    }
                    msgs.put(JSONObject().put("role", "assistant").put("content", content))
                }
                else -> {
                    lastToolResultMsg = null
                    val arr = JSONArray()
                    for (img in m.imagesBase64) {
                        val (mime, data) = splitDataUri(img)
                        arr.put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject().put("type", "base64").put("media_type", mime).put("data", data)
                                )
                        )
                    }
                    for (d in m.docs) {
                        arr.put(
                            JSONObject()
                                .put("type", "document")
                                .put(
                                    "source",
                                    JSONObject().put("type", "base64").put("media_type", d.mime).put("data", d.base64)
                                )
                        )
                    }
                    if (m.content.isNotBlank() || arr.length() == 0) {
                        arr.put(JSONObject().put("type", "text").put("text", m.content.ifBlank { "(no text)" }))
                    }
                    msgs.put(JSONObject().put("role", "user").put("content", arr))
                }
            }
        }
        // Prompt caching breakpoint on the last block (skipped for thinking blocks)
        if (msgs.length() > 0) {
            val lastContent = msgs.getJSONObject(msgs.length() - 1).getJSONArray("content")
            if (lastContent.length() > 0) {
                val lastBlock = lastContent.getJSONObject(lastContent.length() - 1)
                val t = lastBlock.optString("type")
                if (t != "thinking" && t != "redacted_thinking") {
                    lastBlock.put("cache_control", JSONObject().put("type", "ephemeral"))
                }
            }
        }
        root.put("messages", msgs)

        if (provider.supportsTools && req.tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (t in req.tools) {
                toolsArr.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("input_schema", JSONObject(t.parametersJsonSchema))
                )
            }
            root.put("tools", toolsArr)
        }

        val builder = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
        applyCustomHeaders(builder, provider)
        val httpRequest = builder.post(root.toString().toRequestBody(jsonMediaType)).build()

        return runCall(httpRequest) { response ->
            if (!response.isSuccessful) throw apiError(response, "Anthropic")
            parseAnthropicStream(response, provider, onChunk, onReasoning)
        }
    }

    private fun parseAnthropicStream(
        response: Response,
        provider: ProviderConfigEntity,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val source = response.body?.source() ?: throw ApiException(502, "Anthropic returned an empty body")
        val blocks = HashMap<Int, AnthBlock>()
        val rawBlocks = JSONArray()
        val textOut = StringBuilder()
        val reasoningOut = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()
        var inputTokens = 0
        var cacheRead = 0
        var cacheCreate = 0
        var outputTokens = 0
        var stop: String? = null
        var gotAny = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val ev = try { JSONObject(data) } catch (e: Exception) { continue }
            gotAny = true
            when (ev.optString("type")) {
                "message_start" -> {
                    val u = ev.optJSONObject("message")?.optJSONObject("usage")
                    if (u != null) {
                        inputTokens = u.optInt("input_tokens")
                        cacheRead = u.optInt("cache_read_input_tokens")
                        cacheCreate = u.optInt("cache_creation_input_tokens")
                        outputTokens = u.optInt("output_tokens")
                    }
                }
                "content_block_start" -> {
                    val cb = ev.optJSONObject("content_block") ?: JSONObject()
                    val b = AnthBlock()
                    b.type = cb.optString("type")
                    b.id = cb.optString("id")
                    b.name = cb.optString("name")
                    if (b.type == "redacted_thinking") b.raw = cb
                    blocks[ev.optInt("index")] = b
                }
                "content_block_delta" -> {
                    val b = blocks[ev.optInt("index")]
                    val d = ev.optJSONObject("delta")
                    if (b != null && d != null) {
                        when (d.optString("type")) {
                            "text_delta" -> {
                                val t = d.optString("text")
                                b.text.append(t)
                                textOut.append(t)
                                onChunk?.invoke(t)
                            }
                            "thinking_delta" -> {
                                val t = d.optString("thinking")
                                b.thinking.append(t)
                                reasoningOut.append(t)
                                onReasoning?.invoke(t)
                            }
                            "signature_delta" -> b.signature = d.optString("signature")
                            "input_json_delta" -> b.json.append(d.optString("partial_json"))
                        }
                    }
                }
                "content_block_stop" -> {
                    val b = blocks.remove(ev.optInt("index"))
                    if (b != null) {
                        when (b.type) {
                            "text" -> if (b.text.isNotEmpty()) {
                                rawBlocks.put(JSONObject().put("type", "text").put("text", b.text.toString()))
                            }
                            "thinking" -> rawBlocks.put(
                                JSONObject()
                                    .put("type", "thinking")
                                    .put("thinking", b.thinking.toString())
                                    .put("signature", b.signature)
                            )
                            "redacted_thinking" -> {
                                val r = b.raw
                                if (r != null) rawBlocks.put(r)
                            }
                            "tool_use" -> {
                                val argStr = b.json.toString().ifBlank { "{}" }
                                val parsed = try { JSONObject(argStr) } catch (e: Exception) { null }
                                rawBlocks.put(
                                    JSONObject()
                                        .put("type", "tool_use")
                                        .put("id", b.id)
                                        .put("name", b.name)
                                        .put("input", parsed ?: JSONObject())
                                )
                                toolCalls.add(LlmToolCall(b.id, b.name, argStr))
                            }
                        }
                    }
                }
                "message_delta" -> {
                    val d = ev.optJSONObject("delta")
                    if (d != null && !d.isNull("stop_reason")) stop = d.optString("stop_reason")
                    val u = ev.optJSONObject("usage")
                    if (u != null) outputTokens = u.optInt("output_tokens", outputTokens)
                }
                "error" -> {
                    val er = ev.optJSONObject("error")
                    val type = er?.optString("type") ?: ""
                    val msg = er?.optString("message") ?: data
                    val code = when (type) {
                        "overloaded_error" -> 529
                        "rate_limit_error" -> 429
                        "invalid_request_error" -> 400
                        "authentication_error" -> 401
                        "permission_error" -> 403
                        else -> 500
                    }
                    throw ApiException(code, "Anthropic stream error ($type): $msg")
                }
            }
        }
        if (!gotAny) throw ApiException(502, "Anthropic returned an empty stream")

        return LlmResponse(
            content = textOut.toString(),
            reasoning = reasoningOut.toString().ifEmpty { null },
            toolCalls = toolCalls,
            promptTokens = inputTokens + cacheCreate,
            completionTokens = outputTokens,
            cachedTokens = cacheRead,
            finishReason = stop,
            providerUsed = provider.name,
            modelUsed = provider.selectedModel,
            rawContentJson = rawBlocks.toString(),
            rawFormat = "ANTHROPIC"
        )
    }

    // ---------------------------------------------------------------------------------------
    // OpenAI-compatible (OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Ollama, custom...)
    // ---------------------------------------------------------------------------------------

    private class ToolAcc {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private suspend fun executeOpenAiCompatible(
        provider: ProviderConfigEntity,
        apiKey: String,
        req: LlmRequest,
        flags: Flags,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val base = provider.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val modelName = provider.selectedModel.lowercase().substringAfterLast('/')
        val isReasoningModel = Regex("^(o\\d|gpt-5)").containsMatchIn(modelName)
        val useCompletionTokens = isReasoningModel && base.contains("api.openai.com")

        val root = JSONObject()
        root.put("model", provider.selectedModel)
        root.put("stream", true)
        if (flags.streamOptions) {
            root.put("stream_options", JSONObject().put("include_usage", true))
        }
        if (useCompletionTokens) {
            root.put("max_completion_tokens", req.maxTokens + budgetFor(req.reasoningLevel))
        } else {
            root.put("max_tokens", req.maxTokens)
        }
        if (isReasoningModel && flags.reasoning && req.reasoningLevel != ReasoningLevel.OFF) {
            val effort = when (req.reasoningLevel) {
                ReasoningLevel.LOW -> "low"
                ReasoningLevel.HIGH -> "high"
                else -> "medium"
            }
            root.put("reasoning_effort", effort)
        }

        val messagesArr = JSONArray()
        if (req.systemPrompt.isNotEmpty()) {
            messagesArr.put(JSONObject().put("role", "system").put("content", req.systemPrompt))
        }
        for (m in req.messages) {
            val msgObj = JSONObject()
            when (m.role) {
                "tool" -> {
                    msgObj.put("role", "tool")
                    msgObj.put("tool_call_id", m.toolCallId ?: "")
                    msgObj.put("content", m.content.ifEmpty { "(empty)" })
                }
                "assistant" -> {
                    msgObj.put("role", "assistant")
                    msgObj.put("content", if (m.content.isEmpty()) JSONObject.NULL else m.content)
                    val tcs = m.toolCalls
                    if (tcs != null && tcs.isNotEmpty()) {
                        val tcArr = JSONArray()
                        for (tc in tcs) {
                            tcArr.put(
                                JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject().put("name", tc.name).put("arguments", tc.argumentsJson)
                                    )
                            )
                        }
                        msgObj.put("tool_calls", tcArr)
                    }
                }
                else -> {
                    msgObj.put("role", "user")
                    val pdfSupported = base.contains("api.openai.com") || base.contains("openrouter.ai")
                    val sendDocs = pdfSupported && m.docs.isNotEmpty()
                    if (m.imagesBase64.isNotEmpty() || sendDocs) {
                        val contentArr = JSONArray()
                        val extra = if (!pdfSupported && m.docs.isNotEmpty()) {
                            "\n\n[Note: a PDF was attached but this provider cannot read PDFs.]"
                        } else ""
                        contentArr.put(JSONObject().put("type", "text").put("text", (m.content + extra).ifBlank { "(no text)" }))
                        if (sendDocs) {
                            for (d in m.docs) {
                                contentArr.put(
                                    JSONObject()
                                        .put("type", "file")
                                        .put(
                                            "file",
                                            JSONObject()
                                                .put("filename", d.name)
                                                .put("file_data", "data:${d.mime};base64,${d.base64}")
                                        )
                                )
                            }
                        }
                        for (img in m.imagesBase64) {
                            val urlStr = if (img.startsWith("data:")) img else "data:image/jpeg;base64,$img"
                            contentArr.put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", urlStr))
                            )
                        }
                        msgObj.put("content", contentArr)
                    } else {
                        val extra = if (m.docs.isNotEmpty()) "\n\n[Note: a PDF was attached but this provider cannot read PDFs.]" else ""
                        msgObj.put("content", (m.content + extra).ifBlank { "(no text)" })
                    }
                }
            }
            messagesArr.put(msgObj)
        }
        root.put("messages", messagesArr)

        if (provider.supportsTools && req.tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            for (t in req.tools) {
                toolsArr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("parameters", JSONObject(t.parametersJsonSchema))
                        )
                )
            }
            root.put("tools", toolsArr)
        }

        val builder = Request.Builder().url(url)
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        builder.header("content-type", "application/json")
        applyCustomHeaders(builder, provider)
        val httpRequest = builder.post(root.toString().toRequestBody(jsonMediaType)).build()

        return runCall(httpRequest) { response ->
            if (!response.isSuccessful) throw apiError(response, "OpenAI-compatible")
            val ct = response.header("Content-Type") ?: ""
            if (ct.contains("application/json", ignoreCase = true)) {
                parseOpenAiFull(response, provider, onChunk, onReasoning)
            } else {
                parseOpenAiStream(response, provider, onChunk, onReasoning)
            }
        }
    }

    private fun parseOpenAiFull(
        response: Response,
        provider: ProviderConfigEntity,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val resObj = JSONObject(response.body?.string() ?: "{}")
        val choices = resObj.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw ApiException(502, "Provider returned no choices")
        }
        val first = choices.getJSONObject(0)
        val msg = first.optJSONObject("message") ?: JSONObject()
        val content = if (msg.isNull("content")) "" else msg.optString("content")
        val reasoning = if (msg.isNull("reasoning_content")) null else msg.optString("reasoning_content")
        if (content.isNotEmpty()) onChunk?.invoke(content)
        if (!reasoning.isNullOrEmpty()) onReasoning?.invoke(reasoning)

        val toolCalls = mutableListOf<LlmToolCall>()
        val tcArr = msg.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val tc = tcArr.getJSONObject(i)
                val fn = tc.optJSONObject("function")
                val id = tc.optString("id").ifEmpty { UUID.randomUUID().toString() }
                toolCalls.add(LlmToolCall(id, fn?.optString("name") ?: "", fn?.optString("arguments")?.ifBlank { "{}" } ?: "{}"))
            }
        }
        val usage = resObj.optJSONObject("usage")
        val prompt = usage?.optInt("prompt_tokens") ?: 0
        val cached = usage?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0
        return LlmResponse(
            content = content,
            reasoning = reasoning,
            toolCalls = toolCalls,
            promptTokens = (prompt - cached).coerceAtLeast(0),
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            cachedTokens = cached,
            finishReason = first.optString("finish_reason"),
            providerUsed = provider.name,
            modelUsed = provider.selectedModel
        )
    }

    private fun parseOpenAiStream(
        response: Response,
        provider: ProviderConfigEntity,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val source = response.body?.source() ?: throw ApiException(502, "Provider returned an empty body")
        val textOut = StringBuilder()
        val reasoningOut = StringBuilder()
        val tcs = TreeMap<Int, ToolAcc>()
        var promptTokens = 0
        var completionTokens = 0
        var cached = 0
        var finish: String? = null
        var gotAny = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") break
            val ev = try { JSONObject(data) } catch (e: Exception) { continue }
            gotAny = true

            val err = ev.optJSONObject("error")
            if (err != null) {
                throw ApiException(err.optInt("code", 500).let { if (it in 400..599) it else 500 }, "Provider stream error: ${err.optString("message", data)}")
            }
            val usage = ev.optJSONObject("usage")
            if (usage != null) {
                promptTokens = usage.optInt("prompt_tokens", promptTokens)
                completionTokens = usage.optInt("completion_tokens", completionTokens)
                cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: cached
                if (cached == 0) cached = usage.optInt("prompt_cache_hit_tokens", 0)
            }
            val choices = ev.optJSONArray("choices")
            if (choices == null || choices.length() == 0) continue
            val ch = choices.getJSONObject(0)
            val delta = ch.optJSONObject("delta") ?: JSONObject()

            if (!delta.isNull("content")) {
                val t = delta.optString("content")
                if (t.isNotEmpty()) {
                    textOut.append(t)
                    onChunk?.invoke(t)
                }
            }
            val reasoningKey = if (!delta.isNull("reasoning_content")) "reasoning_content" else "reasoning"
            if (!delta.isNull(reasoningKey)) {
                val t = delta.optString(reasoningKey)
                if (t.isNotEmpty()) {
                    reasoningOut.append(t)
                    onReasoning?.invoke(t)
                }
            }
            val tcArr = delta.optJSONArray("tool_calls")
            if (tcArr != null) {
                for (i in 0 until tcArr.length()) {
                    val tc = tcArr.getJSONObject(i)
                    val idx = if (tc.has("index")) {
                        tc.getInt("index")
                    } else if (tc.optString("id").isNotEmpty()) {
                        tcs.size
                    } else {
                        maxOf(tcs.size - 1, 0)
                    }
                    val acc = tcs.getOrPut(idx) { ToolAcc() }
                    val id = tc.optString("id")
                    if (id.isNotEmpty() && id != "null") acc.id = id
                    val fn = tc.optJSONObject("function")
                    if (fn != null) {
                        val n = fn.optString("name")
                        if (n.isNotEmpty() && n != "null") acc.name = n
                        if (!fn.isNull("arguments")) acc.args.append(fn.optString("arguments"))
                    }
                }
            }
            if (!ch.isNull("finish_reason")) finish = ch.optString("finish_reason")
        }
        if (!gotAny) throw ApiException(502, "Provider returned an empty stream")

        val toolCalls = tcs.values
            .filter { it.name.isNotEmpty() }
            .map { LlmToolCall(it.id.ifEmpty { UUID.randomUUID().toString() }, it.name, it.args.toString().ifBlank { "{}" }) }

        return LlmResponse(
            content = textOut.toString(),
            reasoning = reasoningOut.toString().ifEmpty { null },
            toolCalls = toolCalls,
            promptTokens = (promptTokens - cached).coerceAtLeast(0),
            completionTokens = completionTokens,
            cachedTokens = cached,
            finishReason = finish,
            providerUsed = provider.name,
            modelUsed = provider.selectedModel
        )
    }

    // ---------------------------------------------------------------------------------------
    // Gemini
    // ---------------------------------------------------------------------------------------

    private suspend fun executeGemini(
        provider: ProviderConfigEntity,
        apiKey: String,
        req: LlmRequest,
        flags: Flags,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val model = provider.selectedModel.removePrefix("models/")
        val base = provider.baseUrl.trim().trimEnd('/')
        val url = "$base/v1beta/models/$model:streamGenerateContent?alt=sse"

        val root = JSONObject()
        if (req.systemPrompt.isNotEmpty()) {
            root.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", req.systemPrompt)))
            )
        }

        val contents = JSONArray()
        var lastToolResultMsg: JSONObject? = null
        for (m in req.messages) {
            when (m.role) {
                "tool" -> {
                    val part = JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", m.toolName ?: "tool")
                            .put("response", JSONObject().put("result", m.content))
                    )
                    val target = lastToolResultMsg
                    if (target != null) {
                        target.getJSONArray("parts").put(part)
                    } else {
                        val o = JSONObject().put("role", "user").put("parts", JSONArray().put(part))
                        contents.put(o)
                        lastToolResultMsg = o
                    }
                }
                "assistant" -> {
                    lastToolResultMsg = null
                    val parts = JSONArray()
                    if (m.rawFormat == "GEMINI" && m.rawContentJson != null) {
                        val raw = JSONArray(m.rawContentJson)
                        for (i in 0 until raw.length()) parts.put(raw.getJSONObject(i))
                    } else {
                        if (m.content.isNotBlank()) parts.put(JSONObject().put("text", m.content))
                        m.toolCalls?.forEach { tc ->
                            parts.put(
                                JSONObject().put(
                                    "functionCall",
                                    JSONObject().put("name", tc.name).put("args", parseObj(tc.argumentsJson))
                                )
                            )
                        }
                    }
                    if (parts.length() == 0) parts.put(JSONObject().put("text", "(no output)"))
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                else -> {
                    lastToolResultMsg = null
                    val parts = JSONArray()
                    if (m.content.isNotBlank() || m.imagesBase64.isEmpty()) {
                        parts.put(JSONObject().put("text", m.content.ifBlank { "(no text)" }))
                    }
                    for (img in m.imagesBase64) {
                        val (mime, data) = splitDataUri(img)
                        parts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().put("mimeType", mime).put("data", data)
                            )
                        )
                    }
                    for (d in m.docs) {
                        parts.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().put("mimeType", d.mime).put("data", d.base64)
                            )
                        )
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", parts))
                }
            }
        }
        root.put("contents", contents)

        if (provider.supportsTools && req.tools.isNotEmpty()) {
            val decls = JSONArray()
            for (t in req.tools) {
                decls.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", JSONObject(t.parametersJsonSchema))
                )
            }
            root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }

        val gen = JSONObject()
        val budget = if (flags.reasoning) budgetFor(req.reasoningLevel) else 0
        gen.put("maxOutputTokens", req.maxTokens + budget)
        if (flags.reasoning) {
            if (req.reasoningLevel != ReasoningLevel.OFF) {
                gen.put(
                    "thinkingConfig",
                    JSONObject().put("thinkingBudget", budget).put("includeThoughts", true)
                )
            } else if (model.contains("flash")) {
                gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            }
        }
        root.put("generationConfig", gen)

        val builder = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("content-type", "application/json")
        applyCustomHeaders(builder, provider)
        val httpRequest = builder.post(root.toString().toRequestBody(jsonMediaType)).build()

        return runCall(httpRequest) { response ->
            if (!response.isSuccessful) throw apiError(response, "Gemini")
            parseGeminiStream(response, provider, onChunk, onReasoning)
        }
    }

    private fun parseGeminiStream(
        response: Response,
        provider: ProviderConfigEntity,
        onChunk: ((String) -> Unit)?,
        onReasoning: ((String) -> Unit)?
    ): LlmResponse {
        val source = response.body?.source() ?: throw ApiException(502, "Gemini returned an empty body")
        val textOut = StringBuilder()
        val reasoningOut = StringBuilder()
        val rawParts = JSONArray()
        val toolCalls = mutableListOf<LlmToolCall>()
        var prompt = 0
        var cached = 0
        var output = 0
        var finish: String? = null
        var gotAny = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            val ev = try { JSONObject(data) } catch (e: Exception) { continue }
            gotAny = true

            val err = ev.optJSONObject("error")
            if (err != null) {
                val code = err.optInt("code", 500).let { if (it in 400..599) it else 500 }
                throw ApiException(code, "Gemini stream error: ${err.optString("message", data)}")
            }
            val um = ev.optJSONObject("usageMetadata")
            if (um != null) {
                prompt = um.optInt("promptTokenCount", prompt)
                cached = um.optInt("cachedContentTokenCount", cached)
                output = um.optInt("candidatesTokenCount") + um.optInt("thoughtsTokenCount")
            }
            val cands = ev.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val fb = ev.optJSONObject("promptFeedback")
                if (fb != null && fb.has("blockReason")) {
                    throw ApiException(400, "Gemini blocked the prompt: ${fb.optString("blockReason")}")
                }
                continue
            }
            val cand = cands.getJSONObject(0)
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.optBoolean("thought", false)) {
                        val t = p.optString("text")
                        if (t.isNotEmpty()) {
                            reasoningOut.append(t)
                            onReasoning?.invoke(t)
                        }
                        continue
                    }
                    if (p.has("functionCall")) {
                        val fc = p.getJSONObject("functionCall")
                        val args = fc.optJSONObject("args") ?: JSONObject()
                        toolCalls.add(LlmToolCall(UUID.randomUUID().toString(), fc.optString("name"), args.toString()))
                        rawParts.put(p)
                        continue
                    }
                    if (p.has("text")) {
                        val t = p.optString("text")
                        if (t.isNotEmpty()) {
                            textOut.append(t)
                            onChunk?.invoke(t)
                            rawParts.put(p)
                        }
                    }
                }
            }
            if (!cand.isNull("finishReason")) finish = cand.optString("finishReason")
        }
        if (!gotAny) throw ApiException(502, "Gemini returned an empty stream")

        return LlmResponse(
            content = textOut.toString(),
            reasoning = reasoningOut.toString().ifEmpty { null },
            toolCalls = toolCalls,
            promptTokens = (prompt - cached).coerceAtLeast(0),
            completionTokens = output,
            cachedTokens = cached,
            finishReason = finish,
            providerUsed = provider.name,
            modelUsed = provider.selectedModel,
            rawContentJson = rawParts.toString(),
            rawFormat = "GEMINI"
        )
    }

    // ---------------------------------------------------------------------------------------
    // Utilities: model list + connection test
    // ---------------------------------------------------------------------------------------

    /** Fetches the model list from the provider's API (/models). Throws on failure. */
    suspend fun fetchModels(provider: ProviderConfigEntity): List<String> {
        val apiKey = KeyStoreManager.decrypt(provider.encryptedApiKey)
        val base = provider.baseUrl.trim().trimEnd('/')
        val format = provider.apiFormat.uppercase()
        val builder = Request.Builder()
        when (format) {
            "ANTHROPIC" -> {
                builder.url("$base/v1/models?limit=100")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
            }
            "GEMINI" -> {
                builder.url("$base/v1beta/models?pageSize=200")
                    .header("x-goog-api-key", apiKey)
            }
            else -> {
                builder.url("$base/models")
                if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
            }
        }
        applyCustomHeaders(builder, provider)
        return runCall(builder.get().build()) { response ->
            if (!response.isSuccessful) throw apiError(response, "Model list")
            val obj = JSONObject(response.body?.string() ?: "{}")
            val out = mutableListOf<String>()
            if (format == "GEMINI") {
                val arr = obj.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var ok = methods == null
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") ok = true
                        }
                    }
                    if (ok) out.add(m.optString("name").removePrefix("models/"))
                }
            } else {
                val arr = obj.optJSONArray("data") ?: obj.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.opt(i)
                    when (item) {
                        is JSONObject -> out.add(item.optString("id").ifEmpty { item.optString("name") })
                        is String -> out.add(item)
                    }
                }
            }
            out.filter { it.isNotBlank() }.sorted()
        }
    }

    /** Returns null on success, otherwise a readable error message. */
    suspend fun testConnectionError(provider: ProviderConfigEntity): String? {
        return try {
            val req = LlmRequest(
                systemPrompt = "",
                messages = listOf(LlmMessage(role = "user", content = "Reply with the single word OK")),
                tools = emptyList(),
                reasoningLevel = ReasoningLevel.OFF,
                maxTokens = 32
            )
            val res = execute(provider, req)
            if (res.content.isNotBlank() || res.completionTokens > 0) null else "Empty response from provider"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    suspend fun testConnection(provider: ProviderConfigEntity): Boolean = testConnectionError(provider) == null
}

class ApiException(
    val statusCode: Int,
    override val message: String,
    val retryAfter: String? = null
) : Exception(message) {
    fun isRateLimit(): Boolean = statusCode == 429
    fun isOverloaded(): Boolean = statusCode == 503 || statusCode == 529 || statusCode == 500 || statusCode == 502 || statusCode == 504
    fun isAuthError(): Boolean = statusCode == 401
    fun isQuotaExceeded(): Boolean = statusCode == 402 || statusCode == 403 ||
        message.contains("quota", ignoreCase = true) ||
        message.contains("billing", ignoreCase = true) ||
        message.contains("credit", ignoreCase = true) ||
        message.contains("insufficient", ignoreCase = true)
    fun retryAfterSeconds(): Long? = retryAfter?.trim()?.toDoubleOrNull()?.toLong()
}
