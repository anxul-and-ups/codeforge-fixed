package com.example.ui.screens.diff

import android.content.Context
import com.example.ui.theme.AppColors
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.local.entity.CheckpointEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.repository.ProjectRepository
import com.example.domain.model.FileDiff
import com.example.ui.components.FileDiffCard
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.RoseError
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiffReviewScreen(
    projectRepository: ProjectRepository,
    activeProject: ProjectEntity?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var diffs by remember { mutableStateOf<List<FileDiff>>(emptyList()) }
    var checkpoints by remember { mutableStateOf<List<CheckpointEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCheckpointsSection by remember { mutableStateOf(false) }

    fun refreshDiffs() {
        if (activeProject == null) return
        scope.launch {
            isLoading = true
            diffs = projectRepository.computeDiffsFromLastCheckpoint(activeProject.id)
            isLoading = false
        }
    }

    LaunchedEffect(activeProject?.id) {
        refreshDiffs()
        if (activeProject != null) {
            projectRepository.getCheckpoints(activeProject.id).collect { list ->
                checkpoints = list
            }
        }
    }

    fun shareFile(file: File, mimeType: String, chooserTitle: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: Exception) {
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        // Top Toolbar
        Surface(
            color = AppColors.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CompareArrows,
                        contentDescription = "Diff",
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Change Review",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { refreshDiffs() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AppColors.textSecondary)
                    }

                    // Export menu
                    Button(
                        onClick = {
                            if (activeProject != null) {
                                scope.launch {
                                    val zip = projectRepository.exportProjectZip(activeProject.id)
                                    shareFile(zip, "application/zip", "Share Fixed Project ZIP")
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.onAccent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export ZIP", fontSize = 12.sp, color = AppColors.onAccent)
                    }
                }
            }
        }

        // Checkpoints banner accordion
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.surface),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCheckpointsSection = !showCheckpointsSection },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = ForgeAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Checkpoints & Snapshots (${checkpoints.size})",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.warn
                        )
                    }
                    Icon(
                        imageVector = if (showCheckpointsSection) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = ForgeAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = showCheckpointsSection) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (cp in checkpoints.take(5)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.codeBg, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = cp.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.textPrimary)
                                    val dateStr = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(cp.timestamp))
                                    Text(text = dateStr, fontSize = 10.sp, color = AppColors.textMuted)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = projectRepository.restoreCheckpoint(cp.id)
                                            if (ok) {
                                                Toast.makeText(context, "Restored to checkpoint: ${cp.title}", Toast.LENGTH_SHORT).show()
                                                refreshDiffs()
                                            }
                                        }
                                    },
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Restore", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Summary bar: Accept All / Reject All
        if (diffs.isNotEmpty()) {
            Surface(
                color = AppColors.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val totalAdded = diffs.sumOf { it.linesAdded }
                    val totalRemoved = diffs.sumOf { it.linesRemoved }
                    Text(
                        text = "${diffs.size} files changed (+$totalAdded, -$totalRemoved)",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )

                    Row {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    // Reject all by restoring last checkpoint
                                    val last = checkpoints.firstOrNull()
                                    if (last != null) {
                                        projectRepository.restoreCheckpoint(last.id)
                                        Toast.makeText(context, "All changes rejected.", Toast.LENGTH_SHORT).show()
                                        refreshDiffs()
                                    }
                                }
                            },
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseError)
                        ) {
                            Text("Reject All", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    if (activeProject != null) {
                                        // Accept all by creating new baseline checkpoint
                                        projectRepository.createCheckpoint(activeProject.id, "Accepted Changes")
                                        Toast.makeText(context, "All changes accepted!", Toast.LENGTH_SHORT).show()
                                        refreshDiffs()
                                    }
                                }
                            },
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess)
                        ) {
                            Text("Accept All", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Diff Cards list
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyberCyan)
            }
        } else if (diffs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Clean",
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Working tree clean",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No uncommitted modifications compared to baseline.",
                        fontSize = 13.sp,
                        color = AppColors.textSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(diffs, key = { it.filePath }) { diff ->
                    FileDiffCard(
                        diff = diff,
                        onAccept = {
                            scope.launch {
                                if (activeProject != null) {
                                    // Create micro checkpoint
                                    projectRepository.createCheckpoint(activeProject.id, "Accepted ${diff.filePath}")
                                    refreshDiffs()
                                }
                            }
                        },
                        onReject = {
                            scope.launch {
                                if (activeProject != null) {
                                    projectRepository.revertFileToLastCheckpoint(activeProject.id, diff.filePath)
                                    refreshDiffs()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
