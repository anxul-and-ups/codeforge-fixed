package com.example.ui.screens.github

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.CodeForgeApp
import com.example.data.repository.ClonedRepoManager
import com.example.data.repository.ClonedRepoRecord
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ClonedSortMode(val label: String) {
    NEWEST("Newest"),
    NAME("Name"),
    SIZE("Size"),
    MODIFIED("Modified")
}

/**
 * Dedicated "CLONED" Repositories Manager section.
 * Displays every cloned repository with full metadata, search, sorting, total storage usage,
 * and all 8 actions: Open, View files, Import into current project, Delete, Rename, Refresh,
 * Copy path, and Open in code editor.
 */
@Composable
fun ClonedReposSection(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onOpenInCodeEditor: () -> Unit,
    onOpenInChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repos by app.clonedRepoManager.repos.collectAsState()
    val activeProject by chatViewModel.activeProject.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(ClonedSortMode.NEWEST) }
    var isRefreshing by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }

    var renameTarget by remember { mutableStateOf<ClonedRepoRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<ClonedRepoRecord?>(null) }
    var importTarget by remember { mutableStateOf<ClonedRepoRecord?>(null) }
    var viewFilesTarget by remember { mutableStateOf<ClonedRepoRecord?>(null) }
    var showCloneUrlDialog by remember { mutableStateOf(false) }

    val dateFmt = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    val totalBytes = remember(repos) { repos.sumOf { it.sizeBytes } }
    val filtered = remember(repos, searchQuery, sortMode) {
        val q = searchQuery.trim()
        val base = if (q.isBlank()) repos else repos.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.url.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true) ||
                it.branch.contains(q, ignoreCase = true)
        }
        when (sortMode) {
            ClonedSortMode.NEWEST -> base.sortedByDescending { it.clonedAtMs }
            ClonedSortMode.NAME -> base.sortedBy { it.name.lowercase() }
            ClonedSortMode.SIZE -> base.sortedByDescending { it.sizeBytes }
            ClonedSortMode.MODIFIED -> base.sortedByDescending { it.lastModifiedMs }
        }
    }

    suspend fun openRepoAsProject(repo: ClonedRepoRecord, openEditor: Boolean) {
        try {
            val allProjs = chatViewModel.projectsList.value
            val linked = repo.projectId?.let { pid -> allProjs.firstOrNull { it.id == pid } }
            val chosen = if (linked != null) {
                linked
            } else {
                // Create a project pointing to or importing the cloned repo files
                val created = app.projectRepository.createProject(repo.name)
                val destRoot = app.projectRepository.projectDir(created.id)
                app.clonedRepoManager.importIntoProject(repo.id, destRoot, "", overwrite = true)
                app.projectRepository.refreshProjectStats(created.id)
                created
            }
            chatViewModel.selectProject(chosen)
            chatViewModel.refreshCodebaseFiles()
            if (openEditor) onOpenInCodeEditor() else onOpenInChat()
        } catch (e: Exception) {
            statusError = e.message ?: "Could not open repository"
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Summary bar: Count + Storage + Refresh + Clone URL
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = AppColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CLONED (${repos.size})",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = "Total storage: ${ClonedRepoManager.formatBytes(totalBytes)}",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )
                }
                OutlinedButton(
                    onClick = { showCloneUrlDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("cloned_clone_url_button")
                ) {
                    Text("Clone URL", fontSize = 12.sp, color = AppColors.accent)
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            statusError = null
                            try {
                                app.clonedRepoManager.refreshAll()
                            } catch (e: Exception) {
                                statusError = e.message
                            } finally {
                                isRefreshing = false
                            }
                        }
                    },
                    modifier = Modifier.size(38.dp).testTag("cloned_refresh_all_button")
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.accent)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh cloned repos", tint = AppColors.textSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Search & Sort Controls
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppColors.textMuted, modifier = Modifier.size(18.dp)) },
            placeholder = { Text("Search cloned repositories…", fontSize = 13.sp, color = AppColors.textMuted) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("cloned_search_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (mode in ClonedSortMode.entries) {
                val selected = sortMode == mode
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (selected) AppColors.accentSoft else AppColors.surface,
                    modifier = Modifier
                        .border(1.dp, if (selected) AppColors.accent else AppColors.border, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { sortMode = mode }
                ) {
                    Text(
                        text = mode.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) AppColors.accent else AppColors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (statusError != null) {
            Text(
                text = statusError.orEmpty(),
                fontSize = 12.sp,
                color = AppColors.error
            )
        }

        // Empty State or Cloned Repo Cards
        if (filtered.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = AppColors.textMuted,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = if (repos.isEmpty()) "No cloned repositories yet" else "No matching cloned repositories",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = "Clone a GitHub repository via URL, GitHub account, or chat agent to manage and import it here.",
                        fontSize = 12.5.sp,
                        color = AppColors.textSecondary
                    )
                }
            }
        } else {
            for (repo in filtered) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AppColors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
                        .testTag("cloned_repo_card_${repo.name}")
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = repo.name,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AppColors.accentSoft
                            ) {
                                Text(
                                    text = repo.branch.ifBlank { "main" },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.accent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        if (repo.url.isNotBlank()) {
                            Text(
                                text = repo.url,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (repo.description.isNotBlank()) {
                            Text(
                                text = repo.description,
                                fontSize = 12.5.sp,
                                color = AppColors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Metadata grid: location, size, clone date/time, last modified
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = AppColors.codeBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Path: ${repo.localPath}",
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Size: ${ClonedRepoManager.formatBytes(repo.sizeBytes)} (${repo.fileCount} files)",
                                    fontSize = 11.sp,
                                    color = AppColors.textSecondary
                                )
                                Text(
                                    text = "Cloned: ${dateFmt.format(Date(repo.clonedAtMs))} at ${timeFmt.format(Date(repo.clonedAtMs))}",
                                    fontSize = 11.sp,
                                    color = AppColors.textMuted
                                )
                                Text(
                                    text = "Modified: ${dateFmt.format(Date(repo.lastModifiedMs))} ${timeFmt.format(Date(repo.lastModifiedMs))}",
                                    fontSize = 11.sp,
                                    color = AppColors.textMuted
                                )
                            }
                        }

                        // Action Bar (all 8 actions cleanly organized)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RepoActionChip("Open", Icons.Default.FolderOpen, highlight = true) {
                                scope.launch { openRepoAsProject(repo, openEditor = false) }
                            }
                            RepoActionChip("Editor", Icons.Default.Code, highlight = true) {
                                scope.launch { openRepoAsProject(repo, openEditor = true) }
                            }
                            RepoActionChip("View files", Icons.Default.Visibility) {
                                viewFilesTarget = repo
                            }
                            RepoActionChip("Import", Icons.Default.Download) {
                                importTarget = repo
                            }
                            RepoActionChip("Copy path", Icons.Default.ContentCopy) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Cloned repo path", repo.localPath))
                                Toast.makeText(context, "Copied path", Toast.LENGTH_SHORT).show()
                            }
                            RepoActionChip("Rename", Icons.Default.Edit) {
                                renameTarget = repo
                            }
                            RepoActionChip("Refresh", Icons.Default.Refresh) {
                                scope.launch {
                                    app.clonedRepoManager.refreshAll()
                                    Toast.makeText(context, "Refreshed ${repo.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            RepoActionChip("Delete", Icons.Default.DeleteOutline, danger = true) {
                                deleteTarget = repo
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog 1: Clone by GitHub owner/repo or URL
    if (showCloneUrlDialog) {
        var repoInput by remember { mutableStateOf("") }
        var cloning by remember { mutableStateOf(false) }
        var cloneErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!cloning) showCloneUrlDialog = false },
            containerColor = AppColors.surface,
            title = { Text("Clone Repository", color = AppColors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter `owner/repo` or a full GitHub URL to clone directly into your workspace and register under CLONED.",
                        fontSize = 12.5.sp,
                        color = AppColors.textSecondary
                    )
                    OutlinedTextField(
                        value = repoInput,
                        onValueChange = { repoInput = it },
                        singleLine = true,
                        placeholder = { Text("owner/repo or https://github.com/owner/repo") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (cloneErr != null) {
                        Text(cloneErr.orEmpty(), fontSize = 12.sp, color = AppColors.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !cloning && repoInput.isNotBlank(),
                    onClick = {
                        scope.launch {
                            cloning = true
                            cloneErr = null
                            try {
                                val clean = repoInput.trim()
                                    .removePrefix("https://github.com/")
                                    .removePrefix("http://github.com/")
                                    .removePrefix("github.com/")
                                    .removeSuffix(".git")
                                    .trim('/')
                                val parts = clean.split("/").filter { it.isNotBlank() }
                                if (parts.size < 2) {
                                    throw IllegalArgumentException("Enter owner/repo or https://github.com/owner/repo")
                                }
                                val fullName = "${parts[0]}/${parts[1]}"
                                val ghRepo = com.example.data.repository.GhRepo(
                                    fullName = fullName,
                                    name = parts[1],
                                    isPrivate = false,
                                    description = "",
                                    defaultBranch = "main",
                                    updatedAt = "",
                                    isFork = false,
                                    sizeKb = 0
                                )
                                val proj = app.githubManager.cloneRepo(ghRepo)
                                showCloneUrlDialog = false
                                Toast.makeText(context, "Cloned ${proj.name} (${proj.fileCount} files)", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                cloneErr = e.message ?: "Clone failed"
                            } finally {
                                cloning = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                ) {
                    if (cloning) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AppColors.onAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(if (cloning) "Cloning…" else "Clone")
                }
            },
            dismissButton = {
                TextButton(enabled = !cloning, onClick = { showCloneUrlDialog = false }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    // Dialog 2: Rename cloned repository
    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = AppColors.surface,
            title = { Text("Rename Repository", color = AppColors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotBlank()) {
                        scope.launch {
                            app.clonedRepoManager.renameRepo(target.id, trimmed)
                            renameTarget = null
                        }
                    }
                }) { Text("Save", color = AppColors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel", color = AppColors.textSecondary) }
            }
        )
    }

    // Dialog 3: Delete cloned repository
    deleteTarget?.let { target ->
        var deleteDisk by remember(target.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AppColors.surface,
            title = { Text("Delete \"${target.name}\"?", color = AppColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Removes this entry from CLONED repositories.",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Also delete local files on disk", fontSize = 13.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(
                            checked = deleteDisk,
                            onCheckedChange = { deleteDisk = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AppColors.onAccent, checkedTrackColor = AppColors.error)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.clonedRepoManager.deleteRepo(target.id, deleteFilesOnDisk = deleteDisk)
                        deleteTarget = null
                        Toast.makeText(context, "Deleted ${target.name}", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Delete", color = AppColors.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = AppColors.textSecondary) }
            }
        )
    }

    // Dialog 4: Import into current active project
    importTarget?.let { target ->
        var subdir by remember(target.id) { mutableStateOf("") }
        var overwrite by remember(target.id) { mutableStateOf(true) }
        var importing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!importing) importTarget = null },
            containerColor = AppColors.surface,
            title = { Text("Import \"${target.name}\"", color = AppColors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val proj = activeProject
                    Text(
                        text = if (proj != null) "Target project: ${proj.name}" else "No active project selected. Open or create a project first.",
                        fontSize = 13.sp,
                        color = if (proj != null) AppColors.accent else AppColors.error
                    )
                    OutlinedTextField(
                        value = subdir,
                        onValueChange = { subdir = it },
                        singleLine = true,
                        label = { Text("Target subfolder (optional)") },
                        placeholder = { Text("Leave blank for project root") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Overwrite conflicting files", fontSize = 13.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(
                            checked = overwrite,
                            onCheckedChange = { overwrite = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AppColors.onAccent, checkedTrackColor = AppColors.accent)
                        )
                    }
                }
            },
            confirmButton = {
                val proj = activeProject
                Button(
                    enabled = proj != null && !importing,
                    onClick = {
                        if (proj != null) {
                            scope.launch {
                                importing = true
                                try {
                                    val destRoot = app.projectRepository.projectDir(proj.id)
                                    val report = app.clonedRepoManager.importIntoProject(
                                        repoIdOrName = target.id,
                                        targetProjectRoot = destRoot,
                                        targetSubdir = subdir.trim(),
                                        overwrite = overwrite
                                    )
                                    app.projectRepository.refreshProjectStats(proj.id)
                                    chatViewModel.refreshCodebaseFiles()
                                    importTarget = null
                                    Toast.makeText(
                                        context,
                                        "Imported ${report.copiedCount + report.overwrittenCount} files into ${proj.name}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    importing = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                ) {
                    Text(if (importing) "Importing…" else "Import Files")
                }
            },
            dismissButton = {
                TextButton(enabled = !importing, onClick = { importTarget = null }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    // Dialog 5: Quick File Tree Viewer for Cloned Repo
    viewFilesTarget?.let { target ->
        var filesList by remember(target.id) { mutableStateOf<List<String>>(emptyList()) }
        var loadingFiles by remember(target.id) { mutableStateOf(true) }
        LaunchedEffect(target.id) {
            loadingFiles = true
            filesList = withContext(Dispatchers.IO) {
                val dir = File(target.localPath)
                if (!dir.exists() || !dir.isDirectory) emptyList()
                else {
                    val skip = setOf(".git", ".gradle", "build", "node_modules")
                    dir.walkTopDown()
                        .onEnter { it == dir || it.name !in skip }
                        .filter { it.isFile }
                        .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') }
                        .sorted()
                        .take(500)
                        .toList()
                }
            }
            loadingFiles = false
        }
        Dialog(
            onDismissRequest = { viewFilesTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 48.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${target.name} (${filesList.size} files)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = target.localPath,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    when {
                        loadingFiles -> Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AppColors.accent)
                        }
                        filesList.isEmpty() -> Text(
                            "No files found at this location.",
                            fontSize = 13.sp,
                            color = AppColors.textMuted
                        )
                        else -> LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.codeBg)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filesList) { path ->
                                Text(
                                    text = path,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { viewFilesTarget = null }) {
                            Text("Close", color = AppColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlight: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        danger -> AppColors.error
        highlight -> AppColors.accent
        else -> AppColors.textSecondary
    }
    val bg = when {
        danger -> AppColors.diffRemoveBg
        highlight -> AppColors.accentSoft
        else -> AppColors.surfaceAlt
    }
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = bg,
        modifier = Modifier
            .border(1.dp, if (danger) AppColors.error.copy(alpha = 0.35f) else AppColors.border, RoundedCornerShape(9.dp))
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (highlight || danger) FontWeight.SemiBold else FontWeight.Medium,
                color = tint
            )
        }
    }
}
