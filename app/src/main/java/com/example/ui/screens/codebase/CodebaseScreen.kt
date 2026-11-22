package com.example.ui.screens.codebase

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.CodeForgeApp
import com.example.data.local.entity.ProjectEntity
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.screens.chat.CodeFileViewerState
import com.example.ui.screens.chat.CodebaseClipboardItem
import com.example.ui.screens.chat.CodebaseFileItem
import com.example.ui.theme.AppColors

/**
 * Dedicated full-screen Codebase tab (bottom nav in Project Mode) featuring:
 *  - Inline editable Code Viewer/Editor (saves automatically record Manual Edits & sync to AI Agent)
 *  - Upload files & ZIP extraction/replacement (zero-permission SAF)
 *  - Long-press context menu on every file: Edit, Replace via picker, Copy, Download single file, Delete
 *  - Multi-Codebase Split Mode for opening/creating a second Codebase and Copy/Pasting files across projects
 *  - Full Codebase ZIP Download (zero-permission SAF CreateDocument)
 *  - Live WebView HTML/CSS/JS Preview with console error capture
 *  - Push to GitHub dialog
 */
@Composable
fun CodebaseScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onOpenGhBuild: () -> Unit,
    onSendToAgent: (String) -> Unit,
    onOpenGitHubSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val primaryFiles by chatViewModel.codebaseFiles.collectAsState()
    val isLoading by chatViewModel.codebaseLoading.collectAsState()
    val openedFile by chatViewModel.openedFileViewer.collectAsState()
    val clipboardItem by chatViewModel.clipboardItem.collectAsState()
    val secondaryProject by chatViewModel.secondaryProject.collectAsState()
    val secondaryFiles by chatViewModel.secondaryFiles.collectAsState()
    val allProjects by chatViewModel.projectsList.collectAsState()
    val activeProject = uiState.activeProject

    var searchQuery by remember { mutableStateOf("") }
    var activeUploadTargetProjectId by remember { mutableStateOf<String?>(null) }
    var replaceTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // projectId to relativePath
    var singleDownloadTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // projectId to relativePath
    var showNewFileDialogForProjectId by remember { mutableStateOf<String?>(null) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }
    var showWebPreview by remember { mutableStateOf(false) }
    var pasteTargetProjectId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(activeProject?.id) {
        chatViewModel.refreshCodebaseFiles()
    }

    // Zero-permission SAF Upload / Replace / Download launchers
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pid = activeUploadTargetProjectId ?: activeProject?.id
        if (uri != null && pid != null) {
            chatViewModel.uploadFileToCodebase(pid, uri)
        }
    }

    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = replaceTarget
        replaceTarget = null
        if (uri != null && target != null) {
            chatViewModel.replaceCodebaseFile(target.first, target.second, uri)
        }
    }

    val zipExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val pid = activeProject?.id
        if (uri != null && pid != null) {
            chatViewModel.downloadProjectZipToUri(pid, uri)
        }
    }

    val singleFileExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = singleDownloadTarget
        singleDownloadTarget = null
        if (uri != null && target != null) {
            chatViewModel.downloadSingleFileToUri(target.first, target.second, uri)
        }
    }

    // HTML files available for Live WebView Preview
    val htmlFiles = remember(primaryFiles) {
        primaryFiles.map { it.path }.filter { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) }
    }

    // Editable Code Viewer / Editor Dialog
    openedFile?.let { viewer ->
        EditableCodeFileDialog(
            state = viewer,
            onSave = { updated ->
                val pid = viewer.projectId.ifBlank { activeProject?.id.orEmpty() }
                if (pid.isNotBlank()) {
                    chatViewModel.saveManualFileEdit(pid, viewer.path, updated)
                }
            },
            onDownload = {
                val pid = viewer.projectId.ifBlank { activeProject?.id.orEmpty() }
                if (pid.isNotBlank()) {
                    singleDownloadTarget = pid to viewer.path
                    singleFileExportLauncher.launch(viewer.fileName)
                }
            },
            onClose = { chatViewModel.closeCodebaseFile() }
        )
    }

    // Live WebView Preview Dialog
    if (showWebPreview && htmlFiles.isNotEmpty()) {
        val rootDir = chatViewModel.activeProjectRootFile()
        if (rootDir != null) {
            val defaultHtml = htmlFiles.firstOrNull { it.equals("index.html", ignoreCase = true) || it.endsWith("/index.html", ignoreCase = true) }
                ?: htmlFiles.first()
            WebPreviewDialog(
                projectRoot = rootDir,
                htmlRelativePaths = htmlFiles,
                initialHtmlPath = defaultHtml,
                onDismiss = { showWebPreview = false },
                onSendErrorsToAgent = onSendToAgent
            )
        }
    }

    // New File Dialog
    showNewFileDialogForProjectId?.let { targetPid ->
        var newPath by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialogForProjectId = null },
            title = { Text("Create new file", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it },
                    placeholder = { Text("e.g. src/app.js or index.html", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_file_path_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = newPath.trim().removePrefix("/")
                        if (clean.isNotBlank()) {
                            chatViewModel.saveManualFileEdit(targetPid, clean, "")
                            showNewFileDialogForProjectId = null
                        }
                    }
                ) {
                    Text("Create", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialogForProjectId = null }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    // Paste into folder dialog
    pasteTargetProjectId?.let { dstPid ->
        val clip = clipboardItem
        if (clip != null) {
            var folderInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { pasteTargetProjectId = null },
                title = { Text("Paste ${clip.fileName}", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "From: ${clip.sourceProjectName} / ${clip.relativePath}",
                            fontSize = 12.sp,
                            color = AppColors.textSecondary
                        )
                        OutlinedTextField(
                            value = folderInput,
                            onValueChange = { folderInput = it },
                            label = { Text("Target folder (leave empty for root)", fontSize = 12.sp) },
                            placeholder = { Text("e.g. src/components", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        chatViewModel.pasteClipboardInto(dstPid, folderInput)
                        pasteTargetProjectId = null
                    }) {
                        Text("Paste", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pasteTargetProjectId = null }) {
                        Text("Cancel", color = AppColors.textSecondary)
                    }
                }
            )
        }
    }

    // Delete confirmation
    deleteConfirmTarget?.let { (pid, relPath) ->
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = { Text("Delete file?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Remove `$relPath` from this Codebase?", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.deleteCodebaseFileManually(pid, relPath)
                    deleteConfirmTarget = null
                }) {
                    Text("Delete", color = AppColors.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTarget = null }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    // Secondary Codebase Picker Dialog (Multi-Codebase Split)
    if (showSplitPicker) {
        var newSecName by remember { mutableStateOf("") }
        val otherProjects = allProjects.filter { it.id != activeProject?.id }
        AlertDialog(
            onDismissRequest = { showSplitPicker = false },
            title = { Text("Open Secondary Codebase", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Compare or Copy/Paste files between two Codebases side by side.",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )
                    if (otherProjects.isNotEmpty()) {
                        for (p in otherProjects.take(6)) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.surfaceAlt,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        chatViewModel.openSecondaryCodebase(p)
                                        showSplitPicker = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                                    Text("${p.fileCount} files", fontSize = 11.sp, color = AppColors.textMuted)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newSecName,
                        onValueChange = { newSecName = it },
                        label = { Text("Or create new secondary Codebase", fontSize = 12.sp) },
                        placeholder = { Text("e.g. UI Kit", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                if (newSecName.isNotBlank()) {
                    TextButton(onClick = {
                        chatViewModel.createSecondaryCodebase(newSecName)
                        showSplitPicker = false
                    }) {
                        Text("Create & Open", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitPicker = false }) {
                    Text("Close", color = AppColors.textSecondary)
                }
            }
        )
    }

    // Push to GitHub Dialog
    if (showPushDialog && activeProject != null) {
        val defaultRepo = remember(activeProject.id) {
            app.settingsStore.repoFor(activeProject.id).orEmpty().ifBlank { app.settingsStore.githubRepo }
        }
        var repoInput by remember { mutableStateOf(defaultRepo) }
        var commitMsg by remember { mutableStateOf("Update ${activeProject.name} from CodeForge") }
        var pushing by remember { mutableStateOf(false) }
        var pushFeedback by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!pushing) showPushDialog = false },
            title = { Text("Push to GitHub", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (app.settingsStore.githubToken.isBlank()) {
                        Text(
                            "GitHub token is not configured yet. Connect GitHub first.",
                            fontSize = 13.sp,
                            color = AppColors.error
                        )
                    } else {
                        OutlinedTextField(
                            value = repoInput,
                            onValueChange = { repoInput = it },
                            label = { Text("Repository (owner/repo)", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = commitMsg,
                            onValueChange = { commitMsg = it },
                            label = { Text("Commit message", fontSize = 12.sp) },
                            singleLine = false,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (pushFeedback != null) {
                            Text(pushFeedback!!, fontSize = 12.sp, color = AppColors.textSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                if (app.settingsStore.githubToken.isBlank()) {
                    TextButton(onClick = {
                        showPushDialog = false
                        onOpenGitHubSettings()
                    }) {
                        Text("Connect GitHub", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    TextButton(
                        enabled = !pushing && repoInput.isNotBlank(),
                        onClick = {
                            pushing = true
                            pushFeedback = "Pushing changes…"
                            chatViewModel.pushActiveProjectToGitHub(repoInput, commitMsg) { ok, msg ->
                                pushing = false
                                pushFeedback = msg
                                if (ok) {
                                    showPushDialog = false
                                    onOpenGhBuild()
                                }
                            }
                        }
                    ) {
                        Text(if (pushing) "Pushing…" else "Push & Build", color = AppColors.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !pushing, onClick = { showPushDialog = false }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    // Main Codebase Layout
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        // Header bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeProject?.name ?: "Codebase",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${primaryFiles.size} file(s) · Long-press any file for options",
                        fontSize = 11.sp,
                        color = AppColors.textMuted
                    )
                }

                // Live HTML Preview CTA (shown whenever HTML exists)
                if (htmlFiles.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showWebPreview = true }
                            .testTag("codebase_live_preview_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Live Preview", tint = AppColors.onAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Preview", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onAccent)
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                IconButton(
                    onClick = { chatViewModel.refreshCodebaseFiles() },
                    modifier = Modifier.size(36.dp).testTag("codebase_refresh_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh files", tint = AppColors.textSecondary, modifier = Modifier.size(19.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action chips row: Upload/Unzip, New File, Download ZIP, Split Codebase, Push GitHub
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CodebaseActionChip(
                    icon = Icons.Default.UploadFile,
                    label = "Upload / ZIP",
                    testTag = "codebase_upload_button"
                ) {
                    activeUploadTargetProjectId = activeProject?.id
                    uploadPicker.launch(arrayOf("*/*"))
                }
                CodebaseActionChip(
                    icon = Icons.Default.Add,
                    label = "New File",
                    testTag = "codebase_new_file_button"
                ) {
                    showNewFileDialogForProjectId = activeProject?.id
                }
                CodebaseActionChip(
                    icon = Icons.Default.FolderZip,
                    label = "Download ZIP",
                    testTag = "codebase_download_zip_button"
                ) {
                    val safeName = (activeProject?.slug ?: "codebase").ifBlank { "codebase" }
                    zipExportLauncher.launch("${safeName}.zip")
                }
                CodebaseActionChip(
                    icon = Icons.Default.ViewAgenda,
                    label = if (secondaryProject != null) "Close Split" else "Split Codebase",
                    highlighted = secondaryProject != null,
                    testTag = "codebase_split_button"
                ) {
                    if (secondaryProject != null) chatViewModel.closeSecondaryCodebase()
                    else showSplitPicker = true
                }
                CodebaseActionChip(
                    icon = Icons.Default.CloudUpload,
                    label = "Push GitHub",
                    testTag = "codebase_push_github_button"
                ) {
                    showPushDialog = true
                }
            }

            // Clipboard banner when a file is copied
            clipboardItem?.let { clip ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.accentSoft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clipboard: ${clip.fileName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { pasteTargetProjectId = activeProject?.id },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Paste Here", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = AppColors.accent)
                        }
                        IconButton(onClick = { chatViewModel.clearClipboard() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear clipboard", tint = AppColors.textMuted, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().testTag("codebase_search_input"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppColors.textMuted, modifier = Modifier.size(18.dp)) },
                placeholder = { Text("Filter files by name or path…", fontSize = 13.sp) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.accent,
                    unfocusedBorderColor = AppColors.border
                )
            )
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))

        // Primary Codebase Pane
        val filteredPrimary = remember(primaryFiles, searchQuery) {
            if (searchQuery.isBlank()) primaryFiles
            else primaryFiles.filter { it.path.contains(searchQuery, ignoreCase = true) }
        }

        CodebaseFileListPane(
            project = activeProject,
            files = filteredPrimary,
            isLoading = isLoading,
            clipboardItem = clipboardItem,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onOpenFile = { item -> chatViewModel.openCodebaseFile(item, activeProject?.id) },
            onReplaceFile = { item ->
                val pid = activeProject?.id ?: return@CodebaseFileListPane
                replaceTarget = pid to item.path
                replacePicker.launch(arrayOf("*/*"))
            },
            onCopyFile = { item ->
                val p = activeProject ?: return@CodebaseFileListPane
                chatViewModel.copyFileToClipboard(p.id, p.name, item.path)
            },
            onDownloadFile = { item ->
                val pid = activeProject?.id ?: return@CodebaseFileListPane
                singleDownloadTarget = pid to item.path
                singleFileExportLauncher.launch(item.name)
            },
            onDeleteFile = { item ->
                val pid = activeProject?.id ?: return@CodebaseFileListPane
                deleteConfirmTarget = pid to item.path
            }
        )

        // Optional Secondary Split Codebase Pane (Multi-Codebase Copy/Paste)
        secondaryProject?.let { secProj ->
            Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(AppColors.accent.copy(alpha = 0.45f)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surfaceAlt)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Split: ${secProj.name} (${secondaryFiles.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (clipboardItem != null) {
                    TextButton(
                        onClick = { pasteTargetProjectId = secProj.id },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Paste Here", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = AppColors.accent)
                    }
                }
                TextButton(
                    onClick = {
                        activeUploadTargetProjectId = secProj.id
                        uploadPicker.launch(arrayOf("*/*"))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Upload", fontSize = 11.5.sp, color = AppColors.textSecondary)
                }
                IconButton(onClick = { chatViewModel.closeSecondaryCodebase() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close split", tint = AppColors.textMuted, modifier = Modifier.size(16.dp))
                }
            }
            val filteredSecondary = remember(secondaryFiles, searchQuery) {
                if (searchQuery.isBlank()) secondaryFiles
                else secondaryFiles.filter { it.path.contains(searchQuery, ignoreCase = true) }
            }
            CodebaseFileListPane(
                project = secProj,
                files = filteredSecondary,
                isLoading = false,
                clipboardItem = clipboardItem,
                modifier = Modifier.weight(0.9f).fillMaxWidth(),
                onOpenFile = { item -> chatViewModel.openCodebaseFile(item, secProj.id) },
                onReplaceFile = { item ->
                    replaceTarget = secProj.id to item.path
                    replacePicker.launch(arrayOf("*/*"))
                },
                onCopyFile = { item ->
                    chatViewModel.copyFileToClipboard(secProj.id, secProj.name, item.path)
                },
                onDownloadFile = { item ->
                    singleDownloadTarget = secProj.id to item.path
                    singleFileExportLauncher.launch(item.name)
                },
                onDeleteFile = { item ->
                    deleteConfirmTarget = secProj.id to item.path
                }
            )
        }
    }
}

@Composable
private fun CodebaseActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean = false,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) AppColors.accentSoft else AppColors.surfaceAlt,
        modifier = Modifier
            .border(1.dp, if (highlighted) AppColors.accent else AppColors.border, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlighted) AppColors.accent else AppColors.textSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (highlighted) AppColors.accent else AppColors.textPrimary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodebaseFileListPane(
    project: ProjectEntity?,
    files: List<CodebaseFileItem>,
    isLoading: Boolean,
    clipboardItem: CodebaseClipboardItem?,
    modifier: Modifier = Modifier,
    onOpenFile: (CodebaseFileItem) -> Unit,
    onReplaceFile: (CodebaseFileItem) -> Unit,
    onCopyFile: (CodebaseFileItem) -> Unit,
    onDownloadFile: (CodebaseFileItem) -> Unit,
    onDeleteFile: (CodebaseFileItem) -> Unit
) {
    if (isLoading && files.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = AppColors.accent)
        }
        return
    }
    if (files.isEmpty()) {
        Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No files in ${project?.name ?: "Codebase"} yet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Ask the AI Agent to build directly here, or tap Upload / New File above.", fontSize = 12.sp, color = AppColors.textMuted)
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(files, key = { "${project?.id}_${it.path}" }) { item ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        item.isManuallyEdited -> AppColors.accentSoft.copy(alpha = 0.45f)
                        item.isModifiedByAi -> AppColors.diffAddBg.copy(alpha = 0.55f)
                        else -> AppColors.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = when {
                                item.isManuallyEdited -> AppColors.accent.copy(alpha = 0.45f)
                                item.isModifiedByAi -> AppColors.ok.copy(alpha = 0.35f)
                                else -> AppColors.border
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { onOpenFile(item) },
                            onLongClick = { menuExpanded = true }
                        )
                        .testTag("codebase_file_row_${item.name}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (item.isModifiedByAi) Icons.Default.CheckCircle else Icons.Default.Description,
                            contentDescription = null,
                            tint = when {
                                item.isManuallyEdited -> AppColors.accent
                                item.isModifiedByAi -> AppColors.ok
                                else -> AppColors.textSecondary
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
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

                        if (item.isManuallyEdited) {
                            Surface(shape = RoundedCornerShape(6.dp), color = AppColors.accentSoft) {
                                Text(
                                    text = "Manual",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.accent,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        } else if (!item.diffSummary.isNullOrBlank()) {
                            Surface(shape = RoundedCornerShape(6.dp), color = AppColors.diffAddBg) {
                                Text(
                                    text = item.diffSummary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.diffAddText,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                    }
                }

                // Long-Press Context Menu
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit / View Code") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenFile(item)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Replace from Device") },
                        leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onReplaceFile(item)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy File") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCopyFile(item)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download File") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDownloadFile(item)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = AppColors.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = AppColors.error) },
                        onClick = {
                            menuExpanded = false
                            onDeleteFile(item)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Full-screen Code Viewer & Inline Editor.
 * Tapping "Edit" unlocks inline editing; tapping "Save" persists to disk and automatically records
 * a Manual Edit entry so the AI agent knows the exact user diff on the very next turn.
 */
@Composable
fun EditableCodeFileDialog(
    state: CodeFileViewerState,
    onSave: (String) -> Unit,
    onDownload: () -> Unit,
    onClose: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var isEditing by remember(state.path) { mutableStateOf(false) }
    var draftText by remember(state.path, state.content) { mutableStateOf(state.content) }
    val hasUnsaved = isEditing && draftText != state.content

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            color = AppColors.bg,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.fileName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.path,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!state.isLoading && state.error == null) {
                        if (isEditing) {
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = if (hasUnsaved) AppColors.accent else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable {
                                        onSave(draftText)
                                        isEditing = false
                                    }
                                    .testTag("code_editor_save_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Save,
                                        contentDescription = "Save",
                                        tint = if (hasUnsaved) AppColors.onAccent else AppColors.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Save",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (hasUnsaved) AppColors.onAccent else AppColors.textSecondary
                                    )
                                }
                            }
                        } else {
                            IconButton(
                                onClick = { isEditing = true },
                                modifier = Modifier.size(38.dp).testTag("code_editor_edit_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit file", tint = AppColors.accent, modifier = Modifier.size(19.dp))
                            }
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(state.content)) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy all", tint = AppColors.textSecondary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = onDownload,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = AppColors.textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close editor", tint = AppColors.textPrimary, modifier = Modifier.size(20.dp))
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))

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
                        TextField(
                            value = draftText,
                            onValueChange = { draftText = it },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 19.sp,
                                color = AppColors.textPrimary
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = AppColors.codeBg,
                                unfocusedContainerColor = AppColors.codeBg,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = AppColors.accent
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("code_editor_textfield")
                        )
                    }
                    else -> {
                        val lines = remember(state.content) { state.content.lines() }
                        val hScroll = rememberScrollState()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(AppColors.codeBg),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            items(lines.size) { idx ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(hScroll)
                                        .padding(horizontal = 10.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}".padStart(4, ' '),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = AppColors.textMuted
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = lines[idx],
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = AppColors.textPrimary
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
