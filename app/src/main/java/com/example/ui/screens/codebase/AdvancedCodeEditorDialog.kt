package com.example.ui.screens.codebase

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.screens.chat.CodeFileViewerState
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

private data class VisibleCodeRow(
    val lineIndex: Int,
    val text: String,
    val isFoldHeader: Boolean,
    val isFolded: Boolean,
    val hiddenLineCount: Int = 0
)

/**
 * High-performance virtualized Codebase File Viewer & Inline Editor.
 * - Zero scrolling lag: removes shared horizontalScroll contention across LazyColumn rows.
 * - Multi-file tab bar with unsaved modification indicators.
 * - Multi-language syntax highlighting + collapsible code folding + line numbers.
 * - Word wrap toggle + smooth horizontal & vertical scrolling + selectable text.
 * - Built-in Search + Find & Replace across the open file.
 */
@Composable
fun AdvancedCodeEditorDialog(
    state: CodeFileViewerState,
    openTabs: List<CodeFileViewerState>,
    onSelectTab: (CodeFileViewerState) -> Unit,
    onCloseTab: (CodeFileViewerState) -> Unit,
    onSave: (String) -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val tabKey = "${state.projectId}::${state.path}"

    // Track per-tab unsaved drafts so switching tabs never loses user edits
    val draftsByTab = remember { mutableStateMapOf<String, String>() }
    var isEditing by remember(tabKey) { mutableStateOf(false) }
    var wordWrap by remember { mutableStateOf(true) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showReplaceRow by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var activeMatchIdx by remember { mutableIntStateOf(0) }
    var foldedStarts by remember(tabKey) { mutableStateOf(setOf<Int>()) }

    val draftText = draftsByTab[tabKey] ?: state.content
    LaunchedEffect(tabKey, state.content) {
        if (!draftsByTab.containsKey(tabKey)) {
            draftsByTab[tabKey] = state.content
        }
    }
    val hasUnsaved = draftText != state.content
    val displayText = if (isEditing) draftText else draftText
    val lines = remember(displayText) { displayText.lines() }
    val foldRanges = remember(lines) {
        if (lines.size <= 4000) CodeSyntaxHighlighter.computeFoldRanges(lines) else emptyMap()
    }
    val languageLabel = remember(state.fileName) { CodeSyntaxHighlighter.detectLanguageLabel(state.fileName) }

    val visibleRows = remember(lines, foldRanges, foldedStarts) {
        val out = ArrayList<VisibleCodeRow>(lines.size)
        var i = 0
        while (i < lines.size) {
            val foldEnd = foldRanges[i]
            val isFolded = foldEnd != null && i in foldedStarts
            if (isFolded && foldEnd != null) {
                out.add(
                    VisibleCodeRow(
                        lineIndex = i,
                        text = lines[i],
                        isFoldHeader = true,
                        isFolded = true,
                        hiddenLineCount = (foldEnd - i).coerceAtLeast(1)
                    )
                )
                i = foldEnd + 1
            } else {
                out.add(
                    VisibleCodeRow(
                        lineIndex = i,
                        text = lines[i],
                        isFoldHeader = foldEnd != null,
                        isFolded = false
                    )
                )
                i++
            }
        }
        out
    }

    val matchRowIndices = remember(visibleRows, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else visibleRows.mapIndexedNotNull { idx, row ->
            if (row.text.contains(searchQuery, ignoreCase = true)) idx else null
        }
    }
    val listState = rememberLazyListState()

    fun jumpToMatch(targetIdx: Int) {
        if (matchRowIndices.isEmpty()) return
        val wrapped = ((targetIdx % matchRowIndices.size) + matchRowIndices.size) % matchRowIndices.size
        activeMatchIdx = wrapped
        val rowIndex = matchRowIndices[wrapped]
        scope.launch { listState.animateScrollToItem(rowIndex) }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            color = AppColors.bg,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.fileName,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (hasUnsaved) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(AppColors.accent)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AppColors.surfaceAlt
                            ) {
                                Text(
                                    text = languageLabel,
                                    fontSize = 10.sp,
                                    color = AppColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${state.path}  ·  ${lines.size} lines",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!state.isLoading && state.error == null) {
                        // Search / Find & Replace toggle
                        IconButton(
                            onClick = { showSearchBar = !showSearchBar },
                            modifier = Modifier.size(36.dp).testTag("code_editor_search_toggle")
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search in file",
                                tint = if (showSearchBar) AppColors.accent else AppColors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // Word wrap toggle
                        IconButton(
                            onClick = { wordWrap = !wordWrap },
                            modifier = Modifier.size(36.dp).testTag("code_editor_wrap_toggle")
                        ) {
                            Icon(
                                Icons.Default.WrapText,
                                contentDescription = "Toggle word wrap",
                                tint = if (wordWrap) AppColors.accent else AppColors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // Read / Edit switch
                        IconButton(
                            onClick = { isEditing = !isEditing },
                            modifier = Modifier.size(36.dp).testTag("code_editor_edit_button")
                        ) {
                            Icon(
                                imageVector = if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                                contentDescription = if (isEditing) "View syntax highlighted" else "Edit file",
                                tint = if (isEditing) AppColors.ok else AppColors.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // Save button (shown when editing or has unsaved changes)
                        if (isEditing || hasUnsaved) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (hasUnsaved) AppColors.accent else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onSave(draftText)
                                        draftsByTab[tabKey] = draftText
                                        isEditing = false
                                    }
                                    .testTag("code_editor_save_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Save,
                                        contentDescription = "Save",
                                        tint = if (hasUnsaved) AppColors.onAccent else AppColors.textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Save",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (hasUnsaved) AppColors.onAccent else AppColors.textSecondary
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(draftText)) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy all", tint = AppColors.textSecondary, modifier = Modifier.size(17.dp))
                            }
                            IconButton(
                                onClick = onDownload,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = AppColors.textSecondary, modifier = Modifier.size(17.dp))
                            }
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close editor", tint = AppColors.textPrimary, modifier = Modifier.size(19.dp))
                    }
                }

                // Multi-file Open Tabs Strip
                if (openTabs.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.surfaceAlt)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (tab in openTabs) {
                            val tKey = "${tab.projectId}::${tab.path}"
                            val active = tab.projectId == state.projectId && tab.path == state.path
                            val tabModified = (draftsByTab[tKey]?.let { it != tab.content } == true) || tab.modifiedState != null
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (active) AppColors.surface else Color.Transparent,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (active) AppColors.accent.copy(alpha = 0.65f) else AppColors.border,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelectTab(tab) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (tabModified) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(AppColors.accent)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                    }
                                    Text(
                                        text = tab.fileName,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) AppColors.textPrimary else AppColors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close tab",
                                        tint = AppColors.textMuted,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                draftsByTab.remove(tKey)
                                                onCloseTab(tab)
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                // Search + Find & Replace Bar
                if (showSearchBar && !state.isLoading && state.error == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.surfaceAlt)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppColors.bg)
                                    .border(1.dp, AppColors.border, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text("Find in file…", fontSize = 12.sp, color = AppColors.textMuted)
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = {
                                        searchQuery = it
                                        activeMatchIdx = 0
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = AppColors.textPrimary
                                    ),
                                    cursorBrush = SolidColor(AppColors.accent),
                                    modifier = Modifier.fillMaxWidth().testTag("code_editor_find_input")
                                )
                            }
                            if (searchQuery.isNotBlank()) {
                                Text(
                                    text = if (matchRowIndices.isEmpty()) "0" else "${(activeMatchIdx % matchRowIndices.size) + 1}/${matchRowIndices.size}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textSecondary
                                )
                                IconButton(onClick = { jumpToMatch(activeMatchIdx - 1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", tint = AppColors.textPrimary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { jumpToMatch(activeMatchIdx + 1) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", tint = AppColors.textPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                            IconButton(
                                onClick = { showReplaceRow = !showReplaceRow },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.FindReplace,
                                    contentDescription = "Toggle replace",
                                    tint = if (showReplaceRow) AppColors.accent else AppColors.textSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        if (showReplaceRow) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AppColors.bg)
                                        .border(1.dp, AppColors.border, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    if (replaceQuery.isEmpty()) {
                                        Text("Replace with…", fontSize = 12.sp, color = AppColors.textMuted)
                                    }
                                    BasicTextField(
                                        value = replaceQuery,
                                        onValueChange = { replaceQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = AppColors.textPrimary
                                        ),
                                        cursorBrush = SolidColor(AppColors.accent),
                                        modifier = Modifier.fillMaxWidth().testTag("code_editor_replace_input")
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = AppColors.surface,
                                    modifier = Modifier
                                        .border(1.dp, AppColors.border, RoundedCornerShape(7.dp))
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable(enabled = searchQuery.isNotBlank()) {
                                            val idx = draftText.indexOf(searchQuery, ignoreCase = false)
                                            if (idx >= 0) {
                                                draftsByTab[tabKey] = draftText.replaceFirst(searchQuery, replaceQuery)
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "Replace",
                                        fontSize = 11.sp,
                                        color = AppColors.accent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = AppColors.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable(enabled = searchQuery.isNotBlank()) {
                                            draftsByTab[tabKey] = draftText.replace(searchQuery, replaceQuery)
                                        }
                                ) {
                                    Text(
                                        text = "All",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.onAccent,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))

                // Main Editor / Viewer Canvas
                when {
                    state.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AppColors.accent)
                        }
                    }
                    state.error != null -> {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(state.error, fontSize = 13.sp, color = AppColors.error)
                        }
                    }
                    isEditing -> {
                        // Smooth Edit mode with vertical + optional horizontal scrolling & line gutter
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()
                        val editLinesCount = remember(draftText) { draftText.count { it == '\n' } + 1 }
                        val gutterText = remember(editLinesCount) {
                            (1..editLinesCount).joinToString("\n") { it.toString().padStart(4, ' ') }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(AppColors.codeBg)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(vScroll)
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Text(
                                    text = gutterText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 19.sp,
                                    color = AppColors.textMuted
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                val fieldModifier = if (wordWrap) {
                                    Modifier.weight(1f).testTag("code_editor_textfield")
                                } else {
                                    Modifier
                                        .weight(1f)
                                        .horizontalScroll(hScroll)
                                        .widthIn(min = 900.dp)
                                        .testTag("code_editor_textfield")
                                }
                                BasicTextField(
                                    value = draftText,
                                    onValueChange = { draftsByTab[tabKey] = it },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp,
                                        lineHeight = 19.sp,
                                        color = AppColors.textPrimary
                                    ),
                                    cursorBrush = SolidColor(AppColors.accent),
                                    modifier = fieldModifier
                                )
                            }
                        }
                    }
                    else -> {
                        // Ultra-smooth virtualized Read Mode (60fps even on huge files!)
                        // When wordWrap is OFF, wrap LazyColumn in ONE outer horizontal scroll container
                        // instead of attaching horizontalScroll to every row (eliminates layout thrashing!).
                        val outerHScroll = rememberScrollState()
                        val maxLineChars = remember(lines) {
                            lines.maxOfOrNull { it.length }?.coerceIn(80, 320) ?: 80
                        }
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(AppColors.codeBg)
                        ) {
                            val viewportWidth = maxWidth
                            val computedContentWidth = remember(maxLineChars, viewportWidth) {
                                val needed = (maxLineChars * 7.8f + 90f).dp
                                if (needed > viewportWidth) needed else viewportWidth
                            }
                            SelectionContainer {
                                Box(
                                    modifier = if (wordWrap) {
                                        Modifier.fillMaxSize()
                                    } else {
                                        Modifier
                                            .fillMaxSize()
                                            .horizontalScroll(outerHScroll)
                                    }
                                ) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = if (wordWrap) {
                                            Modifier.fillMaxSize()
                                        } else {
                                            Modifier
                                                .width(computedContentWidth)
                                                .fillMaxSize()
                                        },
                                        contentPadding = PaddingValues(vertical = 10.dp)
                                    ) {
                                        items(
                                            items = visibleRows,
                                            key = { it.lineIndex }
                                        ) { row ->
                                            val highlighted = remember(row.text, state.fileName, searchQuery) {
                                                CodeSyntaxHighlighter.highlightLine(
                                                    line = row.text,
                                                    fileName = state.fileName,
                                                    searchQuery = searchQuery
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 1.dp),
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Text(
                                                    text = "${row.lineIndex + 1}".padStart(4, ' '),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 18.sp,
                                                    color = AppColors.textMuted
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .width(18.dp)
                                                        .height(18.dp)
                                                        .let { m ->
                                                            if (row.isFoldHeader) {
                                                                m.clickable {
                                                                    foldedStarts = if (row.lineIndex in foldedStarts) {
                                                                        foldedStarts - row.lineIndex
                                                                    } else {
                                                                        foldedStarts + row.lineIndex
                                                                    }
                                                                }
                                                            } else m
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (row.isFoldHeader) {
                                                        Icon(
                                                            imageVector = if (row.isFolded) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                                                            contentDescription = if (row.isFolded) "Unfold block" else "Fold block",
                                                            tint = AppColors.textMuted,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Row(
                                                    modifier = Modifier.weight(1f),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = highlighted,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        lineHeight = 18.sp,
                                                        softWrap = wordWrap,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (row.isFolded) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = AppColors.accentSoft,
                                                            modifier = Modifier.clickable {
                                                                foldedStarts = foldedStarts - row.lineIndex
                                                            }
                                                        ) {
                                                            Text(
                                                                text = "… +${row.hiddenLineCount} lines",
                                                                fontSize = 10.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = AppColors.accent,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
