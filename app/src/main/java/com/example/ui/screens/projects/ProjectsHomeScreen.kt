package com.example.ui.screens.projects

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CodeForgeApp
import com.example.data.local.entity.ProjectEntity
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Home / Projects screen strictly matching the obsidian dark + coral reference design:
 * - Top brand badge (`>_ CodeForge`)
 * - Hero headline ("Build from your phone") + subtitle
 * - Dual action buttons: solid coral "Quick project" + dark bordered "+ New project"
 * - Expandable "Bring an existing project" card (ZIP file, Git repository, or GitHub)
 * - "Your projects" section with centered empty-state card or dark project list cards
 * - Warm coral extended Floating Action Button: `[ >_ Terminal ]`
 */
@Composable
fun ProjectsHomeScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onOpenChat: () -> Unit,
    onOpenFiles: (ProjectEntity) -> Unit,
    onOpenChanges: (ProjectEntity) -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenTerminal: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by app.projectRepository.allProjects.collectAsState(initial = emptyList())
    val activeProject by chatViewModel.activeProject.collectAsState()

    var expandImportCard by remember { mutableStateOf(false) }
    var showClonedDialog by remember { mutableStateOf(false) }
    val clonedRepos by app.clonedRepoManager.repos.collectAsState()
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectTemplate by remember { mutableStateOf("Empty") }
    var editingAgentMdProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var agentMdText by remember { mutableStateOf("") }

    val quickAdjectives = remember { listOf("Quiet", "Swift", "Solar", "Bright", "Amber", "Obsidian", "Crisp") }
    val quickNouns = remember { listOf("Hopper", "Spark", "Forge", "Pulse", "Atlas", "Nova", "Prism") }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val displayName = app.projectRepository.queryDisplayName(uri) ?: "Imported Project"
                    val p = app.projectRepository.importProjectFromZip(
                        displayName.substringBeforeLast('.').ifBlank { "Imported" },
                        uri
                    )
                    chatViewModel.selectProject(p)
                    Toast.makeText(context, "Imported ${p.name} (${p.fileCount} files)", Toast.LENGTH_SHORT).show()
                    onOpenChat()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            containerColor = AppColors.surface,
            title = { Text("Create New Project", color = AppColors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Project Name") },
                        placeholder = { Text("e.g. au-notes, portfolio-app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Template", fontSize = 12.sp, color = AppColors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tpl in listOf("Empty", "Android Starter")) {
                            val sel = newProjectTemplate == tpl
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (sel) AppColors.accent.copy(alpha = 0.5f) else AppColors.border,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { newProjectTemplate = tpl }
                            ) {
                                Text(
                                    text = tpl,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) AppColors.accent else AppColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newProjectName.trim().ifBlank { "new-project" }
                        showNewProjectDialog = false
                        newProjectName = ""
                        chatViewModel.newStarterProject(name, newProjectTemplate)
                        onOpenChat()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                ) {
                    Text("Create & Open", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    if (editingAgentMdProject != null) {
        val proj = editingAgentMdProject!!
        AlertDialog(
            onDismissRequest = { editingAgentMdProject = null },
            containerColor = AppColors.surface,
            title = { Text("${proj.name} · AGENT.md", color = AppColors.textPrimary) },
            text = {
                Column {
                    Text(
                        "Per-project memory read at the start of every run and updated automatically with learned commands & conventions.",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = agentMdText,
                        onValueChange = { agentMdText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            app.projectRepository.saveAgentMemory(proj.id, agentMdText)
                            editingAgentMdProject = null
                            Toast.makeText(context, "Saved AGENT.md", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                ) {
                    Text("Save Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingAgentMdProject = null }) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        )
    }

    if (showClonedDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showClonedDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = AppColors.bg,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 36.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CLONED Repositories",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showClonedDialog = false }) {
                            Text("Close", color = AppColors.textSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        item {
                            com.example.ui.screens.github.ClonedReposSection(
                                app = app,
                                chatViewModel = chatViewModel,
                                onOpenInCodeEditor = {
                                    showClonedDialog = false
                                    activeProject?.let { onOpenFiles(it) } ?: onOpenChat()
                                },
                                onOpenInChat = {
                                    showClonedDialog = false
                                    onOpenChat()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Brand Row: [ >_ ] CodeForge
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.accentSoft)
                            .border(1.dp, AppColors.accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "CodeForge",
                            tint = AppColors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "CodeForge",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                }
            }

            // Hero Headline & Subtitle
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Build from your phone",
                        fontSize = 31.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = "Chat, review changes, and preview your project.",
                        fontSize = 15.5.sp,
                        lineHeight = 22.sp,
                        color = AppColors.textSecondary
                    )
                }
            }

            // Dual Action Row: [ Quick project ]  [ + New project ]
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val name = "${quickAdjectives.random()} ${quickNouns.random()}"
                            chatViewModel.newStarterProject(name, "Empty")
                            onOpenChat()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.accent,
                            contentColor = AppColors.onAccent
                        ),
                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("quick_project_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubble,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Quick project",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = { showNewProjectDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppColors.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = AppColors.surface,
                            contentColor = AppColors.accent
                        ),
                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("new_project_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "New project",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.accent,
                            maxLines = 1
                        )
                    }
                }
            }

            // Expandable "Bring an existing project" Card
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AppColors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { expandImportCard = !expandImportCard }
                        .testTag("bring_existing_project_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Bring an existing project",
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.textPrimary
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "ZIP file, Git repository, or GitHub",
                                    fontSize = 13.sp,
                                    color = AppColors.textSecondary
                                )
                            }
                            Icon(
                                imageVector = if (expandImportCard) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle import options",
                                tint = AppColors.textSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        AnimatedVisibility(visible = expandImportCard) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, AppColors.border),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            Icons.Default.FolderZip,
                                            contentDescription = null,
                                            tint = AppColors.accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Import ZIP", fontSize = 12.5.sp, color = AppColors.textPrimary)
                                    }
                                    OutlinedButton(
                                        onClick = onOpenGitHub,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, AppColors.border),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("GitHub Repo", fontSize = 12.5.sp, color = AppColors.accent)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { showClonedDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, AppColors.accent.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("home_cloned_repos_button")
                                ) {
                                    Text(
                                        text = "CLONED (${clonedRepos.size})",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.accent
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section Heading: "Your projects"
            item {
                Text(
                    text = "Your projects",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textPrimary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Empty State Card OR Populated Project Cards
            if (projects.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AppColors.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, AppColors.border, RoundedCornerShape(20.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 28.dp, vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(AppColors.accentSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = AppColors.accent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No projects yet",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.textPrimary
                            )
                            Text(
                                text = "Create a named project or start instantly with a Quick Project.",
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp,
                                color = AppColors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(projects, key = { it.id }) { project ->
                    val isSelected = activeProject?.id == project.id
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) AppColors.accent.copy(alpha = 0.65f) else AppColors.border
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                chatViewModel.attachProjectToChat(project)
                                onOpenChat()
                            }
                            .testTag("project_card_${project.id}")
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(AppColors.accentSoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = AppColors.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = project.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = AppColors.accentSoft
                                            ) {
                                                Text(
                                                    text = "Active",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AppColors.accent,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "~/forge/projects/${project.slug.ifBlank { project.name.lowercase() }} · ${project.fileCount} files",
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppColors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch { app.projectRepository.deleteProject(project.id) }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete project",
                                        tint = AppColors.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = AppColors.accentSoft,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onOpenFiles(project) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Folder, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text("Files", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.accent)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = AppColors.surfaceAlt,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onOpenChanges(project) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CompareArrows, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text("Diff", fontSize = 12.sp, color = AppColors.textSecondary)
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = AppColors.surfaceAlt,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            scope.launch {
                                                agentMdText = app.projectRepository.readAgentMemory(project.id)
                                                editingAgentMdProject = project
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "AGENT.md",
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppColors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom-right Warm Coral Extended FAB: [ >_ Terminal ] matching reference screenshot
        ExtendedFloatingActionButton(
            onClick = onOpenTerminal,
            shape = RoundedCornerShape(16.dp),
            containerColor = AppColors.accent,
            contentColor = AppColors.onAccent,
            icon = {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Open Terminal",
                    modifier = Modifier.size(20.dp)
                )
            },
            text = {
                Text(
                    text = "Terminal",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp)
                .testTag("home_terminal_fab")
        )
    }
}
