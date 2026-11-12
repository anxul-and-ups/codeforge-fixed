package com.example.domain.model

data class FileNode(
    val name: String,
    val path: String, // relative to project root
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val children: List<FileNode> = emptyList()
)

enum class DiffLineType {
    ADD,
    REMOVE,
    UNCHANGED
}

data class DiffLine(
    val type: DiffLineType,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val text: String
)

data class FileDiff(
    val filePath: String,
    val oldContent: String,
    val newContent: String,
    val isNewFile: Boolean = false,
    val isDeletedFile: Boolean = false,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    val diffLines: List<DiffLine> = emptyList()
)

enum class ReasoningLevel(val displayName: String, val description: String) {
    OFF("Off", "No extended thinking or reasoning"),
    LOW("Low", "Fast reasoning budget (1k tokens / low effort)"),
    MEDIUM("Medium", "Balanced reasoning budget (2k-4k tokens)"),
    HIGH("High", "Deep thinking budget (8k+ tokens / high effort)"),
    AUTO("Auto", "Intelligently adapt based on prompt complexity")
}

data class TokenPricing(
    val modelPattern: String,
    val inputCostPerMillion: Double,
    val outputCostPerMillion: Double
)

data class FailoverEvent(
    val timestamp: Long,
    val fromProvider: String,
    val toProvider: String,
    val reason: String,
    val errorClass: String = "RATE_LIMIT"
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val contextWindow: Int,
    val supportsVision: Boolean = true,
    val supportsTools: Boolean = true
)

/**
 * Exact 9-class error classification for the Fallback Engine (Section 9.2).
 */
enum class ProviderErrorClass(
    val label: String,
    val plainReason: String,
    val suggestedFix: String
) {
    RATE_LIMIT(
        label = "Rate limited",
        plainReason = "Too many requests in a short window (HTTP 429).",
        suggestedFix = "Wait for the Retry-After cooldown or add a secondary API key for automatic round-robin."
    ),
    QUOTA_EXHAUSTED(
        label = "Quota exhausted",
        plainReason = "Your billing credit or daily quota for this key is exhausted.",
        suggestedFix = "Top up credits in the provider dashboard or add another API key."
    ),
    AUTH(
        label = "Invalid API key",
        plainReason = "The server rejected this API key (HTTP 401/403).",
        suggestedFix = "Check that you copied the full key without spaces, or generate a fresh key."
    ),
    MODEL_UNAVAILABLE(
        label = "Model unavailable",
        plainReason = "The selected model was not found, is overloaded (503), or is not allowed for this key.",
        suggestedFix = "Tap 'Refresh models' to fetch the current server model list and pick an active model."
    ),
    CONTEXT_TOO_LONG(
        label = "Context too long",
        plainReason = "The conversation and tool outputs exceeded this model's context window.",
        suggestedFix = "CodeForge will automatically summarize older messages and retry, or switch to a longer-context model."
    ),
    TRANSIENT(
        label = "Server / timeout error",
        plainReason = "Temporary server error (5xx) or connection timeout.",
        suggestedFix = "CodeForge retries up to 2× with backoff before switching to the next fallback model."
    ),
    CONTENT_BLOCKED(
        label = "Safety block",
        plainReason = "The provider's safety filter blocked this prompt or response.",
        suggestedFix = "Rephrase your message to clarify legitimate software development intent."
    ),
    NETWORK_DOWN(
        label = "No internet connection",
        plainReason = "Your phone cannot reach the internet right now.",
        suggestedFix = "Check Wi-Fi or mobile data; CodeForge will pause without penalizing your keys."
    ),
    BAD_REQUEST(
        label = "Malformed request",
        plainReason = "The provider rejected the request format (HTTP 400).",
        suggestedFix = "Check custom headers or base URL in Provider settings."
    )
}

enum class FallbackStrategy(val displayName: String, val subtitle: String) {
    PRIORITY("Priority order", "Use keys and models strictly from top to bottom"),
    ROUND_ROBIN("Round-robin across keys", "Spread requests evenly across healthy keys"),
    CHEAPEST_FIRST("Cheapest first", "Prefer models with the lowest server-reported token price"),
    FASTEST_FIRST("Fastest first", "Prefer keys/models with the lowest measured latency");

    val title: String get() = displayName
}

enum class PermissionLevel(val id: String, val displayName: String, val subtitle: String) {
    ASK_ALWAYS("ASK_ALWAYS", "Ask every time", "Every file write or shell command requires your approval"),
    ASK_RISKY("ASK_RISKY", "Ask for risky only", "Reads & project edits are automatic; deletes, installs & external commands ask"),
    AUTO_PROJECT("AUTO_PROJECT", "Auto in project", "Everything inside the project folder is automatic; system installs still ask");

    val title: String get() = displayName
    val description: String get() = subtitle

    companion object {
        val ASK_EVERY_TIME: PermissionLevel get() = ASK_ALWAYS
        val ASK_FOR_RISKY: PermissionLevel get() = ASK_RISKY
        val AUTO_IN_PROJECT: PermissionLevel get() = AUTO_PROJECT

        fun fromId(id: String?): PermissionLevel =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ASK_RISKY
    }
}
