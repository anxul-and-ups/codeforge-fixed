package com.example.ui.screens.builds

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.logs.BuildLogEntry
import com.example.data.logs.BuildLogStore
import com.example.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Error-log section: every GitHub build result, with the full log that can be read and copied. */
@Composable
fun BuildLogScreen(
    store: BuildLogStore,
    onSendToAi: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries by store.entries.collectAsState()
    var selected by remember { mutableStateOf<BuildLogEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Build log", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                Text(
                    "Errors from GitHub builds. Tap one to read and copy it.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
            }
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear", color = AppColors.error) }
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = "No builds yet. After you push to GitHub (Settings → GitHub), every build result shows up here.",
                fontSize = 14.sp,
                color = AppColors.textMuted,
                modifier = Modifier.padding(20.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { it.id }) { e ->
                val failed = e.status == "FAILED"
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AppColors.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { selected = e }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (failed) AppColors.error else AppColors.ok,
                                modifier = Modifier.size(9.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (failed) "Build failed" else "Build succeeded",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (failed) AppColors.error else AppColors.ok
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(dateFormat.format(Date(e.time)), fontSize = 11.sp, color = AppColors.textMuted)
                        }
                        Text(
                            text = e.projectName + "  ·  " + e.repo + "  ·  " + e.commit + (if (e.attempt > 1) "  ·  attempt ${e.attempt}" else ""),
                            fontSize = 12.sp,
                            color = AppColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (failed) {
                            Text(
                                text = e.log.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: "",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    val sel = selected
    if (sel != null) {
        BuildLogDetail(
            entry = sel,
            onDismiss = { selected = null },
            onSendToAi = {
                selected = null
                onSendToAi(
                    "The GitHub build failed. Please fix the compile/build errors.\n\nBuild log:\n```\n${sel.log.take(12000)}\n```"
                )
            },
            onDelete = {
                store.delete(sel.id)
                selected = null
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear build log?") },
            text = { Text("All saved build results will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    confirmClear = false
                }) { Text("Clear", color = AppColors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BuildLogDetail(
    entry: BuildLogEntry,
    onDismiss: () -> Unit,
    onSendToAi: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val failed = entry.status == "FAILED"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = AppColors.bg) {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (failed) "Build failed" else "Build succeeded",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (failed) AppColors.error else AppColors.ok
                        )
                        Text(
                            text = entry.projectName + "  ·  " + entry.repo + "  ·  " + entry.commit,
                            fontSize = 12.sp,
                            color = AppColors.textSecondary
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = AppColors.textSecondary) }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.codeBg,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = entry.log.ifBlank { "(empty log)" },
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.textPrimary,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Build log", entry.log))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                    ) { Text("Copy") }
                    OutlinedButton(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, entry.log)
                            }
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Share", color = AppColors.textPrimary) }
                }
                if (failed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onSendToAi, modifier = Modifier.fillMaxWidth()) {
                        Text("Send to AI to fix", color = AppColors.textPrimary)
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    if (entry.runUrl.isNotBlank()) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.runUrl)))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open the link", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Open on GitHub", color = AppColors.accent) }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                    TextButton(onClick = onDelete) { Text("Delete", color = AppColors.error) }
                }
            }
        }
    }
}
