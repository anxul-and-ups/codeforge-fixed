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

enum class AgentProcessPhase(val label: String) {
    IDLE("IDLE"),
    PLANNING("PLANNING"),
    THINKING("THINKING"),
    DISCOVERING("DISCOVERING"),
    EXECUTING_COMMAND("RUNNING CMD"),
    PATCHING("PATCHING"),
    WAITING_APPROVAL("WAITING INPUT"),
    COMPLETED("DONE"),
    ABORTED("ABORTED"),
    ERROR("ERROR")
}

data class AgentProcessState(
    val phase: AgentProcessPhase = AgentProcessPhase.IDLE,
    val detail: String = "",
    val activeCommand: String? = null,
    val stepIndex: Int = 0,
    val startedAtMs: Long? = null
)

/**
 * Deterministic Surgical Code Patching Engine:
 * - Generates clean unified diffs (`--- a/...`, `+++ b/...`, `@@ ... @@`) for UI diff previews.
 * - Applies unified diff hunks (`apply_patch`) with exact + fuzzy line-offset + whitespace-tolerant matching.
 * - Enforces the No-Full-Rewrite Guard on existing files (> 15 lines).
 */
object SurgicalPatchEngine {
    const val MAX_EXISTING_LINES_FOR_FULL_WRITE = 15
    private val HUNK_HEADER_REGEX = Regex("""^@@\s*-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s*@@.*$""")

    /**
     * Prohibits full-file overwrites via `write_file` when a file already exists and has > 15 lines.
     */
    fun checkFullRewriteAllowed(existingLineCount: Int, filePath: String): String? {
        if (existingLineCount > MAX_EXISTING_LINES_FOR_FULL_WRITE) {
            return "SURGICAL PATCH GUARD: Refusing full-file overwrite on existing file `$filePath` ($existingLineCount lines). " +
                "Full-file rewrites are prohibited to prevent instability. " +
                "Call `read_file` on `$filePath` and apply a surgical diff using `apply_patch`, `edit_file`, `replace_line_range`, or `insert_after_line`."
        }
        return null
    }

    /**
     * Generates a clean unified diff (`--- a/file`, `+++ b/file`, `@@ -s,c +s,c @@`) using LCS.
     */
    fun generateUnifiedDiff(
        filePath: String,
        oldContent: String,
        newContent: String,
        contextLines: Int = 3,
        maxDiffLines: Int = 180
    ): String {
        val oldLines = if (oldContent.isEmpty()) emptyList() else oldContent.replace("\r\n", "\n").lines()
        val newLines = if (newContent.isEmpty()) emptyList() else newContent.replace("\r\n", "\n").lines()
        if (oldLines == newLines) return ""

        // For very large files, fall back to focused prefix/suffix trimming before running DP
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < (oldLines.size - prefix) &&
            suffix < (newLines.size - prefix) &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val midOld = oldLines.subList(prefix, oldLines.size - suffix)
        val midNew = newLines.subList(prefix, newLines.size - suffix)

        data class DiffOp(val kind: Char, val line: String, val oldIdx: Int, val newIdx: Int)
        val ops = mutableListOf<DiffOp>()

        // Add leading unchanged lines
        for (i in 0 until prefix) {
            ops.add(DiffOp(' ', oldLines[i], i + 1, i + 1))
        }

        if (midOld.size.toLong() * midNew.size.toLong() <= 250_000L) {
            // Exact LCS table on the changed middle region
            val m = midOld.size
            val n = midNew.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in m - 1 downTo 0) {
                for (j in n - 1 downTo 0) {
                    dp[i][j] = if (midOld[i] == midNew[j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        max(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }
            var i = 0
            var j = 0
            while (i < m && j < n) {
                if (midOld[i] == midNew[j]) {
                    ops.add(DiffOp(' ', midOld[i], prefix + i + 1, prefix + j + 1))
                    i++; j++
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    ops.add(DiffOp('-', midOld[i], prefix + i + 1, prefix + j + 1))
                    i++
                } else {
                    ops.add(DiffOp('+', midNew[j], prefix + i + 1, prefix + j + 1))
                    j++
                }
            }
            while (i < m) {
                ops.add(DiffOp('-', midOld[i], prefix + i + 1, prefix + j + 1))
                i++
            }
            while (j < n) {
                ops.add(DiffOp('+', midNew[j], prefix + i + 1, prefix + j + 1))
                j++
            }
        } else {
            // Large middle region: emit deletions followed by additions
            midOld.forEachIndexed { idx, l -> ops.add(DiffOp('-', l, prefix + idx + 1, prefix + 1)) }
            midNew.forEachIndexed { idx, l -> ops.add(DiffOp('+', l, prefix + midOld.size + 1, prefix + idx + 1)) }
        }

        // Add trailing unchanged lines
        val tailOldStart = oldLines.size - suffix
        val tailNewStart = newLines.size - suffix
        for (k in 0 until suffix) {
            ops.add(DiffOp(' ', oldLines[tailOldStart + k], tailOldStart + k + 1, tailNewStart + k + 1))
        }

        val changeIndices = ops.indices.filter { ops[it].kind != ' ' }
        if (changeIndices.isEmpty()) return ""

        // Group nearby changes into hunks with contextLines
        val hunks = mutableListOf<IntRange>()
        var hunkStart = max(0, changeIndices.first() - contextLines)
        var hunkEnd = min(ops.size - 1, changeIndices.first() + contextLines)
        for (cIdx in changeIndices.drop(1)) {
            val s = max(0, cIdx - contextLines)
            val e = min(ops.size - 1, cIdx + contextLines)
            if (s <= hunkEnd + 1) {
                hunkEnd = max(hunkEnd, e)
            } else {
                hunks.add(hunkStart..hunkEnd)
                hunkStart = s
                hunkEnd = e
            }
        }
        hunks.add(hunkStart..hunkEnd)

        val sb = StringBuilder()
        sb.append("--- a/").append(filePath).append('\n')
        sb.append("+++ b/").append(filePath).append('\n')
        var emittedLines = 2
        for (range in hunks) {
            val slice = ops.subList(range.first, range.last + 1)
            val oldStart = slice.firstOrNull { it.kind != '+' }?.oldIdx ?: 1
            val newStart = slice.firstOrNull { it.kind != '-' }?.newIdx ?: 1
            val oldCount = slice.count { it.kind != '+' }
            val newCount = slice.count { it.kind != '-' }
            sb.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            emittedLines++
            for (op in slice) {
                if (emittedLines >= maxDiffLines) {
                    sb.append("... (diff truncated)\n")
                    return sb.toString().trimEnd()
                }
                sb.append(op.kind).append(op.line).append('\n')
                emittedLines++
            }
        }
        return sb.toString().trimEnd()
    }

    private data class ParsedHunk(
        val oldStart: Int,
        val expectedOldLines: List<String>,
        val replacementLines: List<String>
    )

    /**
     * Applies a unified diff patch (`@@ -a,b +c,d @@` hunks) to [originalContent].
     * Uses exact hunk position first, then fuzzy line-offset search (±40 lines),
     * then trailing-whitespace-tolerant search so small line shifts never break patching.
     */
    fun applyUnifiedPatch(
        originalContent: String,
        patchText: String,
        filePath: String = "file"
    ): Pair<String, EditOutcome> {
        val usesCrLf = originalContent.contains("\r\n")
        val normalizedOriginal = originalContent.replace("\r\n", "\n")
        val currentLines = normalizedOriginal.lines().toMutableList()
        val rawPatchLines = patchText.replace("\r\n", "\n").lines()

        val hunks = mutableListOf<ParsedHunk>()
        var i = 0
        while (i < rawPatchLines.size) {
            val line = rawPatchLines[i]
            val match = HUNK_HEADER_REGEX.matchEntire(line)
            if (match != null) {
                val oldStart = match.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: 1
                val expectedOld = mutableListOf<String>()
                val replacement = mutableListOf<String>()
                i++
                while (i < rawPatchLines.size && !rawPatchLines[i].startsWith("@@") && !rawPatchLines[i].startsWith("--- ") && !rawPatchLines[i].startsWith("+++ ")) {
                    val hl = rawPatchLines[i]
                    when {
                        hl.startsWith("\\ No newline") -> { /* ignore */ }
                        hl.startsWith("-") -> expectedOld.add(hl.substring(1))
                        hl.startsWith("+") -> replacement.add(hl.substring(1))
                        hl.startsWith(" ") -> {
                            val text = hl.substring(1)
                            expectedOld.add(text)
                            replacement.add(text)
                        }
                        hl.isEmpty() -> {
                            // Some LLMs omit the leading space on blank context lines
                            expectedOld.add("")
                            replacement.add("")
                        }
                        else -> {
                            // Treat unprefixed line inside hunk as context
                            expectedOld.add(hl)
                            replacement.add(hl)
                        }
                    }
                    i++
                }
                if (expectedOld.isNotEmpty() || replacement.isNotEmpty()) {
                    hunks.add(ParsedHunk(oldStart, expectedOld, replacement))
                }
            } else {
                i++
            }
        }

        if (hunks.isEmpty()) {
            return Pair(
                originalContent,
                EditOutcome(
                    success = false,
                    message = "Patch error: No valid unified diff hunks (`@@ -start,count +start,count @@`) found. " +
                        "Provide standard unified diff hunks or use `edit_file` / `replace_line_range`."
                )
            )
        }

        var lineOffset = 0
        var totalAdded = 0
        var totalRemoved = 0
        var strategyUsed = "UNIFIED_EXACT"

        for ((hunkIdx, hunk) in hunks.withIndex()) {
            val targetIdx = (hunk.oldStart - 1 + lineOffset).coerceIn(0, currentLines.size)
            val expected = hunk.expectedOldLines
            val replacement = hunk.replacementLines

            if (expected.isEmpty()) {
                currentLines.addAll(targetIdx, replacement)
                lineOffset += replacement.size
                totalAdded += replacement.size
                continue
            }

            fun matchesAt(pos: Int, trimEnd: Boolean): Boolean {
                if (pos < 0 || pos + expected.size > currentLines.size) return false
                for (k in expected.indices) {
                    val a = currentLines[pos + k]
                    val b = expected[k]
                    if (trimEnd) {
                        if (a.trimEnd() != b.trimEnd()) return false
                    } else {
                        if (a != b) return false
                    }
                }
                return true
            }

            var matchedPos: Int? = null
            if (matchesAt(targetIdx, trimEnd = false)) {
                matchedPos = targetIdx
            } else {
                // Fuzzy search within ±40 lines (exact first, then trimEnd)
                for (delta in 1..40) {
                    if (matchesAt(targetIdx - delta, trimEnd = false)) {
                        matchedPos = targetIdx - delta
                        strategyUsed = "UNIFIED_FUZZY_OFFSET"
                        break
                    }
                    if (matchesAt(targetIdx + delta, trimEnd = false)) {
                        matchedPos = targetIdx + delta
                        strategyUsed = "UNIFIED_FUZZY_OFFSET"
                        break
                    }
                }
            }
            if (matchedPos == null) {
                for (delta in 0..40) {
                    if (matchesAt(targetIdx - delta, trimEnd = true)) {
                        matchedPos = targetIdx - delta
                        strategyUsed = "UNIFIED_WHITESPACE_TOLERANT"
                        break
                    }
                    if (delta > 0 && matchesAt(targetIdx + delta, trimEnd = true)) {
                        matchedPos = targetIdx + delta
                        strategyUsed = "UNIFIED_WHITESPACE_TOLERANT"
                        break
                    }
                }
            }

            if (matchedPos == null) {
                val previewStart = max(0, targetIdx - 3)
                val previewEnd = min(currentLines.size, targetIdx + expected.size + 3)
                val snippet = (previewStart until previewEnd).joinToString("\n") { idx ->
                    "${idx + 1}: ${currentLines[idx]}"
                }
                return Pair(
                    originalContent,
                    EditOutcome(
                        success = false,
                        message = "Patch failed on hunk #${hunkIdx + 1} (expected around line ${hunk.oldStart}).\n" +
                            "Current lines ${previewStart + 1}..$previewEnd in `$filePath`:\n```\n$snippet\n```\n" +
                            "Re-read the file and adjust the hunk context lines."
                    )
                )
            }

            // Apply hunk splice
            for (k in 0 until expected.size) {
                currentLines.removeAt(matchedPos)
            }
            currentLines.addAll(matchedPos, replacement)
            lineOffset += (replacement.size - expected.size)

            // Count actual added/removed lines inside hunk
            val oldSet = expected.groupingBy { it }.eachCount().toMutableMap()
            var hunkAdded = 0
            for (r in replacement) {
                val c = oldSet[r] ?: 0
                if (c > 0) oldSet[r] = c - 1 else hunkAdded++
            }
            val hunkRemoved = oldSet.values.sum()
            totalAdded += hunkAdded
            totalRemoved += hunkRemoved
        }

        val updatedNormalized = currentLines.joinToString("\n")
        val finalOut = if (usesCrLf) updatedNormalized.replace("\n", "\r\n") else updatedNormalized
        val diff = generateUnifiedDiff(filePath, normalizedOriginal, updatedNormalized)
        return Pair(
            finalOut,
            EditOutcome(
                success = true,
                message = "Applied ${hunks.size} surgical hunk(s) to `$filePath` (+$totalAdded -$totalRemoved, strategy=$strategyUsed).",
                linesAdded = totalAdded,
                linesRemoved = totalRemoved,
                diffPreview = diff,
                matchStrategyUsed = strategyUsed,
                newContent = finalOut
            )
        )
    }
}

/**
 * Loop Guard (Section 6.5) — deterministic repeat/stall detector.
 * [shouldBlock] reads state before a call; [recordStep] is called once AFTER the call with the real result.
 */
class LoopGuard {
    private val history = ArrayList<Pair<String, Int>>() // signature -> result hash
    private val seenResultHashes = HashSet<Int>()
    private val editFailuresByFile = HashMap<String, Int>()
    private val forbiddenSignatures = HashSet<String>()
    private var stepsWithoutProgress = 0
    private var consecutiveErrorCount = 0
    private var lastErrorTool: String? = null

    companion object {
        const val MAX_STEPS_WITHOUT_PROGRESS = 14
    }

    fun signature(toolName: String, argsJson: String): String {
        val norm = argsJson.trim().replace(Regex("\\s+"), " ")
        return "$toolName::$norm"
    }

    /** Pre-call check (read only). Returns a message for the model if this exact call is currently blocked. */
    fun shouldBlock(toolName: String, argsJson: String): String? {
        return if (signature(toolName, argsJson) in forbiddenSignatures) {
            "[LOOP GUARD]: `$toolName` with these exact arguments already returned the same result 3 times. " +
                "It is blocked until something changes (edit a file, or use different arguments). Try another approach or call `ask_user`."
        } else null
    }

    fun isForbidden(toolName: String, argsJson: String): Boolean =
        signature(toolName, argsJson) in forbiddenSignatures

    /** Compatibility wrapper returning sealed [LoopGuardDecision] for deterministic unit testing. */
    fun recordAndCheck(
        toolName: String,
        argsJson: String,
        isError: Boolean,
        fileChanged: Boolean,
        newEvidence: Boolean = !isError,
        targetFilePath: String? = null,
        resultText: String = if (isError) "ERR::$argsJson" else "OK::$argsJson"
    ): LoopGuardDecision {
        val d = recordStep(
            toolName = toolName,
            argsJson = argsJson,
            resultText = resultText,
            isError = isError,
            fileChanged = fileChanged,
            filePath = targetFilePath
        )
        return when {
            !d.triggered -> LoopGuardDecision.Continue
            d.forceReadFile != null -> LoopGuardDecision.ForceReReadAndSwitchStrategy(d.systemInjection)
            d.shouldStop -> LoopGuardDecision.StopAndSummarize(d.systemInjection)
            else -> LoopGuardDecision.BlockRepeatCall(d.systemInjection)
        }
    }

    /** Post-call check, called once per executed tool with its real result. */
    fun recordStep(
        toolName: String,
        argsJson: String,
        resultText: String,
        isError: Boolean,
        fileChanged: Boolean,
        filePath: String? = null
    ): GuardDecision {
        val sig = signature(toolName, argsJson)
        val rHash = resultText.take(1500).hashCode()
        history.add(sig to rHash)
        if (history.size > 40) history.removeAt(0)

        // Progress = files changed, or a successful call that produced output we have not seen before.
        val newEvidence = !isError && seenResultHashes.add(rHash)
        if (fileChanged) {
            // The world changed: previously blocked calls (e.g. re-running the build) are valid again.
            forbiddenSignatures.clear()
            history.clear()
            seenResultHashes.clear()
            stepsWithoutProgress = 0
        } else if (newEvidence) {
            stepsWithoutProgress = 0
        } else {
            stepsWithoutProgress++
        }

        if (isError) {
            if (lastErrorTool == toolName) consecutiveErrorCount++ else {
                lastErrorTool = toolName
                consecutiveErrorCount = 1
            }
        } else {
            consecutiveErrorCount = 0
            lastErrorTool = null
        }

        // Check 1: same call with the SAME result 3 times in the recent window
        val identical = history.takeLast(8).count { it.first == sig && it.second == rHash }
        if (identical >= 3) {
            forbiddenSignatures.add(sig)
            return GuardDecision(
                triggered = true,
                activityNote = "Trying a different approach…",
                systemInjection = "[LOOP GUARD]: You called `$toolName` 3 times with the same arguments and got the same result. " +
                    "Do not repeat it. Use the result you already have, try a different approach, or call `ask_user`."
            )
        }

        // Check 2: same file edit failing repeatedly
        if (toolName in setOf("apply_patch", "edit_file", "insert_after_line", "replace_line_range") && filePath != null) {
            if (isError) {
                val fails = (editFailuresByFile[filePath] ?: 0) + 1
                editFailuresByFile[filePath] = fails
                if (fails == 3) {
                    return GuardDecision(
                        triggered = true,
                        forceReadFile = filePath,
                        activityNote = "Re-reading $filePath…",
                        systemInjection = "[LOOP GUARD]: Patching `$filePath` failed 3 times. Call `read_file` on it first to see its latest content, then try once more."
                    )
                } else if (fails >= 5) {
                    return GuardDecision(
                        triggered = true,
                        shouldStop = true,
                        activityNote = "Waiting for your guidance on $filePath",
                        systemInjection = "[LOOP GUARD]: Patching `$filePath` keeps failing. Stop, explain the problem briefly and ask the user."
                    )
                }
            } else {
                editFailuresByFile.remove(filePath)
            }
        }

        // Check 3: repeated errors of the same tool
        if (consecutiveErrorCount >= 3) {
            return GuardDecision(
                triggered = true,
                activityNote = "Changing strategy after tool error…",
                systemInjection = "[LOOP GUARD]: `$toolName` failed $consecutiveErrorCount times in a row. " +
                    "Explain the error briefly and change strategy instead of repeating the failing call."
            )
        }

        // Check 4: long stretch with neither file changes nor new information
        if (stepsWithoutProgress >= MAX_STEPS_WITHOUT_PROGRESS) {
            stepsWithoutProgress = 0
            return GuardDecision(
                triggered = true,
                shouldStop = true,
                activityNote = "Paused: no new progress",
                systemInjection = "[LOOP GUARD]: No file changed and nothing new was learned for $MAX_STEPS_WITHOUT_PROGRESS steps. " +
                    "Summarize where you got to and ask the user how to continue."
            )
        }

        return GuardDecision(triggered = false)
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
