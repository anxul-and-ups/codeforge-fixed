package com.example.ui.screens.settings

import android.widget.Toast
import com.example.ui.theme.AppColors
import com.example.ui.theme.ThemeMode
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.example.data.settings.SettingsStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.repository.GitHubRepository
import com.example.data.repository.WorkflowRunInfo
import com.example.domain.model.ReasoningLevel
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ReasoningPurple
import com.example.ui.theme.RoseError
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    gitHubRepository: GitHubRepository,
    settingsStore: SettingsStore,
    onFeedBuildErrorToChat: ((String) -> Unit)? = null,
    onPushAndBuild: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var globalSystemPrompt by remember { mutableStateOf(settingsStore.globalSystemPrompt) }
    var safeModeEnabled by remember { mutableStateOf(settingsStore.safeMode) }
    var selectedReasoningLevel by remember { mutableStateOf(settingsStore.reasoningLevel) }
    var selectedLanguage by remember { mutableStateOf(settingsStore.language) }
    var maxSteps by remember { mutableIntStateOf(settingsStore.maxSteps) }
    var autoPushBuild by remember { mutableStateOf(settingsStore.autoPushBuild) }
    var maxFixAttempts by remember { mutableIntStateOf(settingsStore.maxBuildFixAttempts) }

    // GitHub Settings
    var githubPat by remember { mutableStateOf(settingsStore.githubToken) }
    var githubRepo by remember { mutableStateOf(settingsStore.githubRepo) }
    var githubBranch by remember { mutableStateOf(settingsStore.githubBranch) }

    // Persist every change
    LaunchedEffect(globalSystemPrompt) { settingsStore.globalSystemPrompt = globalSystemPrompt }
    LaunchedEffect(safeModeEnabled) { settingsStore.safeMode = safeModeEnabled }
    LaunchedEffect(selectedReasoningLevel) { settingsStore.reasoningLevel = selectedReasoningLevel }
    LaunchedEffect(selectedLanguage) { settingsStore.language = selectedLanguage }
    LaunchedEffect(maxSteps) { settingsStore.maxSteps = maxSteps }
    LaunchedEffect(autoPushBuild) { settingsStore.autoPushBuild = autoPushBuild }
    LaunchedEffect(maxFixAttempts) { settingsStore.maxBuildFixAttempts = maxFixAttempts }
    LaunchedEffect(githubPat) { settingsStore.githubToken = githubPat }
    LaunchedEffect(githubRepo) { settingsStore.githubRepo = githubRepo }
    LaunchedEffect(githubBranch) { settingsStore.githubBranch = githubBranch }

    var isCheckingGitHub by remember { mutableStateOf(false) }
    var githubConnected by remember { mutableStateOf<Boolean?>(null) }
    var workflowRuns by remember { mutableStateOf<List<WorkflowRunInfo>>(emptyList()) }
    var isLoadingRuns by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Toolbar
        item {
            Text(
                text = "Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.textPrimary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        // Appearance
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Appearance",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val options = listOf(
                            ThemeMode.SYSTEM to "System",
                            ThemeMode.LIGHT to "Light",
                            ThemeMode.DARK to "Dark"
                        )
                        for ((mode, label) in options) {
                            val selected = AppColors.mode == mode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        AppColors.mode = mode
                                        settingsStore.themeMode = mode
                                    }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) AppColors.accent else AppColors.textSecondary,
                                    modifier = Modifier
                                        .padding(vertical = 10.dp)
                                        .fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        // Global System Instructions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Global System Instructions",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Rules injected into every agent prompt across all projects.",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = globalSystemPrompt,
                        onValueChange = { globalSystemPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            }
        }

        // Reasoning / Thinking Level
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = ReasoningPurple, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Thinking / Reasoning Effort",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Maps to Claude extended thinking tokens, OpenAI reasoning effort, or Gemini thinking budget.",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (level in ReasoningLevel.values()) {
                            val isSel = selectedReasoningLevel == level
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) ReasoningPurple else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedReasoningLevel = level }
                                    .padding(vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = level.displayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSel) Color.White else AppColors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Safety & Guardrails
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Safe Mode (Confirm before write)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                            }
                            Text("Asks your confirmation before the agent edits, writes, moves or deletes files.", fontSize = 11.sp, color = AppColors.textSecondary)
                        }
                        Switch(
                            checked = safeModeEnabled,
                            onCheckedChange = { safeModeEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan)
                        )
                    }
                }
            }
        }

        // Language & Max Steps
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Agent Response Language", fontSize = 13.sp, color = AppColors.textPrimary)
                        }
                        Text(
                            "$selectedLanguage  ▸",
                            fontSize = 12.sp,
                            color = CyberCyan,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable {
                                val list = SettingsStore.LANGUAGES
                                val idx = list.indexOf(selectedLanguage)
                                selectedLanguage = list[(idx + 1) % list.size]
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = ForgeAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Max Steps Per Agent Loop", fontSize = 13.sp, color = AppColors.textPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { maxSteps = (maxSteps - 5).coerceAtLeast(5) }) { Text("−5", fontSize = 12.sp) }
                            Text("$maxSteps steps", fontSize = 12.sp, color = ForgeAmber, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { maxSteps = (maxSteps + 5).coerceAtMost(100) }) { Text("+5", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }

        // GitHub Actions CI Integration
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GitHub Actions CI & Push", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                    }
                    Text(
                        "Push your project to GitHub as one commit, run the Actions build, and let the AI read failure logs and fix them. Token is stored encrypted on this device.",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary
                    )

                    OutlinedTextField(
                        value = githubRepo,
                        onValueChange = { githubRepo = it },
                        label = { Text("Repository (e.g. username/my-app)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = githubBranch,
                        onValueChange = { githubBranch = it },
                        label = { Text("Branch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = githubPat,
                        onValueChange = { githubPat = it },
                        label = { Text("GitHub token (Contents: write, Actions: read)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto push, build & fix", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                            Text(
                                "After every AI change: push one commit, wait for the GitHub build, and let the AI fix compile errors automatically.",
                                fontSize = 11.sp,
                                color = AppColors.textSecondary
                            )
                        }
                        Switch(
                            checked = autoPushBuild,
                            onCheckedChange = { autoPushBuild = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Max auto-fix attempts", fontSize = 12.sp, color = AppColors.textPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { maxFixAttempts = (maxFixAttempts - 1).coerceAtLeast(1) }) { Text("−", fontSize = 14.sp) }
                            Text("$maxFixAttempts", fontSize = 12.sp, color = ForgeAmber, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { maxFixAttempts = (maxFixAttempts + 1).coerceAtMost(8) }) { Text("+", fontSize = 14.sp) }
                        }
                    }
                    Button(
                        onClick = {
                            if (githubRepo.isNotBlank() && githubPat.isNotBlank()) {
                                settingsStore.githubToken = githubPat
                                settingsStore.githubRepo = githubRepo
                                settingsStore.githubBranch = githubBranch
                                onPushAndBuild?.invoke()
                                Toast.makeText(context, "Pushing current project and watching the build (see Chat tab)…", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Enter repository and token first.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess)
                    ) {
                        Text("Push project & build now", fontSize = 12.sp, color = AppColors.onAccent)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (githubRepo.isNotBlank() && githubPat.isNotBlank()) {
                                    scope.launch {
                                        isCheckingGitHub = true
                                        val ok = gitHubRepository.testToken(githubPat.trim(), githubRepo.trim())
                                        githubConnected = ok
                                        if (ok) {
                                            isLoadingRuns = true
                                            workflowRuns = gitHubRepository.getRecentWorkflowRuns(githubPat.trim(), githubRepo.trim())
                                            isLoadingRuns = false
                                        }
                                        isCheckingGitHub = false
                                        Toast.makeText(context, if (ok) "GitHub Connected!" else "Connection failed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            if (isCheckingGitHub) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CyberCyan)
                            } else {
                                Text("Test Connection", fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                if (githubRepo.isNotBlank() && githubPat.isNotBlank()) {
                                    scope.launch {
                                        val ok = gitHubRepository.triggerWorkflowDispatch(githubPat.trim(), githubRepo.trim(), githubBranch.trim())
                                        Toast.makeText(context, if (ok) "Triggered build.yml run on GitHub!" else "Trigger failed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.onAccent)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Trigger Build", fontSize = 11.sp, color = AppColors.onAccent)
                        }
                    }

                    // Recent Workflow Runs
                    if (workflowRuns.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Recent Workflow Runs:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        for (run in workflowRuns.take(3)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.codeBg, RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${run.name} (#${run.id})", fontSize = 11.sp, color = AppColors.textPrimary)
                                    Text(
                                        "${run.status} • conclusion: ${run.conclusion ?: "in progress"}",
                                        fontSize = 10.sp,
                                        color = if (run.conclusion == "failure") RoseError else EmeraldSuccess
                                    )
                                }
                                if (run.conclusion == "failure") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val log = gitHubRepository.fetchFailureLog(githubPat.trim(), githubRepo.trim(), run.id)
                                                onFeedBuildErrorToChat?.invoke("GitHub Actions build failed for run #${run.id}:\n\n```\n$log\n```\nPlease fix this build error.")
                                                Toast.makeText(context, "Fed build error into chat!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Fix Build", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Backup & Restore
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = ForgeAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Backup & Security", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "CodeForge never transmits user telemetry or keys off-device. All API keys remain strictly hardware-encrypted in Android Keystore.",
                        fontSize = 11.sp,
                        color = AppColors.textSecondary
                    )
                }
            }
        }
    }
}
