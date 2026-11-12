package com.example.ui.screens.projects

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CodeForgeApp
import com.example.data.local.entity.ProjectEntity
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun ProjectsHomeScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onOpenChat: () -> Unit,
    onOpenFiles: (ProjectEntity) -> Unit,
    onOpenChanges: (ProjectEntity) -> Unit,
    onOpenGitHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by app.projectRepository.allProjects.collectAsState(initial = emptyList())
    val activeProject by chatViewModel.activeProject.collectAsState()
    val engineHealth by app.termuxBridge.healthState.collectAsState()

    var showExplainer by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectTemplate by remember { mutableStateOf("Empty") }
    var editingAgentMdProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var agentMdText by remember { mutableStateOf("") }

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
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("Create New Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Project Name") },
                        placeholder = { Text("e.g. FastAPI Service, My App") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Template", fontSize = 12.sp, color = AppColors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tpl in listOf("Empty", "Android Starter")) {
                            val sel = newProjectTemplate == tpl
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { newProjectTemplate = tpl }
                            ) {
                                Text(
                                    text = tpl,
                                    fontSize = 12.sp,
                                    color = if (sel) AppColors.accent else AppColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newProjectName.trim().ifBlank { "New Project" }
                        showNewProjectDialog = false
                        newProjectName = ""
                        chatViewModel.newStarterProject(name, newProjectTemplate)
                        onOpenChat()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                ) {
                    Text("Create & Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (editingAgentMdProject != null) {
        val proj = editingAgentMdProject!!
        AlertDialog(
            onDismissRequest = { editingAgentMdProject = null },
            title = { Text("${proj.name} · AGENT.md") },
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
                        modifier = Modifier.fillMaxWidth().height(260.dp),
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
                TextButton(onClick = { editingAgentMdProject = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Greeting + Engine Status
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Projects Workspace",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = "~/forge/projects · ${projects.size} project(s)",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AppColors.surface,
                    modifier = Modifier
                        .border(1.dp, AppColors.border, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { showExplainer = !showExplainer }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.HelpOutline, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("What is a project?", fontSize = 11.sp, color = AppColors.accent)
                    }
                }
            }
        }

        // Explainer Card (Section 10.1)
        if (showExplainer) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AppColors.accent)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How CodeForge Projects Work", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text(
                            text = "• Every project is a real folder inside `~/forge/projects/<project-slug>`.\n" +
                                "• It holds your source files, Git repository, automatic checkpoints, and a per-project `AGENT.md` memory file.\n" +
                                "• When you install a tool or MCP server from GitHub, it is isolated in `~/forge/tools/<tool-name>` so it never pollutes your project tree.\n" +
                                "• When you analyze an APK or archive, you can choose to save its decompiled output into a new project or into `<project>/reference/`.",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = AppColors.textSecondary
                        )
                    }
                }
            }
        }

        // Quick Actions Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showNewProjectDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Project", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import ZIP", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onOpenGitHub,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("GitHub Clone", fontSize = 12.sp)
                }
            }
        }

        // Resume Last Chat Banner
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AppColors.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenChat() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AppColors.accentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Resume Active Session",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary
                        )
                        Text(
                            text = if (activeProject != null && activeProject?.id != com.example.data.repository.ProjectRepository.SCRATCH_ID) {
                                "Working in ${activeProject?.name} · Engine: ${if (engineHealth.engineRunning) "Connected" else "Local Shell"}"
                            } else {
                                "Clean chat session · Tap to open workspace"
                            },
                            fontSize = 12.sp,
                            color = AppColors.textSecondary
                        )
                    }
                    Text("Open ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                }
            }
        }

        // Project Cards List
        if (projects.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AppColors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.border, RoundedCornerShape(16.dp))
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No projects yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Create a new project, import a ZIP archive, or clone a GitHub repository to get started.",
                            fontSize = 12.sp,
                            color = AppColors.textSecondary
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
                    border = BorderStroke(1.dp, if (isSelected) AppColors.accent else AppColors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                        Surface(shape = RoundedCornerShape(999.dp), color = AppColors.accentSoft) {
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
                                    text = "~/forge/projects/${project.slug.ifBlank { project.name.lowercase() }} · branch:${project.gitBranch} · ${project.fileCount} files",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textMuted
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch { app.projectRepository.deleteProject(project.id) }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete project", tint = AppColors.textMuted, modifier = Modifier.size(17.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    chatViewModel.attachProjectToChat(project)
                                    onOpenChat()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                            ) {
                                Text("Open in Chat", fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { onOpenFiles(project) }) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Files", fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { onOpenChanges(project) }) {
                                Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Diff", fontSize = 12.sp)
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        agentMdText = app.projectRepository.readAgentMemory(project.id)
                                        editingAgentMdProject = project
                                    }
                                }
                            ) {
                                Text("AGENT.md", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
