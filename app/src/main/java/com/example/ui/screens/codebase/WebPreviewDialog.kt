package com.example.ui.screens.codebase

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.screens.preview.LocalProjectWebViewHelper
import com.example.ui.theme.AppColors
import java.io.File

/**
 * Built-in Android WebView Live Preview for HTML/CSS/JS projects inside the Codebase.
 * Uses [LocalProjectWebViewHelper] virtual HTTPS origin (`https://codeforge.local/`) to serve
 * local HTML, CSS, JS, ES modules, images, and fonts reliably without file:// CORS failures.
 * Supports Desktop/Mobile toggle, Zoom controls, Full-screen mode, multi-HTML pages, and live JS Console.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewDialog(
    projectRoot: File,
    htmlRelativePaths: List<String>,
    initialHtmlPath: String,
    onDismiss: () -> Unit,
    onSendErrorsToAgent: (String) -> Unit
) {
    var activePath by remember { mutableStateOf(initialHtmlPath) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var showConsole by remember { mutableStateOf(false) }
    var desktopMode by remember { mutableStateOf(false) }
    var zoomPercent by remember { mutableIntStateOf(100) }
    var isFullScreen by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<String>() }

    val virtualUrl = remember(activePath) { LocalProjectWebViewHelper.buildVirtualUrl(activePath) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            color = AppColors.bg,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isFullScreen) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.surface)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Web,
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Live HTML Preview",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.textPrimary
                            )
                            Text(
                                text = activePath,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Desktop / Mobile toggle
                        IconButton(
                            onClick = {
                                desktopMode = !desktopMode
                                reloadNonce++
                            },
                            modifier = Modifier.size(34.dp).testTag("webview_desktop_toggle")
                        ) {
                            Icon(
                                imageVector = if (desktopMode) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                                contentDescription = if (desktopMode) "Desktop viewport" else "Mobile viewport",
                                tint = if (desktopMode) AppColors.accent else AppColors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Zoom Out / In
                        IconButton(
                            onClick = { zoomPercent = (zoomPercent - 15).coerceAtLeast(50) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out", tint = AppColors.textSecondary, modifier = Modifier.size(17.dp))
                        }
                        Text(
                            text = "$zoomPercent%",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.textSecondary
                        )
                        IconButton(
                            onClick = { zoomPercent = (zoomPercent + 15).coerceAtMost(200) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in", tint = AppColors.textSecondary, modifier = Modifier.size(17.dp))
                        }

                        // Fullscreen toggle
                        IconButton(
                            onClick = { isFullScreen = true },
                            modifier = Modifier.size(34.dp).testTag("webview_fullscreen_toggle")
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Full screen", tint = AppColors.textSecondary, modifier = Modifier.size(18.dp))
                        }

                        // Console toggle badge
                        val errCount = consoleLogs.count { it.startsWith("[ERROR]") }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (errCount > 0) AppColors.diffRemoveBg else AppColors.surfaceAlt,
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    if (errCount > 0) AppColors.error.copy(alpha = 0.45f) else AppColors.border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showConsole = !showConsole }
                                .testTag("webview_console_toggle")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.BugReport,
                                    contentDescription = "Toggle JS console",
                                    tint = if (errCount > 0) AppColors.error else AppColors.textSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${consoleLogs.size}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (errCount > 0) AppColors.diffRemoveText else AppColors.textSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                consoleLogs.clear()
                                reloadNonce++
                            },
                            modifier = Modifier.size(34.dp).testTag("webview_reload_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload preview", tint = AppColors.textPrimary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp).testTag("webview_close_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close preview", tint = AppColors.textPrimary, modifier = Modifier.size(19.dp))
                        }
                    }

                    // Multi-HTML file selector strip
                    if (htmlRelativePaths.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.surfaceAlt)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (path in htmlRelativePaths) {
                                val selected = path == activePath
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) AppColors.accentSoft else AppColors.surface,
                                    modifier = Modifier
                                        .border(
                                            1.dp,
                                            if (selected) AppColors.accent else AppColors.border,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            activePath = path
                                            consoleLogs.clear()
                                            reloadNonce++
                                        }
                                ) {
                                    Text(
                                        text = path,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (selected) AppColors.accent else AppColors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
                }

                // Real Android WebView rendering local HTML/CSS/JS via virtual HTTPS origin
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.White)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
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
                                        val entry = "[$level] ${msg.message()} (${msg.sourceId()?.substringAfterLast('/') ?: activePath}:${msg.lineNumber()})"
                                        consoleLogs.add(entry)
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
                                            consoleLogs.add("[ERROR] Load error: ${error?.description ?: "Failed to load"} (${request.url})")
                                            showConsole = true
                                        }
                                    }
                                }
                                tag = "$virtualUrl|$reloadNonce|$desktopMode"
                                loadUrl(virtualUrl)
                            }
                        },
                        update = { webView ->
                            webView.settings.textZoom = zoomPercent
                            webView.settings.useWideViewPort = desktopMode
                            webView.settings.userAgentString = if (desktopMode) {
                                LocalProjectWebViewHelper.DESKTOP_USER_AGENT
                            } else {
                                WebSettings.getDefaultUserAgent(webView.context)
                            }
                            val nextTag = "$virtualUrl|$reloadNonce|$desktopMode"
                            if (webView.tag != nextTag) {
                                webView.tag = nextTag
                                webView.clearCache(true)
                                webView.loadUrl(virtualUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Floating Exit-Fullscreen pill when in fullscreen mode
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

                // Live JS Console Panel
                if (showConsole && !isFullScreen) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 210.dp)
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
                                            val dump = consoleLogs.joinToString("\n")
                                            onDismiss()
                                            onSendErrorsToAgent(
                                                "Fix these live WebView preview errors in `$activePath`:\n```\n$dump\n```"
                                            )
                                        }
                                        .testTag("webview_send_errors_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = null, tint = AppColors.onAccent, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text("Fix with Agent", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onAccent)
                                    }
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
}
