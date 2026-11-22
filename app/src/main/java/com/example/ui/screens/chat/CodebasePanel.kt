package com.example.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.agent.WorkingFileState
import com.example.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodebaseDrawerSheet(
    workspaceTitle: String,
    workspacePath: String,
    files: List<CodebaseFileItem>,
    isLoading: Boolean,
    openedFile: CodeFileViewerState?,
    onRefresh: () -> Unit,
    onOpenFile: (CodebaseFileItem) -> Unit,
    onCloseFile: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        onRefresh()
    }

    // If a file is tapped, show the full-screen Code/File Viewer dialog with smooth Back navigation
    if (openedFile != null) {
        CodeFileViewerDialog(
            state = openedFile,
            onBack = onCloseFile
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var modifiedOnly by remember { mutableStateOf(false) }

    val modifiedCount = remember(files) { files.count { it.isModifiedByAi } }
    val filteredFiles = remember(files, searchQuery, modifiedOnly) {
        val q = searchQuery.trim()
        files.filter { item ->
            val matchesMod = !modifiedOnly || item.isModifiedByAi
            val matchesQ = q.isEmpty() ||
                item.path.contains(q, ignoreCase = true) ||
                item.name.contains(q, ignoreCase = true)
            matchesMod && matchesQ
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.surface,
        contentColor = AppColors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.accentSoft,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Codebase",
                            tint = AppColors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Codebase",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary
                        )
                        if (modifiedCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = AppColors.diffAddBg
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Modified files",
                                        tint = AppColors.ok,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "$modifiedCount modified",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.diffAddText
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "$workspaceTitle · $workspacePath",
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("codebase_refresh_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.accent
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh codebase",
                            tint = AppColors.accent
                        )
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("codebase_search_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AppColors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                placeholder = { Text("Filter workspace files…", fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.textPrimary,
                    unfocusedTextColor = AppColors.textPrimary,
                    focusedBorderColor = AppColors.accent,
                    unfocusedBorderColor = AppColors.border,
                    cursorColor = AppColors.accent
                )
            )

            // Filter pills: All vs AI Modified
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CodebaseFilterPill(
                    label = "All Files (${files.size})",
                    selected = !modifiedOnly,
                    onClick = { modifiedOnly = false }
                )
                CodebaseFilterPill(
                    label = "Modified ✓ ($modifiedCount)",
                    selected = modifiedOnly,
                    onClick = { modifiedOnly = true }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = AppColors.border)

            if (isLoading && files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = AppColors.accent
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Scanning active workspace…", fontSize = 13.sp, color = AppColors.textSecondary)
                    }
                }
            } else if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (modifiedOnly) {
                            "No files have been created or edited by the AI yet in this session."
                        } else if (searchQuery.isNotBlank()) {
                            "No files match \"$searchQuery\"."
                        } else {
                            "Workspace is empty or Termux bridge is offline. Tap Refresh once connected."
                        },
                        fontSize = 13.sp,
                        color = AppColors.textSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredFiles, key = { it.path }) { item ->
                        CodebaseFileRow(
                            item = item,
                            onClick = { onOpenFile(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodebaseFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) AppColors.accentSoft else AppColors.surfaceAlt,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(
                1.dp,
                if (selected) AppColors.accent else AppColors.border,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) AppColors.accent else AppColors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CodebaseFileRow(
    item: CodebaseFileItem,
    onClick: () -> Unit
) {
    val isCodeFile = remember(item.name) {
        val ext = item.name.substringAfterLast('.', "").lowercase()
        ext in setOf("kt", "java", "py", "ts", "tsx", "js", "jsx", "xml", "json", "kts", "gradle", "sh", "md", "yaml", "yml", "c", "cpp", "rs", "go", "html", "css")
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (item.isModifiedByAi) AppColors.diffAddBg.copy(alpha = 0.45f) else AppColors.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (item.isModifiedByAi) AppColors.ok.copy(alpha = 0.45f) else AppColors.border
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("codebase_file_${item.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCodeFile) Icons.Default.Code else Icons.Default.Description,
                contentDescription = null,
                tint = if (item.isModifiedByAi) AppColors.ok else AppColors.accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontSize = 13.5.sp,
                    fontWeight = if (item.isModifiedByAi) FontWeight.SemiBold else FontWeight.Medium,
                    color = AppColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.relativeDir.isNotBlank()) {
                    Text(
                        text = item.relativeDir,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (item.isModifiedByAi) {
                Spacer(modifier = Modifier.width(8.dp))
                val stateLabel = if (item.modifiedState == WorkingFileState.CREATED) "Created" else "Edited"
                val detail = item.diffSummary?.takeIf { it.isNotBlank() }?.let { "$stateLabel · $it" } ?: stateLabel
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AppColors.diffAddBg
                ) {
                    Text(
                        text = detail,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.diffAddText,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                // Clear green checkmark (green tick) badge whenever AI created or edited the file
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(AppColors.diffAddBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Modified by AI",
                        tint = AppColors.ok,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CodeFileViewerDialog(
    state: CodeFileViewerState,
    onBack: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(state.path) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(onBack = onBack)
        Surface(
            color = AppColors.bg,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                // Clean top viewer bar with smooth back navigation
                Surface(
                    color = AppColors.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("code_viewer_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Codebase",
                                    tint = AppColors.textPrimary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = state.fileName,
                                        fontSize = 15.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (state.modifiedState == WorkingFileState.CREATED || state.modifiedState == WorkingFileState.EDITED) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Modified by AI",
                                            tint = AppColors.ok,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = state.path,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!state.isLoading && state.error == null && state.content.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (copied) AppColors.diffAddBg else AppColors.accentSoft,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            clipboard.setText(AnnotatedString(state.content))
                                            copied = true
                                        }
                                        .testTag("code_viewer_copy_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy file",
                                            tint = if (copied) AppColors.ok else AppColors.accent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (copied) "Copied" else "Copy",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (copied) AppColors.diffAddText else AppColors.accent
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close viewer",
                                    tint = AppColors.textSecondary
                                )
                            }
                        }
                        HorizontalDivider(color = AppColors.border)
                    }
                }

                // Body
                when {
                    state.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AppColors.accent)
                        }
                    }
                    state.error != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = state.error,
                                color = AppColors.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                    else -> {
                        val lines = remember(state.content) { state.content.lines() }
                        val horizontalScroll = rememberScrollState()
                        val lineNumWidth = remember(lines.size) {
                            (lines.size.toString().length.coerceAtLeast(2) * 9 + 14).dp
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(AppColors.codeBg),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            itemsIndexed(lines) { index, lineText ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = AppColors.textMuted,
                                        modifier = Modifier
                                            .width(lineNumWidth)
                                            .padding(end = 10.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(horizontalScroll)
                                    ) {
                                        Text(
                                            text = lineText.ifEmpty { " " },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = AppColors.textPrimary
                                        )
                                    }
                                }
                            }
                        }

                        // Subtle file metadata footer
                        Surface(
                            color = AppColors.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                HorizontalDivider(color = AppColors.border)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${lines.size} lines · ${state.content.length} chars",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppColors.textMuted
                                    )
                                    if (!state.diffSummary.isNullOrBlank()) {
                                        Text(
                                            text = state.diffSummary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.diffAddText
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
