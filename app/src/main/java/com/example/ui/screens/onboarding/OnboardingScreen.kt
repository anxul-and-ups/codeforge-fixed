package com.example.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CodeForgeApp
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    app: CodeForgeApp,
    onOpenProviders: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val engineHealth by app.termuxBridge.healthState.collectAsState()
    val providers by app.providerRepository.allProviders.collectAsState(initial = emptyList())
    val projects by app.projectRepository.allProjects.collectAsState(initial = emptyList())
    var setupMessage by remember { mutableStateOf<String?>(null) }

    val configuredProviders = providers.count { it.encryptedApiKey.isNotEmpty() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Welcome to CodeForge v2",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.textPrimary
        )
        Text(
            text = "Set up your Termux / POSIX execution engine, connect at least one AI provider with live server model discovery, and pick your workspace.",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = AppColors.textSecondary
        )

        // Step 1: Connect Termux
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (engineHealth.engineRunning) AppColors.ok else AppColors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AppColors.accentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Step 1 · Termux & Forge Engine", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text(
                            text = if (engineHealth.engineRunning) "Connected ✓ (${engineHealth.latencyMs} ms)" else "Status: ${engineHealth.installState}",
                            fontSize = 12.sp,
                            color = if (engineHealth.engineRunning) AppColors.ok else AppColors.textSecondary
                        )
                    }
                }
                Text(
                    text = "Tap 'Termux Connection' below to open the 3-step setup commands for Termux (Git, GitHub CLI & Python Bridge in ~/forge/projects).",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
                com.example.ui.components.TermuxQuickConnectPanel(
                    bridge = app.termuxBridge,
                    status = engineHealth,
                    onAutoConnectClick = {
                        scope.launch {
                            val res = app.termuxBridge.bootstrapAndStartEngine()
                            setupMessage = res.fold({ "✓ $it" }, { "ℹ️ ${it.message}" })
                        }
                    },
                    onRefreshHealthClick = {
                        scope.launch {
                            val h = app.termuxBridge.refreshHealth()
                            setupMessage = if (h.engineRunning) "✓ Termux HTTP Bridge Connected!" else "Status: ${h.installState}"
                        }
                    },
                    compactDefault = false
                )
                if (setupMessage != null) {
                    Surface(shape = RoundedCornerShape(8.dp), color = AppColors.codeBg, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = setupMessage ?: "",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.textPrimary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // Step 2: Add Provider & Test Connection
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (configuredProviders > 0) AppColors.ok else AppColors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AppColors.accentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Step 2 · Add Provider & Fetch Live Models", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text(
                            text = if (configuredProviders > 0) "$configuredProviders provider(s) configured ✓" else "No API keys added yet",
                            fontSize = 12.sp,
                            color = if (configuredProviders > 0) AppColors.ok else AppColors.textSecondary
                        )
                    }
                }
                Text(
                    text = "Add your Anthropic, OpenAI, Gemini, DeepSeek, Groq, OpenRouter, or custom endpoint key and tap Test Connection to fetch real models from the server.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
                OutlinedButton(onClick = onOpenProviders) {
                    Text("Configure Providers & Keys", fontSize = 12.sp, color = AppColors.accent)
                }
            }
        }

        // Step 3: Workspace & Projects
        Card(
            colors = CardDefaults.cardColors(containerColor = AppColors.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppColors.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AppColors.accentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Step 3 · Projects & AGENT.md Memory", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Text(
                            text = "${projects.size} project(s) in ~/forge/projects",
                            fontSize = 12.sp,
                            color = AppColors.textSecondary
                        )
                    }
                }
                Text(
                    text = "Workspace directories (`~/forge/{projects,tools,checkpoints,outputs,.engine}`) are ready.",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    app.settingsStore.onboardingCompleted = true
                    onFinish()
                }
            ) {
                Text("Skip for now", color = AppColors.textMuted)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    app.settingsStore.onboardingCompleted = true
                    onFinish()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Enter CodeForge v2")
            }
        }
    }
}
