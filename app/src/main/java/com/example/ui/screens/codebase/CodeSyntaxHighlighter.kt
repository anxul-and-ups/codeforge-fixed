package com.example.ui.screens.codebase

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.ui.theme.AppColors

/**
 * Lightweight, allocation-conscious syntax highlighter and block-folder for CodeForge's
 * virtualized code viewer. Supports Kotlin, Java, JS/TS, Python, HTML, XML, CSS, JSON,
 * YAML, Markdown, SQL, Shell, C/C++, PHP, Dart, Rust, and Go.
 */
object CodeSyntaxHighlighter {

    private val colorKeyword = Color(0xFFFF7B72)
    private val colorType = Color(0xFFFFA657)
    private val colorString = Color(0xFFA5D6FF)
    private val colorNumber = Color(0xFF79C0FF)
    private val colorComment = Color(0xFF7D8590)
    private val colorAnnotation = Color(0xFFD2A8FF)
    private val colorTag = Color(0xFF7EE787)
    private val colorAttr = Color(0xFF79C0FF)
    private val colorSearchBg = Color(0x66D97757)

    private val genericKeywords = setOf(
        "fun", "val", "var", "class", "interface", "object", "data", "sealed", "enum", "override",
        "private", "public", "protected", "internal", "suspend", "return", "if", "else", "when",
        "for", "while", "do", "try", "catch", "finally", "throw", "import", "package", "null",
        "true", "false", "this", "super", "companion", "lateinit", "by", "in", "is", "as", "out",
        "function", "const", "let", "async", "await", "export", "default", "from", "new", "switch",
        "case", "break", "continue", "typeof", "void", "static", "final", "extends", "implements",
        "def", "elif", "pass", "lambda", "with", "yield", "None", "True", "False", "and", "or", "not",
        "struct", "typedef", "include", "namespace", "using", "virtual", "template", "typename",
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
        "TABLE", "JOIN", "LEFT", "RIGHT", "INNER", "ON", "GROUP", "ORDER", "BY", "LIMIT", "AS",
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "join", "echo", "fi", "then", "done", "esac", "local", "fn", "mut", "pub", "impl",
        "trait", "match", "use", "mod", "crate", "go", "defer", "chan", "func", "type", "range"
    )

    private val tokenRegex = Regex(
        """(//.*$|#.*$|<!--.*?-->|/\*.*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|@[A-Za-z_][A-Za-z0-9_]*|</?[A-Za-z][A-Za-z0-9:_-]*|\b0x[0-9a-fA-F]+\b|\b\d+(?:\.\d+)?[fFdDlL]?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)"""
    )

    fun detectLanguageLabel(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "Kotlin"
            "java" -> "Java"
            "js", "mjs", "cjs" -> "JavaScript"
            "ts" -> "TypeScript"
            "jsx" -> "JSX"
            "tsx" -> "TSX"
            "html", "htm" -> "HTML"
            "css", "scss", "less" -> "CSS"
            "json" -> "JSON"
            "xml" -> "XML"
            "md", "markdown" -> "Markdown"
            "py" -> "Python"
            "c", "h" -> "C"
            "cpp", "cc", "cxx", "hpp" -> "C++"
            "php" -> "PHP"
            "sql" -> "SQL"
            "yml", "yaml" -> "YAML"
            "sh", "bash", "zsh" -> "Shell"
            "dart" -> "Dart"
            "rs" -> "Rust"
            "go" -> "Go"
            "gradle" -> "Gradle"
            "properties", "env", "ini", "toml" -> "Config"
            else -> ext.uppercase().ifBlank { "Plain Text" }
        }
    }

    fun highlightLine(
        line: String,
        fileName: String,
        searchQuery: String = ""
    ): AnnotatedString {
        if (line.isEmpty()) return AnnotatedString("")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isHashCommentLang = ext in setOf("py", "sh", "bash", "zsh", "yml", "yaml", "toml", "properties", "env", "rb")
        val isMarkupLang = ext in setOf("html", "htm", "xml", "svg")
        val isMarkdown = ext in setOf("md", "markdown")
        val isJson = ext == "json"

        return buildAnnotatedString {
            append(line)
            addStyle(SpanStyle(color = AppColors.textPrimary), 0, line.length)

            if (isMarkdown) {
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("#") -> addStyle(
                        SpanStyle(color = colorType, fontWeight = FontWeight.Bold),
                        0,
                        line.length
                    )
                    trimmed.startsWith(">") -> addStyle(
                        SpanStyle(color = colorComment, fontStyle = FontStyle.Italic),
                        0,
                        line.length
                    )
                    trimmed.startsWith("```") -> addStyle(
                        SpanStyle(color = colorAnnotation),
                        0,
                        line.length
                    )
                }
            } else {
                for (match in tokenRegex.findAll(line)) {
                    val value = match.value
                    val start = match.range.first
                    val end = match.range.last + 1

                    val style: SpanStyle? = when {
                        value.startsWith("//") || value.startsWith("/*") || value.startsWith("<!--") ->
                            SpanStyle(color = colorComment, fontStyle = FontStyle.Italic)
                        value.startsWith("#") && isHashCommentLang ->
                            SpanStyle(color = colorComment, fontStyle = FontStyle.Italic)
                        value.startsWith("\"") || value.startsWith("'") || value.startsWith("`") -> {
                            val afterColon = isJson && line.substring(end).trimStart().startsWith(":")
                            if (afterColon) SpanStyle(color = colorAttr) else SpanStyle(color = colorString)
                        }
                        value.startsWith("@") ->
                            SpanStyle(color = colorAnnotation)
                        (value.startsWith("<") || value.startsWith("</")) && isMarkupLang ->
                            SpanStyle(color = colorTag, fontWeight = FontWeight.Medium)
                        value.first().isDigit() ->
                            SpanStyle(color = colorNumber)
                        value in genericKeywords ->
                            SpanStyle(color = colorKeyword, fontWeight = FontWeight.Medium)
                        value.length > 1 && value.first().isUpperCase() && value.any { it.isLowerCase() } ->
                            SpanStyle(color = colorType)
                        else -> null
                    }
                    if (style != null) {
                        addStyle(style, start, end)
                    }
                }
            }

            // Overlay search query highlight matches
            if (searchQuery.isNotBlank()) {
                val lowerLine = line.lowercase()
                val lowerQ = searchQuery.lowercase()
                var idx = lowerLine.indexOf(lowerQ)
                while (idx >= 0) {
                    addStyle(
                        SpanStyle(
                            background = colorSearchBg,
                            color = AppColors.textPrimary,
                            fontWeight = FontWeight.Bold
                        ),
                        idx,
                        (idx + lowerQ.length).coerceAtMost(line.length)
                    )
                    idx = lowerLine.indexOf(lowerQ, idx + lowerQ.length.coerceAtLeast(1))
                }
            }
        }
    }

    /**
     * Computes foldable multi-line brace blocks (`startLine -> endLine`, 0-based indices)
     * so users can fold/unfold classes, functions, JSON objects, and CSS rules smoothly.
     */
    fun computeFoldRanges(lines: List<String>): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        val stack = ArrayDeque<Int>()
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) continue
            var opens = 0
            var closes = 0
            var inStr: Char? = null
            var escaped = false
            for (ch in trimmed) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inStr != null) {
                    escaped = true
                    continue
                }
                if (inStr != null) {
                    if (ch == inStr) inStr = null
                    continue
                }
                when (ch) {
                    '"', '\'' -> inStr = ch
                    '{' -> opens++
                    '}' -> closes++
                }
            }
            repeat(closes) {
                if (stack.isNotEmpty()) {
                    val start = stack.removeLast()
                    if (i - start >= 2 && !result.containsKey(start)) {
                        result[start] = i
                    }
                }
            }
            repeat(opens) {
                stack.addLast(i)
            }
        }
        return result
    }
}
