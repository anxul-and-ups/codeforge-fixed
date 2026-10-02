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
    val reason: String
)

data class ModelOption(
    val id: String,
    val displayName: String,
    val contextWindow: Int = 128000,
    val supportsVision: Boolean = true
)
