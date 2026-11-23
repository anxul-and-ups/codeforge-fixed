package com.example.ui.screens.preview

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.CodeForgeApp
import com.example.ui.components.ProjectWorkspaceTopBar
import com.example.ui.screens.chat.ChatViewModel
import com.example.ui.theme.AppColors
import java.io.File

/**
 * Dedicated Preview screen strictly matching the reference dark Preview tab:
 * - Minimal project top header
 * - Bordered Preview URL bar with live status dot, editable URL field, go arrow, and refresh button
 * - Quick chips for active Termux localhost servers and local Codebase HTML entrypoints
 * - Centered "Preview not running" idle state matching screenshot
 * - Live in-app WebView with JS console error capture + one-tap "Fix with Agent"
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProjectPreviewScreen(
    app: CodeForgeApp,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenChatHistory: () -> Unit,
    onSendErrorsToAgent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by chatViewModel.uiState.collectAsState()
    val codebaseFiles by chatViewModel.codebaseFiles.collectAsState()
    val runningServers by app.localhostServerManager.servers.collectAsState()
    val providers by chatViewModel.providers.collectAsState()
    val preferredId by chatViewModel.preferredProviderId.collectAsState()

    val activeProject = uiState.activeProject
    val projectName = activeProject?.name ?: "CodeForge"
    val activeProvider = providers.firstOrNull { it.id == preferredId } ?: providers.firstOrNull()
    val providerLabel = activeProvider?.name ?: "Provider"
    val convLabel = uiState.activeConversation?.title?.ifBlank { "New chat" } ?: "New chat"

    val htmlFiles = remember(codebaseFiles) {
        codebaseFiles.map { it.path }.filter {
            it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)
        }
    }

    var urlInput by remember { mutableStateOf("") }
    var activeUrl by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }
    val consoleErrors = remember { mutableStateListOf<String>() }

    fun loadTarget(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        consoleErrors.clear()
        urlInput = normalized
        activeUrl = normalized
        reloadNonce++
    }

    fun loadLocalHtml(relPath: String) {
        val root = chatViewModel.activeProjectRootFile() ?: return
        val target = File(root, relPath)
        if (!target.exists()) return
        consoleErrors.clear()
        val fileUrl = "file://${target.absolutePath}"
        urlInput = relPath
        activeUrl = fileUrl
        reloadNonce++
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        ProjectWorkspaceTopBar(
            title = projectName,
            subtitle = "$providerLabel · $convLabel",
            onBack = onBack,
            onHistoryClick = onOpenChatHistory
        )

        // URL Bar Strip matching reference screenshot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.surface)
                .border(0.5.dp, AppColors.border)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.bg)
                    .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (activeUrl != null) AppColors.ok else AppColors.textMuted.copy(alpha = 0.55f))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (urlInput.isEmpty()) {
                        Text(
                            text = "Preview URL",
                            fontSize = 15.sp,
                            color = AppColors.textSecondary
                        )
                    }
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 14.5.sp,
                            color = AppColors.textPrimary
                        ),
                        cursorBrush = SolidColor(AppColors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { loadTarget(urlInput) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preview_url_input")
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Load URL",
                    tint = AppColors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { loadTarget(urlInput) }
                        .testTag("preview_go_button")
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = {
                    if (activeUrl != null) {
                        consoleErrors.clear()
                        reloadNonce++
                    } else if (urlInput.isNotBlank()) {
                        loadTarget(urlInput)
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("preview_refresh_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reload Preview",
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Quick target chips (active Termux localhost servers or local HTML files)
        if (runningServers.isNotEmpty() || htmlFiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (srv in runningServers) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AppColors.diffAddBg,
                        modifier = Modifier
                            .border(1.dp, AppColors.ok.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { loadTarget(srv.url) }
                    ) {
                        Text(
                            text = "● ${srv.url}",
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.ok,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
                for (html in htmlFiles.take(5)) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AppColors.surface,
                        modifier = Modifier
                            .border(1.dp, AppColors.border, RoundedCornerShape(999.dp))
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { loadLocalHtml(html) }
                    ) {
                        Text(
                            text = html,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.accent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // Captured WebView JS/Runtime errors bar
        if (consoleErrors.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.diffRemoveBg)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = "Console error",
                    tint = AppColors.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${consoleErrors.size} console error(s): ${consoleErrors.last()}",
                    fontSize = 11.5.sp,
                    color = AppColors.diffRemoveText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppColors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val summary = consoleErrors.joinToString("\n")
                            onSendErrorsToAgent(
                                "The WebView preview reported these console/runtime errors while loading `$urlInput`:\n```\n$summary\n```\nInspect the web files and apply a minimal surgical fix."
                            )
                        }
                ) {
                    Text(
                        text = "Fix",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Main Preview Area: Idle empty state vs Live WebView
        val currentTarget = activeUrl
        if (currentTarget == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Preview not running",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.textPrimary
                    )
                    Text(
                        text = "Enter a localhost URL above, or start a local web server in the project Terminal.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = AppColors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                    if (htmlFiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val primaryHtml = htmlFiles.firstOrNull {
                            it.equals("index.html", ignoreCase = true) || it.endsWith("/index.html", ignoreCase = true)
                        } ?: htmlFiles.first()
                        Button(
                            onClick = { loadLocalHtml(primaryHtml) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.accent,
                                contentColor = AppColors.onAccent
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Preview $primaryHtml", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                    consoleErrors.add("[${msg.sourceId()}:${msg.lineNumber()}] ${msg.message()}")
                                }
                                return super.onConsoleMessage(msg)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    consoleErrors.add("Load error: ${error?.description}")
                                }
                            }
                        }
                        loadUrl(currentTarget)
                    }
                },
                update = { wv ->
                    val tagNonce = wv.tag as? Int
                    if (tagNonce != reloadNonce) {
                        wv.tag = reloadNonce
                        wv.loadUrl(currentTarget)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}
