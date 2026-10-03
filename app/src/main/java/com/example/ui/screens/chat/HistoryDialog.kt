package com.example.ui.screens.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ConversationEntity
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Content of the side drawer: new chat, search and all old chats (like the Claude app). */
@Composable
fun ChatDrawerContent(
    viewModel: ChatViewModel,
    onClose: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val projectNames by viewModel.projectNames.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val filtered = if (query.isBlank()) conversations else conversations.filter { it.title.contains(query, ignoreCase = true) }
    val pinned = filtered.filter { it.isPinned }
    val recent = filtered.filter { !it.isPinned }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = "CodeForge",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.textPrimary,
            modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 12.dp)
        )

        DrawerAction(label = "New chat") {
            viewModel.newConversation()
            onClose()
        }
        DrawerAction(label = "New app project") {
            viewModel.newStarterProject()
            onClose()
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search chats", fontSize = 14.sp) },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppColors.accent,
                unfocusedBorderColor = AppColors.border,
                focusedTextColor = AppColors.textPrimary,
                unfocusedTextColor = AppColors.textPrimary,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = AppColors.accent,
                focusedPlaceholderColor = AppColors.textMuted,
                unfocusedPlaceholderColor = AppColors.textMuted
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp)
        )

        if (filtered.isEmpty()) {
            Text(
                text = if (conversations.isEmpty()) "No chats yet. Start a conversation!" else "No chats match your search.",
                color = AppColors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(8.dp)
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (pinned.isNotEmpty()) {
                item { SectionTitle("Pinned") }
                items(pinned, key = { "p" + it.id }) { conv ->
                    ChatRow(
                        conv = conv,
                        projectName = projectNames[conv.projectId],
                        active = conv.id == uiState.activeConversation?.id,
                        menuOpen = menuFor == conv.id,
                        onOpenMenu = { menuFor = conv.id },
                        onCloseMenu = { menuFor = null },
                        onClick = {
                            viewModel.selectConversation(conv)
                            onClose()
                        },
                        onPin = { viewModel.togglePin(conv.id) },
                        onRename = {
                            renameTarget = conv
                            renameText = conv.title
                        },
                        onExport = {
                            scope.launch {
                                val md = viewModel.exportConversationMarkdown(conv.id).take(200_000)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, conv.title)
                                    putExtra(Intent.EXTRA_TEXT, md)
                                }
                                context.startActivity(Intent.createChooser(send, "Export chat"))
                            }
                        },
                        onDelete = { deleteTarget = conv }
                    )
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionTitle("Recent") }
                items(recent, key = { "r" + it.id }) { conv ->
                    ChatRow(
                        conv = conv,
                        projectName = projectNames[conv.projectId],
                        active = conv.id == uiState.activeConversation?.id,
                        menuOpen = menuFor == conv.id,
                        onOpenMenu = { menuFor = conv.id },
                        onCloseMenu = { menuFor = null },
                        onClick = {
                            viewModel.selectConversation(conv)
                            onClose()
                        },
                        onPin = { viewModel.togglePin(conv.id) },
                        onRename = {
                            renameTarget = conv
                            renameText = conv.title
                        },
                        onExport = {
                            scope.launch {
                                val md = viewModel.exportConversationMarkdown(conv.id).take(200_000)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, conv.title)
                                    putExtra(Intent.EXTRA_TEXT, md)
                                }
                                context.startActivity(Intent.createChooser(send, "Export chat"))
                            }
                        },
                        onDelete = { deleteTarget = conv }
                    )
                }
            }
        }
    }

    val rename = renameTarget
    if (rename != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameConversation(rename.id, renameText)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${del.title}\" and all its messages will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(del.id)
                    deleteTarget = null
                }) { Text("Delete", color = AppColors.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DrawerAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = AppColors.textPrimary)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.textMuted,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChatRow(
    conv: ConversationEntity,
    projectName: String?,
    active: Boolean,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) AppColors.surfaceAlt else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conv.title,
                    fontSize = 14.sp,
                    color = AppColors.textPrimary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!projectName.isNullOrBlank()) {
                    Text(
                        text = projectName,
                        fontSize = 11.sp,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box {
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Chat options", tint = AppColors.textMuted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
                    DropdownMenuItem(
                        text = { Text(if (conv.isPinned) "Unpin" else "Pin") },
                        onClick = {
                            onCloseMenu()
                            onPin()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            onCloseMenu()
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        onClick = {
                            onCloseMenu()
                            onExport()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = AppColors.error) },
                        onClick = {
                            onCloseMenu()
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
