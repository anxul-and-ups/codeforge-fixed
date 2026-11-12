package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CodeForgeApp
import com.example.data.agent.FileEditEngine
import com.example.data.repository.GitHubRepository
import com.example.data.settings.SettingsStore
import com.example.domain.model.FallbackStrategy
import com.example.domain.model.PermissionLevel
import com.example.domain.model.ProviderErrorClass
import com.example.domain.model.ReasoningLevel
import com.example.ui.components.TermuxQuickConnectPanel
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.AppColors
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.io.File

private data class FeatureHelpInfo(
    val title: String,
    val whatItDoes: String,
    val hinglishGuide: String,
    val whenToUse: String
)

@Composable
fun SettingsScreen(
    gitHubRepository: GitHubRepository,
    settingsStore: SettingsStore,
    onFeedBuildErrorToChat: ((String) -> Unit)? = null,
    onPushAndBuild: (() -> Unit)? = null,
    onOpenUsage: (() -> Unit)? = null,
    onOpenGitHub: (() -> Unit)? = null,
    onOpenProviders: (() -> Unit)? = null,
    onOpenBuilds: (() -> Unit)? = null,
    onReopenOnboarding: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as CodeForgeApp
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val engineHealth by app.termuxBridge.healthState.collectAsState()
    val providers by app.providerRepository.allProviders.collectAsState(initial = emptyList())
    val cachedModels by app.providerRepository.allCachedModels.collectAsState(initial = emptyList())
    val installedTools by app.database.installedToolDao().getAllTools().collectAsState(initial = emptyList())
    val failoverLogs = app.providerRepository.failoverHistory

    var searchQuery by remember { mutableStateOf("") }
    var globalSystemPrompt by remember { mutableStateOf(settingsStore.globalSystemPrompt) }
    var safeModeEnabled by remember { mutableStateOf(settingsStore.safeMode) }
    var autoPushBuild by remember { mutableStateOf(settingsStore.autoPushBuild) }
    var selectedReasoningLevel by remember { mutableStateOf(settingsStore.reasoningLevel) }
    var selectedLanguage by remember { mutableStateOf(settingsStore.language) }
    var maxSteps by remember { mutableIntStateOf(settingsStore.maxSteps) }
    var commandTimeout by remember { mutableIntStateOf(settingsStore.commandTimeoutSeconds) }
    var fallbackStrategy by remember { mutableStateOf(settingsStore.fallbackStrategy) }
    var permissionLevel by remember { mutableStateOf(settingsStore.defaultPermissionLevel) }
    var maxSwitches by remember { mutableIntStateOf(settingsStore.maxProviderSwitchesPerTurn) }
    var showSwitchNotice by remember { mutableStateOf(settingsStore.showProviderSwitchNotice) }
    var autoCompactOnContext by remember { mutableStateOf(settingsStore.autoCompactOnContextTooLong) }
    var autoCheckpoint by remember { mutableStateOf(settingsStore.autoCheckpointBeforeBuild) }
    var autoReadAgentMd by remember { mutableStateOf(settingsStore.autoReadAgentMd) }
    var autoUpdateAgentMd by remember { mutableStateOf(settingsStore.autoUpdateAgentMd) }
    var loopGuardEnabled by remember { mutableStateOf(settingsStore.loopGuardEnabled) }
    var codeFontSize by remember { mutableIntStateOf(settingsStore.codeFontSizeSp) }
    var softWrapCode by remember { mutableStateOf(settingsStore.softWrapCode) }
    var reduceMotion by remember { mutableStateOf(settingsStore.reduceMotion) }
    var contextThreshold by remember { mutableIntStateOf(settingsStore.contextSummarizeThresholdPercent) }
    var selfTestOutput by remember { mutableStateOf<String?>(null) }
    var showAuditLog by remember { mutableStateOf(false) }
    var auditLogText by remember { mutableStateOf("") }
    var activeSimFault by remember { mutableStateOf(app.providerRepository.simulatedNextFailure) }
    var activeHelpInfo by remember { mutableStateOf<FeatureHelpInfo?>(null) }

    LaunchedEffect(globalSystemPrompt) { settingsStore.globalSystemPrompt = globalSystemPrompt }
    LaunchedEffect(safeModeEnabled) { settingsStore.safeMode = safeModeEnabled }
    LaunchedEffect(autoPushBuild) { settingsStore.autoPushBuild = autoPushBuild }
    LaunchedEffect(selectedReasoningLevel) { settingsStore.reasoningLevel = selectedReasoningLevel }
    LaunchedEffect(selectedLanguage) { settingsStore.language = selectedLanguage }
    LaunchedEffect(maxSteps) { settingsStore.maxSteps = maxSteps }
    LaunchedEffect(commandTimeout) { settingsStore.commandTimeoutSeconds = commandTimeout }
    LaunchedEffect(fallbackStrategy) { settingsStore.fallbackStrategy = fallbackStrategy }
    LaunchedEffect(permissionLevel) { settingsStore.defaultPermissionLevel = permissionLevel }
    LaunchedEffect(maxSwitches) { settingsStore.maxProviderSwitchesPerTurn = maxSwitches }
    LaunchedEffect(showSwitchNotice) { settingsStore.showProviderSwitchNotice = showSwitchNotice }
    LaunchedEffect(autoCompactOnContext) { settingsStore.autoCompactOnContextTooLong = autoCompactOnContext }
    LaunchedEffect(autoCheckpoint) { settingsStore.autoCheckpointBeforeBuild = autoCheckpoint }
    LaunchedEffect(autoReadAgentMd) { settingsStore.autoReadAgentMd = autoReadAgentMd }
    LaunchedEffect(autoUpdateAgentMd) { settingsStore.autoUpdateAgentMd = autoUpdateAgentMd }
    LaunchedEffect(loopGuardEnabled) { settingsStore.loopGuardEnabled = loopGuardEnabled }
    LaunchedEffect(codeFontSize) { settingsStore.codeFontSizeSp = codeFontSize }
    LaunchedEffect(softWrapCode) { settingsStore.softWrapCode = softWrapCode }
    LaunchedEffect(reduceMotion) { settingsStore.reduceMotion = reduceMotion }
    LaunchedEffect(contextThreshold) { settingsStore.contextSummarizeThresholdPercent = contextThreshold }

    if (activeHelpInfo != null) {
        val info = activeHelpInfo!!
        AlertDialog(
            onDismissRequest = { activeHelpInfo = null },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = AppColors.accent,
                    modifier = Modifier.size(26.dp)
                )
            },
            title = {
                Text(
                    text = info.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "What it does (English):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.accent
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = info.whatItDoes,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = AppColors.textPrimary
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.accentSoft,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Asaan Bhasha Me (Hinglish):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.accent
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = info.hinglishGuide,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = AppColors.textPrimary
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "When to use (Kis condition me use karein):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.ok
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = info.whenToUse,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = AppColors.textSecondary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { activeHelpInfo = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.accent,
                        contentColor = AppColors.onAccent
                    )
                ) {
                    Text("Got it")
                }
            }
        )
    }

    fun matchesSearch(keywords: String): Boolean {
        val q = searchQuery.trim()
        return q.isEmpty() || keywords.contains(q, ignoreCase = true)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                    Text(
                        text = "Settings & Guide",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = "Tap the (i) button on any feature to see how & when to use it",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppColors.textMuted) },
                placeholder = { Text("Search settings (e.g. Termux, GitHub build, theme, fallback)…", fontSize = 13.sp) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.accent,
                    unfocusedBorderColor = AppColors.border,
                    focusedTextColor = AppColors.textPrimary,
                    unfocusedTextColor = AppColors.textPrimary
                )
            )
        }

        // 1. Termux Connection & Diagnostics
        if (matchesSearch("setup diagnostics termux connection engine health self-test repair audit")) {
            item {
                SettingsCard(
                    title = "1. Termux Connection & Workspace Engine",
                    subtitle = "Connect Termux for shell commands & file sync in ~/forge/projects",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Termux Connection & Workspace Engine",
                            whatItDoes = "Links CodeForge with Termux on `127.0.0.1:8765` so the AI agent can run `git`, `gh`, `python3`, and shell commands directly inside `~/forge/projects/<project>`.",
                            hinglishGuide = "Ye feature CodeForge ko aapke phone ke Termux se jodta hai. Iske andar SDK/JDK install karne ki zaroorat nahi hai kyunki APK build GitHub Actions cloud par hota hai. Termux sirf files sync karne, git commands chalane aur project inspect karne ke kaam aata hai.",
                            whenToUse = "• Jab aapko AI se terminal commands (`ls`, `git`, `python3`) chalwani hon.\n• 'Termux Connection' option par tap karke 3 simple commands copy karke Termux me paste karein."
                        )
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (engineHealth.engineRunning) AppColors.ok else AppColors.warn)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (engineHealth.engineRunning) {
                                "Termux Connected (${engineHealth.latencyMs} ms) · ~/forge/projects"
                            } else {
                                "Status: ${engineHealth.installState} (Tap 'Termux Connection' below)"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Clean Termux Connection option row (opens dialog when tapped)
                    TermuxQuickConnectPanel(
                        bridge = app.termuxBridge,
                        status = engineHealth,
                        onAutoConnectClick = {
                            scope.launch {
                                val r = app.termuxBridge.bootstrapAndStartEngine()
                                Toast.makeText(
                                    context,
                                    r.fold({ it }, { it.message ?: "Failed" }),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        onRefreshHealthClick = {
                            scope.launch {
                                val h = app.termuxBridge.refreshHealth()
                                Toast.makeText(
                                    context,
                                    if (h.engineRunning) "Termux Connected!" else "Termux status: ${h.installState}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        compactDefault = false
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val r = app.termuxBridge.bootstrapAndStartEngine()
                                    Toast.makeText(
                                        context,
                                        r.fold({ it }, { it.message ?: "Failed" }),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Auto-Start Bridge", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val h = app.termuxBridge.refreshHealth()
                                    val editCheck = FileEditEngine.applyEdit("fun hello() {\n  println(1)\n}", "println(1)", "println(2)")
                                    val cmdCheck = app.termuxBridge.execCommand("echo forge_self_test_ok", timeoutSec = 10)
                                    val readyProvs = providers.count { it.encryptedApiKey.isNotEmpty() }
                                    selfTestOutput = buildString {
                                        appendLine("✓ Self-Test Completed:")
                                        appendLine("• Termux Bridge: ${h.installState} (Connected: ${h.engineRunning})")
                                        appendLine("• Workspace Path: ~/forge/projects")
                                        appendLine("• Shell execution: exit=${cmdCheck.exitCode}, out='${cmdCheck.stdout.trim()}' (${cmdCheck.durationMs} ms)")
                                        appendLine("• FileEditEngine: ${if (editCheck.success) "PASS (${editCheck.strategyUsed})" else "FAIL"}")
                                        appendLine("• Providers with keys: $readyProvs / ${providers.size} (${cachedModels.size} cached models)")
                                    }.trim()
                                }
                            }
                        ) {
                            Text("Run Self-Test", fontSize = 12.sp, color = AppColors.accent)
                        }

                        OutlinedButton(
                            onClick = {
                                val report = buildString {
                                    appendLine("# CodeForge Diagnostic Report")
                                    appendLine("- Engine state: ${engineHealth.installState}, running=${engineHealth.engineRunning}")
                                    appendLine("- Workspace: ~/forge/projects")
                                    appendLine("- Providers configured: ${providers.count { it.encryptedApiKey.isNotEmpty() }}")
                                    appendLine("- Cached server models: ${cachedModels.size}")
                                    appendLine("- Fallback strategy: ${fallbackStrategy.name}")
                                }
                                clipboard.setText(AnnotatedString(report))
                                Toast.makeText(context, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy Report", fontSize = 12.sp)
                        }
                    }

                    if (selfTestOutput != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = AppColors.codeBg, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = selfTestOutput ?: "",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.ok,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                auditLogText = app.termuxBridge.readAuditLog(60)
                                showAuditLog = !showAuditLog
                            }
                        ) {
                            Text(if (showAuditLog) "Hide Command Log" else "View Command Log", fontSize = 12.sp, color = AppColors.accent)
                        }
                        if (onReopenOnboarding != null) {
                            TextButton(onClick = onReopenOnboarding) {
                                Text("Setup Wizard", fontSize = 12.sp, color = AppColors.textSecondary)
                            }
                        }
                    }
                    if (showAuditLog) {
                        Surface(shape = RoundedCornerShape(10.dp), color = AppColors.codeBg, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = auditLogText,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textSecondary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. GitHub Cloud Build & Auto-Sync
        if (matchesSearch("github cloud build actions apk auto push upload sync")) {
            item {
                SettingsCard(
                    title = "2. GitHub Actions Cloud Build & Sync",
                    subtitle = "Automatically upload modified files to GitHub & build APK in the cloud",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "GitHub Actions Cloud Build",
                            whatItDoes = "When the AI finishes editing your project in Build mode, it syncs the changed files via Termux, uploads them to your GitHub repository, triggers the GitHub Actions workflow, and shows an expandable `building` step card in Chat.",
                            hinglishGuide = "Jab aap koi GitHub repo select karte hain aur AI code me changes karta hai, to ye feature Termux ki madad se badle hue files ko GitHub par upload karta hai aur wahan GitHub Actions par APK build start kar deta hai. Chat me `building` card par tap karke aap ek-ek choti build step live dekh sakte hain.",
                            whenToUse = "• Jab aap chahte hain ki AI code edit karne ke turant baad GitHub par push karke APK build start kar de, tab 'Auto-Push & Build on GitHub' ko ON rakhein.\n• Agar aap pehle khud changes check karna chahte hain, to isse OFF rakhein aur chat ke upar Cloud button daba kar manual build karein."
                        )
                    }
                ) {
                    ToggleRow(
                        label = "Auto-Push & Build on GitHub after AI edits",
                        checked = autoPushBuild,
                        onCheckedChange = { autoPushBuild = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Auto-Push & Build on GitHub",
                                whatItDoes = "Automatically uploads changed files to your connected GitHub repository and runs the GitHub Actions build after every Build-mode turn that modifies files.",
                                hinglishGuide = "Isse ON rakhne par jab bhi AI kisi file me code write ya edit karega, wo apne aap changed files ko GitHub repo par upload karega aur APK build chalu kar dega.",
                                whenToUse = "• ON rakhein: Jab aapko har code change ke baad automatic APK build chahiye.\n• OFF rakhein: Jab aap kai chote edits karwa rahe hon aur aakhri me ek saath build karna chahte hon."
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenGitHub?.invoke() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("GitHub & Repos", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onOpenBuilds?.invoke() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Live Build Logs", fontSize = 12.sp)
                        }
                        if (onPushAndBuild != null) {
                            OutlinedButton(
                                onClick = onPushAndBuild,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Build Now", fontSize = 12.sp, color = AppColors.accent)
                            }
                        }
                    }
                }
            }
        }

        // 3. Appearance, Theme & Accent Palette
        if (matchesSearch("appearance theme dark light amoled accent amber indigo teal violet rose font wrap motion")) {
            item {
                SettingsCard(
                    title = "3. Appearance, Theme & Accent Color",
                    subtitle = "Default: Light Theme + Amber Accent",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Theme & Accent Customization",
                            whatItDoes = "Controls the app's visual theme (Light, Dark, AMOLED, System) and highlight accent color (Amber, Indigo, Teal, Violet, Rose).",
                            hinglishGuide = "Yahan se aap app ka look badal sakte hain. Default roop se Light Theme aur Amber color set hai taaki din me code aur chat bilkul saaf dikhe.",
                            whenToUse = "• Light + Amber (Default): Clean, bright daytime reading ke liye.\n• Dark / AMOLED: Raat me coding karte waqt aankhon ke aaram aur battery bachane ke liye."
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Theme Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        InfoIconButton {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Theme Mode",
                                whatItDoes = "Switches between Light, Dark, pure black AMOLED, or System default theme.",
                                hinglishGuide = "App ka background Light (safed) ya Dark (kaala) karne ke liye.",
                                whenToUse = "Light mode default hai; agar अंधेरे me use kar rahe hain to Dark ya AMOLED chunein."
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val modes = listOf(
                            ThemeMode.LIGHT to "Light",
                            ThemeMode.DARK to "Dark",
                            ThemeMode.AMOLED to "AMOLED",
                            ThemeMode.SYSTEM to "System"
                        )
                        for ((m, label) in modes) {
                            val sel = AppColors.mode == m
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, if (sel) AppColors.accent else AppColors.border, RoundedCornerShape(10.dp))
                                    .clickable {
                                        AppColors.mode = m
                                        settingsStore.themeMode = m
                                    }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) AppColors.accent else AppColors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Accent Color (Default: Amber)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (preset in AccentPreset.entries) {
                            val sel = AppColors.accentPreset == preset
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .border(1.dp, if (sel) preset.darkColor else AppColors.border, RoundedCornerShape(999.dp))
                                    .clickable {
                                        AppColors.accentPreset = preset
                                        settingsStore.accentPreset = preset
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(preset.darkColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        preset.label,
                                        fontSize = 11.sp,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        color = AppColors.textPrimary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "Soft-wrap long code lines",
                        checked = softWrapCode,
                        onCheckedChange = { softWrapCode = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Soft-Wrap Code Lines",
                                whatItDoes = "Wraps long lines of code onto the next line instead of scrolling horizontally.",
                                hinglishGuide = "Lambi code lines ko screen ke andar mod deta hai taaki aapko baar-baar left-right scroll na karna pade.",
                                whenToUse = "Phone ki choti screen par code padhne ke liye isse ON rakhna best hai."
                            )
                        }
                    )
                    ToggleRow(
                        label = "Reduce screen animations",
                        checked = reduceMotion,
                        onCheckedChange = { reduceMotion = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Reduce Motion Animations",
                                whatItDoes = "Disables screen transition animations for instant navigation.",
                                hinglishGuide = "Screen badalne ke animations band kar deta hai jisse app aur tez lagta hai.",
                                whenToUse = "Agar aapko instant screen switch pasand hai to ON karein."
                            )
                        }
                    )
                }
            }
        }

        // 4. AI Providers & Live Models
        if (matchesSearch("providers api keys models gemini claude openai deepseek groq openrouter")) {
            item {
                SettingsCard(
                    title = "4. AI Providers & Live Models",
                    subtitle = "Add API keys & fetch live model lists from provider servers",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "AI Providers & Live Server Models",
                            whatItDoes = "Stores your API keys encrypted in Android Keystore and fetches the real-time list of models directly from Google Gemini, OpenAI, Anthropic, DeepSeek, Groq, or OpenRouter.",
                            hinglishGuide = "Yahan aap apne AI providers (jaise Gemini, Claude, OpenAI, OpenRouter, DeepSeek) ki API key daal sakte hain. Key dalne ke baad ye server se saare latest models live fetch kar leta hai.",
                            whenToUse = "• Jab aapko naya AI provider add karna ho ya naye models refresh karne hon."
                        )
                    }
                ) {
                    val configuredCount = providers.count { it.encryptedApiKey.isNotEmpty() }
                    Text(
                        text = "$configuredCount provider(s) active · ${cachedModels.size} live models available",
                        fontSize = 13.sp,
                        color = AppColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onOpenProviders?.invoke() },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                        ) {
                            Text("Manage API Keys", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val results = app.providerRepository.testAllConfiguredProviders()
                                    val ok = results.count { it.ok }
                                    Toast.makeText(context, "Tested ${results.size} providers ($ok OK)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh Models", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 5. Agent Behavior, Permissions & Safety
        if (matchesSearch("agent behavior permissions safety loop guard steps timeout reasoning")) {
            item {
                SettingsCard(
                    title = "5. Agent Behavior, Safety & Limits",
                    subtitle = "Control how many steps the AI can take and what permissions it has",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Agent Behavior, Safety & Limits",
                            whatItDoes = "Configures whether the AI asks before running risky commands, stops infinite loops automatically, saves checkpoints before edits, and how long commands can run.",
                            hinglishGuide = "Ye section control karta hai ki AI agent kitne steps tak khud kaam kar sakta hai, galat loop me fasne par kab rukega, aur file edit karne se pehle backup (checkpoint) banayega ya nahi.",
                            whenToUse = "• Normal coding ke liye 'Remember per project' permission aur 'Loop Guard ON' sabse safe aur fast hai."
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Default Permission Level", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        InfoIconButton {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Default Permission Level",
                                whatItDoes = "• Ask every time: Asks confirmation before destructive shell commands.\n• Remember per project: Runs standard workspace commands automatically.\n• Auto-approve safe: Approves read/write/build commands without popups.",
                                hinglishGuide = "Tay karta hai ki AI command chalane se pehle aapse permission maangega ya nahi.",
                                whenToUse = "Fast coding ke liye 'Auto-approve safe' ya 'Remember per project' chunein."
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (lvl in PermissionLevel.entries) {
                            val sel = permissionLevel == lvl
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, if (sel) AppColors.accent else AppColors.border, RoundedCornerShape(10.dp))
                                    .clickable { permissionLevel = lvl }
                            ) {
                                Text(
                                    text = lvl.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) AppColors.accent else AppColors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "Loop Guard (Prevent repeated failing commands)",
                        checked = loopGuardEnabled,
                        onCheckedChange = { loopGuardEnabled = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Loop Guard",
                                whatItDoes = "Detects if the AI calls the exact same command or tool with the same error 3 times in a row and stops it from wasting tokens.",
                                hinglishGuide = "Agar AI ek hi galat command baar-baar chala kar atak jaye, to Loop Guard usse rok deta hai aur aapke tokens kharab hone se bachata hai.",
                                whenToUse = "Isse hamesha ON rakhein (Recommended)."
                            )
                        }
                    )
                    ToggleRow(
                        label = "Auto-Checkpoint before Build runs",
                        checked = autoCheckpoint,
                        onCheckedChange = { autoCheckpoint = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Auto-Checkpoint (Instant Undo)",
                                whatItDoes = "Saves a snapshot of your project files before the AI starts editing so you can revert any file or the entire run in 1 tap.",
                                hinglishGuide = "AI ke code badalne se pehle aapki files ka backup le leta hai. Agar AI koi galti kare to aap Working Files me jaakar 1-tap me 'Revert Run' kar sakte hain.",
                                whenToUse = "Hamesha ON rakhein taaki code kabhi kharab na ho."
                            )
                        }
                    )
                    ToggleRow(
                        label = "Strict Safe Mode (Ask before every file write)",
                        checked = safeModeEnabled,
                        onCheckedChange = { safeModeEnabled = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Strict Safe Mode",
                                whatItDoes = "Shows an approval dialog before the AI writes or edits any file.",
                                hinglishGuide = "Har ek file edit karne se pehle aapse Allow/Deny popup me puchega.",
                                whenToUse = "• OFF rakhein: Jab aap chahte hain ki AI bina ruke pura feature khud banaye.\n• ON rakhein: Jab aap kisi bahut sensitive file par kaam kar rahe hon."
                            )
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Max Steps Per Run", fontSize = 13.sp, color = AppColors.textPrimary)
                            InfoIconButton {
                                activeHelpInfo = FeatureHelpInfo(
                                    title = "Max Steps Per Run",
                                    whatItDoes = "Maximum number of tool calls (shell, read, edit, build) the AI can execute in a single turn.",
                                    hinglishGuide = "Ek message bhejne par AI zyada se zyada kitni commands/actions chala sakta hai.",
                                    whenToUse = "25–40 steps bade tasks ke liye best hai."
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { maxSteps = (maxSteps - 5).coerceAtLeast(5) }) { Text("−5") }
                            Text("$maxSteps steps", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                            TextButton(onClick = { maxSteps = (maxSteps + 5).coerceAtMost(100) }) { Text("+5") }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Command Timeout", fontSize = 13.sp, color = AppColors.textPrimary)
                            InfoIconButton {
                                activeHelpInfo = FeatureHelpInfo(
                                    title = "Command Timeout",
                                    whatItDoes = "Automatically terminates any single shell command that hangs longer than this many seconds.",
                                    hinglishGuide = "Agar koi shell command atak jaye to kitne seconds baad usse roka jaye.",
                                    whenToUse = "60s–120s normal git aur python scripts ke liye kafi hai."
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { commandTimeout = (commandTimeout - 30).coerceAtLeast(30) }) { Text("−30s") }
                            Text("${commandTimeout}s", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.accent)
                            TextButton(onClick = { commandTimeout = (commandTimeout + 30).coerceAtMost(600) }) { Text("+30s") }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Thinking / Reasoning Depth", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        InfoIconButton {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Thinking / Reasoning Depth",
                                whatItDoes = "Controls how deeply reasoning models think before writing code.",
                                hinglishGuide = "AI jawab dene se pehle kitna gehra sochega (Low = Fast, High/Max = Deep problem solving).",
                                whenToUse = "• Medium/High: Complex bugs fix karne ke liye.\n• Low: Fast replies ke liye."
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (level in ReasoningLevel.entries) {
                            val sel = selectedReasoningLevel == level
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedReasoningLevel = level }
                            ) {
                                Text(
                                    text = level.displayName,
                                    fontSize = 11.sp,
                                    color = if (sel) AppColors.accent else AppColors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 6. Auto-Failover & Rate Limit Recovery
        if (matchesSearch("fallback reliability failover rate limit 429 401 quota simulator strategy")) {
            item {
                SettingsCard(
                    title = "6. Auto-Failover & Rate-Limit Protection",
                    subtitle = "Automatically switch to backup AI provider if rate-limited (HTTP 429)",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Auto-Failover & Rate-Limit Protection",
                            whatItDoes = "If your primary AI provider hits a rate limit (429), runs out of quota, or times out in the middle of a task, CodeForge automatically switches to your next configured provider without losing progress.",
                            hinglishGuide = "Agar kaam karte waqt ek AI model ki limit khatam ho jaye (Error 429 ya Quota full), to ye feature bina aapka kaam roke dusre provider/model par switch kar deta hai.",
                            whenToUse = "• Jab aapke paas 2 ya zyada providers (jaise Gemini + OpenRouter/Groq) ki keys hon, tab 'Automatic Chain' select rakhein."
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (st in FallbackStrategy.entries) {
                            val sel = fallbackStrategy == st
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) AppColors.accentSoft else AppColors.surfaceAlt,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, if (sel) AppColors.accent else AppColors.border, RoundedCornerShape(10.dp))
                                    .clickable { fallbackStrategy = st }
                            ) {
                                Text(
                                    text = st.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) AppColors.accent else AppColors.textSecondary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "Show banner when provider auto-switches",
                        checked = showSwitchNotice,
                        onCheckedChange = { showSwitchNotice = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Provider Switch Banner",
                                whatItDoes = "Shows a small banner at the top of Chat whenever a backup provider takes over.",
                                hinglishGuide = "Jab AI ek model se dusre backup model par switch karega to upar chota notification dikhayega.",
                                whenToUse = "ON rakhein taaki aapko pata rahe kaunsa model chal raha hai."
                            )
                        }
                    )
                    ToggleRow(
                        label = "Auto-compact chat when context window is full",
                        checked = autoCompactOnContext,
                        onCheckedChange = { autoCompactOnContext = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Auto-Compact Long Context",
                                whatItDoes = "Automatically summarizes older messages if the chat history exceeds the model's token limit.",
                                hinglishGuide = "Jab chat bahut lambi ho jaye aur token limit bharne lage, to purane messages ko summarize karke error aane se rokta hai.",
                                whenToUse = "Lambi coding chats ke liye hamesha ON rakhein."
                            )
                        }
                    )

                    if (failoverLogs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Recent Failover Log", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        Surface(shape = RoundedCornerShape(10.dp), color = AppColors.codeBg, modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (ev in failoverLogs.take(5)) {
                                    Text(
                                        text = "${ev.fromProvider} → ${ev.toProvider}: ${ev.reason}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AppColors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Workspace Paths & Installed CLI/MCP Tools
        if (matchesSearch("tools mcp manager workspace projects clone install path")) {
            item {
                SettingsCard(
                    title = "7. Workspace Paths & Installed Tools",
                    subtitle = "Projects live in ~/forge/projects · Tools in ~/forge/tools",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Workspace Paths & Installed Tools",
                            whatItDoes = "Displays your unified Termux workspace paths (`~/forge/projects/<slug>`) and manages any external CLI tools or MCP servers installed in `~/forge/tools/`.",
                            hinglishGuide = "Aapke saare projects `~/forge/projects/<project-name>` folder ke andar rehte hain jisse Termux aur CodeForge dono bina kisi 'workdir does not exist' error ke ek hi folder par kaam karte hain.",
                            whenToUse = "• Yahan se aap check kar sakte hain ki kaunse CLI tools active hain."
                        )
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppColors.surfaceAlt,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Projects Root: ~/forge/projects",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.textPrimary
                            )
                            Text(
                                text = "Tools Root:    ~/forge/tools",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (installedTools.isEmpty()) {
                        Text(
                            text = "No extra CLI tools installed yet. Paste any GitHub tool repository URL in Chat (Build mode) to install it into ~/forge/tools/.",
                            fontSize = 12.sp,
                            color = AppColors.textMuted
                        )
                    } else {
                        for (tool in installedTools) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.surfaceAlt,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${tool.name} (${tool.type})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                                        Text("cmd: ${tool.entryCommand}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
                                        Text(
                                            text = app.termuxBridge.toDisplayPath(tool.installPath),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = AppColors.textMuted
                                        )
                                    }
                                    Switch(
                                        checked = tool.enabled,
                                        onCheckedChange = { en ->
                                            scope.launch { app.database.installedToolDao().setToolEnabled(tool.id, en) }
                                        }
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                val res = app.termuxBridge.execCommand("${tool.entryCommand} --help", cwd = tool.installPath, timeoutSec = 20)
                                                Toast.makeText(context, "Test exit=${res.exitCode}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Test tool", tint = AppColors.accent)
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                File(tool.installPath).deleteRecursively()
                                                app.database.installedToolDao().deleteTool(tool.id)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Uninstall tool", tint = AppColors.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 8. Project Memory (AGENT.md) & Custom Instructions
        if (matchesSearch("context memory agent.md instructions system prompt language")) {
            item {
                SettingsCard(
                    title = "8. Project Memory (AGENT.md) & Custom Prompt",
                    subtitle = "Remember project architecture & your personal coding rules",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Project Memory (AGENT.md) & Custom Prompt",
                            whatItDoes = "Reads `<project>/AGENT.md` before every run so the AI remembers your project structure, and applies your Global System Instructions across all chats.",
                            hinglishGuide = "Har project ke andar ek `AGENT.md` file hoti hai jisme AI project ke important rules aur architecture yaad rakhta hai. Neeche box me aap apni pasand ke rules (jaise 'Hamesha Hinglish me samjhao') likh sakte hain.",
                            whenToUse = "• Dono toggles ON rakhein taaki AI ko baar-baar pura project shuru se na samajhna pade."
                        )
                    }
                ) {
                    ToggleRow(
                        label = "Auto-read project AGENT.md at start of every run",
                        checked = autoReadAgentMd,
                        onCheckedChange = { autoReadAgentMd = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Auto-Read AGENT.md",
                                whatItDoes = "Loads the project's AGENT.md memory file into the AI prompt automatically.",
                                hinglishGuide = "Project shuru karte hi AI uski AGENT.md file padh leta hai jisse usse project ka structure turant samajh aa jata hai.",
                                whenToUse = "Hamesha ON rakhein."
                            )
                        }
                    )
                    ToggleRow(
                        label = "Auto-update AGENT.md with learned project notes",
                        checked = autoUpdateAgentMd,
                        onCheckedChange = { autoUpdateAgentMd = it },
                        onInfoClick = {
                            activeHelpInfo = FeatureHelpInfo(
                                title = "Auto-Update AGENT.md",
                                whatItDoes = "Saves key commands and architecture notes into AGENT.md after successful runs.",
                                hinglishGuide = "Jab AI project me kuch naya seekhta hai to AGENT.md me note kar leta hai taaki agli baar aur fast kaam kare.",
                                whenToUse = "Hamesha ON rakhein."
                            )
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Global Custom Instructions", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = globalSystemPrompt,
                        onValueChange = { globalSystemPrompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        placeholder = { Text("e.g. Reply in Hinglish, keep code clean and modular…", fontSize = 12.sp) }
                    )
                }
            }
        }

        // 9. Usage & Token Analytics
        if (matchesSearch("usage budget costs analytics tokens")) {
            item {
                SettingsCard(
                    title = "9. Token Usage & Cost Analytics",
                    subtitle = "Track input/output tokens and latency across providers",
                    onInfoClick = {
                        activeHelpInfo = FeatureHelpInfo(
                            title = "Token Usage & Cost Analytics",
                            whatItDoes = "Shows how many tokens and requests you have used per AI model and provider.",
                            hinglishGuide = "Aapne kis AI model par kitne tokens aur requests use kiye hain, uska pura hisaab dikhata hai.",
                            whenToUse = "• Jab aapko apna API usage ya daily budget check karna ho."
                        )
                    }
                ) {
                    OutlinedButton(
                        onClick = { onOpenUsage?.invoke() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Token Usage & Budget Dashboard", fontSize = 12.sp, color = AppColors.accent)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    onInfoClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = AppColors.textSecondary
                        )
                    }
                }
                if (onInfoClick != null) {
                    InfoIconButton(onClick = onInfoClick)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun InfoIconButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Feature info",
            tint = AppColors.accent,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfoClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = AppColors.textPrimary,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (onInfoClick != null) {
                Spacer(modifier = Modifier.width(4.dp))
                InfoIconButton(onClick = onInfoClick)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.onAccent,
                checkedTrackColor = AppColors.accent
            )
        )
    }
}
