package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.termux.BridgeStatus
import com.example.data.termux.EngineConnectionState
import com.example.data.termux.TermuxBridge
import com.example.ui.theme.AppColors

@Composable
fun TermuxCopyCommandBox(
    stepBadge: String,
    title: String,
    subtitle: String,
    command: String,
    bridge: TermuxBridge,
    highlight: Boolean = false,
    tagPrefix: String = "termux_cmd"
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    val borderColor = if (highlight) AppColors.accent else AppColors.border
    val bgColor = if (highlight) AppColors.accentSoft else AppColors.surfaceAlt

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("${tagPrefix}_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (highlight) AppColors.accent else AppColors.surface)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = stepBadge,
                    color = if (highlight) AppColors.onAccent else AppColors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = AppColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    text = subtitle,
                    color = AppColors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.codeBg)
                .border(1.dp, AppColors.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = command,
                color = AppColors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(command))
                    copied = true
                    Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .testTag("${tagPrefix}_copy_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy command",
                    modifier = Modifier.size(14.dp),
                    tint = if (copied) AppColors.ok else AppColors.accent
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (copied) "Copied" else "Copy",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (copied) AppColors.ok else AppColors.textPrimary
                )
            }

            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(command))
                    copied = true
                    val opened = bridge.openTermuxApp()
                    Toast.makeText(
                        context,
                        if (opened) "Copied! Paste in Termux and press Enter" else "Copied! Open Termux and paste",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.accent,
                    contentColor = AppColors.onAccent
                ),
                modifier = Modifier
                    .weight(1.3f)
                    .height(36.dp)
                    .testTag("${tagPrefix}_copy_open_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Copy and open Termux",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Copy & Open Termux",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Clean "Termux Connection" option row.
 * Commands are NOT dumped open on the main screen — tapping "Termux Connection" opens a dedicated
 * step-by-step screen/dialog with all Termux commands neatly organized.
 */
@Composable
fun TermuxQuickConnectPanel(
    bridge: TermuxBridge,
    status: BridgeStatus,
    onAutoConnectClick: () -> Unit,
    onRefreshHealthClick: () -> Unit,
    compactDefault: Boolean = false
) {
    var showConnectionDialog by remember { mutableStateOf(false) }
    val isTermuxLive = status.connectionState == EngineConnectionState.CONNECTED_TERMUX

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppColors.surfaceAlt,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isTermuxLive) AppColors.ok else AppColors.border, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { showConnectionDialog = true }
            .testTag("termux_quick_connect_panel")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isTermuxLive) AppColors.ok.copy(alpha = 0.15f) else AppColors.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Termux Connection",
                    tint = if (isTermuxLive) AppColors.ok else AppColors.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Termux Connection",
                    color = AppColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (isTermuxLive) {
                        "Connected · ~/forge/projects · 127.0.0.1:${status.port}"
                    } else {
                        "Tap to open step-by-step setup commands & connect ~/forge/projects"
                    },
                    color = if (isTermuxLive) AppColors.ok else AppColors.textSecondary,
                    fontSize = 12.sp
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open Termux Connection",
                tint = AppColors.textMuted
            )
        }
    }

    if (showConnectionDialog) {
        TermuxConnectionScreenDialog(
            bridge = bridge,
            status = status,
            onAutoConnectClick = onAutoConnectClick,
            onRefreshHealthClick = onRefreshHealthClick,
            onDismiss = { showConnectionDialog = false }
        )
    }
}

@Composable
fun TermuxConnectionScreenDialog(
    bridge: TermuxBridge,
    status: BridgeStatus,
    onAutoConnectClick: () -> Unit,
    onRefreshHealthClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val step1Cmd = remember(bridge) { bridge.allowExternalAppsCommand() }
    val step2Cmd = remember(bridge) { bridge.installPackagesCommand() }
    val step3Cmd = remember(bridge) { bridge.directStartEngineCommand() }
    val allInOneCmd = remember(bridge) { bridge.allInOneTermuxSetupCommand() }
    val isTermuxLive = status.connectionState == EngineConnectionState.CONNECTED_TERMUX

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .heightIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = AppColors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Termux Connection",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.textPrimary
                            )
                            Text(
                                text = "Workspace: ~/forge/projects",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.textSecondary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = AppColors.textSecondary)
                    }
                }

                // Connection status bar + quick buttons
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.surfaceAlt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isTermuxLive) AppColors.ok else AppColors.warn)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isTermuxLive) {
                                    "Connected to Termux Bridge (127.0.0.1:${status.port})"
                                } else {
                                    "Status: ${status.installState} · Follow the 3 steps below"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.textPrimary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onAutoConnectClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.accent,
                                    contentColor = AppColors.onAccent
                                ),
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Text("Connect Engine", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { bridge.openAppPermissionSettings() },
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Text("App Permission", fontSize = 12.sp, color = AppColors.textPrimary)
                            }
                            OutlinedButton(
                                onClick = onRefreshHealthClick,
                                modifier = Modifier.height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp), tint = AppColors.accent)
                            }
                        }
                    }
                }

                Text(
                    text = "Step-by-Step Termux Commands",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.textPrimary
                )

                TermuxCopyCommandBox(
                    stepBadge = "STEP 1",
                    title = "Enable External Apps in Termux",
                    subtitle = "Allows CodeForge to run commands inside ~/.termux/termux.properties",
                    command = step1Cmd,
                    bridge = bridge,
                    tagPrefix = "termux_cmd_step1"
                )

                TermuxCopyCommandBox(
                    stepBadge = "STEP 2",
                    title = "Install Git, GitHub CLI & Python",
                    subtitle = "Installs lightweight tools to sync changed files to GitHub and stream GitHub Actions build logs",
                    command = step2Cmd,
                    bridge = bridge,
                    tagPrefix = "termux_cmd_step2"
                )

                TermuxCopyCommandBox(
                    stepBadge = "STEP 3",
                    title = "Start Termux Bridge (~/forge/projects)",
                    subtitle = "Starts the local bridge server in ~/forge/projects and connects immediately",
                    command = step3Cmd,
                    bridge = bridge,
                    highlight = true,
                    tagPrefix = "termux_cmd_step4_direct"
                )

                TermuxCopyCommandBox(
                    stepBadge = "ALL-IN-ONE",
                    title = "Run All 3 Steps in One Command",
                    subtitle = "Optional single command that runs Step 1, Step 2, and Step 3 together",
                    command = allInOneCmd,
                    bridge = bridge,
                    tagPrefix = "termux_cmd_all_in_one"
                )
            }
        }
    }
}
