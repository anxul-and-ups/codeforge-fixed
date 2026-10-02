package com.example.ui.screens.files

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ProjectEntity
import com.example.data.repository.ProjectRepository
import com.example.domain.model.FileNode
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.RoseError
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    projectRepository: ProjectRepository,
    activeProject: ProjectEntity?,
    onSelectProject: (ProjectEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allProjects by projectRepository.allProjects.collectAsState(initial = emptyList())

    var fileTree by remember { mutableStateOf<FileNode?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Selected file for viewer / editor
    var viewingFilePath by remember { mutableStateOf<String?>(null) }
    var viewingFileContent by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }

    // Dialogs
    var showProjectSwitcher by remember { mutableStateOf(false) }
    var showCreateStarterDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }

    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }

    fun loadTree() {
        if (activeProject == null) return
        scope.launch {
            isLoading = true
            fileTree = projectRepository.getFileTree(activeProject.id)
            isLoading = false
        }
    }

    LaunchedEffect(activeProject?.id) {
        loadTree()
    }

    // Zip file picker launcher
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                try {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip") ?: "Imported Project"
                    val imported = projectRepository.importProjectFromZip(name, uri)
                    onSelectProject(imported)
                    Toast.makeText(context, "ZIP extracted successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
    ) {
        // Top Toolbar
        Surface(
            color = Color(0xFF111827),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Project Switcher
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { showProjectSwitcher = true }
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Project",
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeProject?.name ?: "Select Project",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F5F9)
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Switch",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )

                    DropdownMenu(
                        expanded = showProjectSwitcher,
                        onDismissRequest = { showProjectSwitcher = false }
                    ) {
                        for (p in allProjects) {
                            DropdownMenuItem(
                                text = { Text(p.name, fontSize = 13.sp) },
                                onClick = {
                                    onSelectProject(p)
                                    showProjectSwitcher = false
                                }
                            )
                        }
                    }
                }

                // Actions: Import ZIP & Starter
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "Import ZIP",
                            tint = ForgeAmber
                        )
                    }

                    IconButton(
                        onClick = { showCreateStarterDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Starter Project",
                            tint = CyberCyan
                        )
                    }
                }
            }
        }

        // Main content: either File Tree or File Viewer
        if (viewingFilePath != null) {
            // File Viewer / Editor
            Column(modifier = Modifier.fillMaxSize()) {
                // Viewer Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                viewingFilePath = null
                                isEditing = false
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back to Files",
                                tint = Color(0xFFF1F5F9)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = viewingFilePath ?: "",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5F9)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isEditing) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            projectRepository.writeFile(activeProject!!.id, viewingFilePath!!, viewingFileContent)
                                            isEditing = false
                                            Toast.makeText(context, "Saved file changes", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save", fontSize = 11.sp)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { isEditing = true },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Edit", fontSize = 11.sp, color = CyberCyan)
                            }
                        }
                    }
                }

                // File content editor / viewer
                if (isEditing) {
                    OutlinedTextField(
                        value = viewingFileContent,
                        onValueChange = { viewingFileContent = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFF1F5F9)
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A),
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = Color(0xFF1E293B)
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0F172A))
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = viewingFileContent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(0xFFE2E8F0)
                        )
                    }
                }
            }
        } else {
            // File Tree List
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyberCyan)
                }
            } else if (fileTree == null || fileTree?.children?.isEmpty() == true) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Empty",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No files in project yet",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { showCreateStarterDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                        ) {
                            Text("Create Starter Project", color = Color(0xFF003549))
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PROJECT REPOSITORY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                            IconButton(
                                onClick = { showNewFileDialog = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NoteAdd,
                                    contentDescription = "New File",
                                    tint = CyberCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    val nodes = fileTree?.children ?: emptyList()
                    items(nodes) { node ->
                        FileNodeItem(
                            node = node,
                            depth = 0,
                            expandedFolders = expandedFolders,
                            onToggleFolder = { path ->
                                expandedFolders[path] = !(expandedFolders[path] ?: false)
                            },
                            onOpenFile = { path ->
                                scope.launch {
                                    try {
                                        val content = projectRepository.readFile(activeProject!!.id, path)
                                        viewingFilePath = path
                                        viewingFileContent = content
                                        isEditing = false
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // New Starter Project Dialog
    if (showCreateStarterDialog) {
        var starterName by remember { mutableStateOf("My Android App") }
        var selectedType by remember { mutableStateOf("Android Starter") }

        AlertDialog(
            onDismissRequest = { showCreateStarterDialog = false },
            title = { Text("New Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = starterName,
                        onValueChange = { starterName = it },
                        label = { Text("Project Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Select Template:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    val templates = listOf("Android Starter", "Kotlin CLI Tool", "Blank Project")
                    for (t in templates) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedType = t }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = if (selectedType == t) "🔘 " else "⚪ ",
                                fontSize = 14.sp
                            )
                            Text(t, fontSize = 13.sp, color = if (selectedType == t) CyberCyan else Color.White)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val newProj = projectRepository.createProject(starterName, selectedType)
                            onSelectProject(newProj)
                            showCreateStarterDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Text("Create", color = Color(0xFF003549))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateStarterDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // New File Dialog
    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("New File") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    placeholder = { Text("e.g. src/Util.kt") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotBlank() && activeProject != null) {
                            scope.launch {
                                projectRepository.writeFile(activeProject.id, newFileName.trim(), "")
                                newFileName = ""
                                showNewFileDialog = false
                                loadTree()
                            }
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun FileNodeItem(
    node: FileNode,
    depth: Int,
    expandedFolders: Map<String, Boolean>,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    val isExpanded = expandedFolders[node.path] ?: false

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (node.isDirectory) {
                        onToggleFolder(node.path)
                    } else {
                        onOpenFile(node.path)
                    }
                }
                .padding(vertical = 5.dp, horizontal = (8 + depth * 16).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isDirectory) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = "Folder",
                    tint = CyberCyan,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(20.dp))
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "File",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = node.name,
                fontSize = 13.sp,
                fontWeight = if (node.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                color = if (node.isDirectory) Color(0xFFE2E8F0) else Color(0xFFCBD5E1),
                modifier = Modifier.weight(1f)
            )
            if (!node.isDirectory && node.sizeBytes > 0) {
                Text(
                    text = "${node.sizeBytes / 1024} KB",
                    fontSize = 10.sp,
                    color = Color(0xFF64748B)
                )
            }
        }

        if (node.isDirectory && isExpanded) {
            for (child in node.children) {
                FileNodeItem(
                    node = child,
                    depth = depth + 1,
                    expandedFolders = expandedFolders,
                    onToggleFolder = onToggleFolder,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}
