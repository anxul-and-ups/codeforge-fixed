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
    onOpenUsage: (() -> Unit)? = null,
    onOpenGitHub: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var globalSystemPrompt by remember { mutableStateOf(settingsStore.globalSystemPrompt) }
    var safeModeEnabled by remember { mutableStateOf(settingsStore.safeMode) }
    var selectedReasoningLevel by remember { mutableStateOf(settingsStore.reasoningLevel) }
    var selectedLanguage by remember { mutableStateOf(settingsStore.language) }
    var maxSteps by remember { mutableIntStateOf(settingsStore.maxSteps) }

    // Persist every change
    LaunchedEffect(globalSystemPrompt) { settingsStore.globalSystemPrompt = globalSystemPrompt }
    LaunchedEffect(safeModeEnabled) { settingsStore.safeMode = safeModeEnabled }
    LaunchedEffect(selectedReasoningLevel) { settingsStore.reasoningLevel = selectedReasoningLevel }
    LaunchedEffect(selectedLanguage) { settingsStore.language = selectedLanguage }
    LaunchedEffect(maxSteps) { settingsStore.maxSteps = maxSteps }

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

        // API usage
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenUsage?.invoke() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("API usage & budget", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text("Tokens, cost, charts and monthly limit", fontSize = 12.sp, color = AppColors.textSecondary)
                    }
                    Text("›", fontSize = 20.sp, color = AppColors.textMuted)
                }
            }
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

        // GitHub
        item {
            val ghLogin = remember { settingsStore.githubLogin }
            Card(
                colors = CardDefaults.cardColors(containerColor = AppColors.surface),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenGitHub?.invoke() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.example.R.drawable.ic_github),
                        contentDescription = "GitHub",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AppColors.textPrimary),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (ghLogin.isBlank()) "Connect GitHub" else "GitHub · @$ghLogin",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary
                        )
                        Text(
                            text = if (ghLogin.isBlank()) "Enter your username and token to push code and build APKs" else "Push, create repos, accounts and build log",
                            fontSize = 12.sp,
                            color = AppColors.textSecondary
                        )
                    }
                    Text("›", fontSize = 20.sp, color = AppColors.textMuted)
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
