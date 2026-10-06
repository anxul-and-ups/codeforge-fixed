package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppColors

enum class CodeLang { CLIKE, HASH, XML, JSON, MARKDOWN, PLAIN }

object SyntaxHighlighter {

    private val cKeywords = setOf(
        "abstract", "as", "break", "by", "case", "catch", "class", "companion", "const", "constructor", "continue",
        "data", "default", "do", "else", "enum", "extends", "external", "false", "final", "finally", "for", "fun",
        "function", "get", "if", "implements", "import", "in", "init", "inline", "inner", "instanceof", "interface",
        "internal", "is", "lateinit", "let", "lazy", "new", "null", "object", "open", "operator", "out", "override",
        "package", "private", "protected", "public", "return", "sealed", "set", "static", "super", "suspend", "switch",
        "this", "throw", "throws", "true", "try", "typealias", "val", "var", "vararg", "void", "when", "while", "yield",
        "async", "await", "export", "from", "of", "typeof", "undefined", "struct", "impl", "fn", "mut", "use", "pub",
        "plugins", "dependencies", "repositories", "android", "implementation", "api", "kapt", "ksp"
    )
    private val hashKeywords = setOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or",
        "True", "False", "None", "try", "except", "finally", "with", "as", "lambda", "pass", "raise", "yield",
        "echo", "fi", "then", "do", "done", "esac", "function", "export", "true", "false", "null", "on", "jobs",
        "steps", "uses", "run", "name", "runs-on"
    )

    fun languageOf(path: String): CodeLang {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return when {
            ext in setOf("kt", "kts", "java", "js", "ts", "tsx", "jsx", "gradle", "c", "cpp", "h", "hpp", "cs", "swift", "go", "rs", "dart", "php", "scala", "groovy") -> CodeLang.CLIKE
            ext in setOf("py", "sh", "bash", "yml", "yaml", "properties", "toml", "ini", "cfg", "conf", "txt", "gitignore", "pro", "env") || name.startsWith(".") -> CodeLang.HASH
            ext in setOf("xml", "html", "htm", "svg", "xhtml") -> CodeLang.XML
            ext == "json" -> CodeLang.JSON
            ext in setOf("md", "markdown") -> CodeLang.MARKDOWN
            else -> CodeLang.PLAIN
        }
    }

    /** Returns one highlighted AnnotatedString per line. */
    fun highlight(code: String, lang: CodeLang): List<AnnotatedString> {
        val lines = code.split("\n")
        if (lang == CodeLang.PLAIN || lines.size > 6000) {
            return lines.map { AnnotatedString(it.trimEnd('\r')) }
        }
        var inBlock = false      // /* ... */ or <!-- ... -->
        var inTriple = false     // """ ... """
        var inFence = false      // markdown ``` fences
        val out = ArrayList<AnnotatedString>(lines.size)
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            val b = AnnotatedString.Builder(line)
            when (lang) {
                CodeLang.CLIKE, CodeLang.HASH -> {
                    val res = scanCode(line, b, lang == CodeLang.CLIKE, inBlock, inTriple)
                    inBlock = res.first
                    inTriple = res.second
                }
                CodeLang.XML -> inBlock = scanXml(line, b, inBlock)
                CodeLang.JSON -> scanJson(line, b)
                CodeLang.MARKDOWN -> {
                    if (line.trimStart().startsWith("```")) {
                        inFence = !inFence
                        b.addStyle(SpanStyle(color = AppColors.synComment), 0, line.length)
                    } else if (inFence) {
                        b.addStyle(SpanStyle(color = AppColors.synString), 0, line.length)
                    } else if (line.startsWith("#")) {
                        b.addStyle(SpanStyle(color = AppColors.synKeyword, fontWeight = FontWeight.Bold), 0, line.length)
                    } else if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
                        val idx = line.indexOfFirst { it == '-' || it == '*' }
                        if (idx >= 0) b.addStyle(SpanStyle(color = AppColors.synNumber), idx, idx + 1)
                    }
                }
                else -> {}
            }
            out.add(b.toAnnotatedString())
        }
        return out
    }

    private fun scanCode(
        line: String,
        b: AnnotatedString.Builder,
        cLike: Boolean,
        startInBlock: Boolean,
        startInTriple: Boolean
    ): Pair<Boolean, Boolean> {
        var inBlock = startInBlock
        var inTriple = startInTriple
        val n = line.length
        var i = 0
        val keywords = if (cLike) cKeywords else hashKeywords
        fun style(color: Color, from: Int, to: Int) {
            if (to > from) b.addStyle(SpanStyle(color = color), from, to)
        }
        while (i < n) {
            if (inBlock) {
                val end = line.indexOf("*/", i)
                if (end < 0) {
                    style(AppColors.synComment, i, n)
                    return Pair(true, inTriple)
                }
                style(AppColors.synComment, i, end + 2)
                i = end + 2
                inBlock = false
                continue
            }
            if (inTriple) {
                val end = line.indexOf("\"\"\"", i)
                if (end < 0) {
                    style(AppColors.synString, i, n)
                    return Pair(inBlock, true)
                }
                style(AppColors.synString, i, end + 3)
                i = end + 3
                inTriple = false
                continue
            }
            val c = line[i]
            if (cLike && c == '/' && i + 1 < n && line[i + 1] == '/') {
                style(AppColors.synComment, i, n)
                return Pair(inBlock, inTriple)
            }
            if (!cLike && c == '#') {
                style(AppColors.synComment, i, n)
                return Pair(inBlock, inTriple)
            }
            if (cLike && c == '/' && i + 1 < n && line[i + 1] == '*') {
                inBlock = true
                style(AppColors.synComment, i, minOf(i + 2, n))
                i += 2
                continue
            }
            if (c == '"' && line.startsWith("\"\"\"", i)) {
                inTriple = true
                style(AppColors.synString, i, i + 3)
                i += 3
                continue
            }
            if (c == '"' || c == '\'' || (cLike && c == '`')) {
                var j = i + 1
                while (j < n) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == c) break
                    j++
                }
                val end = minOf(j + 1, n)
                style(AppColors.synString, i, end)
                i = end
                continue
            }
            if (c == '@' && cLike && i + 1 < n && line[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                style(AppColors.synAnnotation, i, j)
                i = j
                continue
            }
            if (c.isDigit()) {
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                style(AppColors.synNumber, i, j)
                i = j
                continue
            }
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                val word = line.substring(i, j)
                when {
                    word in keywords -> b.addStyle(SpanStyle(color = AppColors.synKeyword, fontWeight = FontWeight.Medium), i, j)
                    word[0].isUpperCase() && cLike -> style(AppColors.synType, i, j)
                }
                i = j
                continue
            }
            i++
        }
        return Pair(inBlock, inTriple)
    }

    private fun scanXml(line: String, b: AnnotatedString.Builder, startInComment: Boolean): Boolean {
        var inComment = startInComment
        val n = line.length
        var i = 0
        fun style(color: Color, from: Int, to: Int) {
            if (to > from) b.addStyle(SpanStyle(color = color), from, to)
        }
        while (i < n) {
            if (inComment) {
                val end = line.indexOf("-->", i)
                if (end < 0) {
                    style(AppColors.synComment, i, n)
                    return true
                }
                style(AppColors.synComment, i, end + 3)
                i = end + 3
                inComment = false
                continue
            }
            val c = line[i]
            if (c == '<' && line.startsWith("<!--", i)) {
                val end = line.indexOf("-->", i + 4)
                if (end < 0) {
                    style(AppColors.synComment, i, n)
                    return true
                }
                style(AppColors.synComment, i, end + 3)
                i = end + 3
                inComment = false
                continue
            }
            if (c == '<') {
                var j = i + 1
                if (j < n && (line[j] == '/' || line[j] == '?')) j++
                while (j < n && (line[j].isLetterOrDigit() || line[j] == ':' || line[j] == '.' || line[j] == '_' || line[j] == '-')) j++
                style(AppColors.synType, i, j)
                i = j
                // attributes until '>'
                while (i < n && line[i] != '>') {
                    val ch = line[i]
                    if (ch == '"' || ch == '\'') {
                        var k = i + 1
                        while (k < n && line[k] != ch) k++
                        val end = minOf(k + 1, n)
                        style(AppColors.synString, i, end)
                        i = end
                    } else if (ch.isLetter()) {
                        var k = i
                        while (k < n && (line[k].isLetterOrDigit() || line[k] == ':' || line[k] == '.' || line[k] == '_' || line[k] == '-')) k++
                        style(AppColors.synAnnotation, i, k)
                        i = k
                    } else {
                        i++
                    }
                }
                continue
            }
            i++
        }
        return inComment
    }

    private fun scanJson(line: String, b: AnnotatedString.Builder) {
        val n = line.length
        var i = 0
        fun style(color: Color, from: Int, to: Int) {
            if (to > from) b.addStyle(SpanStyle(color = color), from, to)
        }
        while (i < n) {
            val c = line[i]
            if (c == '"') {
                var j = i + 1
                while (j < n) {
                    if (line[j] == '\\') {
                        j += 2
                        continue
                    }
                    if (line[j] == '"') break
                    j++
                }
                val end = minOf(j + 1, n)
                var k = end
                while (k < n && line[k] == ' ') k++
                val isKey = k < n && line[k] == ':'
                style(if (isKey) AppColors.synType else AppColors.synString, i, end)
                i = end
                continue
            }
            if (c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit())) {
                var j = i + 1
                while (j < n && (line[j].isDigit() || line[j] == '.' || line[j] == 'e' || line[j] == 'E')) j++
                style(AppColors.synNumber, i, j)
                i = j
                continue
            }
            if (c.isLetter()) {
                var j = i
                while (j < n && line[j].isLetter()) j++
                val w = line.substring(i, j)
                if (w == "true" || w == "false" || w == "null") style(AppColors.synKeyword, i, j)
                i = j
                continue
            }
            i++
        }
    }
}

/** Scrollable, syntax-highlighted code view with line numbers (wraps long lines). */
@Composable
fun CodeViewer(
    path: String,
    content: String,
    modifier: Modifier = Modifier
) {
    val lang = remember(path) { SyntaxHighlighter.languageOf(path) }
    val dark = AppColors.isDark
    val textColor = AppColors.textPrimary
    // Show plain text immediately; colours are computed in the background so big files never freeze the screen.
    val plain = remember(content) { content.split("\n").map { AnnotatedString(it.trimEnd('\r')) } }
    val lines by produceState(initialValue = plain, content, lang, dark) {
        value = withContext(Dispatchers.Default) { SyntaxHighlighter.highlight(content, lang) }
    }

    Box(modifier = modifier.fillMaxSize().background(AppColors.codeBg)) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines) { index, line ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(
                        text = (index + 1).toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        color = AppColors.textMuted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(34.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = textColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
