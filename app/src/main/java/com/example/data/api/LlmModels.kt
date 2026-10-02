package com.example.data.api

import com.example.domain.model.ReasoningLevel

data class LlmMessage(
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<LlmToolCall>? = null,
    val imagesBase64: List<String> = emptyList(), // data:image/png;base64,...
    /** Raw provider content (Anthropic blocks incl. thinking signatures / Gemini parts) so tool loops stay valid. */
    val rawContentJson: String? = null,
    val rawFormat: String? = null // "ANTHROPIC" or "GEMINI"
)

data class LlmTool(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class LlmRequest(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    val tools: List<LlmTool>,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int = 8192
)

data class LlmResponse(
    val content: String,
    val reasoning: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val promptTokens: Int = 0, // non-cached input tokens
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val finishReason: String? = null,
    val providerUsed: String = "",
    val modelUsed: String = "",
    val rawContentJson: String? = null,
    val rawFormat: String? = null
)

sealed class LlmStreamEvent {
    data class TextChunk(val text: String) : LlmStreamEvent()
    data class ReasoningChunk(val text: String) : LlmStreamEvent()
    data class ToolCallChunk(val toolCall: LlmToolCall) : LlmStreamEvent()
    data class Completed(val response: LlmResponse) : LlmStreamEvent()
    data class Failover(val fromProvider: String, val toProvider: String, val reason: String) : LlmStreamEvent()
    data class Error(val message: String, val isTerminal: Boolean = false) : LlmStreamEvent()
}
