package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.ToolStepEntity
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.RoseError
import org.json.JSONObject

@Composable
fun AgentActivityTimeline(
    toolSteps: List<ToolStepEntity>,
    modifier: Modifier = Modifier
) {
    if (toolSteps.isEmpty()) return

    var isExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF131D2E)
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(CyberCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Agent Steps",
                            tint = CyberCyan,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Agent Activity (${toolSteps.size} tool operations)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFBAE6FD)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val completedCount = toolSteps.count { it.status == "COMPLETED" && !it.isError }
                    Text(
                        text = "$completedCount/${toolSteps.size} done",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (step in toolSteps) {
                        ToolStepItemView(step = step)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolStepItemView(step: ToolStepEntity) {
    var detailsExpanded by remember { mutableStateOf(false) }

    val icon: ImageVector = when (step.toolName) {
        "list_files" -> Icons.Default.FolderOpen
        "read_file" -> Icons.Default.MenuBook
        "search_code" -> Icons.Default.Search
        "edit_file" -> Icons.Default.Edit
        "write_file" -> Icons.Default.NoteAdd
        "delete_file" -> Icons.Default.Delete
        "move_file" -> Icons.Default.DriveFileMove
        else -> Icons.Default.Build
    }

    val parsedTarget: String = remember(step.argumentsJson) {
        try {
            val json = JSONObject(step.argumentsJson)
            when {
                json.has("path") -> json.getString("path")
                json.has("query") -> "\"${json.getString("query")}\""
                json.has("from") -> "${json.getString("from")} -> ${json.getString("to")}"
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    val actionLabel = when (step.toolName) {
        "list_files" -> "Listing files in"
        "read_file" -> "Reading"
        "search_code" -> "Searching code for"
        "edit_file" -> "Editing"
        "write_file" -> "Writing"
        "delete_file" -> "Deleting"
        "move_file" -> "Moving"
        else -> step.toolName
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0F172A),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (step.isError) RoseError.copy(alpha = 0.5f) else Color(0xFF1E293B)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { detailsExpanded = !detailsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = step.toolName,
                        tint = when {
                            step.isError -> RoseError
                            step.status == "RUNNING" -> ForgeAmber
                            else -> CyberCyan
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$actionLabel $parsedTarget",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFE2E8F0),
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step.status == "RUNNING") {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = ForgeAmber,
                            modifier = Modifier.size(12.dp)
                        )
                    } else if (step.isError) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = RoseError,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (step.durationMs > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${step.durationMs}ms",
                            fontSize = 10.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = detailsExpanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = "Result:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF94A3B8)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF090D16), RoundedCornerShape(4.dp))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = step.resultText.take(500) + if (step.resultText.length > 500) "…" else "",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (step.isError) RoseError else Color(0xFFCBD5E1)
                        )
                    }
                }
            }
        }
    }
}
