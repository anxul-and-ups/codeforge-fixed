package com.example.data.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 2 — Robust File Edit Engine, Loop Guard & Prompts (Sections 6.4, 6.5, 6.8).
 */

data class EditOutcome(
    val success: Boolean,
    val message: String,
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    val diffPreview: String = "",
    val matchStrategyUsed: String = "EXACT",
    val newContent: String? = null
) {
    val addedLines: Int get() = linesAdded
    val removedLines: Int get() = linesRemoved
    val strategyUsed: String get() = matchStrategyUsed
}

object FileEditEngine {

    private val LINE_NUMBER_PREFIX_REGEX = Regex("""^\s*\d+\t""")

    /**
     * Strips accidental line-number prefixes (`"   12\t"`) if the model copied them from `read_file`.
     */
    fun stripLineNumberPrefixes(text: String): String {
        val lines = text.lines()
        if (lines.isNotEmpty() && lines.count { LINE_NUMBER_PREFIX_REGEX.containsMatchIn(it) } > lines.size / 2) {
            return lines.joinToString("\n") { it.replaceFirst(LINE_NUMBER_PREFIX_REGEX, "") }
        }
        return text
    }

    /**
     * Multi-tier edit engine (Section 6.4):
     * 1. Exact match
     * 2. CRLF/LF normalized + trailing whitespace normalized match
     * 3. Indentation-insensitive line-by-line match
     * 4. On failure: returns reason (not found / found N times) + nearest fuzzy matching region with line numbers
     *    and exact current content of that region so the model can self-correct on the very next turn.
     */
    fun applyStructuredEdit(
        content: String,
        rawOldText: String,
        rawNewText: String,
        replaceAll: Boolean = false,
        insertAfterLine: Int? = null,
        replaceStartLine: Int? = null,
        replaceEndLine: Int? = null
    ): Pair<String, EditOutcome> {
        val usesCrLf = content.contains("\r\n")
        val normalizedContent = content.replace("\r\n", "\n")
        val contentLines = normalizedContent.lines()

        // Line-based operations if requested
        if (insertAfterLine != null) {
            val idx = insertAfterLine.coerceIn(0, contentLines.size)
            val newLines = stripLineNumberPrefixes(rawNewText.replace("\r\n", "\n")).lines()
            val updatedLines = contentLines.subList(0, idx) + newLines + contentLines.subList(idx, contentLines.size)
            val finalStr = updatedLines.joinToString(if (usesCrLf) "\r\n" else "\n")
            return Pair(
                finalStr,
                EditOutcome(
                    success = true,
                    message = "Inserted ${newLines.size} line(s) after line $idx.",
                    linesAdded = newLines.size,
                    linesRemoved = 0,
                    diffPreview = "+${newLines.size} −0",
                    matchStrategyUsed = "INSERT_AFTER_LINE"
                )
            )
        }

        if (replaceStartLine != null && replaceEndLine != null && replaceStartLine >= 1 && replaceEndLine >= replaceStartLine) {
            val startIdx = (replaceStartLine - 1).coerceIn(0, contentLines.size)
            val endIdx = replaceEndLine.coerceIn(startIdx, contentLines.size)
            val newLines = stripLineNumberPrefixes(rawNewText.replace("\r\n", "\n")).lines()
            val removedCount = endIdx - startIdx
            val updatedLines = contentLines.subList(0, startIdx) + newLines + contentLines.subList(endIdx, contentLines.size)
            val finalStr = updatedLines.joinToString(if (usesCrLf) "\r\n" else "\n")
            return Pair(
                finalStr,
                EditOutcome(
                    success = true,
                    message = "Replaced lines $replaceStartLine..$replaceEndLine (+${newLines.size} −$removedCount).",
                    linesAdded = newLines.size,
                    linesRemoved = removedCount,
                    diffPreview = "+${newLines.size} −$removedCount",
                    matchStrategyUsed = "REPLACE_LINE_RANGE"
                )
            )
        }

        val oldClean = stripLineNumberPrefixes(rawOldText.replace("\r\n", "\n"))
        val newClean = stripLineNumberPrefixes(rawNewText.replace("\r\n", "\n"))

        if (oldClean.isEmpty()) {
            return Pair(content, EditOutcome(false, "Error: old_text must not be empty. Pass the exact lines to replace."))
        }

        // Tier 1: Exact match
        val exactCount = countOccurrences(normalizedContent, oldClean)
        if (exactCount == 1 || (replaceAll && exactCount > 0)) {
            val updated = if (replaceAll) {
                normalizedContent.replace(oldClean, newClean)
            } else {
                normalizedContent.replaceFirst(oldClean, newClean)
            }
            val added = newClean.lines().size * (if (replaceAll) exactCount else 1)
            val removed = oldClean.lines().size * (if (replaceAll) exactCount else 1)
            val finalOut = if (usesCrLf) updated.replace("\n", "\r\n") else updated
            return Pair(
                finalOut,
                EditOutcome(
                    success = true,
                    message = "Edited successfully (+$added −$removed).",
                    linesAdded = added,
                    linesRemoved = removed,
                    diffPreview = "+$added −$removed",
                    matchStrategyUsed = "EXACT"
                )
            )
        }

        if (exactCount > 1 && !replaceAll) {
            val matchLines = findMatchingLineNumbers(contentLines, oldClean.lines().first())
            return Pair(
                content,
                EditOutcome(
                    success = false,
                    message = "Edit failed: old_text matched $exactCount times (at lines ${matchLines.joinToString(", ")}). " +
                        "Include 2-3 more surrounding lines in old_text so it matches uniquely, or set replace_all=true."
                )
            )
        }

        // Tier 2: Trailing-whitespace tolerant line match
        val oldLines = oldClean.lines()
        val newLines = newClean.lines()
        val rtrimMatches = findSubsequenceMatches(contentLines, oldLines) { a, b -> a.trimEnd() == b.trimEnd() }
        if (rtrimMatches.size == 1) {
            val startIdx = rtrimMatches[0]
            val updatedLines = contentLines.subList(0, startIdx) + newLines + contentLines.subList(startIdx + oldLines.size, contentLines.size)
            val finalOut = updatedLines.joinToString(if (usesCrLf) "\r\n" else "\n")
            return Pair(
                finalOut,
                EditOutcome(
                    success = true,
                    message = "Edited successfully using trailing-whitespace tolerance at line ${startIdx + 1} (+${newLines.size} −${oldLines.size}).",
                    linesAdded = newLines.size,
                    linesRemoved = oldLines.size,
                    diffPreview = "+${newLines.size} −${oldLines.size}",
                    matchStrategyUsed = "WHITESPACE_TOLERANT"
                )
            )
        }

        // Tier 3: Indentation-insensitive match (preserves relative indentation)
        val trimMatches = findSubsequenceMatches(contentLines, oldLines) { a, b -> a.trim() == b.trim() }
        if (trimMatches.size == 1) {
            val startIdx = trimMatches[0]
            val existingIndent = contentLines[startIdx].takeWhile { it == ' ' || it == '\t' }
            val oldBaseIndent = oldLines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
            val adjustedNewLines = newLines.map { line ->
                if (line.isBlank()) ""
                else if (oldBaseIndent.isNotEmpty() && line.startsWith(oldBaseIndent)) {
                    existingIndent + line.removePrefix(oldBaseIndent)
                } else {
                    line
                }
            }
            val updatedLines = contentLines.subList(0, startIdx) + adjustedNewLines + contentLines.subList(startIdx + oldLines.size, contentLines.size)
            val finalOut = updatedLines.joinToString(if (usesCrLf) "\r\n" else "\n")
            return Pair(
                finalOut,
                EditOutcome(
                    success = true,
                    message = "Edited successfully using indentation-insensitive match at line ${startIdx + 1} (+${newLines.size} −${oldLines.size}).",
                    linesAdded = newLines.size,
                    linesRemoved = oldLines.size,
                    diffPreview = "+${newLines.size} −${oldLines.size}",
                    matchStrategyUsed = "INDENT_INSENSITIVE"
                )
            )
        }

        // Match failed: locate nearest fuzzy region and return its exact lines so the model can fix immediately
        val nearest = findNearestFuzzyRegion(contentLines, oldLines)
        return Pair(
            content,
            EditOutcome(
                success = false,
                message = "Edit failed: old_text was not found.\n" +
                    "Nearest match in file (lines ${nearest.first}..${nearest.second}):\n" +
                    "```\n${nearest.third}\n```\n" +
                    "Copy the exact lines from the snippet above into old_text and retry."
            )
        )
    }

    fun applyEdit(
        content: String,
        oldStr: String,
        newStr: String,
        replaceAll: Boolean = false
    ): EditOutcome {
        val (updated, outcome) = applyStructuredEdit(
            content = content,
            rawOldText = oldStr,
            rawNewText = newStr,
            replaceAll = replaceAll
        )
        return outcome.copy(newContent = if (outcome.success) updated else null)
    }

    fun insertAfterLine(content: String, line: Int, contentToInsert: String): EditOutcome {
        val (updated, outcome) = applyStructuredEdit(
            content = content,
            rawOldText = "",
            rawNewText = contentToInsert,
            insertAfterLine = line
        )
        return outcome.copy(newContent = if (outcome.success) updated else null)
    }

    fun replaceLineRange(content: String, startLine: Int, endLine: Int, replacementContent: String): EditOutcome {
        val (updated, outcome) = applyStructuredEdit(
            content = content,
            rawOldText = "",
            rawNewText = replacementContent,
            replaceStartLine = startLine,
            replaceEndLine = endLine
        )
        return outcome.copy(newContent = if (outcome.success) updated else null)
    }

    private fun countOccurrences(src: String, target: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = src.indexOf(target, idx)
            if (found == -1) break
            count++
            idx = found + target.length
        }
        return count
    }

    private fun findMatchingLineNumbers(lines: List<String>, firstTargetLine: String): List<Int> {
        val t = firstTargetLine.trim()
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        for (i in lines.indices) {
            if (lines[i].contains(t)) out.add(i + 1)
        }
        return out.take(8)
    }

    private fun findSubsequenceMatches(
        haystack: List<String>,
        needle: List<String>,
        predicate: (String, String) -> Boolean
    ): List<Int> {
        if (needle.isEmpty() || needle.size > haystack.size) return emptyList()
        val matches = mutableListOf<Int>()
        for (i in 0..(haystack.size - needle.size)) {
            var allMatch = true
            for (j in needle.indices) {
                if (!predicate(haystack[i + j], needle[j])) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) matches.add(i)
        }
        return matches
    }

    private fun findNearestFuzzyRegion(contentLines: List<String>, targetLines: List<String>): Triple<Int, Int, String> {
        if (contentLines.isEmpty()) return Triple(1, 1, "(empty file)")
        val windowSize = targetLines.size.coerceIn(1, contentLines.size)
        val targetTokens = targetLines.map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        var bestStart = 0
        var bestScore = -1
        for (i in 0..(contentLines.size - windowSize)) {
            var score = 0
            for (j in 0 until windowSize) {
                val cand = contentLines[i + j].trim().lowercase()
                if (cand.isEmpty()) continue
                for (tok in targetTokens) {
                    if (cand == tok) score += 5
                    else if (cand.contains(tok) || tok.contains(cand)) score += 2
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestStart = i
            }
        }
        val from = max(0, bestStart - 2)
        val to = min(contentLines.size, bestStart + windowSize + 2)
        val snippet = (from until to).joinToString("\n") { idx ->
            "${idx + 1}: ${contentLines[idx]}"
        }
        return Triple(from + 1, to, snippet)
    }
}

/**
 * Loop Guard (Section 6.5):
 * - Tracks rolling window of tool calls (name + normalized args + result hash).
 * - Blocks same call repeated 3 times with the same result.
 * - Forces re-read or `ask_user` if the same file edit fails 3 times.
 * - Stops after 8 steps with no progress.
 * - Escalates after 2 consecutive tool errors of the same type.
 */
class LoopGuard {
    data class CallRecord(
        val toolName: String,
        val normalizedArgs: String,
        val resultHash: Int,
        val isError: Boolean,
        val filePath: String?
    )

    private val history = ArrayList<CallRecord>()
    private val editFailuresByFile = HashMap<String, Int>()
    private val forbiddenSignatures = HashSet<String>()
    private var stepsWithoutProgress = 0
    private var consecutiveErrorCount = 0
    private var lastErrorTool: String? = null

    fun signature(toolName: String, argsJson: String): String {
        val norm = argsJson.trim().replace(Regex("\\s+"), " ")
        return "$toolName::$norm"
    }

    fun isForbidden(toolName: String, argsJson: String): Boolean {
        return signature(toolName, argsJson) in forbiddenSignatures
    }

    fun recordStep(
        toolName: String,
        argsJson: String,
        resultText: String,
        isError: Boolean,
        madeProgress: Boolean,
        filePath: String? = null
    ): GuardDecision {
        val sig = signature(toolName, argsJson)
        val rHash = resultText.take(600).hashCode()
        val rec = CallRecord(toolName, sig, rHash, isError, filePath)
        history.add(rec)

        if (madeProgress) {
            stepsWithoutProgress = 0
        } else {
            stepsWithoutProgress++
        }

        if (isError) {
            if (lastErrorTool == toolName) {
                consecutiveErrorCount++
            } else {
                lastErrorTool = toolName
                consecutiveErrorCount = 1
            }
        } else {
            consecutiveErrorCount = 0
            lastErrorTool = null
        }

        // Check 1: Same call repeated 3 times with same result
        val identicalCount = history.takeLast(8).count { it.normalizedArgs == sig && it.resultHash == rHash }
        if (identicalCount >= 3) {
            forbiddenSignatures.add(sig)
            return GuardDecision(
                triggered = true,
                shouldStop = false,
                activityNote = "Trying a different approach…",
                systemInjection = "[LOOP GUARD]: You have repeated `$toolName` 3 times with the exact same result. " +
                    "This exact call is now temporarily blocked. Try a different approach or call `ask_user`."
            )
        }

        // Check 2: Same file edit failing 3 times
        if ((toolName == "edit_file" || toolName == "fs_edit") && filePath != null) {
            if (isError) {
                val fails = (editFailuresByFile[filePath] ?: 0) + 1
                editFailuresByFile[filePath] = fails
                if (fails == 3) {
                    return GuardDecision(
                        triggered = true,
                        shouldStop = false,
                        forceReadFile = filePath,
                        activityNote = "Re-reading $filePath to align line numbers…",
                        systemInjection = "[LOOP GUARD]: Editing `$filePath` has failed 3 times. " +
                            "You MUST call `read_file` on `$filePath` first to see its latest content, then try once more."
                    )
                } else if (fails >= 4) {
                    return GuardDecision(
                        triggered = true,
                        shouldStop = true,
                        activityNote = "Waiting for your guidance on $filePath",
                        systemInjection = "[LOOP GUARD]: Editing `$filePath` failed after re-reading. Stopping to ask the user."
                    )
                }
            } else {
                editFailuresByFile.remove(filePath)
            }
        }

        // Check 3: 2 consecutive errors of the same type
        if (consecutiveErrorCount >= 2) {
            return GuardDecision(
                triggered = true,
                shouldStop = false,
                activityNote = "Changing strategy after tool error…",
                systemInjection = "[LOOP GUARD]: `$toolName` failed $consecutiveErrorCount times in a row. " +
                    "Explain the error briefly and change strategy instead of repeating the failing call."
            )
        }

        // Check 4: No progress for 8 steps
        if (stepsWithoutProgress >= 8) {
            return GuardDecision(
                triggered = true,
                shouldStop = true,
                activityNote = "Paused after 8 steps without new progress",
                systemInjection = "[LOOP GUARD]: No new files changed or new information discovered in 8 steps. Summarize where you got to and ask the user."
            )
        }

        return GuardDecision(triggered = false)
    }

    fun recordAndCheck(
        toolName: String,
        argsJson: String,
        isError: Boolean,
        fileChanged: Boolean,
        newEvidence: Boolean,
        targetFilePath: String? = null
    ): LoopGuardDecision {
        val d = recordStep(
            toolName = toolName,
            argsJson = argsJson,
            resultText = argsJson,
            isError = isError,
            madeProgress = fileChanged || newEvidence,
            filePath = targetFilePath
        )
        return when {
            !d.triggered -> LoopGuardDecision.Continue
            d.forceReadFile != null -> LoopGuardDecision.ForceReReadAndSwitchStrategy(d.systemInjection)
            d.shouldStop -> LoopGuardDecision.StopAndSummarize(d.systemInjection)
            else -> LoopGuardDecision.BlockRepeatCall(d.systemInjection)
        }
    }
}

sealed class LoopGuardDecision {
    object Continue : LoopGuardDecision()
    data class BlockRepeatCall(val messageForModel: String) : LoopGuardDecision()
    data class ForceReReadAndSwitchStrategy(val messageForModel: String) : LoopGuardDecision()
    data class StopAndSummarize(val reason: String) : LoopGuardDecision()
}

data class GuardDecision(
    val triggered: Boolean,
    val shouldStop: Boolean = false,
    val forceReadFile: String? = null,
    val activityNote: String = "",
    val systemInjection: String = ""
)

/**
 * Per-Project `AGENT.md` Memory Manager (Section 6.6).
 */
object ProjectMemoryManager {

    fun ensureAgentMd(projectRoot: File, projectName: String): File {
        projectRoot.mkdirs()
        val agentMd = File(projectRoot, "AGENT.md")
        if (!agentMd.exists()) {
            agentMd.writeText(
                """
                # AGENT.md — Project Memory for $projectName
                
                ## Project Goal
                - Primary goal: Build and maintain $projectName inside ~/forge/projects/${projectRoot.name}
                
                ## Tech Stack & Structure
                - Auto-discovered as the agent works on this project.
                
                ## How to Run / Test
                - Configure in Project Settings or update here when discovered.
                
                ## Conventions & Decisions
                - Keep edits minimal, atomic, and well-tested.
                
                ## Known Issues & Open Tasks
                - None recorded yet.
                """.trimIndent() + "\n"
            )
        }
        return agentMd
    }

    fun readAgentMd(projectRoot: File, projectName: String): String {
        return try {
            val file = ensureAgentMd(projectRoot, projectName)
            file.readText().take(4000)
        } catch (e: Exception) {
            ""
        }
    }

    fun appendLearningToAgentMd(projectRoot: File, projectName: String, note: String) {
        try {
            val file = ensureAgentMd(projectRoot, projectName)
            val current = file.readText()
            if (!current.contains(note.trim())) {
                file.appendText("\n- ${note.trim()}\n")
            }
        } catch (_: Exception) {}
    }
}
