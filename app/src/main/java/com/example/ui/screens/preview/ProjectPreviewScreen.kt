package com.example.ui.screens.preview

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
 * Dedicated Preview screen matching the reference dark Preview tab, upgraded with:
 * - Virtual HTTPS local project asset resolution (`https://codeforge.local/`) for HTML/CSS/JS/images/fonts
 * - Desktop/Mobile viewport toggle, Zoom controls, Full-screen mode
 * - Expandable JS Console capturing LOG, WARN, ERROR + one-tap "Fix with Agent"
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
    val projectRoot: File? = remember(activeProject?.id, activeProject?.rootPath) {
        chatViewModel.activeProjectRootFile()
    }

    val htmlFiles = remember(codebaseFiles) {
        codebaseFiles.map { it.path }.filter {
            it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)
        }
    }

    var urlInput by remember { mutableStateOf("") }
    var activeUrl by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var desktopMode by remember { mutableStateOf(false) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var isFullScreen by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<String>() }

    fun loadTarget(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        val normalized = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("file://", ignoreCase = true) -> trimmed
            trimmed.endsWith(".html", ignoreCase = true) || trimmed.endsWith(".htm", ignoreCase = true) ->
                LocalProjectWebViewHelper.buildVirtualUrl(trimmed)
            else -> "http://$trimmed"
        }
        consoleLogs.clear()
        urlInput = trimmed
        activeUrl = normalized
        reloadNonce++
    }

    fun loadLocalHtml(relPath: String) {
        val root = projectRoot ?: return
        val target = File(root, relPath)
        if (!target.exists()) return
        consoleLogs.clear()
        urlInput = relPath
        activeUrl = LocalProjectWebViewHelper.buildVirtualUrl(relPath)
        reloadNonce++
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.bg)
    ) {
        if (!isFullScreen) {
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.bg)
                        .border(1.dp, AppColors.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (activeUrl != null) AppColors.ok else AppColors.textMuted.copy(alpha = 0.55f))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (urlInput.isEmpty()) {
                            Text(
                                text = "Preview URL or index.html",
                                fontSize = 14.5.sp,
                                color = AppColors.textSecondary
                            )
                        }
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 14.sp,
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
                    Spacer(modifier = Modifier.width(6.dp))
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

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = {
                        if (activeUrl != null) {
                            consoleLogs.clear()
                            reloadNonce++
                        } else if (urlInput.isNotBlank()) {
                            loadTarget(urlInput)
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("preview_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload Preview",
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            // Preview Controls Strip (Desktop/Mobile, Zoom, Fullscreen, JS Console, Local Targets)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeUrl != null) {
                    PreviewControlChip(
                        label = if (desktopMode) "Desktop" else "Mobile",
                        icon = if (desktopMode) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                        active = desktopMode
                    ) {
                        desktopMode = !desktopMode
                        reloadNonce++
                    }
                    PreviewControlChip(label = "$zoomPercent%", icon = Icons.Default.ZoomOut, active = false) {
                        zoomPercent = (zoomPercent - 15).coerceAtLeast(50)
                    }
                    PreviewControlChip(label = "+", icon = Icons.Default.ZoomIn, active = false) {
                        zoomPercent = (zoomPercent + 15).coerceAtMost(200)
                    }
                    PreviewControlChip(label = "Full", icon = Icons.Default.Fullscreen, active = false) {
                        isFullScreen = true
                    }
                    val errCount = consoleLogs.count { it.startsWith("[ERROR]") }
                    PreviewControlChip(
                        label = "Console (${consoleLogs.size})",
                        icon = Icons.Default.BugReport,
                        active = showConsole || errCount > 0
                    ) {
                        showConsole = !showConsole
                    }
                }
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
                for (html in htmlFiles.take(8)) {
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
                        text = "Enter a localhost URL above, or preview local HTML/CSS/JS files from your Codebase.",
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.allowFileAccess = true
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = desktopMode
                            settings.textZoom = zoomPercent
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            if (desktopMode) {
                                settings.userAgentString = LocalProjectWebViewHelper.DESKTOP_USER_AGENT
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                    val level = when (msg.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                        else -> "LOG"
                                    }
                                    consoleLogs.add("[$level] ${msg.message()} (${msg.sourceId()?.substringAfterLast('/') ?: "page"}:${msg.lineNumber()})")
                                    if (level == "ERROR") showConsole = true
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    return LocalProjectWebViewHelper.interceptLocalRequest(projectRoot, request?.url)
                                        ?: super.shouldInterceptRequest(view, request)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        consoleLogs.add("[ERROR] Load error: ${error?.description}")
                                        showConsole = true
                                    }
                                }
                            }
                            tag = "$currentTarget|$reloadNonce|$desktopMode"
                            loadUrl(currentTarget)
                        }
                    },
                    update = { wv ->
                        wv.settings.textZoom = zoomPercent
                        wv.settings.useWideViewPort = desktopMode
                        wv.settings.userAgentString = if (desktopMode) {
                            LocalProjectWebViewHelper.DESKTOP_USER_AGENT
                        } else {
                            WebSettings.getDefaultUserAgent(wv.context)
                        }
                        val nextTag = "$currentTarget|$reloadNonce|$desktopMode"
                        if (wv.tag != nextTag) {
                            wv.tag = nextTag
                            wv.clearCache(true)
                            wv.loadUrl(currentTarget)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isFullScreen) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AppColors.surface.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .border(1.dp, AppColors.border, RoundedCornerShape(999.dp))
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { isFullScreen = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FullscreenExit, contentDescription = "Exit full screen", tint = AppColors.textPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Exit Full", fontSize = 11.sp, color = AppColors.textPrimary)
                        }
                    }
                }
            }

            // Expandable JS Console Drawer
            if (showConsole && !isFullScreen) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 84.dp, max = 190.dp)
                        .background(AppColors.surface)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "JS Console (${consoleLogs.size})",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (consoleLogs.isNotEmpty()) {
                            Text(
                                text = "Clear",
                                fontSize = 11.sp,
                                color = AppColors.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { consoleLogs.clear() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AppColors.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val summary = consoleLogs.joinToString("\n")
                                        onSendErrorsToAgent(
                                            "The WebView preview reported these console/runtime errors while loading `$urlInput`:\n```\n$summary\n```\nInspect the web files and apply a minimal surgical fix."
                                        )
                                    }
                            ) {
                                Text(
                                    text = "Fix with Agent",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.onAccent,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (consoleLogs.isEmpty()) {
                            Text("No console output yet.", fontSize = 11.sp, color = AppColors.textMuted)
                        } else {
                            for (line in consoleLogs) {
                                val c = when {
                                    line.startsWith("[ERROR]") -> AppColors.error
                                    line.startsWith("[WARN]") -> AppColors.warn
                                    else -> AppColors.textSecondary
                                }
                                Text(
                                    text = line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = c
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewControlChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) AppColors.accentSoft else AppColors.surface,
        modifier = Modifier
            .border(1.dp, if (active) AppColors.accent else AppColors.border, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) AppColors.accent else AppColors.textSecondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) AppColors.accent else AppColors.textSecondary
            )
        }
    }
}
