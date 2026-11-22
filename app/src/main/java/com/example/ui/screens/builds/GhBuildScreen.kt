package com.example.ui.screens.builds

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import com.example.CodeForgeApp
import com.example.data.repository.WorkflowRunInfo
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Dedicated GH Build bottom tab inside Project Mode.
 * Displays live GitHub Actions workflow runs for the active project's linked repository,
 * one-tap Push & Build / Dispatch Workflow, live failure log inspection, and "Fix with Agent".
 */
@Composable
fun GhBuildScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onSendErrorToAgent: (String) -> Unit,
    onOpenGitHubSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by chatViewModel.uiState.collectAsState()
    val activeProject = uiState.activeProject

    val token = app.settingsStore.githubToken
    val linkedRepo = remember(activeProject?.id, app.settingsStore.githubRepo) {
        val projRepo = activeProject?.let { app.settingsStore.repoFor(it.id) }.orEmpty()
        projRepo.ifBlank { app.settingsStore.githubRepo }
    }

    var runs by remember { mutableStateOf<List<WorkflowRunInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var inspectedRun by remember { mutableStateOf<WorkflowRunInfo?>(null) }
    var inspectedLog by remember { mutableStateOf<String?>(null) }
    var loadingLog by remember { mutableStateOf(false) }

    fun refreshRuns() {
        if (token.isBlank() || linkedRepo.isBlank()) return
        isLoading = true
        statusBanner = null
        scope.launch {
            try {
                runs = app.gitHubRepository.getRecentWorkflowRuns(token, linkedRepo)
            } catch (e: Exception) {
                statusBanner = "Could not load runs: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(linkedRepo, token) {
        refreshRuns()
    }

    // Failure / Job Log Inspection Dialog
    inspectedRun?.let { run ->
        AlertDialog(
            onDismissRequest = {
                inspectedRun = null
                inspectedLog = null
            },
            title = {
                Column {
                    Text(run.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${run.branch} · ${run.headSha.take(7)} · ${run.conclusion ?: run.status}",
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when {
                        loadingLog -> {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.accent)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Fetching GitHub Actions error log…", fontSize = 13.sp)
                            }
                        }
                        else -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.codeBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 320.dp)
                                    .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                            ) {
                                Text(
                                    text = inspectedLog ?: "No failure log captured for this run.",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AppColors.textPrimary,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .verticalScroll(rememberScrollState())
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!loadingLog && !inspectedLog.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            val errLog = inspectedLog!!.take(8000)
                            val runName = run.name
                            inspectedRun = null
                            inspectedLog = null
                            onSendErrorToAgent(
                                "GitHub Actions build `$runName` failed on `$linkedRepo` (branch `${run.branch}`). " +
                                    "Diagnose the exact root cause and apply a surgical fix:\n\n```\n$errLog\n```"
                            )
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Fix with Agent", color = AppColors.accent, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                Row {
                    if (run.htmlUrl.startsWith("http")) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(run.htmlUrl)))
                            } catch (_: Exception) {
                            }
                        }) {
                            Text("Open on GitHub", color = AppColors.textSecondary)
                        }
                    }
                    TextButton(onClick = {
                        inspectedRun = null
                        inspectedLog = null
                    }) {
                        Text("Close", color = AppColors.textSecondary)
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.surface)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GitHub Actions Builds",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = if (linkedRepo.isNotBlank()) linkedRepo else "No repository linked yet",
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { refreshRuns() },
                    modifier = Modifier.size(38.dp).testTag("gh_build_refresh_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh builds", tint = AppColors.textSecondary, modifier = Modifier.size(19.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = AppColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (token.isBlank() || linkedRepo.isBlank()) {
                                onOpenGitHubSettings()
                            } else {
                                chatViewModel.pushAndBuild()
                                statusBanner = "Pushing & triggering cloud build…"
                            }
                        }
                        .testTag("gh_build_push_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = AppColors.onAccent, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Push & Build", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onAccent)
                    }
                }

                if (token.isNotBlank() && linkedRepo.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier
                            .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                scope.launch {
                                    statusBanner = "Dispatching build.yml workflow…"
                                    val ok = app.gitHubRepository.triggerWorkflowDispatch(token, linkedRepo)
                                    statusBanner = if (ok) "Workflow dispatched! Tap Refresh in a few seconds." else "Could not dispatch build.yml (ensure workflow_dispatch is enabled)."
                                    refreshRuns()
                                }
                            }
                            .testTag("gh_build_dispatch_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AppColors.textPrimary, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Run Workflow", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AppColors.textPrimary)
                        }
                    }
                }
            }

            if (!statusBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(statusBanner!!, fontSize = 12.sp, color = AppColors.accent)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))

        when {
            token.isBlank() || linkedRepo.isBlank() -> {
                Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (token.isBlank()) "Connect GitHub to track builds" else "Link a GitHub repository",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Monitor GitHub Actions runs, inspect compile errors, and auto-fix failures with CodeForge.",
                            fontSize = 12.5.sp,
                            color = AppColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AppColors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenGitHubSettings)
                        ) {
                            Text(
                                "Open GitHub Settings",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.onAccent,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
            isLoading && runs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = AppColors.accent)
                }
            }
            runs.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No GitHub Actions runs found", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Tap Push & Build above to push your Codebase and trigger a cloud build.", fontSize = 12.5.sp, color = AppColors.textMuted)
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(runs, key = { it.id }) { run ->
                        val isSuccess = run.conclusion == "success"
                        val isFailure = run.conclusion == "failure" || run.conclusion == "timed_out"
                        val isRunning = run.status != "completed"
                        val badgeColor = when {
                            isSuccess -> AppColors.ok
                            isFailure -> AppColors.error
                            else -> AppColors.warn
                        }
                        val statusLabel = when {
                            isRunning -> run.status.replace('_', ' ')
                            else -> run.conclusion ?: run.status
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AppColors.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (isFailure) AppColors.error.copy(alpha = 0.4f) else AppColors.border, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    inspectedRun = run
                                    inspectedLog = null
                                    loadingLog = true
                                    scope.launch {
                                        inspectedLog = app.gitHubRepository.fetchFailureLog(token, linkedRepo, run.id)
                                        loadingLog = false
                                    }
                                }
                                .testTag("gh_build_run_${run.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        isSuccess -> Icons.Default.CheckCircle
                                        isFailure -> Icons.Default.Error
                                        else -> Icons.Default.Schedule
                                    },
                                    contentDescription = statusLabel,
                                    tint = badgeColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = run.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${run.branch} · ${run.headSha.take(7)} · ${run.createdAt.take(16).replace('T', ' ')}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppColors.textMuted
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = badgeColor.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = statusLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                if (run.htmlUrl.startsWith("http")) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(run.htmlUrl)))
                                            } catch (_: Exception) {
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.OpenInNew, contentDescription = "Open run in browser", tint = AppColors.textMuted, modifier = Modifier.size(16.dp))
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
