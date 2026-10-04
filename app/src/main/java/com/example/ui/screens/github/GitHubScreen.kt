package com.example.ui.screens.github

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.github.GitHubManager
import com.example.data.logs.LogKind
import com.example.data.repository.GhRepo
import com.example.data.repository.GhUser
import com.example.data.repository.PushPlan
import com.example.data.settings.SettingsStore
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import kotlinx.coroutines.launch

@Composable
fun GitHubScreen(
    manager: GitHubManager,
    settings: SettingsStore,
    chatViewModel: ChatViewModel,
    onOpenBuilds: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by chatViewModel.uiState.collectAsState()
    val project = uiState.activeProject
    val logLines by manager.log.lines.collectAsState()

    // account state
    var accountsVersion by remember { mutableIntStateOf(0) }
    val accounts = remember(accountsVersion) { settings.githubAccounts() }
    val activeLogin = remember(accountsVersion) { settings.githubLogin }
    var showAddAccount by remember { mutableStateOf(false) }

    var user by remember { mutableStateOf<GhUser?>(null) }
    LaunchedEffect(activeLogin, accountsVersion) {
        user = if (activeLogin.isNotBlank()) manager.profile(activeLogin) else null
    }

    // repo link of the current project
    var linkedRepo by remember(project?.id, accountsVersion) {
        mutableStateOf(if (project != null) settings.repoFor(project.id) else null)
    }
    var showRepoPicker by remember { mutableStateOf(false) }
    var showCreateRepo by remember { mutableStateOf(false) }

    // push flow
    var deleteExtra by remember { mutableStateOf(true) }
    var planning by remember { mutableStateOf(false) }
    var pushing by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<PushPlan?>(null) }

    // build options
    var autoPush by remember { mutableStateOf(settings.autoPushBuild) }
    var askFix by remember { mutableStateOf(settings.askBeforeBuildFix) }
    var attempts by remember { mutableIntStateOf(settings.maxBuildFixAttempts) }
    LaunchedEffect(autoPush) { settings.autoPushBuild = autoPush }
    LaunchedEffect(askFix) { settings.askBeforeBuildFix = askFix }
    LaunchedEffect(attempts) { settings.maxBuildFixAttempts = attempts }

    fun startPush(repo: String) {
        val p = project ?: return
        scope.launch {
            planning = true
            try {
                plan = manager.plan(p.id, repo, deleteExtra)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                manager.log.error(e.message ?: "Could not compare with the repository")
                Toast.makeText(context, e.message ?: "Could not compare with the repository", Toast.LENGTH_LONG).show()
            } finally {
                planning = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ------------- Header -------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = "GitHub",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AppColors.textPrimary),
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (accounts.isEmpty()) "Connect GitHub" else "GitHub",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.textPrimary
            )
        }

        if (accounts.isEmpty()) {
            ConnectForm(
                manager = manager,
                title = null,
                onConnected = {
                    accountsVersion++
                    Toast.makeText(context, "GitHub connected", Toast.LENGTH_SHORT).show()
                }
            )
            HowToToken()
        } else {
            // ------------- Account card -------------
            AccountCard(
                manager = manager,
                user = user,
                login = activeLogin
            )

            // account switcher
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accounts", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textMuted)
                    for (acc in accounts) {
                        val isActive = acc.login.equals(activeLogin, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isActive) AppColors.accentSoft else Color.Transparent)
                                .clickable {
                                    if (!isActive) {
                                        manager.switchAccount(acc.login)
                                        accountsVersion++
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "@" + acc.login,
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = AppColors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (isActive) Text("Active", fontSize = 12.sp, color = AppColors.accent)
                            else Text("Switch", fontSize = 12.sp, color = AppColors.textSecondary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Remove",
                                fontSize = 12.sp,
                                color = AppColors.error,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        manager.removeAccount(acc.login)
                                        accountsVersion++
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                    OutlinedButton(onClick = { showAddAccount = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add account", color = AppColors.textPrimary)
                    }
                }
            }

            // ------------- Repository / push -------------
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Push this project", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textMuted)
                    Text(
                        text = "Project: " + (project?.name ?: "none open"),
                        fontSize = 14.sp,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = if (linkedRepo != null) "Repository: $linkedRepo" else "No repository selected yet.",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (linkedRepo != null) AppColors.accent else AppColors.textSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { showRepoPicker = true }, modifier = Modifier.weight(1f)) {
                            Text("Choose repo", color = AppColors.textPrimary, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { showCreateRepo = true }, modifier = Modifier.weight(1f)) {
                            Text("Create new repo", color = AppColors.textPrimary, fontSize = 13.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Replace repo content", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                            Text(
                                "Files that exist only in the repo are deleted, so the repo matches your project.",
                                fontSize = 11.sp,
                                color = AppColors.textSecondary
                            )
                        }
                        Switch(
                            checked = deleteExtra,
                            onCheckedChange = { deleteExtra = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AppColors.onAccent, checkedTrackColor = AppColors.accent)
                        )
                    }
                    Button(
                        enabled = project != null && linkedRepo != null && !planning && !pushing,
                        onClick = { linkedRepo?.let { startPush(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                    ) {
                        if (planning || pushing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.onAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (pushing) "Pushing…" else if (planning) "Comparing…" else "Push to GitHub")
                    }
                }
            }

            // ------------- Build options -------------
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Builds", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textMuted)
                    ToggleRow(
                        title = "Auto push, build & fix",
                        subtitle = "After every AI change: push, wait for the GitHub build and let the AI fix compile errors.",
                        checked = autoPush,
                        onChange = { autoPush = it }
                    )
                    ToggleRow(
                        title = "Ask before fixing build errors",
                        subtitle = "When a build fails, the AI waits for your OK before changing code.",
                        checked = askFix,
                        onChange = { askFix = it }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Max auto-fix attempts", fontSize = 13.sp, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { attempts = (attempts - 1).coerceAtLeast(1) }) { Text("−", fontSize = 16.sp) }
                        Text("$attempts", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
                        TextButton(onClick = { attempts = (attempts + 1).coerceAtMost(8) }) { Text("+", fontSize = 16.sp) }
                    }
                    TextButton(onClick = onOpenBuilds) { Text("Open build errors (Builds tab)", color = AppColors.accent) }
                }
            }
        }

        // ------------- Terminal log -------------
        TerminalCard(manager = manager, lines = logLines)
        Spacer(modifier = Modifier.height(24.dp))
    }

    // ---------------- Dialogs ----------------
    if (showAddAccount) {
        AddAccountDialog(
            manager = manager,
            onDismiss = { showAddAccount = false },
            onConnected = {
                showAddAccount = false
                accountsVersion++
                Toast.makeText(context, "GitHub connected", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showRepoPicker) {
        RepoPickerDialog(
            manager = manager,
            title = "Choose repository",
            onDismiss = { showRepoPicker = false },
            onPick = { repo ->
                showRepoPicker = false
                project?.let {
                    settings.linkRepo(it.id, repo.fullName)
                    linkedRepo = repo.fullName
                    manager.log.info("Selected ${repo.fullName} for \"${it.name}\"")
                }
            }
        )
    }

    if (showCreateRepo) {
        CreateRepoDialog(
            defaultName = (project?.name ?: "my-app").lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "my-app" },
            onDismiss = { showCreateRepo = false },
            onCreate = { name, desc, priv ->
                scope.launch {
                    try {
                        val repo = manager.createRepo(name, desc, priv)
                        project?.let {
                            settings.linkRepo(it.id, repo.fullName)
                            linkedRepo = repo.fullName
                        }
                        showCreateRepo = false
                        Toast.makeText(context, "Repository ${repo.fullName} created", Toast.LENGTH_LONG).show()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Could not create the repository", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    val currentPlan = plan
    if (currentPlan != null) {
        val total = currentPlan.newFiles.size + currentPlan.modifiedFiles.size + currentPlan.deletedFiles.size
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text(if (currentPlan.isEmpty) "Already up to date" else "Push to ${currentPlan.repo}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (currentPlan.isEmpty) {
                        Text("The repository already contains exactly the same files as your project.", fontSize = 14.sp)
                    } else {
                        if (currentPlan.repoIsEmpty) Text("The repository is empty. The whole project will be uploaded, folder by folder.", fontSize = 14.sp)
                        Text("New files: ${currentPlan.newFiles.size}", fontSize = 14.sp, color = AppColors.ok)
                        Text("Modified files: ${currentPlan.modifiedFiles.size}", fontSize = 14.sp, color = AppColors.warn)
                        Text(
                            "Deleted files: ${currentPlan.deletedFiles.size}",
                            fontSize = 14.sp,
                            color = if (currentPlan.deletedFiles.isNotEmpty()) AppColors.error else AppColors.textSecondary
                        )
                        if (currentPlan.protectedKept.isNotEmpty()) {
                            Text(
                                "${currentPlan.protectedKept.size} workflow file(s) in .github are kept so builds keep working.",
                                fontSize = 12.sp,
                                color = AppColors.textSecondary
                            )
                        }
                        val sample = (currentPlan.deletedFiles.take(4).map { "− $it" } + currentPlan.newFiles.take(3).map { "+ $it" })
                        if (sample.isNotEmpty()) {
                            Text(
                                sample.joinToString("\n"),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textSecondary
                            )
                        }
                        Text("Branch: ${currentPlan.branch}", fontSize = 12.sp, color = AppColors.textMuted)
                    }
                }
            },
            confirmButton = {
                if (!currentPlan.isEmpty) {
                    TextButton(onClick = {
                        val p = project
                        val repo = currentPlan.repo
                        plan = null
                        if (p != null) {
                            scope.launch {
                                pushing = true
                                try {
                                    val result = manager.push(
                                        projectId = p.id,
                                        repo = repo,
                                        deleteExtra = deleteExtra,
                                        message = "CodeForge: push $total file(s) from ${p.name}"
                                    )
                                    Toast.makeText(context, manager.describe(result, repo), Toast.LENGTH_LONG).show()
                                    if (!result.noChanges) manager.watchBuild(p.name, repo, result.commitSha)
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Push failed", Toast.LENGTH_LONG).show()
                                } finally {
                                    pushing = false
                                }
                            }
                        }
                    }) { Text("Push $total file${if (total == 1) "" else "s"}") }
                } else {
                    TextButton(onClick = { plan = null }) { Text("OK") }
                }
            },
            dismissButton = {
                if (!currentPlan.isEmpty) TextButton(onClick = { plan = null }) { Text("Cancel", color = AppColors.textSecondary) }
            }
        )
    }
}

// ----------------------------------------------------------------------------------------------
// Pieces
// ----------------------------------------------------------------------------------------------

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.accent,
    unfocusedBorderColor = AppColors.border,
    focusedLabelColor = AppColors.accent,
    unfocusedLabelColor = AppColors.textMuted,
    focusedTextColor = AppColors.textPrimary,
    unfocusedTextColor = AppColors.textPrimary,
    cursorColor = AppColors.accent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedPlaceholderColor = AppColors.textMuted,
    unfocusedPlaceholderColor = AppColors.textMuted
)

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
            Text(subtitle, fontSize = 11.sp, color = AppColors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AppColors.onAccent, checkedTrackColor = AppColors.accent)
        )
    }
}

@Composable
private fun ConnectForm(
    manager: GitHubManager,
    title: String?,
    onConnected: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("GitHub username") },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) {
                        Text(if (showToken) "Hide" else "Show", fontSize = 12.sp, color = AppColors.accent)
                    }
                },
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
            if (error != null) Text(error ?: "", fontSize = 12.sp, color = AppColors.error)
            Button(
                enabled = !saving && username.isNotBlank() && token.isNotBlank(),
                onClick = {
                    scope.launch {
                        saving = true
                        error = null
                        try {
                            manager.connect(username, token)
                            username = ""
                            token = ""
                            onConnected()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Could not connect"
                        } finally {
                            saving = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.onAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (saving) "Connecting…" else "Save")
            }
        }
    }
}

@Composable
private fun HowToToken() {
    Text(
        text = "How to get a token: GitHub → Settings → Developer settings → Personal access tokens. " +
            "Classic token: tick \"repo\" (and \"workflow\" if you change workflow files). " +
            "Fine-grained token: select your repositories and give Contents: Read and write, Actions: Read, Administration: Read and write only if you want to create repos.",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = AppColors.textSecondary
    )
}

@Composable
private fun AccountCard(manager: GitHubManager, user: GhUser?, login: String) {
    val avatar by produceState<android.graphics.Bitmap?>(initialValue = null, user?.avatarUrl) {
        value = user?.avatarUrl?.takeIf { it.isNotBlank() }?.let { manager.avatar(it) }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AppColors.surfaceAlt),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = avatar
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(login.take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppColors.textSecondary)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user?.name?.ifBlank { null } ?: login,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("@$login", fontSize = 13.sp, color = AppColors.textSecondary)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.accentSoft)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Connected", fontSize = 11.sp, color = AppColors.ok, fontWeight = FontWeight.SemiBold)
                }
            }
            if (user == null) {
                Text("Loading profile…", fontSize = 12.sp, color = AppColors.textMuted)
            } else {
                if (user.bio.isNotBlank()) Text(user.bio, fontSize = 13.sp, lineHeight = 18.sp, color = AppColors.textPrimary)
                val meta = listOf(user.company, user.location).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = AppColors.textSecondary)
                Text(
                    text = "${user.publicRepos} public repos  ·  ${user.followers} followers  ·  ${user.following} following",
                    fontSize = 12.sp,
                    color = AppColors.textMuted
                )
            }
        }
    }
}

@Composable
private fun AddAccountDialog(manager: GitHubManager, onDismiss: () -> Unit, onConnected: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = AppColors.bg) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ConnectForm(manager = manager, title = "Add GitHub account", onConnected = onConnected)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = AppColors.textSecondary)
                }
            }
        }
    }
}

@Composable
fun TerminalCard(manager: GitHubManager, lines: List<com.example.data.logs.LogLine>) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppColors.codeBg,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.border, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(">_  GitHub log", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    text = "Copy",
                    fontSize = 12.sp,
                    color = AppColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("GitHub log", manager.log.asText()))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(6.dp)
                )
                Text(
                    text = "Clear",
                    fontSize = 12.sp,
                    color = AppColors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { manager.log.clear() }
                        .padding(6.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (lines.isEmpty()) {
                Text("No activity yet. Connect GitHub, create a repo or push to see logs here.", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AppColors.textMuted)
            } else {
                val shown = lines.takeLast(60)
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                    for (l in shown) {
                        val (prefix, color) = when (l.kind) {
                            LogKind.CMD -> Pair("$ ", AppColors.accent)
                            LogKind.OK -> Pair("✓ ", AppColors.ok)
                            LogKind.ERROR -> Pair("✗ ", AppColors.error)
                            LogKind.INFO -> Pair("  ", AppColors.textSecondary)
                        }
                        Text(
                            text = prefix + l.text,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontFamily = FontFamily.Monospace,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateRepoDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isPrivate: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var desc by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create new repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Repository name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isPrivate) "Private" else "Public", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
                if (error != null) Text(error ?: "", color = AppColors.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                if (n.isEmpty() || !Regex("[A-Za-z0-9._-]+").matches(n)) {
                    error = "Use only letters, numbers, dots, dashes and underscores."
                } else {
                    onCreate(n, desc, isPrivate)
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** All repositories of the active account with search. [onPick] decides what happens (link or clone). */
@Composable
fun RepoPickerDialog(
    manager: GitHubManager,
    title: String,
    onDismiss: () -> Unit,
    onPick: (GhRepo) -> Unit
) {
    var repos by remember { mutableStateOf<List<GhRepo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            repos = manager.listRepos()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load repositories"
        }
        loading = false
    }
    val filtered = if (query.isBlank()) repos else repos.filter {
        it.fullName.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 40.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AppColors.textPrimary, modifier = Modifier.padding(horizontal = 18.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search repositories", fontSize = 14.sp) },
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
                when {
                    loading -> Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.accent)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Loading your repositories…", fontSize = 13.sp, color = AppColors.textSecondary)
                    }
                    error != null -> Text(error ?: "", fontSize = 13.sp, color = AppColors.error, modifier = Modifier.padding(18.dp))
                    filtered.isEmpty() -> Text(
                        if (repos.isEmpty()) "This account has no repositories yet." else "No repository matches your search.",
                        fontSize = 13.sp, color = AppColors.textMuted, modifier = Modifier.padding(18.dp)
                    )
                    else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.fullName }) { r ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(r) }
                                    .padding(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = r.fullName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AppColors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (r.isPrivate) Text("  private", fontSize = 11.sp, color = AppColors.warn)
                                    if (r.isFork) Text("  fork", fontSize = 11.sp, color = AppColors.textMuted)
                                }
                                if (r.description.isNotBlank()) {
                                    Text(r.description, fontSize = 12.sp, color = AppColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) {
                    Text("Close", color = AppColors.textSecondary)
                }
            }
        }
    }
}
